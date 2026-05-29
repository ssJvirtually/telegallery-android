package com.example.tguploader

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.tguploader.storage.LocalPhoto
import com.example.tguploader.storage.MediaStoreScanner
import com.example.tguploader.storage.PreferencesManager
import com.example.tguploader.storage.UploadDatabase
import com.example.tguploader.storage.UploadEntity
import com.example.tguploader.telegram.AuthManager
import com.example.tguploader.telegram.ChatInfo
import com.example.tguploader.telegram.TdlibManager
import com.example.tguploader.telegram.UploadManager
import com.example.tguploader.worker.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

sealed class GalleryItem {
    data class Header(val date: String) : GalleryItem()
    data class PhotoItem(val photo: LocalPhoto) : GalleryItem()
}

fun isCloudPhoto(uri: String): Boolean = uri.startsWith("cloud://")

fun parseCloudPhotoUri(uri: String): Triple<Long, Int, String>? {
    if (!isCloudPhoto(uri)) return null
    try {
        val parts = uri.removePrefix("cloud://").split("/", limit = 3)
        if (parts.size >= 3) {
            val messageId = parts[0].toLong()
            val telegramFileId = parts[1].toInt()
            val fileName = parts[2]
            return Triple(messageId, telegramFileId, fileName)
        }
    } catch (e: Exception) {}
    return null
}

fun parseDateFromFilename(fileName: String): Long? {
    try {
        // Pattern 1: YYYY-MM-DD_HH-MM-SS (e.g. photo_2026-05-29_16-09-11.jpg)
        val pattern1 = java.util.regex.Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})_(\\d{2})-(\\d{2})-(\\d{2})")
        val matcher1 = pattern1.matcher(fileName)
        if (matcher1.find()) {
            val dateStr = "${matcher1.group(1)}-${matcher1.group(2)}-${matcher1.group(3)} ${matcher1.group(4)}:${matcher1.group(5)}:${matcher1.group(6)}"
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            return sdf.parse(dateStr)?.time
        }

        // Pattern 2: YYYYMMDD_HHMMSS (e.g. IMG_20260529_204420.jpg)
        val pattern2 = java.util.regex.Pattern.compile("(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})(\\d{2})")
        val matcher2 = pattern2.matcher(fileName)
        if (matcher2.find()) {
            val dateStr = "${matcher2.group(1)}-${matcher2.group(2)}-${matcher2.group(3)} ${matcher2.group(4)}:${matcher2.group(5)}:${matcher2.group(6)}"
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            return sdf.parse(dateStr)?.time
        }

        // Pattern 3: YYYY-MM-DD (e.g. 2026-05-29.jpg)
        val pattern3 = java.util.regex.Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})")
        val matcher3 = pattern3.matcher(fileName)
        if (matcher3.find()) {
            val dateStr = "${matcher3.group(1)}-${matcher3.group(2)}-${matcher3.group(3)}"
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            return sdf.parse(dateStr)?.time
        }

        // Pattern 4: YYYYMMDD (e.g. 20260529.jpg)
        val pattern4 = java.util.regex.Pattern.compile("(\\d{4})(\\d{2})(\\d{2})")
        val matcher4 = pattern4.matcher(fileName)
        if (matcher4.find()) {
            val dateStr = "${matcher4.group(1)}-${matcher4.group(2)}-${matcher4.group(3)}"
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            return sdf.parse(dateStr)?.time
        }
    } catch (e: Exception) {}
    return null
}

@Composable
fun rememberCloudThumbnailPath(fileId: Int): String? {
    var localPath by remember(fileId) { mutableStateOf<String?>(null) }
    
    LaunchedEffect(fileId) {
        TdlibManager.getClient().send(TdApi.GetFile(fileId)) { result ->
            if (result is TdApi.File) {
                if (result.local.isDownloadingCompleted) {
                    localPath = result.local.path
                } else if (!result.local.isDownloadingActive) {
                    TdlibManager.getClient().send(TdApi.DownloadFile(fileId, 1, 0, 0, false)) { downloadResult ->
                        if (downloadResult is TdApi.File) {
                            // Download started
                        }
                    }
                }
            }
        }
    }
    
    LaunchedEffect(fileId, localPath) {
        if (localPath == null) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                TdlibManager.getClient().send(TdApi.GetFile(fileId)) { result ->
                    if (result is TdApi.File && result.local.isDownloadingCompleted) {
                        localPath = result.local.path
                    }
                }
                if (localPath != null) break
            }
        }
    }
    
    return localPath
}

