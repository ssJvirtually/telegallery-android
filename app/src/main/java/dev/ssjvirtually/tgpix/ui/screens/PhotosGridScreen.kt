package dev.ssjvirtually.tgpix.ui.screens

import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.lazy.items
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.ui.draw.rotate
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
import dev.ssjvirtually.tgpix.MainActivity
import dev.ssjvirtually.tgpix.storage.LocalPhoto
import dev.ssjvirtually.tgpix.storage.MediaStoreScanner
import dev.ssjvirtually.tgpix.storage.PreferencesManager
import dev.ssjvirtually.tgpix.storage.UploadDatabase
import dev.ssjvirtually.tgpix.storage.UploadEntity
import dev.ssjvirtually.tgpix.storage.AlbumEntity
import dev.ssjvirtually.tgpix.storage.AlbumPhotoEntity
import dev.ssjvirtually.tgpix.storage.getFingerprint
import android.app.AlertDialog
import android.widget.EditText
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import dev.ssjvirtually.tgpix.telegram.UploadManager
import dev.ssjvirtually.tgpix.ui.theme.TelePhotosTheme
import dev.ssjvirtually.tgpix.ui.utils.*
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
    onSettingsClick: () -> Unit,
    mergedPhotosList: List<LocalPhoto>,
    isScanningLocal: Boolean,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    val context = LocalContext.current

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
                text = "To show your local device photos and back them up, TGPix requires permission to access storage.",
                color = TelePhotosTheme.TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = TelePhotosTheme.AccentBlue),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Grant Permission", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    } else {
        // Load Photos and show grid
        var isSelectionMode by remember { mutableStateOf(false) }
        val selectedPhotos = remember { mutableStateListOf<LocalPhoto>() }
        var lastLongPressedPhoto by remember { mutableStateOf<LocalPhoto?>(null) }
        var dragStartPhotoIndex by remember { mutableStateOf<Int?>(null) }
        var dragCurrentPhotoIndex by remember { mutableStateOf<Int?>(null) }
        var isDraggingToSelect by remember { mutableStateOf(false) }
        var initialSelection by remember { mutableStateOf<List<LocalPhoto>>(emptyList()) }
        var isSelecting by remember { mutableStateOf(true) }
        var dragCurrentPosition by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var showAddToAlbumDialog by remember { mutableStateOf(false) }
        var showTelegramShareDialog by remember { mutableStateOf(false) }

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
        val albumsList by db.albumDao().getAllAlbumsFlow().collectAsState(initial = emptyList())
        val uploadedUris = remember(uploadedLogs) { uploadedLogs.map { it.path }.toSet() }
        val syncedCloudFilenames = remember(cloudLogs) { cloudLogs.map { it.fileName }.toSet() }

        val coroutineScope = rememberCoroutineScope()
        val chatId = remember { PreferencesManager.getChatId(context) }



        // 2. Event-driven debounced backup synchronization (checks every 5 minutes after last idle state change)
        val totalRecords = cloudLogs.size + uploadedLogs.size
        LaunchedEffect(totalRecords) {
            if (totalRecords > 0) {
                val lastBackupCount = PreferencesManager.getLastBackupRecordCount(context)
                if (totalRecords != lastBackupCount) {
                    try {
                        dev.ssjvirtually.tgpix.storage.BackupManager.scheduleBackup(context)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
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
                        // 0. Add to Album Multi Action
                        IconButton(onClick = {
                            if (selectedPhotos.isNotEmpty()) {
                                showAddToAlbumDialog = true
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add to Album",
                                tint = TelePhotosTheme.AccentBlue
                            )
                        }

                        // 1. Share Multi Action
                        IconButton(onClick = {
                            if (selectedPhotos.isNotEmpty()) {
                                coroutineScope.launch {
                                    UploadManager.sharePhotosToSystem(context, selectedPhotos.toList())
                                    selectedPhotos.clear()
                                    isSelectionMode = false
                                }
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share selected",
                                tint = TelePhotosTheme.AccentBlue
                            )
                        }

                        // 1.5 Custom Telegram Share (Forward Arrow icon)
                        IconButton(onClick = {
                            if (selectedPhotos.isNotEmpty()) {
                                showTelegramShareDialog = true
                            }
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Forward to Telegram Chat",
                                tint = TelePhotosTheme.AccentBlue,
                                modifier = Modifier.rotate(-30f)
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
                                                        mediaStoreId = photo.id,
                                                        path = photo.uri,
                                                        contentFingerprint = photo.getFingerprint(context),
                                                        uploadedAt = System.currentTimeMillis(),
                                                        telegramMessageId = res.id
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
                                text = "TG",
                                color = TelePhotosTheme.AccentBlue,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Pix",
                                color = TelePhotosTheme.TextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val localCount = remember(mergedPhotosList) { mergedPhotosList.count { !it.uri.startsWith("cloud://") } }
                        Text(
                            text = if (localCount > 0) "${uploadedUris.size}/$localCount Synced" else "${cloudLogs.size} Cloud Photos",
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
                                }
                                .pointerInput(groupedPhotosList) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { startOffset ->
                                            val startIndex = gridState.getItemIndexAt(startOffset)
                                            val startItem = startIndex?.let { groupedPhotosList.getOrNull(it) }
                                            if (startItem is GalleryItem.PhotoItem) {
                                                dragStartPhotoIndex = startIndex
                                                dragCurrentPhotoIndex = startIndex
                                                dragCurrentPosition = startOffset
                                                isDraggingToSelect = true
                                                initialSelection = selectedPhotos.toList()
                                                isSelecting = !initialSelection.contains(startItem.photo)
                                                
                                                if (isSelecting) {
                                                    if (!selectedPhotos.contains(startItem.photo)) selectedPhotos.add(startItem.photo)
                                                } else {
                                                    selectedPhotos.remove(startItem.photo)
                                                }
                                                isSelectionMode = true
                                                lastLongPressedPhoto = startItem.photo
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                
                                                // Start Auto-Scroll loop
                                                coroutineScope.launch {
                                                    while (isDraggingToSelect) {
                                                        val currentY = dragCurrentPosition.y
                                                        val containerHeight = containerHeightPx
                                                        var scrollDelta = 0f
                                                        
                                                        if (currentY < 150f) {
                                                            scrollDelta = -30f
                                                        } else if (currentY > containerHeight - 150f) {
                                                            scrollDelta = 30f
                                                        }
                                                        
                                                        if (scrollDelta != 0f) {
                                                            try {
                                                                gridState.scrollBy(scrollDelta)
                                                                val currentIndex = gridState.getItemIndexAt(dragCurrentPosition)
                                                                if (currentIndex != null && currentIndex != dragCurrentPhotoIndex) {
                                                                    dragCurrentPhotoIndex = currentIndex
                                                                    val start = minOf(dragStartPhotoIndex!!, dragCurrentPhotoIndex!!)
                                                                    val end = maxOf(dragStartPhotoIndex!!, dragCurrentPhotoIndex!!)
                                                                    
                                                                    val photosInRange = (start..end).mapNotNull { idx ->
                                                                        (groupedPhotosList.getOrNull(idx) as? GalleryItem.PhotoItem)?.photo
                                                                    }
                                                                    
                                                                    selectedPhotos.clear()
                                                                    selectedPhotos.addAll(initialSelection)
                                                                    for (photo in photosInRange) {
                                                                        if (isSelecting) {
                                                                            if (!selectedPhotos.contains(photo)) selectedPhotos.add(photo)
                                                                        } else {
                                                                            selectedPhotos.remove(photo)
                                                                        }
                                                                    }
                                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                }
                                                            } catch (e: Exception) {}
                                                        }
                                                        delay(30)
                                                    }
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            isDraggingToSelect = false
                                            if (dragCurrentPhotoIndex != dragStartPhotoIndex) {
                                                lastLongPressedPhoto = null
                                            }
                                            dragStartPhotoIndex = null
                                            dragCurrentPhotoIndex = null
                                        },
                                        onDragCancel = {
                                            isDraggingToSelect = false
                                            if (dragCurrentPhotoIndex != dragStartPhotoIndex) {
                                                lastLongPressedPhoto = null
                                            }
                                            dragStartPhotoIndex = null
                                            dragCurrentPhotoIndex = null
                                        },
                                        onDrag = { change, dragAmount ->
                                            if (isDraggingToSelect && dragStartPhotoIndex != null) {
                                                change.consume()
                                                dragCurrentPosition += dragAmount
                                                val currentIndex = gridState.getItemIndexAt(dragCurrentPosition)
                                                if (currentIndex != null && currentIndex != dragCurrentPhotoIndex) {
                                                    dragCurrentPhotoIndex = currentIndex
                                                    val start = minOf(dragStartPhotoIndex!!, dragCurrentPhotoIndex!!)
                                                    val end = maxOf(dragStartPhotoIndex!!, dragCurrentPhotoIndex!!)
                                                    
                                                    val photosInRange = (start..end).mapNotNull { idx ->
                                                        (groupedPhotosList.getOrNull(idx) as? GalleryItem.PhotoItem)?.photo
                                                    }
                                                    
                                                    selectedPhotos.clear()
                                                    selectedPhotos.addAll(initialSelection)
                                                    for (photo in photosInRange) {
                                                        if (isSelecting) {
                                                            if (!selectedPhotos.contains(photo)) selectedPhotos.add(photo)
                                                        } else {
                                                            selectedPhotos.remove(photo)
                                                        }
                                                    }
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            }
                                        }
                                    )
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
                                                    .clickable {
                                                        if (lastLongPressedPhoto == photo) {
                                                            lastLongPressedPhoto = null
                                                            return@clickable
                                                        }
                                                        lastLongPressedPhoto = null

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
                                                    }
                                            ) {
                                                 // Handle loading thumbnails for cloud-only vs local assets
                                                 if (isCloud) {
                                                     val localThumbnailPath = rememberCloudThumbnailPath(
                                                         messageId = -photo.id,
                                                         isThumbnail = true
                                                     )
                                                     
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
                            val monthSections = remember(groupedPhotosList) {
                                val sections = mutableListOf<Int>()
                                val seenMonths = mutableSetOf<String>()
                                val sdfHeader = java.text.SimpleDateFormat("MMMM dd, yyyy", java.util.Locale.getDefault())
                                val sdfMonthYear = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US)
                                
                                for (index in groupedPhotosList.indices) {
                                    val item = groupedPhotosList[index]
                                    val dateMs = when (item) {
                                        is GalleryItem.Header -> {
                                            try {
                                                sdfHeader.parse(item.date)?.time ?: 0L
                                            } catch (e: Exception) {
                                                0L
                                            }
                                        }
                                        is GalleryItem.PhotoItem -> item.photo.dateTaken
                                    }
                                    
                                    if (dateMs > 0L) {
                                        val monthKey = sdfMonthYear.format(java.util.Date(dateMs))
                                        if (seenMonths.add(monthKey)) {
                                            sections.add(index)
                                        }
                                    }
                                }
                                if (sections.isEmpty()) sections.add(0)
                                sections
                            }

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

                            // Haptic feedback when transitioning to a new month section during scroll or drag
                            val currentMonthKey = remember(firstVisibleIndex, monthSections) {
                                var activeSectionIdx = 0
                                for (i in monthSections.indices) {
                                    if (monthSections[i] <= firstVisibleIndex) {
                                        activeSectionIdx = i
                                    } else {
                                        break
                                    }
                                }
                                activeSectionIdx
                            }
                            LaunchedEffect(currentMonthKey) {
                                if (gridState.isScrollInProgress || isDragging) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
                                                        val targetSectionIndex = (dragOffsetFraction * monthSections.size).toInt().coerceIn(0, monthSections.size - 1)
                                                        val targetGridIndex = monthSections[targetSectionIndex]
                                                        gridState.scrollToItem(targetGridIndex)
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
                                                        val targetSectionIndex = (dragOffsetFraction * monthSections.size).toInt().coerceIn(0, monthSections.size - 1)
                                                        val targetGridIndex = monthSections[targetSectionIndex]
                                                        gridState.scrollToItem(targetGridIndex)
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
                                val activeSectionIndex = (activeFraction * (monthSections.size - 1)).toInt().coerceIn(0, monthSections.size - 1)
                                val activeItemIndex = monthSections.getOrNull(activeSectionIndex) ?: 0
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
        
        // Render custom Add to Album dialog
        if (showAddToAlbumDialog) {
            AlertDialog(
                onDismissRequest = { showAddToAlbumDialog = false },
                title = { Text("Add to Album", fontWeight = FontWeight.Bold, color = TelePhotosTheme.TextPrimary) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    ) {
                        // "Create New Album" row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showAddToAlbumDialog = false
                                    showCreateAlbumDialog(context) { name ->
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val newAlbumId = db.albumDao().insertAlbum(AlbumEntity(name = name))
                                            val albumPhotos = selectedPhotos.map { photo ->
                                                AlbumPhotoEntity(albumId = newAlbumId, photoUri = photo.name)
                                            }
                                            db.albumDao().insertAlbumPhotos(albumPhotos)
                                            
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "Added to new album '$name'!", Toast.LENGTH_SHORT).show()
                                                selectedPhotos.clear()
                                                isSelectionMode = false
                                            }
                                        }
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CreateNewFolder,
                                contentDescription = null,
                                tint = TelePhotosTheme.AccentBlue,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "New Album",
                                color = TelePhotosTheme.AccentBlue,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        HorizontalDivider(color = TelePhotosTheme.TextSecondary.copy(alpha = 0.2f))
                        
                        // Existing albums list
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier.weight(1f)
                        ) {
                            items(albumsList) { album ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showAddToAlbumDialog = false
                                            coroutineScope.launch(Dispatchers.IO) {
                                                val albumPhotos = selectedPhotos.map { photo ->
                                                    AlbumPhotoEntity(albumId = album.id, photoUri = photo.name)
                                                }
                                                db.albumDao().insertAlbumPhotos(albumPhotos)
                                                
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(context, "Added to '${album.name}'!", Toast.LENGTH_SHORT).show()
                                                    selectedPhotos.clear()
                                                    isSelectionMode = false
                                                }
                                            }
                                        }
                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Collections,
                                        contentDescription = null,
                                        tint = TelePhotosTheme.TextSecondary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(
                                        text = album.name,
                                        color = TelePhotosTheme.TextPrimary,
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAddToAlbumDialog = false }) {
                        Text("Cancel", color = TelePhotosTheme.AccentBlue)
                    }
                },
                containerColor = TelePhotosTheme.Surface,
                shape = RoundedCornerShape(20.dp)
            )
        }

        // Render custom Telegram share dialog
        if (showTelegramShareDialog) {
            TelegramShareDialog(
                photosToShare = selectedPhotos.toList(),
                onDismissRequest = { showTelegramShareDialog = false },
                onShareComplete = {
                    showTelegramShareDialog = false
                    selectedPhotos.clear()
                    isSelectionMode = false
                }
            )
        }
    }
}

private fun showCreateAlbumDialog(context: Context, onConfirm: (String) -> Unit) {
    val input = EditText(context).apply {
        hint = "Album Name"
        setSingleLine()
    }
    AlertDialog.Builder(context)
        .setTitle("New Album")
        .setMessage("Enter a name for your collection:")
        .setView(input)
        .setPositiveButton("Create") { _, _ ->
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) {
                onConfirm(name)
            } else {
                Toast.makeText(context, "Album name cannot be empty", Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

private fun androidx.compose.foundation.lazy.grid.LazyGridState.getItemIndexAt(offset: androidx.compose.ui.geometry.Offset): Int? {
    val itemsInfo = layoutInfo.visibleItemsInfo
    val matched = itemsInfo.find { item ->
        val x = offset.x.toInt()
        val y = offset.y.toInt()
        x >= item.offset.x && x <= item.offset.x + item.size.width &&
        y >= item.offset.y && y <= item.offset.y + item.size.height
    }
    return matched?.index
}

