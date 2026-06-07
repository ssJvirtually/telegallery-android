package dev.ssjvirtually.tgpix

import android.app.Application
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
                    
                    // Perform the copy
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
                    
                    // Delete the temporary pending restore file
                    srcFile.delete()
                    Log.d("TGPixApplication", "Pending database restore completed successfully.")
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
}
