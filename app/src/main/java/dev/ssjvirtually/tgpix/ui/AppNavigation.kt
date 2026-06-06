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
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import dev.ssjvirtually.tgpix.ui.screens.*
import dev.ssjvirtually.tgpix.ui.theme.TelePhotosTheme
import dev.ssjvirtually.tgpix.update.*
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
    val context = LocalContext.current
    var activeTab by remember { mutableStateOf("Photos") }
    
    // Manage which photo is currently opened in full screen
    var fullScreenPhotoIndex by remember { mutableStateOf<Int?>(null) }
    var devicePhotosList by remember { mutableStateOf<List<LocalPhoto>>(emptyList()) }

    var telegramProfilePhotoPath by remember { mutableStateOf<String?>(null) }
    val authState by TdlibManager.authState.collectAsState()

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
    var localPhotos by remember { mutableStateOf<List<LocalPhoto>>(emptyList()) }
    var isScanningLocal by remember { mutableStateOf(true) }
    // Tracks whether a background cloud sync (DB restore + Telegram crawl) is in progress.
    // Used to show a subtle sync pill indicator — grid is already visible at this point.
    var isSyncingCloud by remember { mutableStateOf(false) }

    val db = remember { UploadDatabase.getDatabase(context) }
    val cloudLogs by db.cloudDao().getAllFlow().collectAsState(initial = emptyList())
    var mergedPhotosList by remember { mutableStateOf<List<LocalPhoto>>(emptyList()) }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            // ── Phase 1: Scan local photos immediately and show the grid ──────────────
            // This runs first and is fast (~100-300ms). The grid will show local
            // photos as soon as this completes, without waiting for cloud sync.
            isScanningLocal = true
            val scanned = withContext(Dispatchers.IO) {
                MediaStoreScanner.scan(context)
            }
            localPhotos = scanned
            // isScanningLocal will be set to false by the merge LaunchedEffect below
            // as soon as localPhotos is non-empty.

            // ── Phase 2: DB restore + cloud sync run in background ────────────────────
            // The grid is already showing local photos at this point. Cloud photos
            // will be merged in silently when the DB/crawl finishes.
            isSyncingCloud = true
            coroutineScope.launch(Dispatchers.IO) {
                // 2a. Restore local Room DB from remote Telegram backup (network call)
                var restored = false
                try {
                    restored = dev.ssjvirtually.tgpix.storage.BackupManager.restoreDatabase(context)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // 2b. Crawl Telegram channel history to index cloud photos into Room DB
                val chatId = PreferencesManager.getChatId(context)
                if (chatId != 0L) {
                    try {
                        TdlibManager.syncCloudHistory(context, chatId)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // 2c. Crawl fallback: If database backup was not restored, reconstruct the albums from manifest files
                if (!restored) {
                    try {
                        dev.ssjvirtually.tgpix.storage.BackupManager.reconstructAlbumsFromBackupChannel(context)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                withContext(Dispatchers.Main) {
                    isSyncingCloud = false
                }
            }
        }
    }

    // Hoisted background thread merging and deduplication.
    // isScanningLocal is only set to true here when we have no photos yet (first cold start).
    // If we already have localPhotos, the grid is already showing them, so we silently
    // merge in cloud data in the background without flashing the spinner.
    LaunchedEffect(localPhotos, cloudLogs) {
        if (mergedPhotosList.isEmpty()) isScanningLocal = true
        withContext(Dispatchers.IO) {
            val list = mutableListOf<LocalPhoto>()
            
            val regexTrashed = Regex("""^\.trashed-\d+-""")
            fun String.normalize(): String = this.lowercase().replace(regexTrashed, "")
            
            // Build helper maps for multi-layered matching to eliminate duplicates
            val localByFingerprint = localPhotos.associateBy { "${it.name.normalize()}_${it.size}_${it.dateTaken}" }
            val localByName = localPhotos.groupBy { it.name.normalize() }
            val localByDateAndSize = localPhotos.associateBy { "${it.dateTaken / 1000}_${it.size}" }
            val localByDate = localPhotos.groupBy { it.dateTaken / 1000 }
            
            val matchedLocalKeys = mutableSetOf<String>()
            val addedUris = mutableSetOf<String>()

            for (cloud in cloudLogs) {
                val cloudNormName = cloud.fileName.normalize()
                val cloudFingerprintDate = if (cloud.dateTaken > 0L) cloud.dateTaken else cloud.uploadedAt
                val cloudFingerprint = "${cloudNormName}_${cloud.fileSize}_$cloudFingerprintDate"
                val parsedDate = parseDateFromFilename(cloud.fileName)
                val displayDate = if (cloud.dateTaken > 0L) cloud.dateTaken else (parsedDate ?: cloud.uploadedAt)
                
                // Try matching cloud photo to local photo in order of specificity:
                var matchingLocal = localByFingerprint[cloudFingerprint]
                
                if (matchingLocal == null) {
                    matchingLocal = localByName[cloudNormName]?.firstOrNull()
                }
                
                if (matchingLocal == null && parsedDate != null) {
                    matchingLocal = localByDateAndSize["${parsedDate / 1000}_${cloud.fileSize}"]
                }
                
                if (matchingLocal == null && parsedDate != null) {
                    val parsedSeconds = parsedDate / 1000
                    val candidates = (localByDate[parsedSeconds] ?: emptyList()) +
                                     (localByDate[parsedSeconds - 1] ?: emptyList()) +
                                     (localByDate[parsedSeconds + 1] ?: emptyList())
                    matchingLocal = candidates.firstOrNull { candidate ->
                        val cName = candidate.name.normalize()
                        cName == cloudNormName || 
                        (cName.startsWith("img_") && cloudNormName.startsWith("img_")) || 
                        (cName.startsWith("photo_") && cloudNormName.startsWith("photo_"))
                    }
                }
                
                if (matchingLocal != null) {
                    if (!addedUris.contains(matchingLocal.uri)) {
                        list.add(matchingLocal.copy(tags = cloud.tags))
                        addedUris.add(matchingLocal.uri)
                    }
                    matchedLocalKeys.add(matchingLocal.name.lowercase())
                } else {
                    val cloudUri = "cloud://${cloud.messageId}/${cloud.telegramFileId}/${cloud.fileName}"
                    if (!addedUris.contains(cloudUri)) {
                        list.add(
                            LocalPhoto(
                                id = -cloud.messageId,
                                uri = cloudUri,
                                name = cloud.fileName,
                                size = cloud.fileSize,
                                dateTaken = displayDate,
                                tags = cloud.tags
                            )
                        )
                        addedUris.add(cloudUri)
                    }
                }
            }

            // 2. Inject unsynced local device photos
            for (local in localPhotos) {
                if (!matchedLocalKeys.contains(local.name.lowercase()) && !addedUris.contains(local.uri)) {
                    list.add(local)
                    addedUris.add(local.uri)
                }
            }

            // 3. Sort strictly by date taken descending
            val sortedList = list.sortedByDescending { it.dateTaken }
            
            withContext(Dispatchers.Main) {
                mergedPhotosList = sortedList
                isScanningLocal = false
            }
        }
    }

    var scanJob by remember { mutableStateOf<Job?>(null) }
    val triggerScan = {
        scanJob?.cancel()
        scanJob = coroutineScope.launch(Dispatchers.IO) {
            delay(1000) // 1 second debounce to completely prevent multiple scans during fast camera snaps or multiple events
            val scanned = MediaStoreScanner.scan(context)
            withContext(Dispatchers.Main) {
                localPhotos = scanned
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
                triggerScan()
            }
        }

        var wasPaused = false
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                wasPaused = true
            } else if (event == Lifecycle.Event.ON_RESUME) {
                if (wasPaused) {
                    wasPaused = false
                    triggerScan()
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
            scanJob?.cancel()
        }
    }
    
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
                                mergedPhotosList = mergedPhotosList,
                                isScanningLocal = isScanningLocal,
                                isSyncingCloud = isSyncingCloud,
                                hasPermission = hasPermission,
                                onRequestPermission = {
                                    permissionLauncher.launch(permissionsToRequest)
                                }
                            )
                        }
                        "Search" -> {
                            SearchScreen(
                                onPhotoSelected = { index, photos ->
                                    fullScreenPhotoIndex = index
                                    devicePhotosList = photos
                                },
                                mergedPhotosList = mergedPhotosList,
                                isScanning = isScanningLocal
                            )
                        }
                        "Albums" -> {
                            AlbumsScreen(
                                onPhotoSelected = { index, photos ->
                                    fullScreenPhotoIndex = index
                                    devicePhotosList = photos
                                },
                                mergedPhotosList = mergedPhotosList
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
                        }
                    )
                }
            }
        }
    }
}
