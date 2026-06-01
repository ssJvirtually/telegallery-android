package com.example.tguploader.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.tguploader.MainActivity
import com.example.tguploader.storage.LocalPhoto
import com.example.tguploader.storage.MediaStoreScanner
import com.example.tguploader.storage.PreferencesManager
import com.example.tguploader.storage.UploadDatabase
import com.example.tguploader.storage.UploadEntity
import com.example.tguploader.telegram.TdlibManager
import com.example.tguploader.telegram.UploadManager
import com.example.tguploader.ui.theme.TelePhotosTheme
import com.example.tguploader.ui.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotosGridScreen(
    onPhotoSelected: (Int, List<LocalPhoto>) -> Unit,
    profilePhotoPath: String?,
    onSettingsClick: () -> Unit
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

        val configuration = LocalConfiguration.current
        val screenWidthDp = configuration.screenWidthDp
        val isTablet = screenWidthDp >= 600
        val minColumns = if (isTablet) 3 else 2
        val maxColumns = if (isTablet) 8 else 5

        var gridColumns by remember {
            mutableStateOf(PreferencesManager.getGridColumns(context, 3).coerceIn(minColumns, maxColumns))
        }
        var activeScale by remember { mutableStateOf(1f) }
        var transformOrigin by remember { mutableStateOf(TransformOrigin.Center) }
        var isZooming by remember { mutableStateOf(false) }

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
                    try {
                        com.example.tguploader.storage.BackupManager.backupDatabase(context, chatId)
                        PreferencesManager.setLastBackupRecordCount(context, totalRecords)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        // Merge local photos and cloud-only photos
        val mergedPhotosList = remember(localPhotos, cloudLogs) {
            val list = mutableListOf<LocalPhoto>()
            val localMap = localPhotos.associateBy { it.name }
            val tempSyncedCloudFilenames = mutableSetOf<String>()

            // 1. Process cloud vault files
            for (cloud in cloudLogs) {
                val matchingLocal = localMap[cloud.fileName]
                if (matchingLocal != null) {
                    // Match found: Sync verified
                    list.add(matchingLocal)
                    tempSyncedCloudFilenames.add(cloud.fileName)
                } else {
                    // Cloud only asset
                    val parsedDate = parseDateFromFilename(cloud.fileName)
                    val displayDate = parsedDate ?: cloud.uploadedAt
                    list.add(
                        LocalPhoto(
                            id = -cloud.messageId, // Negative IDs strictly delineate cloud-only assets
                            uri = "cloud://${cloud.messageId}/${cloud.telegramFileId}/${cloud.fileName}",
                            name = cloud.fileName,
                            size = cloud.fileSize,
                            dateTaken = displayDate
                        )
                    )
                }
            }

            // 2. Inject unsynced local device photos
            for (local in localPhotos) {
                if (!tempSyncedCloudFilenames.contains(local.name)) {
                    list.add(local)
                }
            }

            // 3. Sort strictly by date taken descending
            list.sortedByDescending { it.dateTaken }
        }

        // Group photos by clean human-readable date header
        val groupedPhotosList = remember(mergedPhotosList) {
            val list = mutableListOf<GalleryItem>()
            val sdf = java.text.SimpleDateFormat("MMMM dd, yyyy", java.util.Locale.getDefault())
            var lastDateHeader = ""

            for (photo in mergedPhotosList) {
                val dateHeader = try {
                    sdf.format(java.util.Date(photo.dateTaken))
                } catch (e: Exception) {
                    "Unknown Date"
                }

                if (dateHeader != lastDateHeader) {
                    list.add(GalleryItem.Header(dateHeader))
                    lastDateHeader = dateHeader
                }
                list.add(GalleryItem.PhotoItem(photo))
            }
            list
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(TelePhotosTheme.Background)
        ) {
            if (isSelectionMode) {
                // Top Action Bar for Selection Mode (Google Photos premium toolbar)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TelePhotosTheme.Surface)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
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
                                contentDescription = "Cancel",
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
                        // 1. Share Multi Action
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

                        // 2. Backup Multi Action
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
                                            val isCloud = isCloudPhoto(photo.uri)
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

                        // 3. Delete Multi Action
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
                                imageVector = Icons.AutoMirrored.Filled.Send, // Elegant paper airplane
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (localPhotos.isNotEmpty()) "${uploadedUris.size}/${localPhotos.size} Synced" else "${cloudLogs.size} Cloud Photos",
                            color = TelePhotosTheme.AccentBlue,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(Color(0x1F2481CC), shape = RoundedCornerShape(12.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        // Telegram Profile Photo Circle (Google Photos Settings Style)
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0x1F2481CC))
                                .border(1.5.dp, TelePhotosTheme.AccentBlue, CircleShape)
                                .clickable { onSettingsClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (profilePhotoPath != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(profilePhotoPath)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "Settings",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = "Settings",
                                    tint = TelePhotosTheme.AccentBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Grid wrapper with fast scrollbar
            val gridState = rememberLazyGridState()
            val haptic = LocalHapticFeedback.current
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .pointerInput(Unit) { // Static key: NEVER cancels mid-gesture when columns change!
                        var zoomAccumulator = 1f
                        try {
                            detectTransformGestures(panZoomLock = true) { centroid, _, zoom, _ ->
                                if (zoom != 1f) {
                                    isZooming = true
                                    
                                    val pivotX = centroid.x / size.width
                                    val pivotY = centroid.y / size.height
                                    transformOrigin = TransformOrigin(pivotX, pivotY)
                                    
                                    zoomAccumulator *= zoom
                                    activeScale = zoomAccumulator.coerceIn(0.5f, 2.0f)
                                    
                                    // Dynamically query changing mutable values
                                    if (zoomAccumulator > 1.35f && gridColumns > minColumns) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        gridColumns -= 1
                                        PreferencesManager.saveGridColumns(context, gridColumns)
                                        zoomAccumulator = 1f
                                        activeScale = 1f
                                    } else if (zoomAccumulator < 0.70f && gridColumns < maxColumns) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        gridColumns += 1
                                        PreferencesManager.saveGridColumns(context, gridColumns)
                                        zoomAccumulator = 1f
                                        activeScale = 1f
                                    }
                                }
                            }
                        } finally {
                            // Guaranteed to run when fingers are lifted or gesture is cancelled
                            isZooming = false
                            coroutineScope.launch {
                                Animatable(activeScale).animateTo(
                                    targetValue = 1f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                                activeScale = 1f
                            }
                        }
                    }
            ) {
                if (isScanningLocal && mergedPhotosList.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = TelePhotosTheme.AccentBlue)
                    }
                } else if (mergedPhotosList.isEmpty()) {
                    // Empty state (no photos)
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = null,
                                tint = TelePhotosTheme.TextSecondary.copy(alpha = 0.4f),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Your gallery is empty",
                                color = TelePhotosTheme.TextSecondary,
                                fontSize = 15.sp
                            )
                        }
                    }
                } else {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val containerHeightPx = constraints.maxHeight.toFloat()

                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(gridColumns),
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    if (isZooming) {
                                        scaleX = activeScale
                                        scaleY = activeScale
                                        this.transformOrigin = transformOrigin
                                    }
                                },
                            contentPadding = PaddingValues(1.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            groupedPhotosList.forEach { item ->
                                when (item) {
                                    is GalleryItem.Header -> {
                                        item(span = { GridItemSpan(gridColumns) }) {
                                            Text(
                                                text = item.date,
                                                color = TelePhotosTheme.TextPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(TelePhotosTheme.Background)
                                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                            )
                                        }
                                    }

                                    is GalleryItem.PhotoItem -> {
                                        val photo = item.photo
                                        val isSynced = uploadedUris.contains(photo.uri)
                                        val isCloud = isCloudPhoto(photo.uri)
                                        val isSelected = selectedPhotos.contains(photo)

                                        item {
                                            Box(
                                                modifier = Modifier
                                                    .aspectRatio(1f)
                                                    .background(TelePhotosTheme.SurfaceVariant)
                                                    .combinedClickable(
                                                        onClick = {
                                                            if (isSelectionMode) {
                                                                if (isSelected) {
                                                                    selectedPhotos.remove(photo)
                                                                    if (selectedPhotos.isEmpty()) {
                                                                        isSelectionMode = false
                                                                    }
                                                                } else {
                                                                    selectedPhotos.add(photo)
                                                                }
                                                            } else {
                                                                // Determine global index in local photos list
                                                                val index = mergedPhotosList.indexOf(photo)
                                                                if (index != -1) {
                                                                    onPhotoSelected(index, mergedPhotosList)
                                                                }
                                                            }
                                                        },
                                                        onLongClick = {
                                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            if (!isSelectionMode) {
                                                                isSelectionMode = true
                                                                selectedPhotos.add(photo)
                                                            }
                                                        }
                                                    )
                                            ) {
                                                // Handle loading thumbnails for cloud-only vs local assets
                                                if (isCloud) {
                                                    val parts = parseCloudPhotoUri(photo.uri)
                                                    if (parts != null) {
                                                        val fileId = parts.second
                                                        val localThumbnailPath = rememberCloudThumbnailPath(fileId)
                                                        
                                                        if (localThumbnailPath != null) {
                                                            AsyncImage(
                                                                model = ImageRequest.Builder(LocalContext.current)
                                                                    .data(localThumbnailPath)
                                                                    .crossfade(true)
                                                                    .build(),
                                                                contentDescription = photo.name,
                                                                contentScale = ContentScale.Crop,
                                                                modifier = Modifier.fillMaxSize()
                                                            )
                                                        } else {
                                                            // Cloud Asset placeholder loading spinner
                                                            Box(
                                                                modifier = Modifier.fillMaxSize(),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                CircularProgressIndicator(
                                                                    color = TelePhotosTheme.AccentBlue.copy(alpha = 0.4f),
                                                                    modifier = Modifier.size(24.dp),
                                                                    strokeWidth = 2.dp
                                                                )
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    AsyncImage(
                                                        model = ImageRequest.Builder(LocalContext.current)
                                                            .data(photo.uri)
                                                            .crossfade(true)
                                                            .build(),
                                                        contentDescription = photo.name,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                }

                                                // Sync Indicator Badges (Google Photos Style)
                                                if (isSelectionMode) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .padding(6.dp),
                                                        contentAlignment = Alignment.TopStart
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(20.dp)
                                                                .clip(CircleShape)
                                                                .background(
                                                                    if (isSelected) TelePhotosTheme.AccentBlue else Color.Black.copy(alpha = 0.35f)
                                                                )
                                                                .border(
                                                                    1.5.dp,
                                                                    Color.White,
                                                                    CircleShape
                                                                ),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            if (isSelected) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Check,
                                                                    contentDescription = null,
                                                                    tint = Color.White,
                                                                    modifier = Modifier.size(12.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    // Local-only assets show a subtle cloud backup trigger icon on hover/overlay
                                                    if (!isSynced && !isCloud) {
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxSize()
                                                                .padding(6.dp),
                                                            contentAlignment = Alignment.BottomEnd
                                                        ) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(20.dp)
                                                                    .clip(CircleShape)
                                                                    .background(Color.Black.copy(alpha = 0.35f)),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.CloudUpload,
                                                                    contentDescription = "Not Synced",
                                                                    tint = Color.White.copy(alpha = 0.9f),
                                                                    modifier = Modifier.size(11.dp)
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

                        // --- Custom Premium Google Photos-style Scrollbar ---
                        val totalItems = groupedPhotosList.size
                        if (totalItems > 5) {
                            val firstVisibleIndex = gridState.firstVisibleItemIndex
                            val firstVisibleOffset = gridState.firstVisibleItemScrollOffset

                            val scrollFraction = remember(firstVisibleIndex, firstVisibleOffset, totalItems) {
                                if (totalItems <= 1) 0f
                                else {
                                    val itemFraction = firstVisibleIndex.toFloat() / totalItems.toFloat()
                                    val detailOffset = if (gridState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                                        val itemHeight = gridState.layoutInfo.visibleItemsInfo.first().size.height
                                        if (itemHeight > 0) {
                                            (firstVisibleOffset.toFloat() / itemHeight.toFloat()) / totalItems.toFloat()
                                        } else 0f
                                    } else 0f
                                    (itemFraction + detailOffset).coerceIn(0f, 1f)
                                }
                            }

                            var isDragging by remember { mutableStateOf(false) }
                            var dragOffsetFraction by remember { mutableStateOf(0f) }

                            // Auto-fade scrollbar logic matching Telegram/Google Photos
                            var scrollbarAlpha by remember { mutableStateOf(0f) }
                            LaunchedEffect(gridState.isScrollInProgress, isDragging) {
                                if (gridState.isScrollInProgress || isDragging) {
                                    scrollbarAlpha = 1f
                                } else {
                                    // Wait 1.5s then fade out
                                    kotlinx.coroutines.delay(1500)
                                    scrollbarAlpha = 0f
                                }
                            }

                            val animatedAlpha by animateFloatAsState(
                                targetValue = scrollbarAlpha,
                                animationSpec = tween(durationMillis = 300),
                                label = "scrollbar_alpha"
                            )

                            if (animatedAlpha > 0f) {
                                val density = androidx.compose.ui.platform.LocalDensity.current
                                val paddingPx = with(density) { 32.dp.toPx() }
                                val trackHeightPx = containerHeightPx - (paddingPx * 2)

                                val thumbHeightDp = 36.dp
                                val thumbHeightPx = with(density) { thumbHeightDp.toPx() }
                                val scrollableRangePx = trackHeightPx - thumbHeightPx

                                val activeFraction = if (isDragging) dragOffsetFraction else scrollFraction
                                val thumbYPx = paddingPx + (activeFraction * scrollableRangePx)

                                val thumbY = with(density) { thumbYPx.toDp() }

                                // Drag & Touch Area Overlay
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .fillMaxHeight()
                                        .width(36.dp)
                                        .graphicsLayer { alpha = animatedAlpha }
                                        .pointerInput(containerHeightPx, scrollableRangePx) {
                                            detectVerticalDragGestures(
                                                onDragStart = { startPosition ->
                                                    isDragging = true
                                                    val relativeY = (startPosition.y - paddingPx - (thumbHeightPx / 2))
                                                    dragOffsetFraction = (relativeY / scrollableRangePx).coerceIn(0f, 1f)
                                                    coroutineScope.launch {
                                                        val targetIndex = (dragOffsetFraction * totalItems).toInt().coerceIn(0, totalItems - 1)
                                                        gridState.scrollToItem(targetIndex)
                                                    }
                                                },
                                                onDragEnd = {
                                                    isDragging = false
                                                },
                                                onDragCancel = {
                                                    isDragging = false
                                                },
                                                onVerticalDrag = { change, dragAmount ->
                                                    change.consume()
                                                    val currentY = paddingPx + (dragOffsetFraction * scrollableRangePx)
                                                    val newY = currentY + dragAmount
                                                    dragOffsetFraction = ((newY - paddingPx) / scrollableRangePx).coerceIn(0f, 1f)
                                                    
                                                    coroutineScope.launch {
                                                        val targetIndex = (dragOffsetFraction * totalItems).toInt().coerceIn(0, totalItems - 1)
                                                        gridState.scrollToItem(targetIndex)
                                                    }
                                                }
                                            )
                                        }
                                ) {
                                    // Track line (subtle background)
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .fillMaxHeight()
                                            .padding(vertical = 32.dp)
                                            .width(2.dp)
                                            .background(
                                                color = if (isDragging) TelePhotosTheme.TextSecondary.copy(alpha = 0.3f)
                                                else TelePhotosTheme.TextSecondary.copy(alpha = 0.1f),
                                                shape = RoundedCornerShape(1.dp)
                                            )
                                    )
                                    
                                    // Handle Thumb (Google Photos style elevated circular bubble matching the reference photo)
                                    Box(
                                        modifier = Modifier
                                            .offset(y = thumbY)
                                            .align(Alignment.TopEnd)
                                            .padding(end = 4.dp)
                                            .size(36.dp)
                                            .shadow(
                                                elevation = if (isDragging) 8.dp else 4.dp,
                                                shape = CircleShape
                                            )
                                            .background(
                                                color = Color.White,
                                                shape = CircleShape
                                            )
                                            .border(
                                                width = 0.5.dp,
                                                color = Color.LightGray.copy(alpha = 0.5f),
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.UnfoldMore,
                                            contentDescription = "Scroll handle",
                                            tint = Color.DarkGray,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                // Floating Date Bubble (Google Photos style month bubble)
                                val activeItemIndex = (activeFraction * (totalItems - 1)).toInt().coerceIn(0, totalItems - 1)
                                val activeItem = groupedPhotosList.getOrNull(activeItemIndex)

                                val bubbleText = remember(activeItem) {
                                    if (activeItem == null) ""
                                    else {
                                        when (activeItem) {
                                            is GalleryItem.Header -> activeItem.date
                                            is GalleryItem.PhotoItem -> {
                                                try {
                                                    val sdf = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault())
                                                    sdf.format(java.util.Date(activeItem.photo.dateTaken))
                                                } catch (e: Exception) {
                                                    ""
                                                }
                                            }
                                        }
                                    }
                                }

                                if (isDragging && bubbleText.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .offset(y = thumbY - 8.dp)
                                            .align(Alignment.TopEnd)
                                            .padding(end = 48.dp) // Offset left to clear the elevated circle handle
                                            .background(
                                                color = TelePhotosTheme.AccentBlue,
                                                shape = RoundedCornerShape(16.dp)
                                            )
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = bubbleText,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
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
