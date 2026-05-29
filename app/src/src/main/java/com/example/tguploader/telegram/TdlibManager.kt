package com.example.tguploader.telegram

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume

data class ChatInfo(val id: Long, val title: String)

object TdlibManager {
    private const val API_ID = YOUR_TELEGRAM_API_ID
    private const val API_HASH = "YOUR_TELEGRAM_API_HASH"

    private var client: Client? = null
    
    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState: StateFlow<TdApi.AuthorizationState?> = _authState

    private val _chats = MutableStateFlow<List<ChatInfo>>(emptyList())
    val chats: StateFlow<List<ChatInfo>> = _chats

    private val _logs = MutableStateFlow<List<String>>(listOf("System initialized."))
    val logs: StateFlow<List<String>> = _logs

    // Active upload tracking
    val pendingUploads = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.CancellableContinuation<TdApi.Object>>()

    fun addLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val current = _logs.value.toMutableList()
        current.add("[$time] $msg")
        if (current.size > 50) current.removeAt(0)
        _logs.value = current
    }

    fun getClient(): Client {
        return client ?: throw IllegalStateException("TDLib Client not initialized!")
    }

    fun initialize(context: Context) {
        if (client != null) return
        addLog("Initializing TDLib library...")
        
        // Evict any stale cache files from previous runs
        try {
            context.cacheDir.listFiles()?.forEach { it.delete() }
            addLog("Cleared stale temporary cache.")
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
                }
            }
            is TdApi.UpdateMessageSendSucceeded -> {
                val oldId = obj.oldMessageId
                val continuation = pendingUploads.remove(oldId)
                if (continuation != null && continuation.isActive) {
                    continuation.resume(obj.message)
                }
                cleanupSentMessageFile(context, obj.message)
            }
            is TdApi.UpdateMessageSendFailed -> {
                val oldId = obj.oldMessageId
                val continuation = pendingUploads.remove(oldId)
                if (continuation != null && continuation.isActive) {
                    continuation.resume(obj.error)
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
        }
    }

    private fun setParameters(context: Context) {
        val parameters = TdApi.SetTdlibParameters().apply {
            databaseDirectory = context.filesDir.absolutePath + "/tdlib"
            useMessageDatabase = true
            useSecretChats = false
            apiId = API_ID
            apiHash = API_HASH
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
}
