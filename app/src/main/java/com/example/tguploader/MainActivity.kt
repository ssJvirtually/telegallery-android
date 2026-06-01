package com.example.tguploader

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.shadow
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

object TelePhotosTheme {
    val Background = Color(0xFFF4F6F9)         // Telegram Light Off-White
    val Surface = Color(0xFFFFFFFF)            // Pure White
    val SurfaceVariant = Color(0xFFE8EDF5)     // Light Blue-Grey
    val AccentBlue = Color(0xFF2481CC)         // Telegram Primary Blue
    val Primary = Color(0xFF0088CC)            // Telegram Standard Blue
    
    // Google multi-colors for micro-accents
    val GoogleBlue = Color(0xFF4285F4)
    val GoogleRed = Color(0xFFEA4335)
    val GoogleYellow = Color(0xFFFBBC05)
    val GoogleGreen = Color(0xFF34A853)
    
    val TextPrimary = Color(0xFF0E1621)        // Telegram Ultra-Dark Grey/Black text
    val TextSecondary = Color(0xFF707C8E)      // refined cool-grey secondary labels
}

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
    private var deleteMultipleLauncher: ManagedActivityResultLauncher<IntentSenderRequest, androidx.activity.result.ActivityResult>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize TDLib core
        TdlibManager.initialize(applicationContext)

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
            .background(TelePhotosTheme.Background)
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
                        color = TelePhotosTheme.TextPrimary,
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
        modifier = Modifier.fillMaxSize().background(TelePhotosTheme.Background),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = TelePhotosTheme.Surface),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Telegram paper airplane icon styled with a Google Photos color ring!
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            Brush.sweepGradient(
                                colors = listOf(
                                    TelePhotosTheme.GoogleBlue,
                                    TelePhotosTheme.GoogleGreen,
                                    TelePhotosTheme.GoogleYellow,
                                    TelePhotosTheme.GoogleRed,
                                    TelePhotosTheme.GoogleBlue
                                )
                             ),
                             shape = CircleShape
                        )
                        .padding(3.dp)
                        .background(TelePhotosTheme.Surface, shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Send, // Elegant paper airplane
                        contentDescription = null,
                        tint = TelePhotosTheme.AccentBlue,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row {
                    Text(
                        text = "Tele",
                        color = TelePhotosTheme.AccentBlue,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Gallery",
                        color = TelePhotosTheme.TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Enter your phone number to authorize storage backup via Telegram",
                    color = TelePhotosTheme.TextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(28.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone Number", color = TelePhotosTheme.TextSecondary) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TelePhotosTheme.AccentBlue,
                        unfocusedBorderColor = TelePhotosTheme.SurfaceVariant,
                        focusedLabelColor = TelePhotosTheme.AccentBlue,
                        unfocusedLabelColor = TelePhotosTheme.TextSecondary,
                        focusedTextColor = TelePhotosTheme.TextPrimary,
                        unfocusedTextColor = TelePhotosTheme.TextPrimary
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
                    colors = ButtonDefaults.buttonColors(containerColor = TelePhotosTheme.AccentBlue),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                    } else {
                        Text("Send OTP Code", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
        modifier = Modifier.fillMaxSize().background(TelePhotosTheme.Background),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = TelePhotosTheme.Surface),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Key / lock verification icon styled with a Google Photos color ring!
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            Brush.sweepGradient(
                                colors = listOf(
                                    TelePhotosTheme.GoogleBlue,
                                    TelePhotosTheme.GoogleGreen,
                                    TelePhotosTheme.GoogleYellow,
                                    TelePhotosTheme.GoogleRed,
                                    TelePhotosTheme.GoogleBlue
                                )
                             ),
                             shape = CircleShape
                        )
                        .padding(3.dp)
                        .background(TelePhotosTheme.Surface, shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock, // Elegant key lock
                        contentDescription = null,
                        tint = TelePhotosTheme.AccentBlue,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Verify Code",
                    color = TelePhotosTheme.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "An authorization code was sent to your official Telegram client.",
                    color = TelePhotosTheme.TextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(28.dp))
                OutlinedTextField(
                    value = otp,
                    onValueChange = { otp = it },
                    label = { Text("Code", color = TelePhotosTheme.TextSecondary) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TelePhotosTheme.AccentBlue,
                        unfocusedBorderColor = TelePhotosTheme.SurfaceVariant,
                        focusedLabelColor = TelePhotosTheme.AccentBlue,
                        unfocusedLabelColor = TelePhotosTheme.TextSecondary,
                        focusedTextColor = TelePhotosTheme.TextPrimary,
                        unfocusedTextColor = TelePhotosTheme.TextPrimary
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
                    colors = ButtonDefaults.buttonColors(containerColor = TelePhotosTheme.AccentBlue),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                    } else {
                        Text("Log In", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
            .background(TelePhotosTheme.Background),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = TelePhotosTheme.Surface),
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
                        color = TelePhotosTheme.TextPrimary,
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
                        color = TelePhotosTheme.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
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
                containerColor = TelePhotosTheme.Surface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = activeTab == "Photos",
                    onClick = { activeTab = "Photos" },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Photos") },
                    label = { Text("Photos") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TelePhotosTheme.AccentBlue,
                        selectedTextColor = TelePhotosTheme.AccentBlue,
                        unselectedIconColor = TelePhotosTheme.TextSecondary,
                        unselectedTextColor = TelePhotosTheme.TextSecondary,
                        indicatorColor = TelePhotosTheme.SurfaceVariant
                    )
                )
                NavigationBarItem(
                    selected = activeTab == "Search",
                    onClick = { activeTab = "Search" },
                    icon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    label = { Text("Search") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TelePhotosTheme.AccentBlue,
                        selectedTextColor = TelePhotosTheme.AccentBlue,
                        unselectedIconColor = TelePhotosTheme.TextSecondary,
                        unselectedTextColor = TelePhotosTheme.TextSecondary,
                        indicatorColor = TelePhotosTheme.SurfaceVariant
                    )
                )
                NavigationBarItem(
                    selected = activeTab == "Settings",
                    onClick = { activeTab = "Settings" },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TelePhotosTheme.AccentBlue,
                        selectedTextColor = TelePhotosTheme.AccentBlue,
                        unselectedIconColor = TelePhotosTheme.TextSecondary,
                        unselectedTextColor = TelePhotosTheme.TextSecondary,
                        indicatorColor = TelePhotosTheme.SurfaceVariant
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
            } else if (activeTab == "Search") {
                BackHandler {
                    activeTab = "Photos"
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
                "Search" -> {
                    SearchScreen(
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

@OptIn(ExperimentalFoundationApi::class)
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
                color = TelePhotosTheme.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "To show your local device photos and back them up, TeleGallery requires permission to access storage.",
                color = TelePhotosTheme.TextSecondary,
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
                colors = ButtonDefaults.buttonColors(containerColor = TelePhotosTheme.AccentBlue),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Grant Permission", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    } else {
        // Load Photos and show grid
        var localPhotos by remember { mutableStateOf<List<LocalPhoto>>(emptyList()) }
        var isScanningLocal by remember { mutableStateOf(true) }
        var isSelectionMode by remember { mutableStateOf(false) }
        val selectedPhotos = remember { mutableStateListOf<LocalPhoto>() }

        if (isSelectionMode) {
            BackHandler {
                selectedPhotos.clear()
                isSelectionMode = false
            }
        }
        val db = remember { UploadDatabase.getDatabase(context) }
        
        val uploadedLogs by db.dao().getAllFlow().collectAsState(initial = emptyList())
        val cloudLogs by db.cloudDao().getAllFlow().collectAsState(initial = emptyList())
        val uploadedUris = remember(uploadedLogs) { uploadedLogs.map { it.path }.toSet() }
        val syncedCloudFilenames = remember(cloudLogs) { cloudLogs.map { it.fileName }.toSet() }

        val coroutineScope = rememberCoroutineScope()
        val chatId = remember { PreferencesManager.getChatId(context) }

        LaunchedEffect(Unit) {
            coroutineScope.launch(Dispatchers.IO) {
                // 1. Attempt to restore local database from remote Telegram backup if cache is empty
                if (chatId != 0L) {
                    try {
                        com.example.tguploader.storage.BackupManager.restoreDatabase(context, chatId)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

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

        // 2. Event-driven debounced backup synchronization (checks every 5 minutes after last idle state change)
        val totalRecords = cloudLogs.size + uploadedLogs.size
        LaunchedEffect(totalRecords) {
            if (chatId != 0L && totalRecords > 0) {
                val lastBackupCount = PreferencesManager.getLastBackupRecordCount(context)
                if (totalRecords != lastBackupCount) {
                    delay(300_000) // Debounce 5 minutes of idle time
                    com.example.tguploader.storage.BackupManager.backupDatabase(context, chatId)
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
                    CircularProgressIndicator(color = TelePhotosTheme.AccentBlue)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Scanning local photos...", color = TelePhotosTheme.TextPrimary, fontSize = 14.sp)
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

            Column(modifier = Modifier.fillMaxSize().background(TelePhotosTheme.Background)) {
                if (isSelectionMode) {
                    // Selection Mode Top Action Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TelePhotosTheme.Surface)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                selectedPhotos.clear()
                                isSelectionMode = false
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close selection",
                                    tint = TelePhotosTheme.TextPrimary
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${selectedPhotos.size} selected",
                                color = TelePhotosTheme.TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Batch Share Action
                            IconButton(onClick = {
                                if (selectedPhotos.isNotEmpty()) {
                                    try {
                                        val shareUris = ArrayList<Uri>().apply {
                                            addAll(selectedPhotos.map { Uri.parse(it.uri) })
                                        }
                                        val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                            type = "image/*"
                                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share Photos"))
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Sharing failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share selected",
                                    tint = TelePhotosTheme.AccentBlue
                                )
                            }
                            
                            // Batch Backup Action
                            var isBackingUpMultiple by remember { mutableStateOf(false) }
                            IconButton(
                                onClick = {
                                    if (selectedPhotos.isNotEmpty()) {
                                        if (chatId == 0L) {
                                            Toast.makeText(context, "Please set a target chat in settings first!", Toast.LENGTH_SHORT).show()
                                            return@IconButton
                                        }
                                        isBackingUpMultiple = true
                                        val isHd = PreferencesManager.isHdMode(context)
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val unsyncedSelected = selectedPhotos.filter { photo ->
                                                val isCloud = photo.uri.startsWith("cloud://")
                                                !(isCloud || uploadedUris.contains(photo.uri) || syncedCloudFilenames.contains(photo.name))
                                            }
                                            val totalToSync = unsyncedSelected.size
                                            
                                            if (totalToSync == 0) {
                                                withContext(Dispatchers.Main) {
                                                    isBackingUpMultiple = false
                                                    Toast.makeText(context, "All selected photos are already synced!", Toast.LENGTH_SHORT).show()
                                                    selectedPhotos.clear()
                                                    isSelectionMode = false
                                                }
                                                return@launch
                                            }
                                            
                                            var successCount = 0
                                            for (photo in unsyncedSelected) {
                                                val res = UploadManager.uploadPhoto(context, photo, chatId, isHd)
                                                if (res is TdApi.Message) {
                                                    db.dao().insert(
                                                        UploadEntity(
                                                            path = photo.uri,
                                                            uploadedAt = System.currentTimeMillis()
                                                        )
                                                    )
                                                    successCount++
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(context, "Synced $successCount of $totalToSync photos...", Toast.LENGTH_SHORT).show()
                                                    }
                                                    delay(5000)
                                                }
                                            }
                                            withContext(Dispatchers.Main) {
                                                isBackingUpMultiple = false
                                                Toast.makeText(context, "Batch backup complete: Synced $successCount of $totalToSync photos!", Toast.LENGTH_LONG).show()
                                                selectedPhotos.clear()
                                                isSelectionMode = false
                                            }
                                        }
                                    }
                                },
                                enabled = !isBackingUpMultiple
                            ) {
                                if (isBackingUpMultiple) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = TelePhotosTheme.AccentBlue)
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Cloud,
                                        contentDescription = "Backup selected",
                                        tint = TelePhotosTheme.AccentBlue
                                    )
                                }
                            }
                            
                            // Batch Delete Action
                            IconButton(onClick = {
                                if (selectedPhotos.isNotEmpty()) {
                                    (context as MainActivity).triggerBatchDelete(selectedPhotos.toList())
                                    selectedPhotos.clear()
                                    isSelectionMode = false
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete selected from device",
                                    tint = TelePhotosTheme.GoogleRed
                                )
                            }
                        }
                    }
                } else {
                    // Top App Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TelePhotosTheme.Surface)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Telegram paper airplane icon styled with a Google Photos color ring!
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(
                                        Brush.sweepGradient(
                                            colors = listOf(
                                                TelePhotosTheme.GoogleBlue,
                                                TelePhotosTheme.GoogleGreen,
                                                TelePhotosTheme.GoogleYellow,
                                                TelePhotosTheme.GoogleRed,
                                                TelePhotosTheme.GoogleBlue
                                            )
                                        ),
                                        shape = CircleShape
                                    )
                                    .padding(2.dp)
                                    .background(TelePhotosTheme.Surface, shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Send, // Elegant paper airplane
                                    contentDescription = null,
                                    tint = TelePhotosTheme.AccentBlue,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Row {
                                Text(
                                    text = "Tele",
                                    color = TelePhotosTheme.AccentBlue,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Gallery",
                                    color = TelePhotosTheme.TextPrimary,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        Text(
                            text = if (localPhotos.isNotEmpty()) "${uploadedUris.size}/${localPhotos.size} Synced" else "${cloudLogs.size} Cloud Photos",
                            color = TelePhotosTheme.AccentBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(Color(0x1F2481CC), shape = RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
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
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(TelePhotosTheme.Background)
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(4.dp)
                                            .height(16.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(
                                                Brush.verticalGradient(
                                                    colors = listOf(
                                                        TelePhotosTheme.AccentBlue,
                                                        TelePhotosTheme.GoogleGreen,
                                                        TelePhotosTheme.GoogleYellow,
                                                        TelePhotosTheme.GoogleRed
                                                    )
                                                )
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = item.date,
                                        color = TelePhotosTheme.TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
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

                                val isSelected = selectedPhotos.any { it.uri == photo.uri }
                                val haptic = LocalHapticFeedback.current
                                val animatedPadding by animateDpAsState(
                                    targetValue = if (isSelected) 8.dp else 0.dp,
                                    label = "padding"
                                )

                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .background(if (isSelected) TelePhotosTheme.AccentBlue.copy(alpha = 0.25f) else Color.Transparent)
                                        .combinedClickable(
                                            onLongClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                if (!isSelectionMode) {
                                                    isSelectionMode = true
                                                    selectedPhotos.add(photo)
                                                } else {
                                                    if (selectedPhotos.any { it.uri == photo.uri }) {
                                                        selectedPhotos.removeAll { it.uri == photo.uri }
                                                        if (selectedPhotos.isEmpty()) {
                                                            isSelectionMode = false
                                                        }
                                                    } else {
                                                        selectedPhotos.add(photo)
                                                    }
                                                }
                                                Unit
                                            },
                                            onClick = {
                                                if (isSelectionMode) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    if (selectedPhotos.any { it.uri == photo.uri }) {
                                                        selectedPhotos.removeAll { it.uri == photo.uri }
                                                        if (selectedPhotos.isEmpty()) {
                                                            isSelectionMode = false
                                                        }
                                                    } else {
                                                        selectedPhotos.add(photo)
                                                    }
                                                } else {
                                                    if (photoIndex != -1) {
                                                        onPhotoSelected(photoIndex, unifiedPhotos)
                                                    }
                                                }
                                                Unit
                                            }
                                        )
                                ) {
                                    // Content container that shrinks when selected
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(animatedPadding)
                                            .clip(RoundedCornerShape(if (isSelected) 8.dp else 0.dp))
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
                                        
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(TelePhotosTheme.AccentBlue.copy(alpha = 0.2f))
                                            )
                                        }
                                        
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
                                    
                                    // Selection checkbox overlay
                                    if (isSelectionMode) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .padding(8.dp)
                                                .size(24.dp)
                                                .background(
                                                    color = if (isSelected) TelePhotosTheme.AccentBlue else Color.Black.copy(alpha = 0.3f),
                                                    shape = CircleShape
                                                )
                                                .border(
                                                    width = 1.5.dp,
                                                    color = Color.White,
                                                    shape = CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Telegram-style Media Fast Scrollbar at the right edge
                if (galleryItems.size > 10) {
                    var isDragging by remember { mutableStateOf(false) }
                    var dragOffsetY by remember { mutableStateOf<Float?>(null) }
                    val density = LocalDensity.current
                    val thumbHeight = 60.dp
                    val thumbHeightPx = remember(density) { with(density) { thumbHeight.toPx() } }
                    val bubbleOffsetPx = remember(density) { with(density) { 25.dp.toPx() } }
                    val bubbleHeightPx = remember(density) { with(density) { 50.dp.toPx() } }

                    val bubbleText by remember(galleryItems) {
                        derivedStateOf {
                            val visibleIndex = gridState.firstVisibleItemIndex
                            val item = galleryItems.getOrNull(visibleIndex)
                            if (item != null) {
                                val dateMs = when (item) {
                                    is GalleryItem.Header -> {
                                        try {
                                            val sdf = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault())
                                            sdf.parse(item.date)?.time
                                        } catch (e: Exception) { null }
                                    }
                                    is GalleryItem.PhotoItem -> item.photo.dateTaken
                                }
                                if (dateMs != null) {
                                    SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(dateMs))
                                } else ""
                            } else ""
                        }
                    }

                    val scrollPercent by remember {
                        derivedStateOf {
                            val layoutInfo = gridState.layoutInfo
                            val totalItemsCount = layoutInfo.totalItemsCount
                            if (totalItemsCount == 0) 0f else {
                                val visibleItems = layoutInfo.visibleItemsInfo
                                if (visibleItems.isEmpty()) 0f else {
                                    val firstItem = visibleItems.first()
                                    val firstVisibleIndex = firstItem.index
                                    val firstVisibleItemOffset = firstItem.offset.y.toFloat()
                                    val firstVisibleItemHeight = firstItem.size.height.toFloat()
                                    val itemOffsetFraction = if (firstVisibleItemHeight > 0f) {
                                        -firstVisibleItemOffset / firstVisibleItemHeight
                                    } else 0f
                                    
                                    val columns = 3
                                    val smoothIndex = firstVisibleIndex.toFloat() + (itemOffsetFraction * columns)
                                    (smoothIndex / totalItemsCount.toFloat()).coerceIn(0f, 1f)
                                }
                            }
                        }
                    }

                    fun performScroll(ratio: Float) {
                        val columns = 3
                        val totalRows = (galleryItems.size + columns - 1) / columns
                        val targetFloatRow = ratio * totalRows.toFloat()
                        val targetRow = targetFloatRow.toInt().coerceIn(0, totalRows - 1)
                        val fractionalPart = targetFloatRow - targetRow

                        val targetIndex = (targetRow * columns).coerceIn(0, galleryItems.size - 1)
                        val itemHeightEstimate = 120.dp
                        val itemHeightPx = with(density) { itemHeightEstimate.toPx() }
                        val scrollOffset = -(fractionalPart * itemHeightPx).toInt()
                        
                        coroutineScope.launch {
                            gridState.scrollToItem(targetIndex, scrollOffset)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(180.dp) // wide enough to hold the floating date bubble + thumb
                            .align(Alignment.CenterEnd)
                    ) {
                        // Thin vertical scrollbar track line
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                                .background(TelePhotosTheme.TextSecondary.copy(alpha = 0.15f))
                                .align(Alignment.CenterEnd)
                                .padding(end = 12.dp)
                        )

                        // Floating Date Bubble (Telegram style)
                        if (isDragging && bubbleText.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(end = 36.dp)
                                    .graphicsLayer {
                                        val currentY = dragOffsetY ?: (scrollPercent * (size.height - thumbHeightPx))
                                        // Center bubble vertically with the scroll thumb
                                        translationY = (currentY + (thumbHeightPx / 2) - bubbleOffsetPx).coerceIn(0f, size.height - bubbleHeightPx)
                                    }
                                    .shadow(6.dp, shape = RoundedCornerShape(16.dp))
                                    .background(TelePhotosTheme.Surface, shape = RoundedCornerShape(16.dp))
                                    .border(1.dp, TelePhotosTheme.SurfaceVariant, shape = RoundedCornerShape(16.dp))
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = bubbleText,
                                    color = TelePhotosTheme.TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Draggable scrollbar thumb capsule (Sleek Telegram style)
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(end = 8.dp)
                                .width(if (isDragging) 8.dp else 6.dp)
                                .height(thumbHeight)
                                .graphicsLayer {
                                    val currentY = dragOffsetY ?: (scrollPercent * (size.height - thumbHeightPx))
                                    translationY = currentY.coerceIn(0f, size.height - thumbHeightPx)
                                }
                                .background(
                                    color = if (isDragging) TelePhotosTheme.AccentBlue else TelePhotosTheme.TextSecondary.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(4.dp)
                                )
                        )

                        // Gesture detector covering the rightmost 36.dp for easy grabbing
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(36.dp)
                                .align(Alignment.CenterEnd)
                                .pointerInput(galleryItems.size) {
                                    detectTapGestures { offset ->
                                        val ratio = (offset.y / size.height).coerceIn(0f, 1f)
                                        performScroll(ratio)
                                    }
                                }
                                .pointerInput(galleryItems.size) {
                                    detectDragGestures(
                                        onDragStart = { _ ->
                                            isDragging = true
                                            val maxTravel = size.height - thumbHeightPx
                                            dragOffsetY = scrollPercent * maxTravel
                                        },
                                        onDragEnd = {
                                            isDragging = false
                                            dragOffsetY = null
                                        },
                                        onDragCancel = {
                                            isDragging = false
                                            dragOffsetY = null
                                        }
                                    ) { change, dragAmount ->
                                        change.consume()
                                        val maxTravel = size.height - thumbHeightPx
                                        val currentY = dragOffsetY ?: (scrollPercent * maxTravel)
                                        val nextY = (currentY + dragAmount.y).coerceIn(0f, maxTravel)
                                        dragOffsetY = nextY
                                        
                                        val ratio = nextY / maxTravel
                                        performScroll(ratio)
                                    }
                                }
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
            .background(TelePhotosTheme.Background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Backup Settings",
                color = TelePhotosTheme.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Selected Chat Display card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = TelePhotosTheme.Surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Backup Target Chat",
                        color = TelePhotosTheme.TextSecondary,
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
                            color = TelePhotosTheme.AccentBlue,
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
                            Text("Change", color = TelePhotosTheme.AccentBlue, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Core Backup Preferences Toggle Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = TelePhotosTheme.Surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Backup Options",
                        color = TelePhotosTheme.TextPrimary,
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
                            Text("Active Backup Sync", color = TelePhotosTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Auto-backup your local gallery in background", color = TelePhotosTheme.TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = backupActive,
                            onCheckedChange = { checked ->
                                backupActive = checked
                                PreferencesManager.setBackupActive(context, checked)
                                context.scheduleSyncWorker()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TelePhotosTheme.AccentBlue,
                                checkedTrackColor = TelePhotosTheme.AccentBlue.copy(alpha = 0.3f),
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFFD1D1D6)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = TelePhotosTheme.SurfaceVariant)
                    Spacer(modifier = Modifier.height(14.dp))

                    // Toggle 2: Wifi Only (Mobile Data Saver Toggle)
                    var wifiOnly by remember { mutableStateOf(PreferencesManager.isWifiOnly(context)) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Back Up Over Wi-Fi Only", color = TelePhotosTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Do not use mobile cellular data for backups", color = TelePhotosTheme.TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = wifiOnly,
                            onCheckedChange = { checked ->
                                wifiOnly = checked
                                PreferencesManager.setWifiOnly(context, checked)
                                context.scheduleSyncWorker()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TelePhotosTheme.AccentBlue,
                                checkedTrackColor = TelePhotosTheme.AccentBlue.copy(alpha = 0.3f),
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFFD1D1D6)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = TelePhotosTheme.SurfaceVariant)
                    Spacer(modifier = Modifier.height(14.dp))

                    // Toggle 3: Send Photos in HD Lossless Quality
                    var hdMode by remember { mutableStateOf(PreferencesManager.isHdMode(context)) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Send Photos in HD Quality", color = TelePhotosTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Upload original lossless files without quality loss", color = TelePhotosTheme.TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = hdMode,
                            onCheckedChange = { checked ->
                                hdMode = checked
                                PreferencesManager.setHdMode(context, checked)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TelePhotosTheme.AccentBlue,
                                checkedTrackColor = TelePhotosTheme.AccentBlue.copy(alpha = 0.3f),
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFFD1D1D6)
                            )
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
                colors = ButtonDefaults.buttonColors(containerColor = TelePhotosTheme.AccentBlue),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(14.dp)
            ) {
                Icon(Icons.Default.Cloud, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Force Synchronize Local Photos Now", color = Color.White, fontWeight = FontWeight.Bold)
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
                border = BorderStroke(1.dp, TelePhotosTheme.GoogleRed),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TelePhotosTheme.GoogleRed)
            ) {
                Text("Logout Telegram Session", fontWeight = FontWeight.Bold)
            }
        }

        // System JNI Core Logs Console
        item {
            Text(
                text = "Active JNI Core Logs",
                color = TelePhotosTheme.TextPrimary,
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
                                log.contains("fail", ignoreCase = true) || log.contains("error", ignoreCase = true) -> TelePhotosTheme.GoogleRed
                                log.contains("success", ignoreCase = true) -> Color(0xFF00E676)
                                else -> TelePhotosTheme.TextSecondary
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
                colors = CardDefaults.cardColors(containerColor = TelePhotosTheme.Surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.75f)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Change Target Chat",
                        color = TelePhotosTheme.TextPrimary,
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
                        placeholder = { Text("Search chats...", color = TelePhotosTheme.TextSecondary) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = TelePhotosTheme.TextSecondary) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear search", tint = TelePhotosTheme.TextSecondary)
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TelePhotosTheme.AccentBlue,
                            unfocusedBorderColor = TelePhotosTheme.SurfaceVariant,
                            focusedTextColor = TelePhotosTheme.TextPrimary,
                            unfocusedTextColor = TelePhotosTheme.TextPrimary
                        )
                    )

                    if (chats.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = TelePhotosTheme.AccentBlue)
                        }
                    } else {
                        if (filteredChats.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No chats match your search", color = TelePhotosTheme.TextSecondary, fontSize = 14.sp)
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(filteredChats) { chat ->
                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = TelePhotosTheme.SurfaceVariant),
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
                                            color = TelePhotosTheme.TextPrimary,
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
    var showDetails by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage) {
        showDetails = false
    }

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

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(photo.uri) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    if (dragAmount < -15f) {
                                        showDetails = true
                                    } else if (dragAmount > 15f) {
                                        showDetails = false
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
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

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = currentPhoto?.name ?: "Photo",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp)
                    )
                    Text(
                        text = "${pagerState.currentPage + 1} of ${photosList.size}",
                        color = Color(0xFFBDBDBD),
                        fontSize = 11.sp
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { showDetails = !showDetails }) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Details",
                        tint = if (showDetails) TelePhotosTheme.AccentBlue else Color.White
                    )
                }
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

        // Photo details sheet
        AnimatedVisibility(
            visible = showDetails && currentPhoto != null,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
            ) + fadeIn(),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
            ) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val photo = currentPhoto!!
            val isCloud = photo.uri.startsWith("cloud://")
            val isSynced = if (isCloud) true else (uploadedUris.contains(photo.uri) || syncedCloudFilenames.contains(photo.name))

            // File size formatted
            val kb = photo.size / 1024.0
            val mb = kb / 1024.0
            val formattedSize = if (mb >= 1.0) {
                String.format(java.util.Locale.US, "%.2f MB", mb)
            } else {
                String.format(java.util.Locale.US, "%.1f KB", kb)
            }

            // Date taken formatted
            val formattedDate = try {
                java.text.SimpleDateFormat("EEEE, MMMM dd, yyyy 'at' h:mm a", java.util.Locale.getDefault()).format(java.util.Date(photo.dateTaken))
            } catch (e: Exception) {
                "Unknown Date"
            }

            Card(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                colors = CardDefaults.cardColors(containerColor = TelePhotosTheme.Surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(16.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(top = 8.dp, bottom = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Small drag handle
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .background(Color.LightGray, shape = RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Title + Close
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Details",
                            color = TelePhotosTheme.TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { showDetails = false }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close details",
                                tint = TelePhotosTheme.TextSecondary
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))

                    // Vertical scrollable list of detail elements
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Date element
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.DateRange,
                                contentDescription = null,
                                tint = TelePhotosTheme.AccentBlue,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = formattedDate,
                                    color = TelePhotosTheme.TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Date Taken",
                                    color = TelePhotosTheme.TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // File info element
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = TelePhotosTheme.AccentBlue,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "${photo.name} ($formattedSize)",
                                    color = TelePhotosTheme.TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (isCloud) "Telegram Cloud Only" else if (isSynced) "Synced Local Photo" else "Local Device Storage",
                                    color = TelePhotosTheme.TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // Storage / cloud sync element (if synced or cloud-only)
                        if (isSynced || isCloud) {
                            val cloudMsgId = if (photo.id < 0) -photo.id else photo.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudQueue,
                                    contentDescription = null,
                                    tint = TelePhotosTheme.GoogleBlue,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = "Message ID: $cloudMsgId",
                                        color = TelePhotosTheme.TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Telegram Server Vault",
                                        color = TelePhotosTheme.TextSecondary,
                                        fontSize = 12.sp
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

data class SearchItem(val photo: LocalPhoto, val keywords: String)

@Composable
fun SearchScreen(
    onPhotoSelected: (Int, List<LocalPhoto>) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var typedQuery by remember { mutableStateOf("") }
    
    // 200ms keyboard input debounce to keep typing butter-smooth
    LaunchedEffect(typedQuery) {
        delay(200)
        searchQuery = typedQuery
    }
    
    var localPhotos by remember { mutableStateOf<List<LocalPhoto>>(emptyList()) }
    var isScanning by remember { mutableStateOf(true) }
    
    val db = remember { UploadDatabase.getDatabase(context) }
    val uploadedLogs by db.dao().getAllFlow().collectAsState(initial = emptyList())
    val cloudLogs by db.cloudDao().getAllFlow().collectAsState(initial = emptyList())
    val uploadedUris = remember(uploadedLogs) { uploadedLogs.map { it.path }.toSet() }
    val syncedCloudFilenames = remember(cloudLogs) { cloudLogs.map { it.fileName }.toSet() }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val scanned = com.example.tguploader.storage.MediaStoreScanner.scan(context)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                localPhotos = scanned
                isScanning = false
            }
        }
    }

    // Merge, deduplicate and sort
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

    // Pre-computed lowercase search keywords for zero-allocation performance in typing loops
    val sdf = remember { java.text.SimpleDateFormat("EEEE, MMMM dd, yyyy", java.util.Locale.getDefault()) }
    val indexedPhotos = remember(unifiedPhotos) {
        unifiedPhotos.map { photo ->
            val formattedDate = try {
                sdf.format(java.util.Date(photo.dateTaken)).lowercase()
            } catch (e: Exception) { "" }
            val keywords = "${photo.name.lowercase()} $formattedDate"
            SearchItem(photo, keywords)
        }
    }

    // Filter photos based on search query using sub-millisecond pre-computed string matching
    val filteredPhotos = remember(indexedPhotos, searchQuery) {
        val query = searchQuery.trim().lowercase()
        if (query.isEmpty()) {
            emptyList()
        } else {
            indexedPhotos.filter { it.keywords.contains(query) }.map { it.photo }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TelePhotosTheme.Background)
            .padding(16.dp)
    ) {
        // Search Input Bar
        OutlinedTextField(
            value = typedQuery,
            onValueChange = { typedQuery = it },
            placeholder = { Text("Search by file name or date...", color = TelePhotosTheme.TextSecondary) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = TelePhotosTheme.AccentBlue) },
            trailingIcon = {
                if (typedQuery.isNotEmpty()) {
                    IconButton(onClick = { 
                        typedQuery = "" 
                        searchQuery = "" 
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = TelePhotosTheme.TextSecondary)
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TelePhotosTheme.AccentBlue,
                unfocusedBorderColor = TelePhotosTheme.SurfaceVariant,
                focusedContainerColor = TelePhotosTheme.Surface,
                unfocusedContainerColor = TelePhotosTheme.Surface,
                focusedTextColor = TelePhotosTheme.TextPrimary,
                unfocusedTextColor = TelePhotosTheme.TextPrimary
            )
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        if (isScanning && unifiedPhotos.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TelePhotosTheme.AccentBlue)
            }
        } else {
            if (typedQuery.isBlank()) {
                // Empty state search illustration
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = TelePhotosTheme.TextSecondary.copy(alpha = 0.3f),
                            modifier = Modifier.size(80.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Search your photo archive",
                            color = TelePhotosTheme.TextSecondary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Find photos by name or date, like 'May 2026' or 'IMG'",
                            color = TelePhotosTheme.TextSecondary.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                    }
                }
            } else if (filteredPhotos.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = TelePhotosTheme.TextSecondary.copy(alpha = 0.3f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No results found for \"$typedQuery\"",
                            color = TelePhotosTheme.TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else {
                // Results Grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filteredPhotos.size) { index ->
                        val photo = filteredPhotos[index]
                        val isCloud = photo.uri.startsWith("cloud://")
                        val isSynced = if (isCloud) true else (uploadedUris.contains(photo.uri) || syncedCloudFilenames.contains(photo.name))

                        val cloudThumbnailPath = if (isCloud) {
                            val triple = parseCloudPhotoUri(photo.uri)
                            if (triple != null) {
                                rememberCloudThumbnailPath(triple.second)
                            } else null
                        } else null

                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onPhotoSelected(index, filteredPhotos)
                                }
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(cloudThumbnailPath ?: photo.uri)
                                    .size(256)
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
                                        tint = Color(0xFF4285F4),
                                        modifier = Modifier.size(14.dp)
                                    )
                                } else if (isSynced) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Synced",
                                        tint = Color(0xFF00E676),
                                        modifier = Modifier.size(14.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Cloud,
                                        contentDescription = "Pending Sync",
                                        tint = Color.White.copy(alpha = 0.5f),
                                        modifier = Modifier.size(14.dp)
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
