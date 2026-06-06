package dev.ssjvirtually.tgpix.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.ssjvirtually.tgpix.storage.BackupManager
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import org.drinkless.tdlib.TdApi

class DatabaseBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // 1. Initialize TDLib (no-op if already running)
            TdlibManager.initialize(applicationContext)

            // 2. Wait up to 20 seconds for Telegram auth to be Ready
            //    (session files load instantly on subsequent launches; only slow on first-ever launch)
            val isReady = withTimeoutOrNull(20_000) {
                TdlibManager.authState.first { it is TdApi.AuthorizationStateReady }
            }
            if (isReady == null) {
                TdlibManager.addLog("DatabaseBackupWorker: TDLib not authenticated in time. Retrying later.")
                return Result.retry()
            }

            // 3. Wait up to 15 seconds for network connection to be established
            //    (TDLib may still be connecting to servers even after auth state is Ready)
            val isConnected = withTimeoutOrNull(15_000) {
                TdlibManager.connectionStatus.first {
                    it == TdlibManager.ConnectionStatus.CONNECTED
                }
            }
            if (isConnected == null) {
                TdlibManager.addLog("DatabaseBackupWorker: No Telegram connection in time. Retrying later.")
                return Result.retry()
            }

            // 4. Run the backup now that TDLib is fully ready
            BackupManager.backupDatabase(applicationContext)
            Result.success()
        } catch (e: Exception) {
            TdlibManager.addLog("DatabaseBackupWorker: Exception during backup: ${e.message}")
            e.printStackTrace()
            Result.retry()
        }
    }
}
