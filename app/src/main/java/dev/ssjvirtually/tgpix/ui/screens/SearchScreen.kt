package dev.ssjvirtually.tgpix.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.ssjvirtually.tgpix.MainActivity
import dev.ssjvirtually.tgpix.storage.LocalPhoto
import dev.ssjvirtually.tgpix.storage.PreferencesManager
import dev.ssjvirtually.tgpix.storage.UploadDatabase
import dev.ssjvirtually.tgpix.storage.UploadEntity
import dev.ssjvirtually.tgpix.storage.getFingerprint
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import dev.ssjvirtually.tgpix.telegram.UploadManager
import dev.ssjvirtually.tgpix.ui.theme.TelePhotosTheme
import dev.ssjvirtually.tgpix.ui.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val headerDateFormatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.getDefault())
private val monthYearDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM", Locale.US)
private val bubbleDateFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

data class SearchItem(val photo: LocalPhoto, val keywords: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    onPhotoSelected: (Int, List<LocalPhoto>) -> Unit,
    viewModel: dev.ssjvirtually.tgpix.ui.GalleryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    searchViewModel: dev.ssjvirtually.tgpix.ui.SearchViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val searchIndex by viewModel.searchIndex.collectAsState()
    val uploadedUris by viewModel.uploadedUrisSet.collectAsState()
    val isScanning by viewModel.isScanningLocal.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var typedQuery by remember { mutableStateOf("") }
    
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedPhotos = remember { mutableStateListOf<LocalPhoto>() }
    var lastLongPressedPhoto by remember { mutableStateOf<LocalPhoto?>(null) }
    var showTelegramShareDialog by remember { mutableStateOf(false) }
    var dragStartPhotoIndex by remember { mutableStateOf<Int?>(null) }
    var dragCurrentPhotoIndex by remember { mutableStateOf<Int?>(null) }
    var isDraggingToSelect by remember { mutableStateOf(false) }
    var initialSelection by remember { mutableStateOf<List<LocalPhoto>>(emptyList()) }
    var isSelecting by remember { mutableStateOf(true) }
    var dragCurrentPosition by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
 
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
    
    // Sync keyboard input to searchViewModel (which handles 200ms debounce internally)
    LaunchedEffect(typedQuery) {
        searchViewModel.setSearchQuery(typedQuery)
    }
    
    val cloudLogs by viewModel.cloudLogs.collectAsState(initial = emptyList())
    val syncedCloudFilenames = remember(cloudLogs) { cloudLogs.map { it.fileName }.toSet() }
 
    // Filter photos based on search query using SearchViewModel (combining in-memory local and SQLite FTS cloud searches)
    val filteredPhotos by searchViewModel.searchPhotos(viewModel.searchIndex).collectAsState(initial = emptyList())
 
    // Group filtered photos by clean human-readable date header
    val groupedPhotosList = remember(filteredPhotos) {
        val list = mutableListOf<GalleryItem>()
        var lastDateHeader = ""

        for (photo in filteredPhotos) {
            val dateHeader = try {
                headerDateFormatter.format(Instant.ofEpochMilli(photo.dateTaken).atZone(ZoneId.systemDefault()))
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
            .padding(16.dp)
    ) {
        if (isSelectionMode) {
            // Selection Mode Top Action Bar styled elegantly to match search bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TelePhotosTheme.Surface, shape = RoundedCornerShape(16.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
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
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Batch Share Action
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
                    
                    // Telegram Share Action (Instagram-like paper airplane icon)
                    IconButton(onClick = {
                        if (selectedPhotos.isNotEmpty()) {
                            showTelegramShareDialog = true
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Share to Telegram Chat",
                            tint = TelePhotosTheme.AccentBlue,
                            modifier = Modifier.rotate(-30f)
                        )
                    }
                    
                    // Batch Download Action
                    var isDownloadingMultiple by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = {
                            if (selectedPhotos.isNotEmpty()) {
                                isDownloadingMultiple = true
                                coroutineScope.launch {
                                    UploadManager.downloadPhotosToDevice(context, selectedPhotos.toList())
                                    isDownloadingMultiple = false
                                    selectedPhotos.clear()
                                    isSelectionMode = false
                                }
                            }
                        },
                        enabled = !isDownloadingMultiple
                    ) {
                        if (isDownloadingMultiple) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = TelePhotosTheme.AccentBlue)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download selected",
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
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        if (isScanning && searchIndex.isEmpty()) {
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
                            imageVector = Icons.Default.SearchOff,
                            contentDescription = null,
                            tint = TelePhotosTheme.TextSecondary.copy(alpha = 0.3f),
                            modifier = Modifier.size(80.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No photos found",
                            color = TelePhotosTheme.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Check the spelling or try searching for a different date",
                            color = TelePhotosTheme.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                val gridState = rememberLazyGridState()
                val haptic = LocalHapticFeedback.current
                
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { // Dynamic Zoom Columns
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
                                                        val plainPhotos = groupedPhotosList.filterIsInstance<GalleryItem.PhotoItem>().map { it.photo }
                                                        val index = plainPhotos.indexOf(photo)
                                                        if (index != -1) {
                                                            onPhotoSelected(index, plainPhotos)
                                                        }
                                                    }
                                                }
                                        ) {
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
                                                            .border(1.5.dp, Color.White, CircleShape),
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
                            
                            for (index in groupedPhotosList.indices) {
                                val item = groupedPhotosList[index]
                                val dateMs = when (item) {
                                    is GalleryItem.Header -> {
                                        try {
                                            java.time.LocalDate.parse(item.date, headerDateFormatter)
                                                .atStartOfDay(ZoneId.systemDefault())
                                                .toInstant()
                                                .toEpochMilli()
                                        } catch (e: Exception) {
                                            0L
                                        }
                                    }
                                    is GalleryItem.PhotoItem -> item.photo.dateTaken
                                }
                                
                                if (dateMs > 0L) {
                                    val monthKey = try {
                                        monthYearDateFormatter.format(Instant.ofEpochMilli(dateMs).atZone(ZoneId.systemDefault()))
                                    } catch (e: Exception) { "" }
                                    if (monthKey.isNotEmpty() && seenMonths.add(monthKey)) {
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
                                // Track line
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
                                
                                // Handle Thumb
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

                            // Floating Date Bubble
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
                                                bubbleDateFormatter.format(Instant.ofEpochMilli(activeItem.photo.dateTaken).atZone(ZoneId.systemDefault()))
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
                                        .padding(end = 48.dp)
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
