package dev.ssjvirtually.tgpix.ui.screens

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.EditText
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.ssjvirtually.tgpix.storage.LocalPhoto
import dev.ssjvirtually.tgpix.storage.AlbumEntity
import dev.ssjvirtually.tgpix.storage.AlbumPhotoEntity
import dev.ssjvirtually.tgpix.storage.UploadDatabase
import dev.ssjvirtually.tgpix.storage.MediaStoreScanner
import dev.ssjvirtually.tgpix.telegram.UploadManager
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import dev.ssjvirtually.tgpix.ui.theme.TelePhotosTheme
import dev.ssjvirtually.tgpix.ui.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AlbumUiModel(
    val id: Long,
    val name: String,
    val photoCount: Int,
    val coverUri: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    onPhotoSelected: (Int, List<LocalPhoto>) -> Unit,
    viewModel: dev.ssjvirtually.tgpix.ui.GalleryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    albumViewModel: dev.ssjvirtually.tgpix.ui.AlbumViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val mergedPhotosList by viewModel.mergedPhotosList.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 1. Load albums from DB flow via ViewModel
    val albums by albumViewModel.albumsFlow.collectAsState(initial = emptyList())
    var albumUiModels by remember { mutableStateOf<List<AlbumUiModel>>(emptyList()) }

    // Re-query database when albums or photos list change to get correct item count and cover photo
    LaunchedEffect(albums, mergedPhotosList) {
        withContext(Dispatchers.IO) {
            val uiModels = albums.map { album ->
                val photos = albumViewModel.getAlbumPhotosDirect(album.id)
                val coverFileName = photos.lastOrNull()?.photoUri
                val coverPhoto = coverFileName?.let { key ->
                    mergedPhotosList.firstOrNull { it.name == key || it.uri == key }?.uri
                }
                AlbumUiModel(
                    id = album.id,
                    name = album.name,
                    photoCount = photos.size,
                    coverUri = coverPhoto
                )
            }
            withContext(Dispatchers.Main) {
                albumUiModels = uiModels
            }
        }
    }

    // 2. Active Screen state (grid of albums vs album details view)
    var selectedAlbumId by remember { mutableStateOf<Long?>(null) }
    var selectedAlbumName by remember { mutableStateOf("") }

    // States for long-press Album options actions dialog
    var selectedAlbumForActions by remember { mutableStateOf<AlbumUiModel?>(null) }
    var showTelegramAlbumShareDialog by remember { mutableStateOf(false) }
    var photosToShareInTelegram by remember { mutableStateOf<List<LocalPhoto>>(emptyList()) }

    if (selectedAlbumId != null) {
        // Detailed album view
        AlbumDetailsView(
            albumId = selectedAlbumId!!,
            albumName = selectedAlbumName,
            mergedPhotosList = mergedPhotosList,
            onPhotoSelected = onPhotoSelected,
            onBack = {
                selectedAlbumId = null
                selectedAlbumName = ""
            },
            albumViewModel = albumViewModel
        )
    } else {
        // Main Grid view of all albums
        Scaffold(
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        showCreateAlbumDialog(context) { name ->
                            albumViewModel.createAlbum(name) {
                                Toast.makeText(context, "Album '$name' created!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    containerColor = TelePhotosTheme.AccentBlue,
                    contentColor = Color.White
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Create Album")
                }
            },
            containerColor = TelePhotosTheme.Background
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(TelePhotosTheme.Background)
            ) {
                // Top header
                Text(
                    text = "Albums",
                    color = TelePhotosTheme.TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                )

                if (albumUiModels.isEmpty()) {
                    // Beautiful empty state illustration
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Collections,
                                contentDescription = null,
                                tint = TelePhotosTheme.TextSecondary.copy(alpha = 0.5f),
                                modifier = Modifier.size(96.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No Albums Yet",
                                color = TelePhotosTheme.TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Create collections to organize your favorite photos and sync them automatically to your cloud vault.",
                                color = TelePhotosTheme.TextSecondary,
                                fontSize = 14.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(albumUiModels) { album ->
                            AlbumCard(
                                album = album,
                                onClick = {
                                    selectedAlbumId = album.id
                                    selectedAlbumName = album.name
                                },
                                onLongClick = {
                                    selectedAlbumForActions = album
                                }
                            )
                        }
                    }
                }
            }
            
            // Album options menu on long-press
            if (selectedAlbumForActions != null) {
                AlertDialog(
                    onDismissRequest = { selectedAlbumForActions = null },
                    title = {
                        Text(
                            text = selectedAlbumForActions!!.name,
                            fontWeight = FontWeight.Bold,
                            color = TelePhotosTheme.TextPrimary,
                            fontSize = 18.sp
                        )
                    },
                    text = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // 1. Share via Telegram
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val albumId = selectedAlbumForActions!!.id
                                        selectedAlbumForActions = null
                                        coroutineScope.launch {
                                            val photosMapping = albumViewModel.getAlbumPhotosDirect(albumId)
                                            val photoUris = photosMapping.map { it.photoUri }
                                            val photosList = mergedPhotosList.filter { photo ->
                                                photoUris.any { it == photo.name || it == photo.uri }
                                            }
                                            
                                            if (photosList.isEmpty()) {
                                                Toast.makeText(context, "This album has no photos to share!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                photosToShareInTelegram = photosList
                                                showTelegramAlbumShareDialog = true
                                            }
                                        }
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    tint = TelePhotosTheme.AccentBlue,
                                    modifier = Modifier.size(24.dp).rotate(-30f)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(text = "Share via Telegram", color = TelePhotosTheme.TextPrimary, fontSize = 16.sp)
                            }

                            // 2. Share via System (Standard Share)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val albumId = selectedAlbumForActions!!.id
                                        selectedAlbumForActions = null
                                        coroutineScope.launch {
                                            val photosMapping = albumViewModel.getAlbumPhotosDirect(albumId)
                                            val photoUris = photosMapping.map { it.photoUri }
                                            val photosList = mergedPhotosList.filter { photo ->
                                                photoUris.any { it == photo.name || it == photo.uri }
                                            }
                                            
                                            if (photosList.isEmpty()) {
                                                Toast.makeText(context, "This album has no photos to share!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                UploadManager.sharePhotosToSystem(context, photosList)
                                            }
                                        }
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    tint = TelePhotosTheme.AccentBlue,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(text = "Share via other apps", color = TelePhotosTheme.TextPrimary, fontSize = 16.sp)
                            }

                            // 2.5 Download Album Action
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val albumId = selectedAlbumForActions!!.id
                                        selectedAlbumForActions = null
                                        coroutineScope.launch {
                                            val photosMapping = albumViewModel.getAlbumPhotosDirect(albumId)
                                            val photoUris = photosMapping.map { it.photoUri }
                                            val photosList = mergedPhotosList.filter { photo ->
                                                photoUris.any { it == photo.name || it == photo.uri }
                                            }
                                            if (photosList.isEmpty()) {
                                                Toast.makeText(context, "This album has no photos to download!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                UploadManager.downloadPhotosToDevice(context, photosList)
                                            }
                                        }
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    tint = TelePhotosTheme.AccentBlue,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(text = "Download all to device", color = TelePhotosTheme.TextPrimary, fontSize = 16.sp)
                            }

                            // 3. Delete Album
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val albumId = selectedAlbumForActions!!.id
                                        val albumName = selectedAlbumForActions!!.name
                                        selectedAlbumForActions = null
                                        albumViewModel.deleteAlbum(albumId) {
                                            Toast.makeText(context, "Album '$albumName' deleted.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = TelePhotosTheme.GoogleRed,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(text = "Delete Album", color = TelePhotosTheme.GoogleRed, fontSize = 16.sp)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { selectedAlbumForActions = null }) {
                            Text("Close", color = TelePhotosTheme.AccentBlue)
                        }
                    },
                    containerColor = TelePhotosTheme.Surface,
                    shape = RoundedCornerShape(20.dp)
                )
            }

            // Render custom Telegram share dialog
            if (showTelegramAlbumShareDialog) {
                TelegramShareDialog(
                    photosToShare = photosToShareInTelegram,
                    onDismissRequest = { 
                        showTelegramAlbumShareDialog = false 
                        photosToShareInTelegram = emptyList()
                    },
                    onShareComplete = {
                        showTelegramAlbumShareDialog = false
                        photosToShareInTelegram = emptyList()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumCard(
    album: AlbumUiModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TelePhotosTheme.Surface)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(8.dp)
    ) {
        // Thumbnail cover with gradient fallback
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(Color(0xFFE0E0E0), Color(0xFFF5F5F5))
                    )
                )
        ) {
            if (album.coverUri != null) {
                val isCloud = isCloudPhoto(album.coverUri)
                if (isCloud) {
                    val parsed = parseCloudPhotoUri(album.coverUri)
                    val messageId = parsed?.first ?: 0L
                    if (messageId != 0L) {
                        val localThumbnailPath = rememberCloudThumbnailPath(
                            messageId = messageId,
                            isThumbnail = true
                        )
                        if (localThumbnailPath != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(localThumbnailPath)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = album.name,
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
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Collections,
                                contentDescription = null,
                                tint = TelePhotosTheme.TextSecondary.copy(alpha = 0.3f),
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                } else {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(album.coverUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = album.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Collections,
                        contentDescription = null,
                        tint = TelePhotosTheme.TextSecondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Album details text
        Text(
            text = album.name,
            color = TelePhotosTheme.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Text(
            text = "${album.photoCount} items",
            color = TelePhotosTheme.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumDetailsView(
    albumId: Long,
    albumName: String,
    mergedPhotosList: List<LocalPhoto>,
    onPhotoSelected: (Int, List<LocalPhoto>) -> Unit,
    onBack: () -> Unit,
    albumViewModel: dev.ssjvirtually.tgpix.ui.AlbumViewModel
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Get all photo Uris currently mapped to this album via ViewModel flow
    val albumPhotoUrisFlow = remember(albumViewModel, albumId) {
        albumViewModel.getPhotoUrisForAlbumFlow(albumId)
    }
    val albumPhotoUris by albumPhotoUrisFlow.collectAsState(initial = emptyList())
    
    // Filter overall timeline photos down to exactly the ones inside this album
    val albumPhotos = remember(albumPhotoUris, mergedPhotosList) {
        mergedPhotosList.filter { photo ->
            albumPhotoUris.any { it == photo.name || it == photo.uri }
        }
    }

    BackHandler {
        onBack()
    }

    // Selection mode within the album for removing photos
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedPhotos = remember { mutableStateListOf<LocalPhoto>() }
    var lastLongPressedPhoto by remember { mutableStateOf<LocalPhoto?>(null) }
    val haptic = LocalHapticFeedback.current
    val gridState = rememberLazyGridState()
    var dragStartPhotoIndex by remember { mutableStateOf<Int?>(null) }
    var dragCurrentPhotoIndex by remember { mutableStateOf<Int?>(null) }
    var isDraggingToSelect by remember { mutableStateOf(false) }
    var initialSelection by remember { mutableStateOf<List<LocalPhoto>>(emptyList()) }
    var isSelecting by remember { mutableStateOf(true) }
    var dragCurrentPosition by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    if (isSelectionMode) {
        BackHandler {
            selectedPhotos.clear()
            isSelectionMode = false
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TelePhotosTheme.Surface)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        if (isSelectionMode) {
                            selectedPhotos.clear()
                            isSelectionMode = false
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            imageVector = if (isSelectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TelePhotosTheme.TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isSelectionMode) "${selectedPhotos.size} selected" else albumName,
                        color = TelePhotosTheme.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelectionMode) {
                        // Download selected
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
                                    contentDescription = "Download selected to device",
                                    tint = TelePhotosTheme.AccentBlue
                                )
                            }
                        }

                        // Remove from Album action
                        IconButton(onClick = {
                            albumViewModel.removePhotosFromAlbum(albumId, selectedPhotos.toList()) {
                                Toast.makeText(context, "Removed ${selectedPhotos.size} photos from '$albumName'", Toast.LENGTH_SHORT).show()
                                selectedPhotos.clear()
                                isSelectionMode = false
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Remove from Album",
                                tint = TelePhotosTheme.GoogleRed
                            )
                        }
                    } else {
                        // Delete Entire Album option
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Options",
                                    tint = TelePhotosTheme.TextPrimary
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                modifier = Modifier.background(TelePhotosTheme.Surface)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Delete Album", color = TelePhotosTheme.GoogleRed) },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = TelePhotosTheme.GoogleRed) },
                                    onClick = {
                                        showMenu = false
                                        albumViewModel.deleteAlbum(albumId) {
                                            Toast.makeText(context, "Album '$albumName' deleted.", Toast.LENGTH_SHORT).show()
                                            onBack()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        containerColor = TelePhotosTheme.Background
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(TelePhotosTheme.Background)
        ) {
            val containerHeightPx = constraints.maxHeight.toFloat()
            if (albumPhotos.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Collections,
                            contentDescription = null,
                            tint = TelePhotosTheme.TextSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(80.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "This Album is Empty",
                            color = TelePhotosTheme.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Go to the Photos timeline, select multiple items, and tap the Add to Album icon to add photos here.",
                            color = TelePhotosTheme.TextSecondary,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(1.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(albumPhotos) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { startOffset ->
                                    val startIndex = gridState.getItemIndexAt(startOffset)
                                    val startPhoto = startIndex?.let { albumPhotos.getOrNull(it) }
                                    if (startPhoto != null) {
                                        dragStartPhotoIndex = startIndex
                                        dragCurrentPhotoIndex = startIndex
                                        dragCurrentPosition = startOffset
                                        isDraggingToSelect = true
                                        initialSelection = selectedPhotos.toList()
                                        isSelecting = !initialSelection.contains(startPhoto)
                                        
                                        if (isSelecting) {
                                            if (!selectedPhotos.contains(startPhoto)) selectedPhotos.add(startPhoto)
                                        } else {
                                            selectedPhotos.remove(startPhoto)
                                        }
                                        isSelectionMode = true
                                        lastLongPressedPhoto = startPhoto
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
                                                                albumPhotos.getOrNull(idx)
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
                                                albumPhotos.getOrNull(idx)
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
                        }
                ) {
                    items(albumPhotos) { photo ->
                        val isSelected = selectedPhotos.contains(photo)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    if (lastLongPressedPhoto == photo) {
                                        lastLongPressedPhoto = null
                                        return@clickable
                                    }
                                    lastLongPressedPhoto = null

                                    if (isSelectionMode) {
                                        if (isSelected) selectedPhotos.remove(photo) else selectedPhotos.add(photo)
                                        if (selectedPhotos.isEmpty()) isSelectionMode = false
                                    } else {
                                        // Tap opens in our main full-screen viewer
                                        val indexInAlbum = albumPhotos.indexOf(photo)
                                        onPhotoSelected(indexInAlbum, albumPhotos)
                                    }
                                }
                        ) {
                            val isCloud = isCloudPhoto(photo.uri)
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

                            // Visual overlay for selection
                            if (isSelectionMode) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            if (isSelected) TelePhotosTheme.AccentBlue.copy(alpha = 0.4f)
                                            else Color.Black.copy(alpha = 0.1f)
                                        ),
                                    contentAlignment = Alignment.TopStart
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = null, // Handled by outer combinedClickable
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = TelePhotosTheme.AccentBlue,
                                            unselectedColor = Color.White
                                        ),
                                        modifier = Modifier.padding(4.dp)
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
