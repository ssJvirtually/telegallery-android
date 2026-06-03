package dev.ssjvirtually.tgpix.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ssjvirtually.tgpix.MainActivity
import dev.ssjvirtually.tgpix.storage.LocalPhoto
import dev.ssjvirtually.tgpix.storage.PreferencesManager
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import dev.ssjvirtually.tgpix.ui.screens.*
import dev.ssjvirtually.tgpix.ui.theme.TelePhotosTheme
import dev.ssjvirtually.tgpix.update.*
import android.widget.Toast
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume

@Composable
fun AppNavigation() {
    val context = LocalContext.current as MainActivity
    val authState by TdlibManager.authState.collectAsState()

    val selectedChatId = remember { mutableStateOf(PreferencesManager.getChatId(context)) }
    val selectedChatTitle = remember { mutableStateOf(PreferencesManager.getChatTitle(context)) }

    var activeUpdateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateState by remember { mutableStateOf(UpdateState.IDLE) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var isBackgroundDownloading by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val update = UpdateManager.checkForUpdates()
        if (update != null) {
            activeUpdateInfo = update
            showUpdateDialog = true
        }
    }

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

        if (activeUpdateInfo != null && showUpdateDialog) {
            UpdateDialog(
                updateInfo = activeUpdateInfo!!,
                updateState = updateState,
                downloadProgress = downloadProgress,
                downloadedFile = downloadedFile,
                errorMessage = errorMessage,
                onStartDownload = {
                    updateState = UpdateState.DOWNLOADING
                    downloadProgress = 0f
                    errorMessage = null
                    coroutineScope.launch {
                        val file = UpdateManager.downloadApk(context, activeUpdateInfo!!.apkUrl) { progress ->
                            downloadProgress = progress
                        }
                        if (file != null) {
                            downloadedFile = file
                            if (UpdateManager.canInstallPackages(context)) {
                                UpdateManager.installApk(context, file)
                                if (isBackgroundDownloading) {
                                    Toast.makeText(context, "Update downloaded. Launching installer...", Toast.LENGTH_LONG).show()
                                }
                                if (!activeUpdateInfo!!.forceUpdate) {
                                    activeUpdateInfo = null
                                    showUpdateDialog = false
                                } else {
                                    updateState = UpdateState.READY_TO_INSTALL
                                }
                            } else {
                                updateState = UpdateState.PERMISSION_REQUIRED
                                if (isBackgroundDownloading) {
                                    showUpdateDialog = true
                                    Toast.makeText(context, "TGPix update downloaded. Permission required to install.", Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            updateState = UpdateState.ERROR
                            errorMessage = "Failed to download update. Please try again."
                            if (isBackgroundDownloading) {
                                showUpdateDialog = true
                            }
                        }
                    }
                },
                onRunInBackground = {
                    isBackgroundDownloading = true
                    showUpdateDialog = false
                    Toast.makeText(context, "Downloading update in background...", Toast.LENGTH_SHORT).show()
                },
                onDismiss = {
                    activeUpdateInfo = null
                    showUpdateDialog = false
                    isBackgroundDownloading = false
                    updateState = UpdateState.IDLE
                    downloadedFile = null
                    downloadProgress = 0f
                }
            )
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

    var telegramProfilePhotoPath by remember { mutableStateOf<String?>(null) }
    val authState by TdlibManager.authState.collectAsState()
    
    LaunchedEffect(authState) {
        if (authState is TdApi.AuthorizationStateReady) {
            try {
                // Get active user data from TDLib thread-safely via suspend coroutine
                val user = withContext(Dispatchers.IO) {
                    suspendCancellableCoroutine<TdApi.User?> { continuation ->
                        try {
                            TdlibManager.getClient().send(TdApi.GetMe()) { result ->
                                continuation.resume(result as? TdApi.User)
                            }
                        } catch (e: Exception) {
                            continuation.resume(null)
                        }
                    }
                }

                if (user != null) {
                    val photo = user.profilePhoto
                    if (photo != null) {
                        val fileId = photo.small.id
                        withContext(Dispatchers.IO) {
                            // Check initial local status of the small profile picture file
                            val initialFile = suspendCancellableCoroutine<TdApi.File?> { continuation ->
                                try {
                                    TdlibManager.getClient().send(TdApi.GetFile(fileId)) { fileRes ->
                                        continuation.resume(fileRes as? TdApi.File)
                                    }
                                } catch (e: Exception) {
                                    continuation.resume(null)
                                }
                            }

                            if (initialFile != null) {
                                if (initialFile.local.isDownloadingCompleted) {
                                    // Already downloaded! Publish the path to state.
                                    telegramProfilePhotoPath = initialFile.local.path
                                } else {
                                    // Start downloading exactly ONCE.
                                    TdlibManager.getClient().send(TdApi.DownloadFile(fileId, 1, 0, 0, false)) { }
                                    
                                    // Poll the download status every 1.5 seconds until download completes
                                    var downloaded = false
                                    while (!downloaded) {
                                        delay(1500)
                                        val currentFile = suspendCancellableCoroutine<TdApi.File?> { continuation ->
                                            try {
                                                TdlibManager.getClient().send(TdApi.GetFile(fileId)) { fileRes ->
                                                    continuation.resume(fileRes as? TdApi.File)
                                                }
                                            } catch (e: Exception) {
                                                continuation.resume(null)
                                            }
                                        }
                                        if (currentFile != null && currentFile.local.isDownloadingCompleted) {
                                            telegramProfilePhotoPath = currentFile.local.path
                                            downloaded = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

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
                    selected = activeTab == "Albums",
                    onClick = { activeTab = "Albums" },
                    icon = { Icon(Icons.Default.Collections, contentDescription = "Albums") },
                    label = { Text("Albums") },
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
            } else if (activeTab == "Albums") {
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
                        },
                        profilePhotoPath = telegramProfilePhotoPath,
                        onSettingsClick = {
                            activeTab = "Settings"
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
                "Albums" -> {
                    AlbumsScreen(
                        onPhotoSelected = { index, photos ->
                            fullScreenPhotoIndex = index
                            devicePhotosList = photos
                        }
                    )
                }
                "Settings" -> {
                    SettingsScreen(
                        selectedChatTitle = selectedChatTitle,
                        onResetChat = onResetChat,
                        onBack = { activeTab = "Photos" }
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
