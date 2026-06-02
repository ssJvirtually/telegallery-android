package dev.ssjvirtually.tgpix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ssjvirtually.tgpix.MainActivity
import dev.ssjvirtually.tgpix.storage.PreferencesManager
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import dev.ssjvirtually.tgpix.ui.theme.TelePhotosTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.util.UUID
import kotlin.coroutines.resume

@Composable
fun AutoVaultSetupScreen(onSetupComplete: (Long, String) -> Unit) {
    val context = LocalContext.current as MainActivity
    val coroutineScope = rememberCoroutineScope()
    
    var progressText by remember { mutableStateOf("Initializing secure TGPix vault...") }
    var errorText by remember { mutableStateOf<String?>(null) }

    // Helper suspending function to send TDLib requests
    suspend fun sendRequest(request: TdApi.Function<out TdApi.Object>): TdApi.Object = suspendCancellableCoroutine<TdApi.Object> { continuation ->
        try {
            TdlibManager.getClient().send(request) { result ->
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

    suspend fun sendMessageAndWait(chatId: Long, welcomeText: String): TdApi.Object = suspendCancellableCoroutine<TdApi.Object> { continuation ->
        try {
            val messageRequest = TdApi.SendMessage().apply {
                this.chatId = chatId
                this.inputMessageContent = TdApi.InputMessageText().apply {
                    text = TdApi.FormattedText(welcomeText, emptyArray())
                }
            }
            
            TdlibManager.getClient().send(messageRequest) { result ->
                if (result is TdApi.Message) {
                    // Register continuation to resume when UpdateMessageSendSucceeded fires
                    TdlibManager.pendingUploads[result.id] = continuation
                } else {
                    continuation.resume(result)
                }
            }
        } catch (e: Exception) {
            continuation.resume(TdApi.Error(500, e.message ?: "Exception in sendMessageAndWait"))
        }
    }

    fun generateVaultSignature(userId: Long): String {
        return try {
            val input = "TGPix-Secure-Salt-$userId"
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }.take(32).uppercase()
        } catch (e: Exception) {
            "FALLBACK-SIG-${userId}"
        }
    }

    LaunchedEffect(Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                // Step 0: Check for existing TGPix channel and verify key signature in pinned message
                withContext(Dispatchers.Main) {
                    progressText = "Searching for existing 'TGPix' vault..."
                }
                
                var existingChatId: Long? = null
                
                // Get current user to compute expected signature
                val meResult = sendRequest(TdApi.GetMe())
                if (meResult is TdApi.User) {
                    val userId = meResult.id
                    val expectedSignature = generateVaultSignature(userId)
                    
                    // First, fetch main chats list to populate cache
                    val chatsResult = sendRequest(TdApi.GetChats(TdApi.ChatListMain(), 100))
                    if (chatsResult is TdApi.Chats) {
                        for (chatId in chatsResult.chatIds) {
                            val chat = sendRequest(TdApi.GetChat(chatId))
                            if (chat is TdApi.Chat && chat.title.equals("TGPix", ignoreCase = true)) {
                                // Fetch and verify pinned message
                                val pinnedMsg = sendRequest(TdApi.GetChatPinnedMessage(chat.id))
                                if (pinnedMsg is TdApi.Message) {
                                    val content = pinnedMsg.content
                                    if (content is TdApi.MessageText && content.text.text.contains("TG-SIG-$expectedSignature")) {
                                        existingChatId = chat.id
                                        break
                                    }
                                }
                            }
                        }
                    }
                    
                    // If not found in recent chats, perform a server-side search
                    if (existingChatId == null) {
                        val searchRequest = TdApi.SearchChatsOnServer().apply {
                            query = "TGPix"
                            limit = 10
                        }
                        val searchResult = sendRequest(searchRequest)
                        if (searchResult is TdApi.Chats) {
                            for (chatId in searchResult.chatIds) {
                                val chat = sendRequest(TdApi.GetChat(chatId))
                                if (chat is TdApi.Chat && chat.title.equals("TGPix", ignoreCase = true)) {
                                    val pinnedMsg = sendRequest(TdApi.GetChatPinnedMessage(chat.id))
                                    if (pinnedMsg is TdApi.Message) {
                                        val content = pinnedMsg.content
                                        if (content is TdApi.MessageText && content.text.text.contains("TG-SIG-$expectedSignature")) {
                                            existingChatId = chat.id
                                            break
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                if (existingChatId != null) {
                    withContext(Dispatchers.Main) {
                        progressText = "Existing 'TGPix' vault verified! Linking..."
                    }
                    
                    PreferencesManager.saveChatId(context, existingChatId)
                    PreferencesManager.saveChatTitle(context, "TGPix (Private Vault)")
                    
                    context.scheduleSyncWorker()
                    
                    withContext(Dispatchers.Main) {
                        progressText = "Vault linked successfully! Opening gallery..."
                        kotlinx.coroutines.delay(1000)
                        onSetupComplete(existingChatId, "TGPix (Private Vault)")
                    }
                    return@launch
                }
                
                // Step 1: Create a private Telegram channel named TGPix
                withContext(Dispatchers.Main) {
                    progressText = "Creating private backup channel 'TGPix' on Telegram..."
                }
                
                val createRequest = TdApi.CreateNewSupergroupChat().apply {
                    title = "TGPix"
                    isChannel = true
                    description = "TGPix secure photo backup repository. Please do not delete."
                }
                
                val chatResult = sendRequest(createRequest)
                
                if (chatResult is TdApi.Chat) {
                    val newChatId = chatResult.id
                    
                    // Step 2: Generate a secure vault backup key and compute signature
                    withContext(Dispatchers.Main) {
                        progressText = "Generating unique vault encryption key..."
                    }
                    val uniqueKey = "TG-VAULT-${UUID.randomUUID().toString().uppercase().take(8)}"
                    
                    var signature = ""
                    val meResult = sendRequest(TdApi.GetMe())
                    if (meResult is TdApi.User) {
                        signature = generateVaultSignature(meResult.id)
                    }
                    
                    val welcomeText = "🔑 TGPix Secure Backup Vault Initialized!\n\n" +
                            "Vault Key: `$uniqueKey`\n" +
                            "Vault Signature: `TG-SIG-$signature`\n\n" +
                            "This private channel is used exclusively by your TGPix app to securely back up your photos. " +
                            "Please do not delete or modify this pinned message, as it stores your sync information."
                    
                    // Step 3: Send welcome message containing the key (and wait for server delivery)
                    withContext(Dispatchers.Main) {
                        progressText = "Saving configuration keys to the channel..."
                    }
                    val messageResult = sendMessageAndWait(newChatId, welcomeText)
                    
                    if (messageResult is TdApi.Message) {
                        // Step 4: Pin the welcome message in the channel (now using positive server message ID!)
                        withContext(Dispatchers.Main) {
                            progressText = "Pinning configurations inside your private vault..."
                        }
                        val pinRequest = TdApi.PinChatMessage().apply {
                            chatId = newChatId
                            messageId = messageResult.id
                            disableNotification = true
                            onlyForSelf = false
                        }
                        sendRequest(pinRequest)
                    }
                    
                    // Step 5: Save target chat preferences
                    withContext(Dispatchers.Main) {
                        progressText = "Finalizing secure integration..."
                    }
                    PreferencesManager.saveChatId(context, newChatId)
                    PreferencesManager.saveChatTitle(context, "TGPix (Private Vault)")
                    
                    // Schedule background sync worker
                    context.scheduleSyncWorker()
                    
                    withContext(Dispatchers.Main) {
                        progressText = "Vault ready! Opening your gallery..."
                        kotlinx.coroutines.delay(1000)
                        onSetupComplete(newChatId, "TGPix (Private Vault)")
                    }
                } else if (chatResult is TdApi.Error) {
                    withContext(Dispatchers.Main) {
                        errorText = "Telegram Error: [Code ${chatResult.code}] ${chatResult.message}"
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        errorText = "Unexpected response from Telegram core."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorText = "Setup Exception: ${e.message ?: "Unknown error"}"
                }
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(TelePhotosTheme.Background),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = TelePhotosTheme.Surface),
            modifier = Modifier.fillMaxWidth(0.9f).padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    tint = TelePhotosTheme.AccentBlue,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Cloud Vault Provisioning",
                    color = TelePhotosTheme.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                if (errorText != null) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Vault Setup Failed",
                        color = TelePhotosTheme.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorText!!,
                        color = Color.Red,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = {
                            errorText = null
                            progressText = "Retrying vault creation..."
                            context.recreate()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TelePhotosTheme.AccentBlue)
                    ) {
                        Text("Retry Setup")
                    }
                } else {
                    CircularProgressIndicator(
                        color = TelePhotosTheme.AccentBlue,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = progressText,
                        color = TelePhotosTheme.TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
