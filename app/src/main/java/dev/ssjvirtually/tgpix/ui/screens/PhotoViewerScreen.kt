package dev.ssjvirtually.tgpix.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.ssjvirtually.tgpix.MainActivity
import dev.ssjvirtually.tgpix.storage.LocalPhoto
import dev.ssjvirtually.tgpix.storage.PreferencesManager
import dev.ssjvirtually.tgpix.storage.UploadDatabase
import dev.ssjvirtually.tgpix.storage.UploadEntity
import dev.ssjvirtually.tgpix.storage.CloudPhotoEntity
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
import kotlin.math.roundToInt

private val viewerTitleFormatter = java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy • h:mm a", java.util.Locale.getDefault())
private val viewerDetailFormatter = java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy • h:mm a", java.util.Locale.getDefault())

private fun formatTimestamp(ms: Long, formatter: java.time.format.DateTimeFormatter): String {
    return try {
        formatter.format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()))
    } catch (e: Exception) {
        ""
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerScreen(
    photosList: List<LocalPhoto>,
    startIndex: Int,
    onClose: () -> Unit,
    viewModel: dev.ssjvirtually.tgpix.ui.GalleryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val uploadedUris by viewModel.uploadedUrisSet.collectAsState()
    val context = LocalContext.current as MainActivity
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { photosList.size })
    
    var showChrome by remember { mutableStateOf(true) }
    var isDetailsVisible by remember { mutableStateOf(false) }

    val dbVersion by TdlibManager.dbVersion.collectAsState()
    val db = remember(dbVersion) { UploadDatabase.getDatabase(context) }
    val cloudLogs by db.cloudDao().getAllFlow().collectAsState(initial = emptyList())

    val activePhoto = photosList.getOrNull(pagerState.currentPage)
    val isSynced = activePhoto?.let { uploadedUris.contains(it.uri) } ?: false
    val isCloud = activePhoto?.let { isCloudPhoto(it.uri) } ?: false
    
    val activeCloudPhoto = remember(activePhoto, cloudLogs) {
        if (activePhoto == null) null
        else if (isCloudPhoto(activePhoto.uri)) {
            // Cloud-only photo: already IS a cloud entry — match it directly by messageId encoded in URI
            val messageId = activePhoto.uri.removePrefix("cloud://").substringBefore("/").toLongOrNull()
            if (messageId != null) {
                cloudLogs.firstOrNull { it.messageId == messageId }
            } else {
                cloudLogs.firstOrNull { it.fileName == activePhoto.name }
            }
        } else {
            // Local photo: match by fingerprint (name+size+date) first, then fallback to filename.
            // NOTE: Do NOT call getFingerprint() here — it reads file bytes on the main thread.
            // Use a lightweight in-memory fingerprint key instead.
            val lightKey = "${activePhoto.name.lowercase()}_${activePhoto.size}"
            cloudLogs.firstOrNull { "${it.fileName.lowercase()}_${it.fileSize}" == lightKey }
                ?: cloudLogs.firstOrNull { it.fileName == activePhoto.name }
        }
    }
    
    // Bottom Sheet sheetState to handle metadata scrolling panel
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 16.dp,
            userScrollEnabled = !isDetailsVisible // Lock swiping when details sheet is open
        ) { page ->
            val photo = photosList.getOrNull(page)
            if (photo != null) {
                val isCloud = isCloudPhoto(photo.uri)
                
                val scale = remember { Animatable(1f) }
                val offsetX = remember { Animatable(0f) }
                val offsetY = remember { Animatable(0f) }
                
                val density = LocalDensity.current
                val configuration = LocalConfiguration.current
                val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
                val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
                
                // Track dynamic swipe up/down gesture offset
                val dragOffsetY = remember { Animatable(0f) }
                val backgroundAlpha = (1f - (dragOffsetY.value.coerceAtLeast(0f) / screenHeightPx)).coerceIn(0f, 1f)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = backgroundAlpha))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { showChrome = !showChrome },
                                    onDoubleTap = { tapOffset ->
                                        val targetScale = if (scale.value > 1.1f) 1f else 2.5f
                                        val targetX = if (targetScale == 1f) 0f else {
                                            val centerX = screenWidthPx / 2f
                                            ((centerX - tapOffset.x) * (targetScale - 1f)).coerceIn(
                                                -(targetScale - 1f) * screenWidthPx / 2f,
                                                (targetScale - 1f) * screenWidthPx / 2f
                                            )
                                        }
                                        val targetY = if (targetScale == 1f) 0f else {
                                            val centerY = screenHeightPx / 2f
                                            ((centerY - tapOffset.y) * (targetScale - 1f)).coerceIn(
                                                -(targetScale - 1f) * screenHeightPx / 2f,
                                                (targetScale - 1f) * screenHeightPx / 2f
                                            )
                                        }
                                        
                                        coroutineScope.launch {
                                            launch {
                                                scale.animateTo(targetScale, spring(stiffness = Spring.StiffnessMediumLow))
                                            }
                                            launch {
                                                offsetX.animateTo(targetX, spring(stiffness = Spring.StiffnessMediumLow))
                                            }
                                            launch {
                                                offsetY.animateTo(targetY, spring(stiffness = Spring.StiffnessMediumLow))
                                            }
                                        }
                                    }
                                )
                            }
                            .pointerInput(Unit) {
                                while (true) {
                                    detectTransformGesturesCustom(
                                        panZoomLock = true,
                                        getScale = { scale.value }
                                    ) { centroid, pan, zoom ->
                                        coroutineScope.launch {
                                            val currentScale = scale.value
                                            // Direct scale/pan manipulation, allow rubber-banding [0.75f, 5f]
                                            val newScale = (currentScale * zoom).coerceIn(0.75f, 5f)
                                            val scaleFactor = newScale / currentScale
                                            
                                            val centroidSec = centroid - Offset(screenWidthPx / 2f, screenHeightPx / 2f)
                                            
                                            val newOffsetX = offsetX.value + pan.x + (centroidSec.x - offsetX.value) * (1f - scaleFactor)
                                            val newOffsetY = offsetY.value + pan.y + (centroidSec.y - offsetY.value) * (1f - scaleFactor)
                                            
                                            val maxBoundX = (newScale - 1f).coerceAtLeast(0f) * screenWidthPx / 2f
                                            val maxBoundY = (newScale - 1f).coerceAtLeast(0f) * screenHeightPx / 2f
                                            
                                            scale.snapTo(newScale)
                                            offsetX.snapTo(newOffsetX.coerceIn(-maxBoundX, maxBoundX))
                                            offsetY.snapTo(newOffsetY.coerceIn(-maxBoundY, maxBoundY))
                                        }
                                    }
                                    
                                    // Gesture ended: Animate back to boundaries if needed
                                    val targetScale = scale.value.coerceIn(1f, 4f)
                                    val maxBoundX = (targetScale - 1f) * screenWidthPx / 2f
                                    val maxBoundY = (targetScale - 1f) * screenHeightPx / 2f
                                    val targetOffsetX = offsetX.value.coerceIn(-maxBoundX, maxBoundX)
                                    val targetOffsetY = offsetY.value.coerceIn(-maxBoundY, maxBoundY)
                                    
                                    coroutineScope.launch {
                                        launch {
                                            scale.animateTo(targetScale, spring(stiffness = Spring.StiffnessMediumLow))
                                        }
                                        launch {
                                            offsetX.animateTo(targetOffsetX, spring(stiffness = Spring.StiffnessMediumLow))
                                        }
                                        launch {
                                            offsetY.animateTo(targetOffsetY, spring(stiffness = Spring.StiffnessMediumLow))
                                        }
                                    }
                                }
                            }
                            .pointerInput(scale.value <= 1.05f) {
                                if (scale.value <= 1.05f) {
                                    detectVerticalDragGestures(
                                        onDragEnd = {
                                            coroutineScope.launch {
                                                if (dragOffsetY.value < -180f) {
                                                    isDetailsVisible = true
                                                    dragOffsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                                } else if (dragOffsetY.value > 180f) {
                                                    // Slide down completely and dismiss
                                                    dragOffsetY.animateTo(screenHeightPx, spring(stiffness = Spring.StiffnessMediumLow))
                                                    onClose()
                                                } else {
                                                    dragOffsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                                }
                                            }
                                        },
                                        onDragCancel = {
                                            coroutineScope.launch {
                                                dragOffsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                            }
                                        },
                                        onVerticalDrag = { _, dragAmount ->
                                            if (showChrome) {
                                                showChrome = false
                                            }
                                            coroutineScope.launch {
                                                dragOffsetY.snapTo((dragOffsetY.value + dragAmount).coerceIn(-screenHeightPx, screenHeightPx))
                                            }
                                        }
                                    )
                                }
                            }
                            .graphicsLayer {
                                scaleX = scale.value
                                scaleY = scale.value
                                translationX = offsetX.value
                                translationY = offsetY.value + dragOffsetY.value
                            },
                        contentAlignment = Alignment.Center
                    ) {
                         if (isCloud) {
                            val parts = parseCloudPhotoUri(photo.uri)
                            if (parts != null) {
                                val downloadState = rememberCloudPhotoDownloadState(
                                    messageId = parts.first,
                                    isThumbnail = false
                                )
                                val localThumbnailPath = rememberCloudThumbnailPath(
                                    messageId = parts.first,
                                    isThumbnail = true
                                )
                                
                                Box(modifier = Modifier.fillMaxSize()) {
                                    // 1. Cloud Thumbnail Image (displays instantly from cache)
                                    if (localThumbnailPath != null) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(LocalContext.current)
                                                .data(localThumbnailPath)
                                                .build(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    
                                    // 2. Cloud Full-Res Image
                                    if (downloadState.path != null) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(LocalContext.current)
                                                .data(downloadState.path)
                                                .build(),
                                            contentDescription = photo.name,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier.align(Alignment.Center),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(
                                                progress = { downloadState.progress },
                                                color = TelePhotosTheme.AccentBlue,
                                                trackColor = Color.White.copy(alpha = 0.2f),
                                                modifier = Modifier.size(64.dp)
                                            )
                                            Text(
                                                text = "${(downloadState.progress * 100).toInt()}%",
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize()) {
                                // 1. Local Thumbnail Image (small size for instant cache hit)
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(photo.uri)
                                        .size(300)
                                        .build(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                                
                                // 2. Local Full-Res Image
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(photo.uri)
                                        .build(),
                                    contentDescription = photo.name,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }

        // Top Chrome: Standard Google Photos header bar with close, metadata, and share action triggers
        AnimatedVisibility(
            visible = showChrome && !isDetailsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activePhoto?.name ?: "",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = activePhoto?.let {
                            formatTimestamp(it.dateTaken, viewerTitleFormatter)
                        } ?: "",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
                
                // Details Info Button (Tap triggers bottom metadata sheet)
                IconButton(onClick = { isDetailsVisible = true }) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Photo info metadata details",
                        tint = Color.White
                    )
                }
            }
        }

        // Bottom Chrome: Direct Share, Backup sync, and Delete action triggers
        AnimatedVisibility(
            visible = showChrome && !isDetailsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Action 1: Native Android Share Action Sheet
                TextButton(onClick = {
                    activePhoto?.let { photo ->
                        try {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/*"
                                putExtra(Intent.EXTRA_STREAM, Uri.parse(photo.uri))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Photo via..."))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Share", color = Color.White, fontSize = 10.sp)
                    }
                }

                // Action 2: Cloud Sync (Trigger instant TDLib upload)
                                val chatId = remember { PreferencesManager.getChatId(context) }
                                var isUploading by remember { mutableStateOf(false) }

                TextButton(
                    onClick = {
                        activePhoto?.let { photo ->
                            if (chatId == 0L) {
                                Toast.makeText(context, "Please configure backup channel targets inside settings first!", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            isUploading = true
                            val isHd = PreferencesManager.isHdMode(context)
                            coroutineScope.launch(Dispatchers.IO) {
                                val resultMsg = UploadManager.uploadPhoto(context, photo, chatId, isHd)
                                // Compute fingerprint on IO thread (reads 64KB of file bytes)
                                val fingerprint = photo.getFingerprint(context)
                                withContext(Dispatchers.Main) {
                                    isUploading = false
                                    if (resultMsg is TdApi.Message) {
                                        db.dao().insert(
                                            UploadEntity(
                                                mediaStoreId = photo.id,
                                                path = photo.uri,
                                                contentFingerprint = fingerprint,
                                                uploadedAt = System.currentTimeMillis(),
                                                telegramMessageId = resultMsg.id
                                            )
                                        )
                                        Toast.makeText(context, "Asset successfully synchronized to secure Telegram vault!", Toast.LENGTH_SHORT).show()
                                    } else if (resultMsg is TdApi.Error) {
                                        Toast.makeText(context, "Backup failed: ${resultMsg.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    },
                    enabled = !isSynced && !isCloud && !isUploading
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (isUploading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Icon(
                                imageVector = if (isSynced || isCloud) Icons.Default.CloudDone else Icons.Default.CloudUpload,
                                contentDescription = "Backup",
                                tint = if (isSynced || isCloud) Color(0xFF00E676) else Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isSynced || isCloud) "Backed Up" else "Back Up Now",
                            color = if (isSynced || isCloud) Color(0xFF00E676) else Color.White,
                            fontSize = 10.sp
                        )
                    }
                }

                // Action 3: MediaStore File Deletion
                TextButton(onClick = {
                    activePhoto?.let { photo ->
                        if (isCloud) {
                            Toast.makeText(context, "Cannot delete Telegram cloud vault assets directly.", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        context.triggerDelete(photo)
                        onClose()
                    }
                }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = TelePhotosTheme.GoogleRed)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Delete", color = TelePhotosTheme.GoogleRed, fontSize = 10.sp)
                    }
                }
            }
        }

        // Animated Info Modal Bottom Sheet (Google Photos metadata details sheet)
        if (isDetailsVisible && activePhoto != null) {
            ModalBottomSheet(
                onDismissRequest = { isDetailsVisible = false },
                sheetState = sheetState,
                containerColor = TelePhotosTheme.Surface,
                dragHandle = { BottomSheetDefaults.DragHandle(color = TelePhotosTheme.TextSecondary.copy(alpha = 0.5f)) }
            ) {
                PhotoDetailsSheet(
                    photo = activePhoto,
                    isSynced = isSynced,
                    isCloud = isCloud,
                    cloudPhoto = activeCloudPhoto
                )
            }
        }
    }
}

@Composable
fun PhotoDetailsSheet(
    photo: LocalPhoto,
    isSynced: Boolean,
    isCloud: Boolean,
    cloudPhoto: CloudPhotoEntity? = null
) {
    val context = LocalContext.current
    val formattedSize = remember(photo.size) {
        val sizeInMb = photo.size.toDouble() / (1024.0 * 1024.0)
        if (sizeInMb < 0.1) {
            val sizeInKb = photo.size.toDouble() / 1024.0
            "%.1f KB".format(sizeInKb)
        } else {
            "%.2f MB".format(sizeInMb)
        }
    }
    
    val formattedDate = remember(photo.dateTaken) {
        formatTimestamp(photo.dateTaken, viewerDetailFormatter)
    }

    // Resolve localFilePath for EXIF parsing if cloud photo
    var localFilePath: String? = null
    if (isCloud) {
        val parts = remember(photo.uri) { parseCloudPhotoUri(photo.uri) }
        if (parts != null) {
            localFilePath = rememberCloudThumbnailPath(
                messageId = parts.first,
                isThumbnail = false
            )
        }
    }

    // Resolve absolute file path for device files
    val absolutePath = remember(photo.uri) {
        if (!isCloud) {
            getAbsolutePathFromUri(context, photo.uri) ?: photo.uri
        } else {
            "Telegram Cloud Vault"
        }
    }

    // Extract EXIF latitude and longitude coordinates
    val latLong = remember(photo, localFilePath) {
        if (isCloud) {
            localFilePath?.let { path ->
                try {
                    val exifInterface = androidx.exifinterface.media.ExifInterface(path)
                    val latLongArr = FloatArray(2)
                    if (exifInterface.getLatLong(latLongArr)) {
                        Pair(latLongArr[0].toDouble(), latLongArr[1].toDouble())
                    } else null
                } catch (e: Exception) {
                    null
                }
            }
        } else {
            try {
                val rawUri = android.net.Uri.parse(photo.uri)
                val photoUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    try {
                        android.provider.MediaStore.setRequireOriginal(rawUri)
                    } catch (e: Exception) {
                        rawUri
                    }
                } else {
                    rawUri
                }
                context.contentResolver.openInputStream(photoUri)?.use { inputStream ->
                    val exifInterface = androidx.exifinterface.media.ExifInterface(inputStream)
                    val latLongArr = FloatArray(2)
                    if (exifInterface.getLatLong(latLongArr)) {
                        Pair(latLongArr[0].toDouble(), latLongArr[1].toDouble())
                    } else null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    // Reverse geocode to find a nice location name
    val locationName = latLong?.let { (lat, lon) ->
        rememberLocationName(context, lat, lon)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .navigationBarsPadding()
    ) {
        Text(
            text = "Details",
            color = TelePhotosTheme.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Time and date element
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
                    text = "Timestamp",
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
                
                val sourceText = if (isCloud) "Telegram Cloud Only" else if (isSynced) "Synced Local Photo" else "Local Device Storage"
                val uploadDetails = if (cloudPhoto != null) {
                    if (cloudPhoto.isHd) {
                        " • HD Lossless"
                    } else {
                        val savedBytes = cloudPhoto.originalSizeBytes - cloudPhoto.fileSize
                        if (savedBytes > 0) {
                            val savedMb = savedBytes.toDouble() / (1024.0 * 1024.0)
                            " • Compressed (Saved %.1f MB)".format(savedMb)
                        } else {
                            " • Compressed"
                        }
                    }
                } else ""
                
                Text(
                    text = "$sourceText$uploadDetails",
                    color = TelePhotosTheme.TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        // Tags element (if exists)
        if (photo.tags.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Label,
                    contentDescription = null,
                    tint = TelePhotosTheme.AccentBlue,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = photo.tags,
                        color = TelePhotosTheme.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Tags",
                        color = TelePhotosTheme.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Path / Storage Location element
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = TelePhotosTheme.AccentBlue,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = absolutePath,
                    color = TelePhotosTheme.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "File Path",
                    color = TelePhotosTheme.TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        // Location GPS element (if EXIF GPS info exists)
        if (latLong != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Place,
                    contentDescription = null,
                    tint = TelePhotosTheme.AccentBlue,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = locationName ?: "Resolving location...",
                        color = TelePhotosTheme.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = formatCoordinates(latLong.first, latLong.second),
                        color = TelePhotosTheme.TextSecondary,
                        fontSize = 12.sp
                    )
                }
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

fun getAbsolutePathFromUri(context: Context, uriString: String): String? {
    try {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "content") {
            val projection = arrayOf(android.provider.MediaStore.Images.Media.DATA)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(android.provider.MediaStore.Images.Media.DATA)
                    if (columnIndex != -1) {
                        return cursor.getString(columnIndex)
                    }
                }
            }
        } else if (uri.scheme == "file") {
            return uri.path
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}

@Composable
fun rememberLocationName(context: Context, latitude: Double, longitude: Double): String? {
    var locationName by remember(latitude, longitude) { mutableStateOf<String?>(null) }
    LaunchedEffect(latitude, longitude) {
        withContext(Dispatchers.IO) {
            try {
                if (android.location.Geocoder.isPresent()) {
                    val geocoder = android.location.Geocoder(context)
                    val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val address = addresses[0]
                        val city = address.locality ?: address.subAdminArea ?: address.adminArea
                        val country = address.countryName
                        val name = if (city != null && country != null) {
                            "$city, $country"
                        } else {
                            city ?: country ?: address.getAddressLine(0)
                        }
                        locationName = name
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    return locationName
}

fun formatCoordinates(latitude: Double, longitude: Double): String {
    val latDirection = if (latitude >= 0) "N" else "S"
    val lonDirection = if (longitude >= 0) "E" else "W"
    return "%.4f° %s, %.4f° %s".format(Math.abs(latitude), latDirection, Math.abs(longitude), lonDirection)
}

// Premium custom pinch-to-zoom and pan gesture detector that avoids consuming horizontal drag/swipes when scale is 1f,
// allowing the parent HorizontalPager to execute butter-smooth left/right swiping navigation natively.
suspend fun PointerInputScope.detectTransformGesturesCustom(
    panZoomLock: Boolean = false,
    getScale: () -> Float,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit
) {
    awaitEachGesture {
        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop

        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (!canceled) {
                val activePointers = event.changes.filter { it.pressed }
                
                // If only 1 finger is down and scale is 1f, let's completely ignore and bubble up unconsumed
                // so that the parent swiper horizontal pager receives the drag gesture cleanly.
                val currentScale = getScale()
                val shouldIgnore = activePointers.size <= 1 && currentScale <= 1.05f
                
                if (!shouldIgnore) {
                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()

                    if (!pastTouchSlop) {
                        zoom *= zoomChange
                        pan += panChange

                        val centroid = event.calculateCentroid(useCurrent = false).let {
                            if (it == Offset.Unspecified) event.calculateCentroid(useCurrent = true) else it
                        }
                        val zoomMotion = if (centroid != Offset.Unspecified) {
                            Math.abs(1 - zoom) * centroid.getDistance()
                        } else {
                            0f
                        }
                        val panMotion = pan.getDistance()

                        if (zoomMotion > touchSlop || panMotion > touchSlop) {
                            pastTouchSlop = true
                        }
                    }

                    if (pastTouchSlop) {
                        val centroid = event.calculateCentroid(useCurrent = false).let {
                            if (it == Offset.Unspecified) event.calculateCentroid(useCurrent = true) else it
                        }
                        if (zoomChange != 1f || panChange != Offset.Zero) {
                            onGesture(centroid, panChange, zoomChange)
                        }
                        event.changes.forEach {
                            if (it.positionChanged()) {
                                it.consume()
                            }
                        }
                    }
                }
            }
        } while (!canceled && event.changes.any { it.pressed })
    }
}
