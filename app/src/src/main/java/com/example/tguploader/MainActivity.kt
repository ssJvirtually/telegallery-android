package com.example.tguploader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.example.tguploader.storage.FolderScanner
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
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                PreferencesManager.saveFolder(this, uri)
                Toast.makeText(this, "Folder configured successfully!", Toast.LENGTH_SHORT).show()
                recreate() // Simple recreation to refresh UI
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to store folder permission: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize TDLib library
        TdlibManager.initialize(applicationContext)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF54A0FF),
                    background = Color(0xFF1E272E),
                    surface = Color(0xFF2C3E50),
                    onPrimary = Color.White,
                    onBackground = Color(0xFFECEFF1),
                    onSurface = Color(0xFFECEFF1)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }

    fun launchFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        folderLauncher.launch(null)
    }

    fun scheduleSyncWorker() {
        val request = PeriodicWorkRequestBuilder<UploadWorker>(
            15, TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        ).build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniquePeriodicWork(
                "upload_worker",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        Toast.makeText(this, "Periodic sync scheduled!", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun AppNavigation() {
    val context = LocalContext.current as MainActivity
    val authState by TdlibManager.authState.collectAsState()
    val chats by TdlibManager.chats.collectAsState()

    val folderUri = remember { mutableStateOf(PreferencesManager.getFolder(context)) }
    val selectedChatId = remember { mutableStateOf(PreferencesManager.getChatId(context)) }
    val selectedChatTitle = remember { mutableStateOf(PreferencesManager.getChatTitle(context)) }

    // Dynamic background gradient
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))
                )
            )
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        when (authState) {
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                PhoneLoginScreen()
            }
            is TdApi.AuthorizationStateWaitCode -> {
                OtpVerifyScreen()
            }
            is TdApi.AuthorizationStateReady -> {
                when {
                    folderUri.value == null -> {
                        FolderPickerOnboarding {
                            context.launchFolderPicker()
                        }
                    }
                    selectedChatId.value == 0L -> {
                        ChatPickerOnboarding(chats) { chat ->
                            PreferencesManager.saveChatId(context, chat.id)
                            PreferencesManager.saveChatTitle(context, chat.title)
                            selectedChatId.value = chat.id
                            selectedChatTitle.value = chat.title
                            context.scheduleSyncWorker()
                        }
                    }
                    else -> {
                        DashboardScreen(
                            folderUri = folderUri.value!!,
                            chatTitle = selectedChatTitle.value ?: "Selected Chat",
                            onReset = {
                                PreferencesManager.saveFolder(context, Uri.EMPTY)
                                PreferencesManager.saveChatId(context, 0L)
                                PreferencesManager.saveChatTitle(context, "")
                                folderUri.value = null
                                selectedChatId.value = 0L
                                selectedChatTitle.value = ""
                            }
                        )
                    }
                }
            }
            else -> {
                // Loading / Splash state
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF54A0FF))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Initializing Telegram Core...",
                        color = Color.White,
                        fontSize = 16.sp,
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

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x33FFFFFF)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Telegram Login",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Enter your international phone number with country code (e.g. +1234567890)",
                color = Color(0xFFB0BEC5),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Phone Number", color = Color(0xFFECEFF1)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF54A0FF),
                    unfocusedBorderColor = Color(0xFFB0BEC5),
                    focusedLabelColor = Color(0xFF54A0FF),
                    unfocusedLabelColor = Color(0xFFB0BEC5),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    isLoading = true
                    AuthManager.sendPhone(phone) {
                        isLoading = false
                    }
                },
                enabled = phone.isNotBlank() && !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF54A0FF)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                } else {
                    Text("Send Code", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun OtpVerifyScreen() {
    var otp by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x33FFFFFF)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Enter OTP",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "We sent an authorization code to your official Telegram app",
                color = Color(0xFFB0BEC5),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedTextField(
                value = otp,
                onValueChange = { otp = it },
                label = { Text("Verification Code", color = Color(0xFFECEFF1)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF54A0FF),
                    unfocusedBorderColor = Color(0xFFB0BEC5),
                    focusedLabelColor = Color(0xFF54A0FF),
                    unfocusedLabelColor = Color(0xFFB0BEC5),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    isLoading = true
                    AuthManager.verifyOtp(otp) {
                        isLoading = false
                    }
                },
                enabled = otp.isNotBlank() && !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF54A0FF)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                } else {
                    Text("Verify & Continue", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun FolderPickerOnboarding(onPick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x33FFFFFF)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Select Audio Folder",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Select the local directory where call recordings or audio files are stored. The app will sync them periodically in the background.",
                color = Color(0xFFB0BEC5),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onPick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF54A0FF)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Select Folder", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ChatPickerOnboarding(chats: List<ChatInfo>, onSelect: (ChatInfo) -> Unit) {
    LaunchedEffect(Unit) {
        TdlibManager.loadChats()
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x33FFFFFF)),
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Target Destination",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Select the group, channel, or chat where recordings will be uploaded.",
                color = Color(0xFFB0BEC5),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (chats.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF54A0FF))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Fetching Telegram chats...", color = Color.White)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(chats) { chat ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(chat) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = chat.title.ifEmpty { "Saved Messages (Me)" },
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(
    folderUri: Uri,
    chatTitle: String,
    onReset: () -> Unit
) {
    val context = LocalContext.current
    var scannedFiles by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var dbLogs by remember { mutableStateOf<List<UploadEntity>>(emptyList()) }
    var isSyncing by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    fun loadDashboardData() {
        coroutineScope.launch(Dispatchers.Default) {
            val files = FolderScanner.scan(context)
            val logs = UploadDatabase.getDatabase(context).dao().getAll()
            withContext(Dispatchers.Main) {
                scannedFiles = files
                dbLogs = logs
            }
        }
    }

    LaunchedEffect(Unit) {
        loadDashboardData()
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x33FFFFFF)),
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.95f)
            .padding(4.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            // Dashboard Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Sync Dashboard",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Auto sync: Active",
                        color = Color(0xFF2ECC71),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                IconButton(onClick = onReset) {
                    Text("Reset", color = Color(0xFFFF7675), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Scanned Files", color = Color(0xFFB0BEC5), fontSize = 12.sp)
                        Text("${scannedFiles.size}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Uploaded Logs", color = Color(0xFFB0BEC5), fontSize = 12.sp)
                        Text("${dbLogs.size}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Configuration Info
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Destination:", color = Color(0xFFB0BEC5), fontSize = 11.sp)
                    Text(chatTitle, color = Color(0xFF54A0FF), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Scan and Sync now button
            Button(
                onClick = {
                    isSyncing = true
                    coroutineScope.launch(Dispatchers.Default) {
                        // Trigger immediate manual sync request via WorkManager
                        val oneTimeWorkRequest = OneTimeWorkRequestBuilder<UploadWorker>().build()
                        WorkManager.getInstance(context).enqueue(oneTimeWorkRequest)
                        
                        // Briefly delay to let WorkManager run
                        kotlinx.coroutines.delay(3000)
                        loadDashboardData()
                        withContext(Dispatchers.Main) {
                            isSyncing = false
                            Toast.makeText(context, "Scan & Sync complete!", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = !isSyncing,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2ECC71)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                } else {
                    Text("Scan & Sync Now", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Scanned recordings list
            Text("Pending Recordings", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            val pendingFiles = scannedFiles.filter { file -> dbLogs.none { it.path == file.uri.toString() } }

            if (pendingFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("All recordings synchronized!", color = Color(0xFFB0BEC5), fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(pendingFiles) { file ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0x0DFFFFFF)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(file.name ?: "Unknown", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text("Size: ${file.length() / 1024} KB", color = Color(0xFFB0BEC5), fontSize = 11.sp)
                                }
                                Text("Pending", color = Color(0xFFF1C40F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color(0x1AFFFFFF))
            Spacer(modifier = Modifier.height(12.dp))

            Text("Sync Activity Logs", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            val systemLogs by TdlibManager.logs.collectAsState()

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0x33000000)),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.8f),
                shape = RoundedCornerShape(12.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(systemLogs) { log ->
                        Text(
                            text = log,
                            color = when {
                                log.contains("failed", ignoreCase = true) || log.contains("Error", ignoreCase = true) -> Color(0xFFFF7675)
                                log.contains("successfully", ignoreCase = true) -> Color(0xFF2ECC71)
                                else -> Color(0xFFECEFF1)
                            },
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
