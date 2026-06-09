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

    fun createAlbum(name: String, onResult: (Long) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val db = UploadDatabase.getDatabase(context)
            val newId = db.albumDao().insertAlbum(AlbumEntity(name = name))
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
            val albumPhotos = photos.map { photo ->
                AlbumPhotoEntity(albumId = albumId, photoUri = photo.name)
            }
            db.albumDao().insertAlbumPhotos(albumPhotos)
            BackupManager.onAlbumUpdated(context, albumId)
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
            db.albumDao().deleteAlbum(albumId)
            if (album != null) {
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
            photos.forEach { photo ->
                db.albumDao().removePhotoFromAlbum(albumId, photo.name)
                db.albumDao().removePhotoFromAlbum(albumId, photo.uri)
            }
            BackupManager.onAlbumUpdated(context, albumId)
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
