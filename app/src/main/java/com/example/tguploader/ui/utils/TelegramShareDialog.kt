package com.example.tguploader.ui.utils

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tguploader.storage.LocalPhoto
import com.example.tguploader.telegram.TdlibManager
import com.example.tguploader.telegram.UploadManager
import com.example.tguploader.ui.theme.TelePhotosTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramShareDialog(
    photosToShare: List<LocalPhoto>,
    onDismissRequest: () -> Unit,
    onShareComplete: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var isLoadingChats by remember { mutableStateOf(true) }
    var chatList by remember { mutableStateOf<List<TdApi.Chat>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    
    var isSharing by remember { mutableStateOf(false) }
    var sharingProgress by remember { mutableStateOf("") }

    // Helper suspending function to send TDLib requests
    suspend fun sendRequest(request: TdApi.Function<out TdApi.Object>): TdApi.Object = suspendCancellableCoroutine { continuation ->
        try {
            TdlibManager.getClient().send(request) { result ->
                continuation.resume(result)
            }
        } catch (e: Exception) {
            continuation.resume(TdApi.Error(500, e.message ?: "Exception in sendRequest"))
        }
    }

    // Load active chats list on launch
    LaunchedEffect(Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                // Fetch chats list (main chat list)
                val chatsResult = sendRequest(TdApi.GetChats(TdApi.ChatListMain(), 40))
                if (chatsResult is TdApi.Chats) {
                    val loadedChats = mutableListOf<TdApi.Chat>()
                    for (chatId in chatsResult.chatIds) {
                        val chat = sendRequest(TdApi.GetChat(chatId))
                        if (chat is TdApi.Chat) {
                            loadedChats.add(chat)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        chatList = loadedChats
                        isLoadingChats = false
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isLoadingChats = false
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isLoadingChats = false
                }
            }
        }
    }

    // Filter chats based on query
    val filteredChats = remember(chatList, searchQuery) {
        if (searchQuery.isEmpty()) {
            chatList
        } else {
            chatList.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isSharing) onDismissRequest() },
        title = {
            Text(
                text = "Share to Telegram Chat",
                fontWeight = FontWeight.Bold,
                color = TelePhotosTheme.TextPrimary,
                fontSize = 18.sp
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(350.dp)
            ) {
                if (isSharing) {
                    // Sharing progress overlay
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = TelePhotosTheme.AccentBlue)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = sharingProgress,
                            color = TelePhotosTheme.TextPrimary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                    }
                } else if (isLoadingChats) {
                    // Loading chats spinner
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = TelePhotosTheme.AccentBlue)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Loading chats...",
                            color = TelePhotosTheme.TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    // Main Chat list + Search bar
                    Column(modifier = Modifier.fillMaxSize()) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search chats...", color = TelePhotosTheme.TextSecondary, fontSize = 14.sp) },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TelePhotosTheme.TextSecondary) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = TelePhotosTheme.AccentBlue,
                                unfocusedBorderColor = TelePhotosTheme.TextSecondary.copy(alpha = 0.3f),
                                focusedLabelColor = TelePhotosTheme.AccentBlue
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        )
                        
                        if (filteredChats.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No chats found",
                                    color = TelePhotosTheme.TextSecondary,
                                    fontSize = 14.sp
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                 items(filteredChats) { chat ->
                                    ChatRow(chat = chat) {
                                        // Enqueue background ShareWorker Foreground Service
                                        val photoUris = photosToShare.map { it.uri }.toTypedArray()
                                        val inputData = androidx.work.Data.Builder()
                                            .putLong("chat_id", chat.id)
                                            .putStringArray("photo_uris", photoUris)
                                            .build()
                                            
                                        val shareRequest = androidx.work.OneTimeWorkRequestBuilder<com.example.tguploader.worker.ShareWorker>()
                                            .setInputData(inputData)
                                            .addTag("share_work")
                                            .build()
                                            
                                        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                                            "share_work_" + System.currentTimeMillis(),
                                            androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
                                            shareRequest
                                        )
                                        
                                        Toast.makeText(
                                            context, 
                                            "Sharing ${photosToShare.size} items to ${chat.title} in the background...", 
                                            Toast.LENGTH_LONG
                                        ).show()
                                        
                                        onShareComplete()
                                    }
                                 }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isSharing) {
                TextButton(onClick = onDismissRequest) {
                    Text("Cancel", color = TelePhotosTheme.AccentBlue)
                }
            }
        },
        containerColor = TelePhotosTheme.Surface,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
fun ChatRow(
    chat: TdApi.Chat,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Chat Avatar / Icon Based on Type
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(TelePhotosTheme.AccentBlue.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            val icon = when (chat.type) {
                is TdApi.ChatTypePrivate -> Icons.Default.Person
                is TdApi.ChatTypeBasicGroup, is TdApi.ChatTypeSupergroup -> Icons.Default.Groups
                else -> Icons.Default.Cloud
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TelePhotosTheme.AccentBlue,
                modifier = Modifier.size(20.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // Chat title
        Text(
            text = chat.title,
            color = TelePhotosTheme.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}
