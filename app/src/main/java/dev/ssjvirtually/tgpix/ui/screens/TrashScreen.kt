package dev.ssjvirtually.tgpix.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.ssjvirtually.tgpix.storage.BackupManager
import dev.ssjvirtually.tgpix.storage.CloudPhotoEntity
import dev.ssjvirtually.tgpix.storage.LocalPhoto
import dev.ssjvirtually.tgpix.ui.GalleryViewModel
import dev.ssjvirtually.tgpix.ui.theme.TelePhotosTheme
import dev.ssjvirtually.tgpix.ui.utils.rememberCloudThumbnailPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun CloudPhotoEntity.toLocalPhoto(): LocalPhoto {
    val cloudUri = "cloud://${this.messageId}/${this.telegramFileId}/${this.fileName}"
    val displayDate = if (this.dateTaken > 0L) this.dateTaken else this.uploadedAt
    return LocalPhoto(
        id = -this.messageId,
        uri = cloudUri,
        name = this.fileName,
        size = this.fileSize,
        dateTaken = displayDate,
        tags = this.tags
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    onPhotoSelected: (Int, List<LocalPhoto>) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val trashedEntities by viewModel.trashedPhotos.collectAsState(initial = emptyList())
    val trashedPhotos = remember(trashedEntities) {
        trashedEntities.map { it.toLocalPhoto() }
    }

    var selectedPhotoForAction by remember { mutableStateOf<LocalPhoto?>(null) }
    var showEmptyTrashConfirm by remember { mutableStateOf(false) }
    var isProcessingAction by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TelePhotosTheme.Surface)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TelePhotosTheme.TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Trash",
                            color = TelePhotosTheme.TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Items will be permanently deleted after 30 days.",
                            color = TelePhotosTheme.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                if (trashedPhotos.isNotEmpty()) {
                    TextButton(
                        onClick = { showEmptyTrashConfirm = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = TelePhotosTheme.GoogleRed)
                    ) {
                        Text("Empty Trash", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TelePhotosTheme.Background)
                .padding(paddingValues)
        ) {
            if (trashedPhotos.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Trash Empty",
                        tint = TelePhotosTheme.TextSecondary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Trash is empty",
                        color = TelePhotosTheme.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Photos you delete from the cloud will appear here.",
                        color = TelePhotosTheme.TextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(trashedPhotos) { photo ->
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { selectedPhotoForAction = photo }
                        ) {
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
                        }
                    }
                }
            }

            if (isProcessingAction) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = TelePhotosTheme.AccentBlue)
                }
            }
        }
    }

    // Confirmation dialog for individual photo actions
    selectedPhotoForAction?.let { photo ->
        val msgId = -photo.id
        AlertDialog(
            onDismissRequest = { if (!isProcessingAction) selectedPhotoForAction = null },
            title = { Text("Restore or Delete permanently?") },
            text = { Text("Restoring will return the photo to your timeline. Deleting permanently removes it forever from your Telegram cloud and local cache.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        isProcessingAction = true
                        coroutineScope.launch {
                            BackupManager.restoreCloudPhoto(context, msgId)
                            isProcessingAction = false
                            selectedPhotoForAction = null
                            Toast.makeText(context, "Photo restored to timeline ✓", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !isProcessingAction
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Restore")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        isProcessingAction = true
                        coroutineScope.launch {
                            val success = BackupManager.deleteCloudPhotoPermanently(context, msgId)
                            isProcessingAction = false
                            selectedPhotoForAction = null
                            if (success) {
                                Toast.makeText(context, "Photo permanently deleted", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Failed to delete photo", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isProcessingAction,
                    colors = ButtonDefaults.textButtonColors(contentColor = TelePhotosTheme.GoogleRed)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete Permanently")
                    }
                }
            }
        )
    }

    // Confirmation dialog for Empty Trash
    if (showEmptyTrashConfirm) {
        AlertDialog(
            onDismissRequest = { if (!isProcessingAction) showEmptyTrashConfirm = false },
            title = { Text("Empty Trash?") },
            text = { Text("All items in the Trash will be permanently deleted from your Telegram cloud vault and local cache. This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        isProcessingAction = true
                        coroutineScope.launch {
                            var successCount = 0
                            var failCount = 0
                            for (photo in trashedPhotos) {
                                val success = BackupManager.deleteCloudPhotoPermanently(context, -photo.id)
                                if (success) successCount++ else failCount++
                            }
                            isProcessingAction = false
                            showEmptyTrashConfirm = false
                            if (failCount > 0) {
                                Toast.makeText(context, "Emptied trash: $successCount deleted, $failCount failed", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Trash emptied successfully ✓", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isProcessingAction,
                    colors = ButtonDefaults.buttonColors(containerColor = TelePhotosTheme.GoogleRed)
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEmptyTrashConfirm = false },
                    enabled = !isProcessingAction
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
