package com.example.tguploader.ui.screens

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.tguploader.storage.LocalPhoto
import com.example.tguploader.storage.AlbumEntity
import com.example.tguploader.storage.AlbumPhotoEntity
import com.example.tguploader.storage.UploadDatabase
import com.example.tguploader.storage.MediaStoreScanner
import com.example.tguploader.ui.theme.TelePhotosTheme
import com.example.tguploader.ui.utils.*
import kotlinx.coroutines.Dispatchers
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
    onPhotoSelected: (Int, List<LocalPhoto>) -> Unit
) {
    val context = LocalContext.current
    val db = remember { UploadDatabase.getDatabase(context) }
    val coroutineScope = rememberCoroutineScope()

    // Standalone MediaStore and Cloud matching to avoid parent lifting complexity
    var localPhotos by remember { mutableStateOf<List<LocalPhoto>>(emptyList()) }
    val cloudLogs by db.cloudDao().getAllFlow().collectAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            val scanned = MediaStoreScanner.scan(context)
            withContext(Dispatchers.Main) {
                localPhotos = scanned
            }
        }
    }

    val mergedPhotosList = remember(localPhotos, cloudLogs) {
        val list = mutableListOf<LocalPhoto>()
        val localByFingerprint = localPhotos.associateBy { "${it.name}_${it.size}_${it.dateTaken}" }
        val localByName = localPhotos.associateBy { it.name }
        val matchedLocalKeys = mutableSetOf<String>()

        for (cloud in cloudLogs) {
            val cloudFingerprint = "${cloud.fileName}_${cloud.fileSize}_${cloud.uploadedAt}"
            val matchingLocal = localByFingerprint[cloudFingerprint]
                ?: localByName[cloud.fileName]
            if (matchingLocal != null) {
                list.add(matchingLocal)
                matchedLocalKeys.add(matchingLocal.name)
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
            if (!matchedLocalKeys.contains(local.name)) {
                list.add(local)
            }
        }

        list.sortedByDescending { it.dateTaken }
    }

    // 1. Load albums from DB
    val albums by db.albumDao().getAllAlbumsFlow().collectAsState(initial = emptyList())
    var albumUiModels by remember { mutableStateOf<List<AlbumUiModel>>(emptyList()) }

    // Re-query database when albums or photos list change to get correct item count and cover photo
    LaunchedEffect(albums, mergedPhotosList) {
        withContext(Dispatchers.IO) {
            val uiModels = albums.map { album ->
                val photos = db.albumDao().getAlbumPhotosDirect(album.id)
                val coverPhoto = photos.lastOrNull()?.photoUri
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
            db = db
        )
    } else {
        // Main Grid view of all albums
        Scaffold(
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        showCreateAlbumDialog(context) { name ->
                            coroutineScope.launch(Dispatchers.IO) {
                                db.albumDao().insertAlbum(AlbumEntity(name = name))
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Album '$name' created!", Toast.LENGTH_SHORT).show()
                                }
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
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumCard(
    album: AlbumUiModel,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TelePhotosTheme.Surface)
            .clickable { onClick() }
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
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(album.coverUri)
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
    db: UploadDatabase
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Get all photo Uris currently mapped to this album
    val albumPhotoUris by db.albumDao().getPhotoUrisForAlbumFlow(albumId).collectAsState(initial = emptyList())
    
    // Filter overall timeline photos down to exactly the ones inside this album
    val albumPhotos = remember(albumPhotoUris, mergedPhotosList) {
        mergedPhotosList.filter { photo -> albumPhotoUris.contains(photo.uri) }
    }

    BackHandler {
        onBack()
    }

    // Selection mode within the album for removing photos
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedPhotos = remember { mutableStateListOf<LocalPhoto>() }

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
                        // Remove from Album action
                        IconButton(onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                selectedPhotos.forEach { photo ->
                                    db.albumDao().removePhotoFromAlbum(albumId, photo.uri)
                                }
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Removed ${selectedPhotos.size} photos from '$albumName'", Toast.LENGTH_SHORT).show()
                                    selectedPhotos.clear()
                                    isSelectionMode = false
                                }
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
                                        coroutineScope.launch(Dispatchers.IO) {
                                            db.albumDao().deleteAlbum(albumId)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "Album '$albumName' deleted.", Toast.LENGTH_SHORT).show()
                                                onBack()
                                            }
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(TelePhotosTheme.Background)
        ) {
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
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(1.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(albumPhotos) { photo ->
                        val isSelected = selectedPhotos.contains(photo)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .combinedClickable(
                                    onClick = {
                                        if (isSelectionMode) {
                                            if (isSelected) selectedPhotos.remove(photo) else selectedPhotos.add(photo)
                                            if (selectedPhotos.isEmpty()) isSelectionMode = false
                                        } else {
                                            // Tap opens in our main full-screen viewer
                                            val indexInAlbum = albumPhotos.indexOf(photo)
                                            onPhotoSelected(indexInAlbum, albumPhotos)
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSelectionMode) {
                                            isSelectionMode = true
                                            selectedPhotos.add(photo)
                                        }
                                    }
                                )
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(photo.uri)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = photo.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )

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
