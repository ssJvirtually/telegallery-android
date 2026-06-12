package dev.ssjvirtually.tgpix.telegram

import dev.ssjvirtually.tgpix.ErrorMonitor
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume
import dev.ssjvirtually.tgpix.BuildConfig
import dev.ssjvirtually.tgpix.storage.UploadDatabase
import dev.ssjvirtually.tgpix.storage.CloudPhotoEntity
import java.io.File
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.ssjvirtually.tgpix.storage.PreferencesManager
import dev.ssjvirtually.tgpix.storage.BackupManager
import dev.ssjvirtually.tgpix.storage.RegisteredDeviceEntity

data class ChatInfo(val id: Long, val title: String)

open class TdlibManager {
    companion object : TdlibManager()

    enum class ConnectionStatus { CONNECTED, CONNECTING, WAITING_FOR_NETWORK }

    private var client: Client? = null
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var myUserId: Long = 0L
    
    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState: StateFlow<TdApi.AuthorizationState?> = _authState

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.CONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val _chats = MutableStateFlow<List<ChatInfo>>(emptyList())
    val chats: StateFlow<List<ChatInfo>> = _chats

    private val _logs = MutableStateFlow<List<String>>(listOf("System initialized."))
    val logs: StateFlow<List<String>> = _logs

    /**
     * Incremented whenever the Room database singleton is closed and recreated
     * (e.g. after restoring a backup). UI composables key their `remember { db }`
     * on this value so they automatically re-subscribe to the new instance's Flows.
     */
    private val _dbVersion = MutableStateFlow(0)
    val dbVersion: StateFlow<Int> = _dbVersion

    open fun notifyDatabaseReplaced() {
        _dbVersion.value += 1
    }

    private val _profilePhotoPath = MutableStateFlow<String?>(null)
    val profilePhotoPath: StateFlow<String?> = _profilePhotoPath

    // Active upload tracking
    val pendingUploads = java.util.concurrent.ConcurrentHashMap<Long, (TdApi.Object) -> Unit>()
    val completedUploads = java.util.concurrent.ConcurrentHashMap<Long, TdApi.Object>()

    private val pendingTempFiles = java.util.concurrent.ConcurrentHashMap<Int, String>() // fileId -> path

    open fun registerPendingTempFile(fileId: Int, path: String) {
        pendingTempFiles[fileId] = path
    }

    open fun registerPendingUpload(messageId: Long, callback: (TdApi.Object) -> Unit) {
        val completed = completedUploads.remove(messageId)
        if (completed != null) {
            callback(completed)
        } else {
            pendingUploads[messageId] = callback
        }
    }

    private val logTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss", java.util.Locale.getDefault())

    open fun addLog(msg: String) {
        val time = try {
            logTimeFormatter.format(java.time.LocalTime.now())
        } catch (e: Exception) {
            ""
        }
        android.util.Log.d("TGPix", msg)
        val current = _logs.value.toMutableList()
        current.add("[$time] $msg")
        if (current.size > 50) current.removeAt(0)
        _logs.value = current
    }

    open fun getClient(): Client {
        return client ?: throw IllegalStateException("TDLib Client not initialized!")
    }

    @Synchronized
    open fun initialize(context: Context) {
        if (client != null) return
        addLog("Initializing TDLib library...")
        
        // Evict any stale upload temp files from previous runs (dedicated subfolder only)
        try {
            val uploadCacheDir = File(context.cacheDir, "tgpix_uploads")
            if (uploadCacheDir.exists()) {
                uploadCacheDir.listFiles()?.forEach { it.delete() }
            }
            addLog("Cleared stale upload temp cache.")
        } catch (e: Exception) {}

        try {
            System.loadLibrary("tdjni")
            addLog("Native library tdjni loaded.")
        } catch (e: Exception) {
            addLog("Failed to load tdjni: ${e.message}")
            ErrorMonitor.log(e)
        }

        client = Client.create(
            { tdObject ->
                handleUpdate(context, tdObject)
            },
            { exception ->
                addLog("TDLib Exception: ${exception.message}")
                ErrorMonitor.log(exception)
            },
            { exception ->
                addLog("TDLib Default Exception: ${exception.message}")
                ErrorMonitor.log(exception)
            }
        )
    }

