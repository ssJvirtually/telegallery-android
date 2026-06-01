package com.example.tguploader.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.tguploader.MainActivity
import com.example.tguploader.storage.LocalPhoto
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

data class SearchItem(val photo: LocalPhoto, val keywords: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    onPhotoSelected: (Int, List<LocalPhoto>) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var typedQuery by remember { mutableStateOf("") }
    
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
                    val nestedCoroutineScope = rememberCoroutineScope()
                    val chatId = remember { PreferencesManager.getChatId(context) }
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
                                nestedCoroutineScope.launch(Dispatchers.IO) {
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
        }
        
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
                
                Box(
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
                        items(filteredPhotos) { photo ->
                            val isSynced = uploadedUris.contains(photo.uri)
                            val isCloud = isCloudPhoto(photo.uri)
                            val isSelected = selectedPhotos.contains(photo)

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
                                                val index = filteredPhotos.indexOf(photo)
                                                if (index != -1) {
                                                    onPhotoSelected(index, filteredPhotos)
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
    }
}