class MainActivity : ComponentActivity() {

    private var deleteLauncher: ManagedActivityResultLauncher<IntentSenderRequest, androidx.activity.result.ActivityResult>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize TDLib core
        TdlibManager.initialize(applicationContext)

        setContent {
            // Standard dark theme matching high-end photo gallery aesthetics
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4285F4), // Google Blue
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    onPrimary = Color.White,
                    onBackground = Color(0xFFE3E3E3),
                    onSurface = Color(0xFFE3E3E3)
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
                // REPLACE will instantly apply new network constraints (e.g. Wi-Fi toggle changes)
                ExistingPeriodicWorkPolicy.REPLACE,
                request
            )
        
        val dataMsg = if (wifiOnly) "Wi-Fi Only (Data Saver Active)" else "Wi-Fi + Mobile Data allowed"
        runOnUiThread {
            Toast.makeText(this, "Backup active: $dataMsg", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun AppNavigation() {
    val context = LocalContext.current as MainActivity
    val authState by TdlibManager.authState.collectAsState()
    val chats by TdlibManager.chats.collectAsState()

    val selectedChatId = remember { mutableStateOf(PreferencesManager.getChatId(context)) }
    val selectedChatTitle = remember { mutableStateOf(PreferencesManager.getChatTitle(context)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F14))
    ) {
        when (authState) {
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                PhoneLoginScreen()
            }
            is TdApi.AuthorizationStateWaitCode -> {
                OtpVerifyScreen()
            }
            is TdApi.AuthorizationStateReady -> {
                if (selectedChatId.value == 0L) {
                    AutoVaultSetupScreen(
                        onSetupComplete = { chatId, chatTitle ->
                            selectedChatId.value = chatId
                            selectedChatTitle.value = chatTitle
                        }
                    )
                } else {
                    MainAppLayout(
                        selectedChatTitle = selectedChatTitle.value ?: "Telegram Backup Chat",
                        onResetChat = {
                            PreferencesManager.saveChatId(context, 0L)
                            PreferencesManager.saveChatTitle(context, "")
                            selectedChatId.value = 0L
                            selectedChatTitle.value = ""
                        }
                    )
                }
            }
            else -> {
                // Splash/loading state
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        tint = Color(0xFF4285F4),
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    CircularProgressIndicator(color = Color(0xFF4285F4))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Connecting to Telegram...",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun PhoneLoginScreen() {
    var phone by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    tint = Color(0xFF4285F4),
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "TeleGallery Sync",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Enter your phone number to authorize storage backup via Telegram",
                    color = Color(0xFF9E9E9E),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(28.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone Number", color = Color(0xFFBDBDBD)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF4285F4),
                        unfocusedBorderColor = Color(0xFF424242),
                        focusedLabelColor = Color(0xFF4285F4),
                        unfocusedLabelColor = Color(0xFFBDBDBD),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Spacer(modifier = Modifier.height(28.dp))
                Button(
                    onClick = {
                        isLoading = true
                        AuthManager.sendPhone(phone) {
                            isLoading = false
                        }
                    },
                    enabled = phone.isNotBlank() && !isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                    } else {
                        Text("Send OTP Code", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun OtpVerifyScreen() {
    var otp by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = Color(0xFF4285F4),
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Verify Code",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "An authorization code was sent to your official Telegram client.",
                    color = Color(0xFF9E9E9E),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(28.dp))
                OutlinedTextField(
                    value = otp,
                    onValueChange = { otp = it },
                    label = { Text("Code", color = Color(0xFFBDBDBD)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF4285F4),
                        unfocusedBorderColor = Color(0xFF424242),
                        focusedLabelColor = Color(0xFF4285F4),
                        unfocusedLabelColor = Color(0xFFBDBDBD),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Spacer(modifier = Modifier.height(28.dp))
                Button(
                    onClick = {
                        isLoading = true
                        AuthManager.verifyOtp(otp) {
                            isLoading = false
                        }
                    },
                    enabled = otp.isNotBlank() && !isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                    } else {
                        Text("Log In", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun AutoVaultSetupScreen(onSetupComplete: (Long, String) -> Unit) {
    val context = LocalContext.current as MainActivity
    val coroutineScope = rememberCoroutineScope()
    
    var progressText by remember { mutableStateOf("Initializing secure TeleGallery vault...") }
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
            val input = "TeleGallery-Secure-Salt-$userId"
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
                // Step 0: Check for existing TeleGallery channel and verify key signature in pinned message
                withContext(Dispatchers.Main) {
                    progressText = "Searching for existing 'TeleGallery' vault..."
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
                            if (chat is TdApi.Chat && chat.title.equals("TeleGallery", ignoreCase = true)) {
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
                            query = "TeleGallery"
                            limit = 10
                        }
                        val searchResult = sendRequest(searchRequest)
                        if (searchResult is TdApi.Chats) {
                            for (chatId in searchResult.chatIds) {
                                val chat = sendRequest(TdApi.GetChat(chatId))
                                if (chat is TdApi.Chat && chat.title.equals("TeleGallery", ignoreCase = true)) {
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
                        progressText = "Existing 'TeleGallery' vault verified! Linking..."
                    }
                    
                    PreferencesManager.saveChatId(context, existingChatId)
                    PreferencesManager.saveChatTitle(context, "TeleGallery (Private Vault)")
                    
                    context.scheduleSyncWorker()
                    
                    withContext(Dispatchers.Main) {
                        progressText = "Vault linked successfully! Opening gallery..."
                        kotlinx.coroutines.delay(1000)
                        onSetupComplete(existingChatId, "TeleGallery (Private Vault)")
                    }
                    return@launch
                }
                
                // Step 1: Create a private Telegram channel named TeleGallery
                withContext(Dispatchers.Main) {
                    progressText = "Creating private backup channel 'TeleGallery' on Telegram..."
                }
                
                val createRequest = TdApi.CreateNewSupergroupChat().apply {
                    title = "TeleGallery"
                    isChannel = true
                    description = "TeleGallery secure photo backup repository. Please do not delete."
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
                    
                    val welcomeText = "🔑 TeleGallery Secure Backup Vault Initialized!\n\n" +
                            "Vault Key: `$uniqueKey`\n" +
                            "Vault Signature: `TG-SIG-$signature`\n\n" +
                            "This private channel is used exclusively by your TeleGallery app to securely back up your photos. " +
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
                    PreferencesManager.saveChatTitle(context, "TeleGallery (Private Vault)")
                    
                    // Schedule background sync worker
                    context.scheduleSyncWorker()
                    
                    withContext(Dispatchers.Main) {
                        progressText = "Vault ready! Opening your gallery..."
                        kotlinx.coroutines.delay(1000)
                        onSetupComplete(newChatId, "TeleGallery (Private Vault)")
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
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F14)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    tint = Color(0xFF4285F4),
                    modifier = Modifier.size(72.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
                
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
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorText!!,
                        color = Color(0xFFFF5252),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            errorText = null
                            context.recreate()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Retry Setup", fontWeight = FontWeight.Bold)
                    }
                } else {
                    CircularProgressIndicator(color = Color(0xFF4285F4))
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Setting Up Secure Vault",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = progressText,
                        color = Color(0xFF9E9E9E),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun MainAppLayout(
    selectedChatTitle: String,
    onResetChat: () -> Unit
) {
    var activeTab by remember { mutableStateOf("Photos") }
    
    // Manage which photo is currently opened in full screen
    var fullScreenPhotoIndex by remember { mutableStateOf<Int?>(null) }
    var devicePhotosList by remember { mutableStateOf<List<LocalPhoto>>(emptyList()) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF1E1E1E),
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = activeTab == "Photos",
                    onClick = { activeTab = "Photos" },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Photos") },
                    label = { Text("Photos") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF4285F4),
                        selectedTextColor = Color(0xFF4285F4),
                        unselectedIconColor = Color(0xFF757575),
                        unselectedTextColor = Color(0xFF757575),
                        indicatorColor = Color(0xFF2C2C2C)
                    )
                )
                NavigationBarItem(
                    selected = activeTab == "Settings",
                    onClick = { activeTab = "Settings" },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF4285F4),
                        selectedTextColor = Color(0xFF4285F4),
                        unselectedIconColor = Color(0xFF757575),
                        unselectedTextColor = Color(0xFF757575),
                        indicatorColor = Color(0xFF2C2C2C)
                    )
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Android System Back Button Handlers
            if (fullScreenPhotoIndex != null && devicePhotosList.isNotEmpty()) {
                BackHandler {
                    fullScreenPhotoIndex = null
                    devicePhotosList = emptyList()
                }
            } else if (activeTab == "Settings") {
                BackHandler {
                    activeTab = "Photos"
                }
            }

            when (activeTab) {
                "Photos" -> {
                    PhotosGridScreen(
                        onPhotoSelected = { index, photos ->
                            fullScreenPhotoIndex = index
                            devicePhotosList = photos
                        }
                    )
                }
                "Settings" -> {
                    SettingsScreen(
                        selectedChatTitle = selectedChatTitle,
                        onResetChat = onResetChat
                    )
                }
            }

            // Animate Full Screen Photo Viewer
            AnimatedVisibility(
                visible = fullScreenPhotoIndex != null && devicePhotosList.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                if (fullScreenPhotoIndex != null && devicePhotosList.isNotEmpty()) {
                    PhotoViewerScreen(
                        photosList = devicePhotosList,
                        startIndex = fullScreenPhotoIndex!!,
                        onClose = {
                            fullScreenPhotoIndex = null
                            devicePhotosList = emptyList()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PhotosGridScreen(
    onPhotoSelected: (Int, List<LocalPhoto>) -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
    }

    if (!hasPermission) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFF4B400),
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Storage Access Required",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "To show your local device photos and back them up, TeleGallery requires permission to access storage.",
                color = Color(0xFF9E9E9E),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        launcher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                    } else {
                        launcher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Grant Permission", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    } else {
        // Load Photos and show grid
        var localPhotos by remember { mutableStateOf<List<LocalPhoto>>(emptyList()) }
        var isScanningLocal by remember { mutableStateOf(true) }
        val db = remember { UploadDatabase.getDatabase(context) }
        
        val uploadedLogs by db.dao().getAllFlow().collectAsState(initial = emptyList())
        val cloudLogs by db.cloudDao().getAllFlow().collectAsState(initial = emptyList())
        val uploadedUris = remember(uploadedLogs) { uploadedLogs.map { it.path }.toSet() }
        val syncedCloudFilenames = remember(cloudLogs) { cloudLogs.map { it.fileName }.toSet() }

        val coroutineScope = rememberCoroutineScope()
        val chatId = remember { PreferencesManager.getChatId(context) }

        LaunchedEffect(Unit) {
            coroutineScope.launch(Dispatchers.IO) {
                val scanned = MediaStoreScanner.scan(context)
                withContext(Dispatchers.Main) {
                    localPhotos = scanned
                    isScanningLocal = false
                }
                
                // Trigger background server vault index crawl
                if (chatId != 0L) {
                    try {
                        TdlibManager.syncCloudHistory(context, chatId)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        // Merge, deduplicate, and sort Local + Cloud photos
        val unifiedPhotos = remember(localPhotos, uploadedLogs, cloudLogs) {
            val localMap = localPhotos.associateBy { it.name }
            val list = mutableListOf<LocalPhoto>()
            val tempSyncedCloudFilenames = mutableSetOf<String>()
            
            for (cloud in cloudLogs) {
                val matchingLocal = localMap[cloud.fileName]
                if (matchingLocal != null) {
                    list.add(matchingLocal)
                    tempSyncedCloudFilenames.add(cloud.fileName)
                } else {
                    val parsedDate = parseDateFromFilename(cloud.fileName)
                    val displayDate = parsedDate ?: cloud.uploadedAt
                    list.add(
                        LocalPhoto(
                            id = -cloud.messageId,
                            uri = "cloud://${cloud.messageId}/${cloud.telegramFileId}/${cloud.fileName}",
                            name = cloud.fileName,
                            size = cloud.fileSize,
                            dateTaken = displayDate
                        )
                    )
                }
            }
            
            for (local in localPhotos) {
                if (!tempSyncedCloudFilenames.contains(local.name)) {
                    list.add(local)
                }
            }
            
            list.sortedByDescending { it.dateTaken }
        }

        if (isScanningLocal && localPhotos.isEmpty() && cloudLogs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF4285F4))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Scanning local photos...", color = Color.White, fontSize = 14.sp)
                }
            }
        } else {
            // Group photos by date header
            val galleryItems = remember(unifiedPhotos) {
                val grouped = unifiedPhotos.groupBy { photo ->
                    val sdf = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault())
                    sdf.format(Date(photo.dateTaken))
                }
                val items = mutableListOf<GalleryItem>()
                for ((date, list) in grouped) {
                    items.add(GalleryItem.Header(date))
                    for (photo in list) {
                        items.add(GalleryItem.PhotoItem(photo))
                    }
                }
                items
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // Top App Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E1E1E))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Cloud,
                            contentDescription = null,
                            tint = Color(0xFF4285F4),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "TeleGallery",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = if (localPhotos.isNotEmpty()) "${uploadedUris.size}/${localPhotos.size} Synced" else "${cloudLogs.size} Cloud Photos",
                        color = Color(0xFF4285F4),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Color(0x1F4285F4), shape = RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                // Grid
                // Grid wrapper with fast scrollbar
                val gridState = rememberLazyGridState()
                Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                    items(
                        items = galleryItems,
                        span = { item ->
                            if (item is GalleryItem.Header) {
                                GridItemSpan(maxLineSpan)
                            } else {
                                GridItemSpan(1)
                            }
                        }
                    ) { item ->
                        when (item) {
                            is GalleryItem.Header -> {
                                Text(
                                    text = item.date,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF0F0F14))
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                            is GalleryItem.PhotoItem -> {
                                val photo = item.photo
                                val isCloud = photo.uri.startsWith("cloud://")
                                val isSynced = if (isCloud) true else (uploadedUris.contains(photo.uri) || syncedCloudFilenames.contains(photo.name))
                                
                                // Map LocalPhoto back to its index in unifiedPhotos
                                val photoIndex = remember(photo, unifiedPhotos) {
                                    unifiedPhotos.indexOfFirst { it.uri == photo.uri }
                                }

                                val cloudThumbnailPath = if (isCloud) {
                                    val triple = parseCloudPhotoUri(photo.uri)
                                    if (triple != null) {
                                        rememberCloudThumbnailPath(triple.second)
                                    } else null
                                } else null

                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clickable {
                                            if (photoIndex != -1) {
                                                onPhotoSelected(photoIndex, unifiedPhotos)
                                            }
                                        }
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(cloudThumbnailPath ?: photo.uri)
                                            .size(256) // Thumbnails only to prevent memory crashes
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    
                                    // Subtle gradient at bottom-right for badge legibility
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .size(36.dp)
                                            .background(
                                                Brush.radialGradient(
                                                    colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                                                )
                                            )
                                    )

                                    // Cloud Backup state icon overlay
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(6.dp)
                                    ) {
                                        if (isCloud) {
                                            Icon(
                                                imageVector = Icons.Default.Cloud,
                                                contentDescription = "Cloud Only",
                                                tint = Color(0xFF4285F4), // Premium Google Blue
                                                modifier = Modifier.size(16.dp)
                                            )
                                        } else if (isSynced) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Synced",
                                                tint = Color(0xFF00E676), // Bright Green
                                                modifier = Modifier.size(16.dp)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Cloud,
                                                contentDescription = "Pending Sync",
                                                tint = Color.White.copy(alpha = 0.5f), // Dim cloud outline
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Fast scrollbar at the right edge
                if (galleryItems.size > 10) {
                    var isDragging by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(30.dp)
                            .align(Alignment.CenterEnd)
                            .pointerInput(galleryItems.size) {
                                detectTapGestures { offset ->
                                    val ratio = (offset.y / size.height).coerceIn(0f, 1f)
                                    val targetIndex = (ratio * galleryItems.size).toInt().coerceIn(0, galleryItems.size - 1)
                                    coroutineScope.launch {
                                        gridState.scrollToItem(targetIndex)
                                    }
                                }
                            }
                            .pointerInput(galleryItems.size) {
                                detectDragGestures(
                                    onDragStart = { isDragging = true },
                                    onDragEnd = { isDragging = false },
                                    onDragCancel = { isDragging = false }
                                ) { change, dragAmount ->
                                    change.consume()
                                    val y = change.position.y
                                    val ratio = (y / size.height).coerceIn(0f, 1f)
                                    val targetIndex = (ratio * galleryItems.size).toInt().coerceIn(0, galleryItems.size - 1)
                                    coroutineScope.launch {
                                        gridState.scrollToItem(targetIndex)
                                    }
                                }
                            }
                    ) {
                        // Draggable scrollbar thumb
                        val thumbHeight = 60.dp
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 11.dp)
                                .width(8.dp)
                                .height(thumbHeight)
                                .graphicsLayer {
                                    val scrollPercent = if (galleryItems.isNotEmpty()) {
                                        gridState.firstVisibleItemIndex.toFloat() / galleryItems.size.toFloat()
                                    } else 0f
                                    translationY = scrollPercent * (size.height - thumbHeight.toPx())
                                }
                                .background(
                                    color = if (isDragging) Color(0xFF4285F4) else Color.White.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(4.dp)
                                )
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
fun SettingsScreen(
    selectedChatTitle: String,
    onResetChat: () -> Unit
) {
    val context = LocalContext.current as MainActivity
    val chats by TdlibManager.chats.collectAsState()
    val systemLogs by TdlibManager.logs.collectAsState()
    var showChatPickerDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Backup Settings",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Selected Chat Display card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Backup Target Chat",
                        color = Color(0xFF9E9E9E),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = selectedChatTitle,
                            color = Color(0xFF4285F4),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            TdlibManager.loadChats()
                            showChatPickerDialog = true
                        }) {
                            Text("Change", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Core Backup Preferences Toggle Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Backup Options",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Toggle 1: Start or Stop Backup Engine
                    var backupActive by remember { mutableStateOf(PreferencesManager.isBackupActive(context)) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Active Backup Sync", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Auto-backup your local gallery in background", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                        }
                        Switch(
                            checked = backupActive,
                            onCheckedChange = { checked ->
                                backupActive = checked
                                PreferencesManager.setBackupActive(context, checked)
                                context.scheduleSyncWorker()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4285F4))
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = Color(0xFF2C2C35))
                    Spacer(modifier = Modifier.height(14.dp))

                    // Toggle 2: Wifi Only (Mobile Data Saver Toggle)
                    var wifiOnly by remember { mutableStateOf(PreferencesManager.isWifiOnly(context)) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Back Up Over Wi-Fi Only", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Do not use mobile cellular data for backups", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                        }
                        Switch(
                            checked = wifiOnly,
                            onCheckedChange = { checked ->
                                wifiOnly = checked
                                PreferencesManager.setWifiOnly(context, checked)
                                context.scheduleSyncWorker()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4285F4))
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = Color(0xFF2C2C35))
                    Spacer(modifier = Modifier.height(14.dp))

                    // Toggle 3: Send Photos in HD Lossless Quality
                    var hdMode by remember { mutableStateOf(PreferencesManager.isHdMode(context)) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Send Photos in HD Quality", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Upload original lossless files without quality loss", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                        }
                        Switch(
                            checked = hdMode,
                            onCheckedChange = { checked ->
                                hdMode = checked
                                PreferencesManager.setHdMode(context, checked)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4285F4))
                        )
                    }
                }
            }
        }

        // Action Buttons
        item {
            Button(
                onClick = {
                    val isBackupActive = PreferencesManager.isBackupActive(context)
                    if (!isBackupActive) {
                        Toast.makeText(context, "Please enable 'Active Backup Sync' first!", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val oneTimeWorkRequest = OneTimeWorkRequestBuilder<UploadWorker>().build()
                    WorkManager.getInstance(context).enqueue(oneTimeWorkRequest)
                    Toast.makeText(context, "Force sync started in background!", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(14.dp)
            ) {
                Icon(Icons.Default.Cloud, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Force Synchronize Local Photos Now", fontWeight = FontWeight.Bold)
            }
        }

        item {
            OutlinedButton(
                onClick = {
                    AuthManager.logOut {
                        coroutineScope.launch(Dispatchers.Main) {
                            Toast.makeText(context, "Logged out of Telegram successfully", Toast.LENGTH_LONG).show()
                            onResetChat()
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Color(0xFFFF5252)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252))
            ) {
                Text("Logout Telegram Session", fontWeight = FontWeight.Bold)
            }
        }

        // System JNI Core Logs Console
        item {
            Text(
                text = "Active JNI Core Logs",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF09090C)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(systemLogs) { log ->
                        Text(
                            text = log,
                            color = when {
                                log.contains("fail", ignoreCase = true) || log.contains("error", ignoreCase = true) -> Color(0xFFFF5252)
                                log.contains("success", ignoreCase = true) -> Color(0xFF00E676)
                                else -> Color(0xFFB0BEC5)
                            },
                            fontSize = 10.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }

    // Modal popup to select active chat
    if (showChatPickerDialog) {
        Dialog(onDismissRequest = { showChatPickerDialog = false }) {
            var searchQuery by remember { mutableStateOf("") }
            val filteredChats = remember(chats, searchQuery) {
                chats.filter { it.title.contains(searchQuery, ignoreCase = true) }
            }

            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.75f)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Change Target Chat",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Dialog Search Bar Input
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search chats...", color = Color(0xFF757575)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color(0xFF757575)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear search", tint = Color(0xFFBDBDBD))
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF4285F4),
                            unfocusedBorderColor = Color(0xFF424242),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )

                    if (chats.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(0xFF4285F4))
                        }
                    } else {
                        if (filteredChats.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No chats match your search", color = Color(0xFF9E9E9E), fontSize = 14.sp)
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(filteredChats) { chat ->
                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C35)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                PreferencesManager.saveChatId(context, chat.id)
                                                PreferencesManager.saveChatTitle(context, chat.title)
                                                onResetChat() // Triggers UI redraw in parent
                                                showChatPickerDialog = false
                                            }
                                    ) {
                                        Text(
                                            text = chat.title.ifEmpty { "Saved Messages" },
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerScreen(
    photosList: List<LocalPhoto>,
    startIndex: Int,
    onClose: () -> Unit
) {
    val context = LocalContext.current as MainActivity
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { photosList.size })
    val currentPhoto = photosList.getOrNull(pagerState.currentPage)

    val db = remember { UploadDatabase.getDatabase(context) }
    val uploadedLogs by db.dao().getAllFlow().collectAsState(initial = emptyList())
    val uploadedUris = remember(uploadedLogs) { uploadedLogs.map { it.path }.toSet() }
    val cloudLogs by db.cloudDao().getAllFlow().collectAsState(initial = emptyList())
    val syncedCloudFilenames = remember(cloudLogs) { cloudLogs.map { it.fileName }.toSet() }

    val coroutineScope = rememberCoroutineScope()

    var isBackingUpSingle by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // High resolution Horizontal Pager
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val photo = photosList.getOrNull(page)
            if (photo != null) {
                val isCloud = photo.uri.startsWith("cloud://")
                val cloudFileId = if (isCloud) {
                    parseCloudPhotoUri(photo.uri)?.second
                } else null

                var fullResPath by remember(photo.uri) { mutableStateOf<String?>(null) }
                var isDownloading by remember(photo.uri) { mutableStateOf(false) }

                LaunchedEffect(photo.uri, cloudFileId) {
                    if (cloudFileId != null) {
                        isDownloading = true
                        TdlibManager.getClient().send(TdApi.GetFile(cloudFileId)) { result ->
                            if (result is TdApi.File) {
                                if (result.local.isDownloadingCompleted) {
                                    fullResPath = result.local.path
                                    isDownloading = false
                                } else {
                                    TdlibManager.getClient().send(TdApi.DownloadFile(cloudFileId, 32, 0, 0, false)) { downloadResult ->
                                        // started
                                    }
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(photo.uri, cloudFileId, fullResPath) {
                    if (cloudFileId != null && fullResPath == null) {
                        while (true) {
                            kotlinx.coroutines.delay(1000)
                            TdlibManager.getClient().send(TdApi.GetFile(cloudFileId)) { result ->
                                if (result is TdApi.File && result.local.isDownloadingCompleted) {
                                    fullResPath = result.local.path
                                    isDownloading = false
                                }
                            }
                            if (fullResPath != null) break
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(fullResPath ?: photo.uri)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    if (isCloud && isDownloading && fullResPath == null) {
                        CircularProgressIndicator(color = Color(0xFF4285F4))
                    }
                }
            }
        }

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = currentPhoto?.name ?: "Photo",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${pagerState.currentPage + 1} of ${photosList.size}",
                    color = Color(0xFFBDBDBD),
                    fontSize = 11.sp
                )
            }
        }

        // Bottom action bar
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                .padding(vertical = 24.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Share icon
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable {
                    if (currentPhoto != null) {
                        try {
                            val uri = Uri.parse(currentPhoto.uri)
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Photo"))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Sharing failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            ) {
                Icon(imageVector = Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Share", color = Color.White, fontSize = 11.sp)
            }

            // Back up single photo manually OR Download to Device if cloud-only
            if (currentPhoto != null) {
                val isCloud = currentPhoto.uri.startsWith("cloud://")
                val isSynced = if (isCloud) true else (uploadedUris.contains(currentPhoto.uri) || syncedCloudFilenames.contains(currentPhoto.name))
                
                if (isCloud) {
                    var isDownloadingToDevice by remember(currentPhoto.uri) { mutableStateOf(false) }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable(enabled = !isDownloadingToDevice) {
                            val triple = parseCloudPhotoUri(currentPhoto.uri)
                            if (triple != null) {
                                isDownloadingToDevice = true
                                TdlibManager.getClient().send(TdApi.GetFile(triple.second)) { result ->
                                    if (result is TdApi.File && result.local.isDownloadingCompleted) {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            try {
                                                val source = java.io.File(result.local.path)
                                                val dest = java.io.File(
                                                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                                                    triple.third
                                                )
                                                source.copyTo(dest, overwrite = true)
                                                withContext(Dispatchers.Main) {
                                                    isDownloadingToDevice = false
                                                    Toast.makeText(context, "Saved to Downloads: ${dest.name}", Toast.LENGTH_LONG).show()
                                                }
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                    isDownloadingToDevice = false
                                                    Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    } else {
                                        isDownloadingToDevice = false
                                        Toast.makeText(context, "Please wait for high-res photo to load first!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    ) {
                        if (isDownloadingToDevice) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color(0xFF4285F4))
                        } else {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = "Download to Device",
                                tint = Color(0xFF4285F4)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Save to Device",
                            color = Color(0xFF4285F4),
                            fontSize = 11.sp
                        )
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable(enabled = !isSynced && !isBackingUpSingle) {
                            val chatId = PreferencesManager.getChatId(context)
                            if (chatId == 0L) {
                                Toast.makeText(context, "Please set a target chat in settings first!", Toast.LENGTH_SHORT).show()
                                return@clickable
                            }
                            
                            val isHd = PreferencesManager.isHdMode(context)
                            isBackingUpSingle = true
                            
                            coroutineScope.launch(Dispatchers.IO) {
                                val result = UploadManager.uploadPhoto(context, currentPhoto, chatId, isHd)
                                withContext(Dispatchers.Main) {
                                    isBackingUpSingle = false
                                    if (result is TdApi.Message) {
                                        val modeStr = if (isHd) "in HD quality" else "in standard quality"
                                        Toast.makeText(context, "Backed up successfully $modeStr!", Toast.LENGTH_SHORT).show()
                                        db.dao().insert(
                                            UploadEntity(
                                                path = currentPhoto.uri,
                                                uploadedAt = System.currentTimeMillis()
                                            )
                                        )
                                    } else if (result is TdApi.Error) {
                                        Toast.makeText(context, "Upload failed: ${result.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    ) {
                        if (isBackingUpSingle) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color(0xFF4285F4))
                        } else {
                            Icon(
                                imageVector = if (isSynced) Icons.Default.Check else Icons.Default.Cloud,
                                contentDescription = "Backup",
                                tint = if (isSynced) Color(0xFF00E676) else Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isSynced) "Backed Up" else "Back Up Now",
                            color = if (isSynced) Color(0xFF00E676) else Color.White,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Delete locally
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable {
                    if (currentPhoto != null) {
                        context.triggerDelete(currentPhoto)
                    }
                }
            ) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252))
                Spacer(modifier = Modifier.height(4.dp))
                Text("Delete", color = Color(0xFFFF5252), fontSize = 11.sp)
            }
        }
    }
}