    private fun handleUpdate(context: Context, obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> {
                val state = obj.authorizationState
                _authState.value = state
                addLog("Auth State: ${state::class.java.simpleName}")
                
                if (state is TdApi.AuthorizationStateWaitTdlibParameters) {
                    setParameters(context)
                } else if (state is TdApi.AuthorizationStateReady) {
                    getClient().send(TdApi.GetMe()) { result ->
                        if (result is TdApi.User) {
                            myUserId = result.id
                            addLog("Logged in as ${result.firstName} (User ID: ${result.id}, Saved Messages target active)")
                            managerScope.launch(Dispatchers.IO) {
                                fetchAndMonitorProfilePhoto(result)
                            }
                        }
                    }
                } else if (state is TdApi.AuthorizationStateClosed) {
                    addLog("Session closed. Restarting TDLib client...")
                    client = null
                    managerScope.launch {
                        delay(500)
                        initialize(context)
                    }
                } else if (state is TdApi.AuthorizationStateWaitPhoneNumber) {
                    val wasLoggedIn = PreferencesManager.getChatId(context) != 0L
                    val isManual = PreferencesManager.isManualLogout(context)
                    addLog("Auth State: WaitPhoneNumber (wasLoggedIn: $wasLoggedIn, isManual: $isManual)")
                    
                    if (wasLoggedIn) {
                        if (!isManual) {
                            addLog("External logout detected. Triggering system notification.")
                            showSessionExpiredNotification(context)
                        } else {
                            addLog("Manual logout detected in state listener.")
                        }
                        performLogoutCleanup(context)
                    }
                }
            }
            is TdApi.UpdateMessageSendSucceeded -> {
                val oldId = obj.oldMessageId
                val callback = pendingUploads.remove(oldId)
                if (callback != null) {
                    callback(obj.message)
                } else {
                    if (completedUploads.size > 200) {
                        completedUploads.clear()
                    }
                    completedUploads[oldId] = obj.message
                }
                cleanupSentMessageFile(context, obj.message)
            }
            is TdApi.UpdateMessageSendFailed -> {
                val oldId = obj.oldMessageId
                val callback = pendingUploads.remove(oldId)
                if (callback != null) {
                    callback(obj.error)
                } else {
                    if (completedUploads.size > 200) {
                        completedUploads.clear()
                    }
                    completedUploads[oldId] = obj.error
                }
            }
            is TdApi.UpdateNewChat -> {
                val current = _chats.value.toMutableList()
                if (current.none { it.id == obj.chat.id }) {
                    current.add(ChatInfo(obj.chat.id, obj.chat.title))
                    _chats.value = current
                }
            }
            is TdApi.UpdateChatTitle -> {
                _chats.value = _chats.value.map { 
                    if (it.id == obj.chatId) it.copy(title = obj.title) else it 
                }
            }
            is TdApi.UpdateConnectionState -> {
                handleConnectionState(obj.state)
            }
            is TdApi.UpdateNewMessage -> {
                val msg = obj.message
                val vaultChatId = PreferencesManager.getChatId(context)
                val dbChatId = PreferencesManager.getDbChatId(context)
                
                if (vaultChatId != 0L && msg.chatId == vaultChatId) {
                    managerScope.launch(Dispatchers.IO) {
                        try {
                            HistorySyncManager.parseAndIndexUploadedMessage(context, msg)
                        } catch (e: Exception) {
                            ErrorMonitor.log(e)
                        }
                    }
                } else if (dbChatId != 0L && msg.chatId == dbChatId) {
                    val content = msg.content
                    if (content is TdApi.MessageText) {
                        val text = content.text.text
                        if (text.startsWith("{") && text.contains("\"type\"")) {
                            managerScope.launch(Dispatchers.IO) {
                                try {
                                    BackupManager.applyMetadataEvent(context, text, msg.id)
                                } catch (e: Exception) {
                                    ErrorMonitor.log(e)
                                }
                            }
                        }
                    } else if (content is TdApi.MessageDocument) {
                        val captionText = content.caption.text
                        if (captionText.contains("#tgpix_album")) {
                            managerScope.launch(Dispatchers.IO) {
                                try {
                                    BackupManager.reconstructAlbum(context, msg)
                                } catch (e: Exception) {
                                    ErrorMonitor.log(e)
                                }
                            }
                        }
                    }
                }
            }
            is TdApi.UpdateDeleteMessages -> {
                val vaultChatId = PreferencesManager.getChatId(context)
                if (vaultChatId != 0L && obj.chatId == vaultChatId) {
                    val messageIds = obj.messageIds
                    managerScope.launch(Dispatchers.IO) {
                        try {
                            val db = UploadDatabase.getDatabase(context)
                            messageIds.forEach { msgId ->
                                db.cloudDao().deleteByMessageId(msgId)
                            }
                        } catch (e: Exception) {
                            ErrorMonitor.log(e)
                        }
                    }
                }
            }
        }
    }

    private fun handleConnectionState(state: TdApi.ConnectionState) {
        val newStatus = when (state) {
            is TdApi.ConnectionStateReady -> ConnectionStatus.CONNECTED
            is TdApi.ConnectionStateWaitingForNetwork -> ConnectionStatus.WAITING_FOR_NETWORK
            else -> ConnectionStatus.CONNECTING
        }
        _connectionStatus.value = newStatus
        addLog("Telegram connection status changed to: $newStatus (${state::class.java.simpleName})")
    }

    private fun setParameters(context: Context) {
        val parameters = TdApi.SetTdlibParameters().apply {
            databaseDirectory = context.filesDir.absolutePath + "/tdlib"
            useMessageDatabase = true
            useSecretChats = false
            apiId = BuildConfig.TELEGRAM_API_ID
            apiHash = BuildConfig.TELEGRAM_API_HASH
            systemLanguageCode = "en"
            deviceModel = "Android"
            applicationVersion = "1.0"
        }

        getClient().send(parameters) { result ->
            // Parameters applied
        }
    }

    open fun loadChats() {
        val getChats = TdApi.GetChats(TdApi.ChatListMain(), 100)
        getClient().send(getChats) { result ->
            // Results will flow via UpdateNewChat events
        }
    }

    private fun cleanupSentMessageFile(context: Context, message: TdApi.Message) {
        try {
            val fileId = when (val content = message.content) {
                is TdApi.MessageDocument -> content.document.document.id
                is TdApi.MessagePhoto -> content.photo.sizes.lastOrNull()?.photo?.id
                else -> null
            }
            if (fileId != null) {
                val path = pendingTempFiles.remove(fileId)
                if (path != null) {
                    val file = java.io.File(path)
                    if (file.exists()) {
                        file.delete()
                        addLog("Cleaned up temp upload file via fileId matching: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            ErrorMonitor.log(e)
        }
    }

    open suspend fun sendRequest(request: TdApi.Function<out TdApi.Object>): TdApi.Object = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        try {
            val cl = client
            if (cl == null) {
                continuation.resume(TdApi.Error(500, "TDLib Client not initialized!"))
                return@suspendCancellableCoroutine
            }
            cl.send(request) { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        } catch (e: Exception) {
            if (continuation.isActive) {
                continuation.resume(TdApi.Error(500, e.message ?: "Exception in sendRequest"))
            }
        }
    }



    open fun optimizeStorage(context: Context) {
        val client = client ?: return
        addLog("Enforcing 500 MB file cache limits on TDLib storage...")
        val sizeLimit = 500L * 1024L * 1024L // 500 MB threshold
        val request = TdApi.OptimizeStorage().apply {
            size = sizeLimit
            ttl = 0
            count = 0
            immunityDelay = 0
            fileTypes = null
            chatIds = longArrayOf()
            excludeChatIds = longArrayOf()
            returnDeletedFileStatistics = false
        }
        client.send(request) { result ->
            if (result is TdApi.StorageStatistics) {
                addLog("File cache optimized successfully.")
            }
        }
    }



    private fun showSessionExpiredNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "tgpix_session_channel"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "TGPix Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical session alerts and logout notices."
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        val intent = Intent(context, dev.ssjvirtually.tgpix.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            100,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Telegram Session Expired")
            .setContentText("Your session was terminated or revoked. Please log in again to resume backups.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
            
        notificationManager.notify(888, notification)
    }

    open fun performLogoutCleanup(context: Context) {
        addLog("Performing logout cleanup...")
        _profilePhotoPath.value = null
        
        // 1. Cancel background workers
        try {
            androidx.work.WorkManager.getInstance(context.applicationContext).cancelAllWork()
            addLog("Cancelled all background work requests.")
        } catch (e: Exception) {
            addLog("Failed to cancel background workers: ${e.message}")
        }

        // 2. Clear local Room database & DataStore preferences
        val db = UploadDatabase.getDatabase(context)
        managerScope.launch(Dispatchers.IO) {
            try {
                // Clear cached paths first
                db.cloudDao().clearAllCachedPaths()
                // Wipe all tables to prevent privacy leakage
                db.cloudDao().clearAll()
                db.dao().clearAll()
                db.albumDao().clearAllAlbums()
                db.albumDao().clearAllAlbumPhotos()
                addLog("Cleared all local database tables.")
            } catch (e: Exception) {
                addLog("Failed to clear local database tables: ${e.message}")
            }

            // 3. Delete actual cached thumbnail/large files from cacheDir
            try {
                context.cacheDir.listFiles()?.filter {
                    it.name.startsWith("tgpix_")
                }?.forEach { file ->
                    file.delete()
                }
                
                // Clean temp uploads folder
                val uploadCacheDir = File(context.cacheDir, "tgpix_uploads")
                if (uploadCacheDir.exists()) {
                    uploadCacheDir.deleteRecursively()
                }
                addLog("Deleted all local cached media files and temp uploads.")
            } catch (e: Exception) {
                addLog("Failed to delete cached media files: ${e.message}")
            }

            // 4. Wipe all shared preferences
            try {
                PreferencesManager.clearAll(context)
                addLog("Cleared all shared preferences.")
            } catch (e: Exception) {
                addLog("Failed to clear shared preferences: ${e.message}")
            }
        }
    }

    open fun checkAndHandleChatError(context: Context, error: TdApi.Error): Boolean {
        val msg = error.message.uppercase()
        val isForbidden = error.code == 400 && (
            msg.contains("CHAT_WRITE_FORBIDDEN") || 
            msg.contains("CHAT_SEND_MEDIA_FORBIDDEN") || 
            msg.contains("CHAT_SEND_PHOTOS_FORBIDDEN") ||
            msg.contains("CHAT_SEND_DOCUMENTS_FORBIDDEN") ||
            msg.contains("CHAT_SEND_MESSAGES_FORBIDDEN")
        )
        val isAdminReq = error.code == 403 && (
            msg.contains("CHAT_ADMIN_REQUIRED") || 
            msg.contains("USER_IS_BANNED") ||
            msg.contains("CHAT_WRITE_FORBIDDEN")
        )
        val isNotFound = error.code == 404 && (
            msg.contains("CHAT_NOT_FOUND") || 
            msg.contains("CHAT_ID_INVALID")
        )
        
        if (isForbidden || isAdminReq || isNotFound) {
            addLog("Critical Telegram error detected [${error.code}]: ${error.message}. Resetting backup chat configuration.")
            
            // 1. Reset chat preferences locally so user is forced to re-select
            PreferencesManager.saveChatId(context, 0L)
            PreferencesManager.saveChatTitle(context, "")
            PreferencesManager.saveDbChatId(context, 0L)
            PreferencesManager.saveDbChatTitle(context, "Private Saved Messages")
            
            // 2. Cancel active background jobs
            try {
                androidx.work.WorkManager.getInstance(context.applicationContext).cancelAllWork()
            } catch (e: Exception) {
                ErrorMonitor.log(e)
            }
            
            // 3. Show high priority system notification
            showChatUnavailableNotification(context)
            return true
        }
        return false
    }

    private fun showChatUnavailableNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "tgpix_session_channel"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "TGPix Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical session alerts and logout notices."
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        val intent = Intent(context, dev.ssjvirtually.tgpix.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            300,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Backup Chat Unavailable")
            .setContentText("Your target Telegram backup chat is no longer accessible. Tap to choose a new destination.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
            
        notificationManager.notify(777, notification)
    }

    open fun forceReconnect(context: Context) {
        if (client == null) return
        try {
            val cl = getClient()
            cl.send(TdApi.SetNetworkType(TdApi.NetworkTypeNone())) { _ ->
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val activeNetwork = cm.activeNetwork
                val capabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
                val tdNetworkType = when {
                    capabilities == null -> TdApi.NetworkTypeNone()
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> TdApi.NetworkTypeWiFi()
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> TdApi.NetworkTypeMobile()
                    else -> TdApi.NetworkTypeOther()
                }
                cl.send(TdApi.SetNetworkType(tdNetworkType)) { result ->
                    addLog("Forced TDLib reconnection to: ${tdNetworkType::class.java.simpleName} (Result: ${result::class.java.simpleName})")
                }
            }
        } catch (e: Exception) {
            addLog("Failed to force TDLib reconnection: ${e.message}")
        }
    }

    open fun setNetworkOffline() {
        if (client == null) return
        try {
            getClient().send(TdApi.SetNetworkType(TdApi.NetworkTypeNone())) { result ->
                addLog("Sent SetNetworkType update: NetworkTypeNone (Result: ${result::class.java.simpleName})")
            }
        } catch (e: Exception) {
            addLog("Failed to set network offline: ${e.message}")
        }
    }

    private suspend fun fetchAndMonitorProfilePhoto(user: TdApi.User) {
        val photo = user.profilePhoto ?: return
        val fileId = photo.small.id
        addLog("Profile photo found (File ID: $fileId). Fetching path...")
        try {
            val initialFile = kotlin.coroutines.suspendCoroutine<TdApi.File?> { cont ->
                try {
                    getClient().send(TdApi.GetFile(fileId)) { fileRes ->
                        cont.resume(fileRes as? TdApi.File)
                    }
                } catch (e: Exception) {
                    cont.resume(null)
                }
            }

            if (initialFile != null) {
                if (initialFile.local.isDownloadingCompleted) {
                    addLog("Profile photo download complete: ${initialFile.local.path}")
                    _profilePhotoPath.value = initialFile.local.path
                } else {
                    addLog("Profile photo not downloaded yet. Requesting download...")
                    getClient().send(TdApi.DownloadFile(fileId, 1, 0, 0, false)) { }
                    var downloaded = false
                    var attempts = 0
                    while (!downloaded && attempts < 10) {
                        delay(1500)
                        attempts++
                        val currentFile = kotlin.coroutines.suspendCoroutine<TdApi.File?> { cont ->
                            try {
                                getClient().send(TdApi.GetFile(fileId)) { fileRes ->
                                    cont.resume(fileRes as? TdApi.File)
                                }
                            } catch (e: Exception) {
                                cont.resume(null)
                            }
                        }
                        if (currentFile != null && currentFile.local.isDownloadingCompleted) {
                            addLog("Profile photo download complete: ${currentFile.local.path}")
                            _profilePhotoPath.value = currentFile.local.path
                            downloaded = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            addLog("Failed to fetch or monitor profile photo: ${e.message}")
            ErrorMonitor.log(e)
        }
    }
}
