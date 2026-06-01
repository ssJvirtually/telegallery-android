package com.example.tguploader.ui.screens

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
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerScreen(
    photosList: List<LocalPhoto>,
    startIndex: Int,
    onClose: () -> Unit
) {
    val context = LocalContext.current as MainActivity
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { photosList.size })
    
    var showChrome by remember { mutableStateOf(true) }
    var isDetailsVisible by remember { mutableStateOf(false) }

    val db = remember { UploadDatabase.getDatabase(context) }
    val uploadedLogs by db.dao().getAllFlow().collectAsState(initial = emptyList())
    val cloudLogs by db.cloudDao().getAllFlow().collectAsState(initial = emptyList())
    val uploadedUris = remember(uploadedLogs) { uploadedLogs.map { it.path }.toSet() }
    val syncedCloudFilenames = remember(cloudLogs) { cloudLogs.map { it.fileName }.toSet() }

    val activePhoto = photosList.getOrNull(pagerState.currentPage)
    val isSynced = activePhoto?.let { uploadedUris.contains(it.uri) } ?: false
    val isCloud = activePhoto?.let { isCloudPhoto(it.uri) } ?: false
    
    // Bottom Sheet sheetState to handle metadata scrolling panel
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
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
                
                var scale by remember { mutableStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                
                val density = LocalDensity.current
                val configuration = LocalConfiguration.current
                val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
                
                // Track dynamic swipe up to open metadata panel (Google Photos Swipe Up Gestures)
                val dragOffsetY = remember { Animatable(0f) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(scale) {
                            detectTapGestures(
                                onTap = { showChrome = !showChrome },
                                onDoubleTap = {
                                    if (scale > 1f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                }
                            )
                        }
                        .pointerInput(scale) {
                            detectTransformGesturesCustom(panZoomLock = true, scale = scale) { _, pan, zoom ->
                                scale = (scale * zoom).coerceIn(1f, 4f)
                                if (scale > 1f) {
                                    offset += pan
                                } else {
                                    offset = Offset.Zero
                                }
                            }
                        }
                        .pointerInput(scale) {
                            if (scale == 1f) {
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        coroutineScope.launch {
                                            if (dragOffsetY.value < -180f) {
                                                isDetailsVisible = true
                                            }
                                            dragOffsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                        }
                                    },
                                    onDragCancel = {
                                        coroutineScope.launch {
                                            dragOffsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                        }
                                    },
                                    onVerticalDrag = { _, dragAmount ->
                                        if (dragAmount < 0 || dragOffsetY.value < 0) {
                                            coroutineScope.launch {
                                                dragOffsetY.snapTo((dragOffsetY.value + dragAmount).coerceIn(-screenHeightPx.toFloat(), 0f))
                                            }
                                        }
                                    }
                                )
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y + dragOffsetY.value
                        },
                    contentAlignment = Alignment.Center
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
                                        .build(),
                                    contentDescription = photo.name,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                CircularProgressIndicator(color = TelePhotosTheme.AccentBlue)
                            }
                        }
                    } else {
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
                            java.text.SimpleDateFormat("MMM dd, yyyy • h:mm a", java.util.Locale.getDefault()).format(java.util.Date(it.dateTaken))
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
                                withContext(Dispatchers.Main) {
                                    isUploading = false
                                    if (resultMsg is TdApi.Message) {
                                        db.dao().insert(
                                            UploadEntity(
                                                path = photo.uri,
                                                uploadedAt = System.currentTimeMillis()
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
                PhotoDetailsSheet(photo = activePhoto, isSynced = isSynced, isCloud = isCloud)
            }
        }
    }
}

@Composable
fun PhotoDetailsSheet(photo: LocalPhoto, isSynced: Boolean, isCloud: Boolean) {
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
        java.text.SimpleDateFormat("EEEE, MMMM dd, yyyy • h:mm a", java.util.Locale.getDefault()).format(java.util.Date(photo.dateTaken))
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

// Premium custom pinch-to-zoom and pan gesture detector that avoids consuming horizontal drag/swipes when scale is 1f,
// allowing the parent HorizontalPager to execute butter-smooth left/right swiping navigation natively.
suspend fun PointerInputScope.detectTransformGesturesCustom(
    panZoomLock: Boolean = false,
    scale: Float,
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
                val shouldIgnore = activePointers.size <= 1 && scale <= 1f
                
                if (!shouldIgnore) {
                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()

                    if (!pastTouchSlop) {
                        zoom *= zoomChange
                        pan += panChange

                        val centroid = event.calculateCentroid(useCurrent = false)
                        val zoomMotion = Math.abs(1 - zoom) * centroid.getDistance()
                        val panMotion = pan.getDistance()

                        if (zoomMotion > touchSlop || panMotion > touchSlop) {
                            pastTouchSlop = true
                        }
                    }

                    if (pastTouchSlop) {
                        val centroid = event.calculateCentroid(useCurrent = false)
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
