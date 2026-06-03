package dev.ssjvirtually.tgpix

import android.app.RecoverableSecurityException
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.work.*
import dev.ssjvirtually.tgpix.storage.LocalPhoto
import dev.ssjvirtually.tgpix.storage.PreferencesManager
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import dev.ssjvirtually.tgpix.ui.AppNavigation
import dev.ssjvirtually.tgpix.ui.theme.TelePhotosTheme
import dev.ssjvirtually.tgpix.worker.UploadWorker
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private var deleteLauncher: ManagedActivityResultLauncher<IntentSenderRequest, androidx.activity.result.ActivityResult>? = null
    private var deleteMultipleLauncher: ManagedActivityResultLauncher<IntentSenderRequest, androidx.activity.result.ActivityResult>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize TDLib core
        TdlibManager.initialize(applicationContext)
        
        // Start background backup sync immediately if configured
        if (PreferencesManager.getChatId(applicationContext) != 0L) {
            scheduleSyncWorker()
        }

        setContent {
            // Premium light theme matching Google Photos & Telegram aesthetics
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = TelePhotosTheme.AccentBlue,
                    background = TelePhotosTheme.Background,
                    surface = TelePhotosTheme.Surface,
                    onPrimary = Color.White,
                    onBackground = TelePhotosTheme.TextPrimary,
                    onSurface = TelePhotosTheme.TextPrimary,
                    surfaceVariant = TelePhotosTheme.SurfaceVariant,
                    onSurfaceVariant = TelePhotosTheme.TextSecondary
                )
            ) {
                // Register intent sender launcher for Android 10+ MediaStore deletions
                deleteLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartIntentSenderForResult()
                ) { result ->
                    if (result.resultCode == RESULT_OK) {
                        Toast.makeText(this, "Photo deleted successfully!", Toast.LENGTH_SHORT).show()
                    }
                }

                // Register intent sender launcher for batch deletions
                deleteMultipleLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartIntentSenderForResult()
                ) { result ->
                    if (result.resultCode == RESULT_OK) {
                        Toast.makeText(this, "Selected photos deleted successfully!", Toast.LENGTH_SHORT).show()
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }

    fun triggerDelete(photo: LocalPhoto) {
        val uri = Uri.parse(photo.uri)
        try {
            contentResolver.delete(uri, null, null)
            Toast.makeText(this, "Photo deleted successfully!", Toast.LENGTH_SHORT).show()
        } catch (securityException: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val recoverableSecurityException = securityException as? RecoverableSecurityException
                val intentSender = recoverableSecurityException?.userAction?.actionIntent?.intentSender
                if (intentSender != null) {
                    deleteLauncher?.launch(IntentSenderRequest.Builder(intentSender).build())
                }
            } else {
                Toast.makeText(this, "Delete failed: ${securityException.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun triggerBatchDelete(photos: List<LocalPhoto>) {
        val localPhotos = photos.filter { !it.uri.startsWith("cloud://") }
        val cloudCount = photos.size - localPhotos.size
        
        if (localPhotos.isEmpty()) {
            if (cloudCount > 0) {
                Toast.makeText(this, "Skipped cloud-only photos. No local photos to delete.", Toast.LENGTH_LONG).show()
            }
            return
        }
        
        if (cloudCount > 0) {
            Toast.makeText(this, "Skipped $cloudCount cloud-only photos.", Toast.LENGTH_SHORT).show()
        }
        
        val uris = localPhotos.map { Uri.parse(it.uri) }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, uris)
                deleteMultipleLauncher?.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
            } else {
                var successCount = 0
                for (photo in localPhotos) {
                    try {
                        contentResolver.delete(Uri.parse(photo.uri), null, null)
                        successCount++
                    } catch (e: Exception) {}
                }
                Toast.makeText(this, "Deleted $successCount photos successfully!", Toast.LENGTH_SHORT).show()
            }
        } catch (securityException: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val recoverableSecurityException = securityException as? RecoverableSecurityException
                val intentSender = recoverableSecurityException?.userAction?.actionIntent?.intentSender
                if (intentSender != null) {
                    deleteMultipleLauncher?.launch(IntentSenderRequest.Builder(intentSender).build())
                }
            } else {
                Toast.makeText(this, "Batch delete failed: ${securityException.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun scheduleSyncWorker() {
        val isBackupActive = PreferencesManager.isBackupActive(applicationContext)
        if (!isBackupActive) {
            // Cancel background backups instantly
            WorkManager.getInstance(applicationContext).cancelUniqueWork("upload_worker")
            runOnUiThread {
                Toast.makeText(this, "Background backup synchronization disabled/paused.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val wifiOnly = PreferencesManager.isWifiOnly(applicationContext)
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

        // 1. Enqueue Periodic Work for long-term scheduling (every 15 mins)
        val request = PeriodicWorkRequestBuilder<UploadWorker>(
            15, TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .build()
        ).build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniquePeriodicWork(
                "upload_worker",
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )

        val oneTimeRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(networkType)
                    .build()
            ).build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(
                "upload_worker_one_time",
                ExistingWorkPolicy.REPLACE,
                oneTimeRequest
            )
        
        val dataMsg = if (wifiOnly) "Wi-Fi Only (Data Saver Active)" else "Wi-Fi + Mobile Data allowed"
        runOnUiThread {
            Toast.makeText(this, "Backup active: $dataMsg", Toast.LENGTH_SHORT).show()
        }
    }
}
