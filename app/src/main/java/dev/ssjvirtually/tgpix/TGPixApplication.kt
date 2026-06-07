package dev.ssjvirtually.tgpix

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import dev.ssjvirtually.tgpix.storage.PreferencesManager
import java.io.File

class TGPixApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Check if there is a pending restore database file to copy
        val pendingRestorePath = PreferencesManager.getPendingRestorePath(this)
        if (pendingRestorePath != null) {
            val srcFile = File(pendingRestorePath)
            if (srcFile.exists()) {
                try {
                    val dbFile = getDatabasePath("upload_database")
                    Log.d("TGPixApplication", "Found pending database restore. Overwriting database at ${dbFile.absolutePath}")
                    
                    // Ensure parent directory exists
                    dbFile.parentFile?.mkdirs()
                    
                    // Perform the copy (pure file IO, synchronous and fast)
                    srcFile.inputStream().use { input ->
                        dbFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    // Delete any WAL and SHM files to ensure no old journal conflicts
                    val walFile = getDatabasePath("upload_database-wal")
                    val shmFile = getDatabasePath("upload_database-shm")
                    if (walFile.exists()) {
                        walFile.delete()
                    }
                    if (shmFile.exists()) {
                        shmFile.delete()
                    }

                    // Open raw SQLite connection to clear cached thumbnail and large photo paths
                    // (prevents other ViewModels or Repositories reading stale paths before UI initializes)
                    clearCachedPathsRaw(dbFile)
                    
                    // Delete the temporary pending restore file
                    srcFile.delete()
                    Log.d("TGPixApplication", "Pending database restore and post-restore cleanup completed successfully.")
                } catch (e: Exception) {
                    Log.e("TGPixApplication", "Failed to restore database from pending restore file", e)
                }
            } else {
                Log.w("TGPixApplication", "Pending restore file path was set but file does not exist: $pendingRestorePath")
            }
            // Clear the preference so we don't try to restore again on subsequent startups
            PreferencesManager.setPendingRestorePath(this, null)
        }
    }

    private fun clearCachedPathsRaw(dbFile: File) {
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(
                dbFile.path, null, SQLiteDatabase.OPEN_READWRITE
            )
            db.execSQL("""
                UPDATE cloud_photos 
                SET localCachedThumbnailPath = NULL, 
                    localCachedLargePath = NULL
            """)
            Log.d("TGPixApplication", "Cleared local cached paths via raw SQLite successfully.")
        } catch (e: Exception) {
            Log.e("TGPixApplication", "Failed to clear local cached paths via raw SQLite", e)
        } finally {
            try {
                db?.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
        }
    }
}
