package dev.ssjvirtually.tgpix.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ssjvirtually.tgpix.storage.AlbumEntity
import dev.ssjvirtually.tgpix.storage.AlbumPhotoEntity
import dev.ssjvirtually.tgpix.storage.BackupManager
import dev.ssjvirtually.tgpix.storage.LocalPhoto
import dev.ssjvirtually.tgpix.storage.UploadDatabase
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlbumViewModel(application: Application) : AndroidViewModel(application) {

    @OptIn(ExperimentalCoroutinesApi::class)
    val albumsFlow: Flow<List<AlbumEntity>> = TdlibManager.dbVersion.flatMapLatest {
        UploadDatabase.getDatabase(application).albumDao().getAllAlbumsFlow()
    }

    private suspend fun resolveMessageIdForPhoto(context: Context, photo: LocalPhoto): Long? {
        if (photo.uri.startsWith("cloud://")) {
            return photo.uri.substringAfter("cloud://").substringBefore("/").toLongOrNull()
        }
        val db = UploadDatabase.getDatabase(context)
        val cloudPhoto = db.cloudDao().findByFileName(photo.name)
        if (cloudPhoto != null) {
            return cloudPhoto.messageId
        }
        val fingerprint = "${photo.name}_${photo.size}_${photo.dateTaken}"
        val cloudPhotoByFingerprint = db.cloudDao().findByFingerprint(fingerprint)
        return cloudPhotoByFingerprint?.messageId
    }

    fun createAlbum(name: String, onResult: (Long) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val db = UploadDatabase.getDatabase(context)
            val uuid = java.util.UUID.randomUUID().toString()
            val newId = db.albumDao().insertAlbum(AlbumEntity(uuid = uuid, name = name))
            BackupManager.onAlbumUpdated(context, newId)
            withContext(Dispatchers.Main) {
                onResult(newId)
            }
        }
    }

    fun addPhotosToAlbum(albumId: Long, photos: List<LocalPhoto>, onResult: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val db = UploadDatabase.getDatabase(context)
            val album = db.albumDao().getAlbumById(albumId)
            if (album != null) {
                val albumPhotos = photos.map { photo ->
                    AlbumPhotoEntity(albumId = albumId, photoUri = photo.name)
                }
                db.albumDao().insertAlbumPhotos(albumPhotos)
                BackupManager.onAlbumUpdated(context, albumId)
                dev.ssjvirtually.tgpix.worker.BackupScheduler.schedulePhotoBackup(context)
            }
            withContext(Dispatchers.Main) {
                onResult()
            }
        }
    }

    fun deleteAlbum(albumId: Long, onResult: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val db = UploadDatabase.getDatabase(context)
            val album = db.albumDao().getAlbumById(albumId)
            if (album != null) {
                db.albumDao().deleteAlbum(albumId)
                db.albumDao().deleteAlbumPhotos(albumId)
                BackupManager.logAlbumDelete(context, album.uuid)
                BackupManager.onAlbumDeleted(context, album.telegramMessageId)
            }
            withContext(Dispatchers.Main) {
                onResult()
            }
        }
    }

    fun removePhotosFromAlbum(albumId: Long, photos: List<LocalPhoto>, onResult: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val db = UploadDatabase.getDatabase(context)
            val album = db.albumDao().getAlbumById(albumId)
            if (album != null) {
                photos.forEach { photo ->
                    db.albumDao().removePhotoFromAlbum(albumId, photo.name)
                    db.albumDao().removePhotoFromAlbum(albumId, photo.uri)
                }
                BackupManager.onAlbumUpdated(context, albumId)
            }
            withContext(Dispatchers.Main) {
                onResult()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getPhotoUrisForAlbumFlow(albumId: Long): Flow<List<String>> {
        val application = getApplication<Application>()
        return TdlibManager.dbVersion.flatMapLatest {
            UploadDatabase.getDatabase(application).albumDao().getPhotoUrisForAlbumFlow(albumId)
        }
    }

    suspend fun getAlbumPhotosDirect(albumId: Long): List<AlbumPhotoEntity> = withContext(Dispatchers.IO) {
        UploadDatabase.getDatabase(getApplication()).albumDao().getAlbumPhotosDirect(albumId)
    }
}
