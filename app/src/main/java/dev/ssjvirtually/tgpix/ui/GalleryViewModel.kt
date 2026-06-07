package dev.ssjvirtually.tgpix.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ssjvirtually.tgpix.storage.BackupManager
import dev.ssjvirtually.tgpix.storage.LocalPhoto
import dev.ssjvirtually.tgpix.storage.MediaStoreScanner
import dev.ssjvirtually.tgpix.storage.PhotosRepository
import dev.ssjvirtually.tgpix.storage.PreferencesManager
import dev.ssjvirtually.tgpix.storage.UploadDatabase
import dev.ssjvirtually.tgpix.storage.MergeResult
import dev.ssjvirtually.tgpix.storage.CloudPhotoEntity
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import dev.ssjvirtually.tgpix.ui.screens.SearchItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val searchDateFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy", Locale.getDefault())

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val _localPhotos = MutableStateFlow<List<LocalPhoto>>(emptyList())
    val localPhotos: StateFlow<List<LocalPhoto>> = _localPhotos.asStateFlow()

    private val _isScanningLocal = MutableStateFlow(false)
    val isScanningLocal: StateFlow<Boolean> = _isScanningLocal.asStateFlow()

    private val _isSyncingCloud = MutableStateFlow(false)
    val isSyncingCloud: StateFlow<Boolean> = _isSyncingCloud.asStateFlow()

    init {
        if (PreferencesManager.isRestoreNeedsCleanup(application)) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val db = UploadDatabase.getDatabase(application)
                    db.cloudDao().clearAllCachedPaths()
                    PreferencesManager.setRestoreNeedsCleanup(application, false)
                    TdlibManager.addLog("Post-restore database cache path cleanup executed.")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // Whenever dbVersion updates (e.g. after database restore), flatMapLatest will switch to the new database's Flow
    val cloudLogs: Flow<List<CloudPhotoEntity>> = TdlibManager.dbVersion.flatMapLatest { _ ->
        UploadDatabase.getDatabase(application).cloudDao().getAllFlow()
    }

    // 1. mergeResult combines local photos and cloud logs, executing in the background (Default dispatcher)
    val mergeResult: StateFlow<MergeResult> = combine(_localPhotos, cloudLogs) { local, cloud ->
        PhotosRepository.mergeAndDeduplicate(local, cloud)
    }.flowOn(Dispatchers.Default)
     .stateIn(
         scope = viewModelScope,
         started = SharingStarted.Lazily,
         initialValue = MergeResult(emptyList(), emptySet())
     )

    val mergedPhotosList: StateFlow<List<LocalPhoto>> = mergeResult.map { it.mergedPhotos }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList()
        )

    val uploadedUrisSet: StateFlow<Set<String>> = mergeResult.map { it.uploadedUris }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptySet()
        )

    // 2. Search indexing using thread-safe DateTimeFormatter and running on Default thread pool

    val searchIndex: StateFlow<List<SearchItem>> = mergedPhotosList.map { photos ->
        photos.map { photo ->
            val formattedDate = try {
                searchDateFormatter.format(Instant.ofEpochMilli(photo.dateTaken).atZone(ZoneId.systemDefault()))
            } catch (e: Exception) { "" }
            val keywords = "${photo.name.lowercase()} ${photo.tags.lowercase()} ${formattedDate.lowercase()}"
            SearchItem(photo, keywords)
        }
    }.flowOn(Dispatchers.Default)
     .stateIn(
         scope = viewModelScope,
         started = SharingStarted.Lazily,
         initialValue = emptyList()
     )

    private var scanJob: Job? = null

    fun loadLocalPhotos(hasPermission: Boolean) {
        if (!hasPermission) return
        viewModelScope.launch {
            _isScanningLocal.value = true
            val scanned = withContext(Dispatchers.IO) {
                MediaStoreScanner.scan(getApplication())
            }
            _localPhotos.value = scanned
            _isScanningLocal.value = false
        }
    }

    fun triggerScan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            delay(1000) // 1 second debounce
            _isScanningLocal.value = true
            val scanned = withContext(Dispatchers.IO) {
                MediaStoreScanner.scan(getApplication())
            }
            _localPhotos.value = scanned
            _isScanningLocal.value = false
        }
    }

    fun startCloudSync() {
        if (_isSyncingCloud.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isSyncingCloud.value = true
            val context = getApplication<Application>()
            var restored = false
            try {
                restored = BackupManager.restoreDatabase(context)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val chatId = PreferencesManager.getChatId(context)
            if (chatId != 0L) {
                try {
                    TdlibManager.syncCloudHistory(context, chatId, forceFullCrawl = restored)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (!restored) {
                try {
                    BackupManager.reconstructAlbumsFromBackupChannel(context)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            _isSyncingCloud.value = false
        }
    }
}
