package dev.ssjvirtually.tgpix

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import dev.ssjvirtually.tgpix.storage.PreferencesManager
import java.io.File
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class TGPixApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)   // 25% of available RAM for decoded bitmaps
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_thumbnails"))
                    .maxSizeBytes(100 * 1024 * 1024)   // 100MB disk cache
                    .build()
            }
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()

        // Legacy fallback: handle any pending_restore.db that was staged by an older
        // version of the app (before the in-process restore was introduced). On new
        // installs this path will never be hit.
        val pendingRestorePath = PreferencesManager.getPendingRestorePath(this)
        if (pendingRestorePath != null) {
            val srcFile = File(pendingRestorePath)
            if (srcFile.exists()) {
                try {
                    val dbFile = getDatabasePath("upload_database")
                    Log.d("TGPixApplication", "Legacy pending restore found. Overwriting database at ${dbFile.absolutePath}")

                    dbFile.parentFile?.mkdirs()
                    srcFile.inputStream().use { input ->
                        dbFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    // Delete WAL/SHM to prevent journal conflicts
                    getDatabasePath("upload_database-wal").takeIf { it.exists() }?.delete()
                    getDatabasePath("upload_database-shm").takeIf { it.exists() }?.delete()

                    // Clear device-specific cached file paths before Room opens
                    clearCachedPathsRaw(dbFile)

                    srcFile.delete()
                    Log.d("TGPixApplication", "Legacy pending restore completed successfully.")
                } catch (e: Exception) {
                    Log.e("TGPixApplication", "Legacy pending restore failed", e)
                    try { srcFile.delete() } catch (_: Exception) {}
                }
            } else {
                Log.w("TGPixApplication", "Pending restore path set but file missing: $pendingRestorePath")
            }
            PreferencesManager.setPendingRestorePath(this, null)
        }

        // Pre-warm database on a background thread to prevent concurrent lazy initialization conflicts
        Thread {
            try {
                val db = dev.ssjvirtually.tgpix.storage.UploadDatabase.getDatabase(this)
                db.openHelper.writableDatabase
                Log.d("TGPixApplication", "Database pre-warmed successfully.")
            } catch (e: Exception) {
                Log.e("TGPixApplication", "Failed to pre-warm database", e)
            }
        }.start()

        // Start the thumbnail database write batching loop
        dev.ssjvirtually.tgpix.ui.utils.ThumbnailWriteBuffer.startTimeoutLoop(this)
    }

    private fun clearCachedPathsRaw(dbFile: File) {
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
            db.execSQL("""
                UPDATE cloud_photos 
                SET localCachedThumbnailPath = NULL, 
                    localCachedLargePath = NULL
            """)
            Log.d("TGPixApplication", "Cleared local cached paths via raw SQLite successfully.")
        } catch (e: Exception) {
            Log.e("TGPixApplication", "Failed to clear local cached paths via raw SQLite", e)
        } finally {
            try { db?.close() } catch (_: Exception) {}
        }
    }
}
