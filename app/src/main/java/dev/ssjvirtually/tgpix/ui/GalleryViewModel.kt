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
import dev.ssjvirtually.tgpix.storage.UploadEntity
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import dev.ssjvirtually.tgpix.telegram.HistorySyncManager
import dev.ssjvirtually.tgpix.ui.screens.SearchItem
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import dev.ssjvirtually.tgpix.worker.RestoreWorker
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
import kotlinx.coroutines.flow.debounce
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

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class GalleryViewModel @JvmOverloads constructor(
    application: Application,
    private val photosRepository: PhotosRepository = PhotosRepository(),
    private val preferencesManager: PreferencesManager = PreferencesManager,
    private val backupManager: BackupManager = BackupManager,
    private val historySyncManager: HistorySyncManager = HistorySyncManager
) : AndroidViewModel(application) {

    private val _localPhotos = MutableStateFlow<List<LocalPhoto>>(emptyList())
    val localPhotos: StateFlow<List<LocalPhoto>> = _localPhotos.asStateFlow()

    private val _isScanningLocal = MutableStateFlow(false)
    val isScanningLocal: StateFlow<Boolean> = _isScanningLocal.asStateFlow()

    private val _isSyncingCloud = MutableStateFlow(false)
    val isSyncingCloud: StateFlow<Boolean> = _isSyncingCloud.asStateFlow()

    private val _syncProgressText = MutableStateFlow<String?>(null)
    val syncProgressText: StateFlow<String?> = _syncProgressText.asStateFlow()

    // Whenever dbVersion updates (e.g. after database restore), flatMapLatest will switch to the new database's Flow
    val cloudLogs: Flow<List<CloudPhotoEntity>> = TdlibManager.dbVersion.flatMapLatest { _ ->
        UploadDatabase.getDatabase(application).cloudDao().getAllFlow()
    }

    val trashedPhotos: Flow<List<CloudPhotoEntity>> = TdlibManager.dbVersion.flatMapLatest { _ ->
        UploadDatabase.getDatabase(application).cloudDao().getTrashedFlow()
    }

    val uploadedPaths: Flow<List<String>> = TdlibManager.dbVersion.flatMapLatest { _ ->
        UploadDatabase.getDatabase(application).dao().getUploadedPathsFlow()
    }

    init {
        val workManager = WorkManager.getInstance(application)
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow("tgpix_restore_sync").collect { workInfos ->
                val workInfo = workInfos.firstOrNull()
                if (workInfo != null) {
                    when {
                        workInfo.state.isFinished -> {
                            // SUCCEEDED, FAILED, or CANCELLED — always clear the banner
                            _isSyncingCloud.value = false
                            _syncProgressText.value = null
                        }
                        workInfo.state == WorkInfo.State.RUNNING -> {
                            _isSyncingCloud.value = true
                            val progressText = workInfo.progress.getString("progressText")
                            _syncProgressText.value = progressText ?: "Restoring gallery..."
                        }
                        workInfo.state == WorkInfo.State.ENQUEUED -> {
                            // Work is queued but not started yet — show banner while waiting
                            _isSyncingCloud.value = true
                            _syncProgressText.value = "Waiting to sync…"
                        }
                        else -> {
                            _isSyncingCloud.value = false
                            _syncProgressText.value = null
                        }
                    }
                }
            }
        }

        // Event-driven debounced backup synchronization (checks 5 seconds after databases stop updating)
        viewModelScope.launch(Dispatchers.Default) {
            combine(cloudLogs, uploadedPaths) { cloud, uploads ->
                cloud.size + uploads.size
            }.debounce(5000L)
             .collect { totalRecords ->
                 if (totalRecords > 0) {
                     val lastBackupCount = preferencesManager.getLastBackupRecordCount(application)
                     if (totalRecords != lastBackupCount) {
                         try {
                             backupManager.scheduleBackup(application)
                         } catch (e: Exception) {
                             e.printStackTrace()
                         }
                     }
                 }
             }
        }
    }

    // 1. mergeResult combines local photos, cloud logs, and local uploads, executing in the background (Default dispatcher)
    val mergeResult: StateFlow<MergeResult> = combine(_localPhotos, cloudLogs, uploadedPaths) { local, cloud, uploads ->
        photosRepository.mergeAndDeduplicate(local, cloud, uploads)
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
            dev.ssjvirtually.tgpix.worker.BackupScheduler.schedulePhotoBackup(getApplication())
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
            dev.ssjvirtually.tgpix.worker.BackupScheduler.schedulePhotoBackup(getApplication())
        }
    }

    private fun enqueueRestoreWorker(app: Application, forceFullCrawl: Boolean) {
        val workManager = WorkManager.getInstance(app)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<RestoreWorker>()
            .setConstraints(constraints)
            .setInputData(androidx.work.workDataOf("forceFullCrawl" to forceFullCrawl))
            .build()
        workManager.enqueueUniqueWork(
            "tgpix_restore_sync",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun startCloudSync() {
        if (_isSyncingCloud.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isSyncingCloud.value = true
            val context = getApplication<Application>()
            try {
                val chatId = preferencesManager.getChatId(context)
                if (chatId != 0L) {
                    val db = UploadDatabase.getDatabase(context)
                    val currentCount = db.cloudDao().getRecordCountDirect()
                    val startMsgId = preferencesManager.getLastScannedMessageId(context)

                    val forceFullCrawl = currentCount == 0 || startMsgId != 0L
                    enqueueRestoreWorker(getApplication(), forceFullCrawl)
                } else {
                    _isSyncingCloud.value = false
                }
            } catch (e: Exception) {
                // Safety net — always clear the banner if something unexpected throws
                e.printStackTrace()
                _isSyncingCloud.value = false
            }
        }
    }
}
