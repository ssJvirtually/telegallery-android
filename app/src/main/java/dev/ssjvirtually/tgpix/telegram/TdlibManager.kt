package dev.ssjvirtually.tgpix.telegram

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

data class ChatInfo(val id: Long, val title: String)

object TdlibManager {

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

    // Active upload tracking
    val pendingUploads = java.util.concurrent.ConcurrentHashMap<Long, (TdApi.Object) -> Unit>()
    val completedUploads = java.util.concurrent.ConcurrentHashMap<Long, TdApi.Object>()

    fun registerPendingUpload(messageId: Long, callback: (TdApi.Object) -> Unit) {
        val completed = completedUploads.remove(messageId)
        if (completed != null) {
            callback(completed)
        } else {
            pendingUploads[messageId] = callback
        }
    }

    fun addLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        android.util.Log.d("TGPix", msg)
        val current = _logs.value.toMutableList()
        current.add("[$time] $msg")
        if (current.size > 50) current.removeAt(0)
        _logs.value = current
    }

    fun getClient(): Client {
        return client ?: throw IllegalStateException("TDLib Client not initialized!")
    }

    @Synchronized
    fun initialize(context: Context) {
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
            e.printStackTrace()
        }

        client = Client.create(
            { tdObject ->
                handleUpdate(context, tdObject)
            },
            { exception ->
                addLog("TDLib Exception: ${exception.message}")
                exception.printStackTrace()
            },
            { exception ->
                addLog("TDLib Default Exception: ${exception.message}")
                exception.printStackTrace()
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
                    
                    if (wasLoggedIn && !isManual) {
                        addLog("External logout detected. Triggering system notification.")
                        showSessionExpiredNotification(context)
                        // Reset credentials locally
                        PreferencesManager.saveChatId(context, 0L)
                        PreferencesManager.saveChatTitle(context, "")
                        PreferencesManager.saveDbChatId(context, 0L)
                        PreferencesManager.saveDbChatTitle(context, "Private Saved Messages")
                    } else if (isManual) {
                        PreferencesManager.setManualLogout(context, false)
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

    fun loadChats() {
        val getChats = TdApi.GetChats(TdApi.ChatListMain(), 100)
        getClient().send(getChats) { result ->
            // Results will flow via UpdateNewChat events
        }
    }

    private fun cleanupSentMessageFile(context: Context, message: TdApi.Message) {
        try {
            val content = message.content
            if (content is TdApi.MessageDocument) {
                val path = content.document.document.local.path
                if (path.isNotEmpty() && path.contains(context.cacheDir.absolutePath)) {
                    val file = java.io.File(path)
                    if (file.exists()) {
                        file.delete()
                        addLog("Cleaned up temp upload file: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun sendRequest(request: TdApi.Function<out TdApi.Object>): TdApi.Object = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
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

    suspend fun syncCloudHistory(context: Context, chatId: Long) {
        val database = UploadDatabase.getDatabase(context)
        val cloudDao = database.cloudDao()
        
        // Staging: Clear any stale Room DB cache items referencing DB backups
        try {
            cloudDao.deleteBackupDbFiles()
        } catch (e: Exception) {}
        
        var lastMessageId = 0L
        var crawling = true
        addLog("Starting server vault synchronization crawl...")

        while (crawling) {
            val getHistory = TdApi.GetChatHistory().apply {
                this.chatId = chatId
                this.fromMessageId = lastMessageId
                this.offset = 0
                this.limit = 50
                this.onlyLocal = false
            }
            
            val result = sendRequest(getHistory)
            if (result is TdApi.Messages && result.messages.isNotEmpty()) {
                val entities = mutableListOf<CloudPhotoEntity>()
                for (msg in result.messages) {
                    // Check if this message already exists in the local database
                    if (cloudDao.exists(msg.id)) {
                        addLog("Found existing message index in database. Terminating crawl.")
                        crawling = false
                        break
                    }

                    val content = msg.content
                    var fileName = ""
                    var fileId = 0
                    var thumbFileId = 0
                    var remoteId = ""
                    var fileSize = 0L
                    var isDoc = false
                    var uploadedAt = msg.date.toLong() * 1000L
                    var dateTaken = msg.date.toLong() * 1000L
                    var tags = ""
                    var metadata: ParsedMetadata? = null
                    
                    if (content is TdApi.MessagePhoto) {
                        val sizes = content.photo.sizes
                        if (sizes.isNotEmpty()) {
                            val largest = sizes.last()
                            fileId = largest.photo.id
                            remoteId = largest.photo.remote.id
                            fileSize = largest.photo.size.toLong()
                            
                            // Store first photo size as thumbnail (usually 's' or 'm')
                            thumbFileId = sizes.first().photo.id
                            
                            val captionText = content.caption.text
                            metadata = parseMetadataFromCaption(captionText)
                            if (metadata != null) {
                                fileName = metadata.name
                                uploadedAt = metadata.dateTaken
                                dateTaken = metadata.dateTaken
                                fileSize = metadata.size
                                tags = metadata.tags.joinToString(" ")
                            } else {
                                fileName = if (captionText.isNotEmpty()) {
                                    captionText.substringBefore("\n").trim()
                                } else {
                                    "photo_${msg.id}.jpg"
                                }
                                dateTaken = dev.ssjvirtually.tgpix.ui.utils.parseDateFromFilename(fileName) ?: (msg.date.toLong() * 1000L)
                            }
                        }
                    } else if (content is TdApi.MessageDocument) {
                        val doc = content.document
                        val docName = if (doc.fileName.isNotEmpty()) doc.fileName else {
                            val captionText = content.caption.text
                            if (captionText.isNotEmpty()) captionText.substringBefore("\n").trim() else "doc_${msg.id}"
                        }
                        
                        // Filter to only catalog document attachments that are valid image formats
                        val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif")
                        val extension = docName.substringAfterLast('.', "").lowercase()
                        val isImage = imageExtensions.contains(extension)
                        
                        if (isImage && !docName.startsWith("tgpix_backup", ignoreCase = true)) {
                            fileId = doc.document.id
                            remoteId = doc.document.remote.id
                            fileSize = doc.document.size
                            isDoc = true
                            
                            // Store document thumbnail if present, fallback to document fileId
                            thumbFileId = doc.thumbnail?.file?.id ?: doc.document.id
                            
                            val captionText = content.caption.text
                            metadata = parseMetadataFromCaption(captionText)
                            if (metadata != null) {
                                fileName = metadata.name
                                uploadedAt = metadata.dateTaken
                                dateTaken = metadata.dateTaken
                                fileSize = metadata.size
                                tags = metadata.tags.joinToString(" ")
                            } else {
                                fileName = docName
                                dateTaken = dev.ssjvirtually.tgpix.ui.utils.parseDateFromFilename(fileName) ?: (msg.date.toLong() * 1000L)
                            }
                        }
                    }
                    
                    if (fileId != 0 && fileName.isNotEmpty()) {
                        val computedFingerprint = if (metadata != null && metadata.hash.isNotEmpty()) {
                            "${fileName}_${fileSize}_${dateTaken}_${metadata.hash}"
                        } else {
                            "${fileName}_${fileSize}_${dateTaken}"
                        }
                        val isHdValue = metadata?.isHd ?: !isDoc
                        val originalSizeValue = metadata?.originalSizeBytes ?: fileSize
                        
                        val extension = fileName.substringAfterLast('.', "").lowercase()
                        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"

                        entities.add(
                            CloudPhotoEntity(
                                messageId = msg.id,
                                telegramFileId = fileId,
                                uniqueRemoteId = remoteId,
                                fileName = fileName,
                                uploadedAt = uploadedAt,
                                fileSize = fileSize,
                                isDocument = isDoc,
                                contentFingerprint = computedFingerprint,
                                telegramThumbnailFileId = thumbFileId,
                                tags = tags,
                                fileIdCachedAt = System.currentTimeMillis(),
                                isHd = isHdValue,
                                originalSizeBytes = originalSizeValue,
                                dateTaken = dateTaken,
                                mimeType = mime
                            )
                        )
                    }
                }
                
                if (entities.isNotEmpty()) {
                    cloudDao.insertBatch(entities)
                    addLog("Indexed ${entities.size} vault photos from Telegram server.")
                }
                
                lastMessageId = result.messages.last().id
            } else {
                crawling = false
            }
        }
        addLog("Vault server synchronization crawl completed.")
        
        // Trigger storage cache cleanup to stay within 500 MB limit
        try {
            optimizeStorage(context)
        } catch (e: Exception) {}
    }

    fun optimizeStorage(context: Context) {
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

    private data class ParsedMetadata(
        val id: Long,
        val name: String,
        val size: Long,
        val dateTaken: Long,
        val tags: List<String> = emptyList(),
        val hash: String = "",
        val isHd: Boolean? = null,
        val originalSizeBytes: Long? = null
    )

    private fun parseMetadataFromCaption(caption: String): ParsedMetadata? {
        if (!caption.contains("#tgpix_metadata")) return null
        try {
            val jsonStr = caption.substringAfter("#tgpix_metadata").trim()
            val jsonObj = org.json.JSONObject(jsonStr)
            
            val tagsList = mutableListOf<String>()
            val tagsArray = jsonObj.optJSONArray("tags")
            if (tagsArray != null) {
                for (i in 0 until tagsArray.length()) {
                    tagsList.add(tagsArray.getString(i))
                }
            }

            return ParsedMetadata(
                id = jsonObj.optLong("id", 0L),
                name = jsonObj.optString("name", ""),
                size = jsonObj.optLong("size", 0L),
                dateTaken = jsonObj.optLong("dateTaken", 0L),
                tags = tagsList,
                hash = jsonObj.optString("hash", ""),
                isHd = if (jsonObj.has("isHd")) jsonObj.getBoolean("isHd") else null,
                originalSizeBytes = if (jsonObj.has("origSize")) jsonObj.getLong("origSize") else null
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
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
}
