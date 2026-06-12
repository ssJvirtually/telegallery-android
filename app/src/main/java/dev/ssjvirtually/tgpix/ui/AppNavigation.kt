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
import androidx.compose.material.icons.filled.Warning
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
import dev.ssjvirtually.tgpix.storage.BackupManager
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import dev.ssjvirtually.tgpix.ui.screens.*
import dev.ssjvirtually.tgpix.ui.theme.TelePhotosTheme
import dev.ssjvirtually.tgpix.update.*
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import android.Manifest
import android.os.Build
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.ssjvirtually.tgpix.storage.MediaStoreScanner
import dev.ssjvirtually.tgpix.storage.UploadDatabase
import dev.ssjvirtually.tgpix.ui.utils.parseDateFromFilename
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
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
                            val expectedSha256 = activeUpdateInfo!!.sha256
                            val isVerified = if (expectedSha256 != null) {
                                UpdateManager.verifyFileSha256(file, expectedSha256)
                            } else {
                                true
                            }
                            
                            if (isVerified) {
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
                                errorMessage = "APK integrity check failed (SHA-256 mismatch). The file may have been tampered with."
                                if (isBackgroundDownloading) {
                                    showUpdateDialog = true
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
    val context = LocalContext.current
    var activeTab by remember { mutableStateOf("Photos") }
    
    // Manage which photo is currently opened in full screen
    var fullScreenPhotoIndex by remember { mutableStateOf<Int?>(null) }
    var devicePhotosList by remember { mutableStateOf<List<LocalPhoto>>(emptyList()) }

    val telegramProfilePhotoPath by TdlibManager.profilePhotoPath.collectAsState()
    val authState by TdlibManager.authState.collectAsState()

    // Initialize stateful ViewModels to handle data flows reactively
    val galleryViewModel: GalleryViewModel = viewModel()
    val albumViewModel: AlbumViewModel = viewModel()
    val searchViewModel: SearchViewModel = viewModel()

    // Hoisted grid scroll state — survives tab switches so Photos grid remembers scroll position
    val photosGridState = rememberLazyGridState()

    // HOISTED STATES FOR PHOTOS GRID, SEARCH, AND ALBUMS
    var hasPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionsToRequest = remember {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            list.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
        list.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.READ_MEDIA_IMAGES] == true
        } else {
            permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        }
        hasPermission = storageGranted
    }

    val coroutineScope = rememberCoroutineScope()
    
    // Observe stateful flows from ViewModel

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            galleryViewModel.loadLocalPhotos(hasPermission)
            galleryViewModel.startCloudSync()
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    BackupManager.registerDevice(context)
                    BackupManager.pruneExpiredTrash(context)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // Real-time updates via ContentObserver (detects new images instantly) and LifecycleObserver (detects photos taken while app was minimized)
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, hasPermission) {
        if (!hasPermission) return@DisposableEffect onDispose {}

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val contentObserver = object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                super.onChange(selfChange, uri)
                galleryViewModel.triggerScan()
            }
        }

        var wasPaused = false
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                wasPaused = true
            } else if (event == Lifecycle.Event.ON_RESUME) {
                if (wasPaused) {
                    wasPaused = false
                    galleryViewModel.triggerScan()
                }
            }
        }

        // Register both observers
        context.contentResolver.registerContentObserver(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver
        )
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            context.contentResolver.unregisterContentObserver(contentObserver)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
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
                    onClick = {
                        if (activeTab == "Photos") {
                            if (fullScreenPhotoIndex != null) {
                                fullScreenPhotoIndex = null
                                devicePhotosList = emptyList()
                            } else {
                                coroutineScope.launch {
                                    photosGridState.animateScrollToItem(0)
                                }
                            }
                        } else {
                            fullScreenPhotoIndex = null
                            devicePhotosList = emptyList()
                            activeTab = "Photos"
                        }
                    },
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
                    onClick = {
                        fullScreenPhotoIndex = null
                        devicePhotosList = emptyList()
                        activeTab = "Search"
                    },
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
                    onClick = {
                        fullScreenPhotoIndex = null
                        devicePhotosList = emptyList()
                        activeTab = "Albums"
                    },
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
        val connectionStatus by TdlibManager.connectionStatus.collectAsState()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Display connection status banner if NOT connected
                androidx.compose.animation.AnimatedVisibility(
                    visible = connectionStatus != TdlibManager.ConnectionStatus.CONNECTED,
                    enter = androidx.compose.animation.expandVertically(),
                    exit = androidx.compose.animation.shrinkVertically()
                ) {
                    val (bgColor, textColor, iconPair) = when (connectionStatus) {
                        TdlibManager.ConnectionStatus.WAITING_FOR_NETWORK -> {
                            Triple(
                                Color(0xFF8C1D18), // Google Red Container
                                Color(0xFFF9DEDC),
                                Icons.Default.Cloud to "No internet connection — waiting for network..."
                            )
                        }
                        else -> { // CONNECTING
                            Triple(
                                Color(0xFFFFF3CD), // Warn Yellow
                                Color(0xFF856404),
                                Icons.Default.Warning to "Connecting to Telegram servers..."
                            )
                        }
                    }
                    val icon = iconPair.first
                    val text = iconPair.second
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bgColor)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = text,
                            color = textColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
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
                    } else if (activeTab == "Trash") {
                        BackHandler {
                            activeTab = "Settings"
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
                                },
                                hasPermission = hasPermission,
                                onRequestPermission = {
                                    permissionLauncher.launch(permissionsToRequest)
                                },
                                gridState = photosGridState,
                                viewModel = galleryViewModel,
                                albumViewModel = albumViewModel
                            )
                        }
                        "Search" -> {
                            SearchScreen(
                                onPhotoSelected = { index, photos ->
                                    fullScreenPhotoIndex = index
                                    devicePhotosList = photos
                                },
                                viewModel = galleryViewModel,
                                searchViewModel = searchViewModel
                            )
                        }
                        "Albums" -> {
                            AlbumsScreen(
                                onPhotoSelected = { index, photos ->
                                    fullScreenPhotoIndex = index
                                    devicePhotosList = photos
                                },
                                viewModel = galleryViewModel,
                                albumViewModel = albumViewModel
                            )
                        }
                        "Settings" -> {
                            SettingsScreen(
                                selectedChatTitle = selectedChatTitle,
                                onResetChat = onResetChat,
                                onBack = { activeTab = "Photos" },
                                onTrashClick = { activeTab = "Trash" }
                            )
                        }
                        "Trash" -> {
                            TrashScreen(
                                viewModel = galleryViewModel,
                                onBack = { activeTab = "Settings" },
                                onPhotoSelected = { index, photos ->
                                    fullScreenPhotoIndex = index
                                    devicePhotosList = photos
                                }
                            )
                        }
                    }
                }
            }

            // Animate Full Screen Photo Viewer
            androidx.compose.animation.AnimatedVisibility(
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
                        },
                        viewModel = galleryViewModel
                    )
                }
            }
        }
    }
}
