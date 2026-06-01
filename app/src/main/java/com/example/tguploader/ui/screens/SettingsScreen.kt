package com.example.tguploader.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.tguploader.MainActivity
import com.example.tguploader.storage.PreferencesManager
import com.example.tguploader.telegram.AuthManager
import com.example.tguploader.telegram.TdlibManager
import com.example.tguploader.ui.theme.TelePhotosTheme
import com.example.tguploader.worker.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    selectedChatTitle: String,
    onResetChat: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current as MainActivity
    val chats by TdlibManager.chats.collectAsState()
    val systemLogs by TdlibManager.logs.collectAsState()
    var showChatPickerDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TelePhotosTheme.Background)
    ) {
        // Premium Google Photos-style settings top bar with back navigation
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TelePhotosTheme.Surface)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TelePhotosTheme.TextPrimary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Settings",
                color = TelePhotosTheme.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Selected Chat Display card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = TelePhotosTheme.Surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Backup Target Chat",
                            color = TelePhotosTheme.TextSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedChatTitle,
                                color = TelePhotosTheme.AccentBlue,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                TdlibManager.loadChats()
                                showChatPickerDialog = true
                            }) {
                                Text("Change", color = TelePhotosTheme.AccentBlue, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Core Backup Preferences Toggle Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = TelePhotosTheme.Surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Backup Options",
                            color = TelePhotosTheme.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // Toggle 1: Start or Stop Backup Engine
                        var backupActive by remember { mutableStateOf(PreferencesManager.isBackupActive(context)) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Active Backup Sync", color = TelePhotosTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Auto-backup your local gallery in background", color = TelePhotosTheme.TextSecondary, fontSize = 11.sp)
                            }
                            Switch(
                                checked = backupActive,
                                onCheckedChange = { checked ->
                                    backupActive = checked
                                    PreferencesManager.setBackupActive(context, checked)
                                    context.scheduleSyncWorker()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = TelePhotosTheme.AccentBlue,
                                    checkedTrackColor = TelePhotosTheme.AccentBlue.copy(alpha = 0.3f),
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = Color(0xFFD1D1D6)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = TelePhotosTheme.SurfaceVariant)
                        Spacer(modifier = Modifier.height(14.dp))

                        // Toggle 2: Wifi Only (Mobile Data Saver Toggle)
                        var wifiOnly by remember { mutableStateOf(PreferencesManager.isWifiOnly(context)) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Back Up Over Wi-Fi Only", color = TelePhotosTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Do not use mobile cellular data for backups", color = TelePhotosTheme.TextSecondary, fontSize = 11.sp)
                            }
                            Switch(
                                checked = wifiOnly,
                                onCheckedChange = { checked ->
                                    wifiOnly = checked
                                    PreferencesManager.setWifiOnly(context, checked)
                                    context.scheduleSyncWorker()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = TelePhotosTheme.AccentBlue,
                                    checkedTrackColor = TelePhotosTheme.AccentBlue.copy(alpha = 0.3f),
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = Color(0xFFD1D1D6)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = TelePhotosTheme.SurfaceVariant)
                        Spacer(modifier = Modifier.height(14.dp))

                        // Toggle 3: Send Photos in HD Lossless Quality
                        var hdMode by remember { mutableStateOf(PreferencesManager.isHdMode(context)) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Send Photos in HD Quality", color = TelePhotosTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Upload original lossless files without quality loss", color = TelePhotosTheme.TextSecondary, fontSize = 11.sp)
                            }
                            Switch(
                                checked = hdMode,
                                onCheckedChange = { checked ->
                                    hdMode = checked
                                    PreferencesManager.setHdMode(context, checked)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = TelePhotosTheme.AccentBlue,
                                    checkedTrackColor = TelePhotosTheme.AccentBlue.copy(alpha = 0.3f),
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = Color(0xFFD1D1D6)
                                )
                            )
                        }
                    }
                }
            }

            // Action Buttons
            item {
                Button(
                    onClick = {
                        val isBackupActive = PreferencesManager.isBackupActive(context)
                        if (!isBackupActive) {
                            Toast.makeText(context, "Please enable 'Active Backup Sync' first!", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val oneTimeWorkRequest = OneTimeWorkRequestBuilder<UploadWorker>().build()
                        WorkManager.getInstance(context).enqueue(oneTimeWorkRequest)
                        Toast.makeText(context, "Force sync started in background!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TelePhotosTheme.AccentBlue),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(14.dp)
                ) {
                    Icon(Icons.Default.Cloud, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Force Synchronize Local Photos Now", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            item {
                OutlinedButton(
                    onClick = {
                        AuthManager.logOut {
                            coroutineScope.launch(Dispatchers.Main) {
                                Toast.makeText(context, "Logged out of Telegram successfully", Toast.LENGTH_LONG).show()
                                onResetChat()
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, TelePhotosTheme.GoogleRed),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TelePhotosTheme.GoogleRed)
                ) {
                    Text("Logout Telegram Session", fontWeight = FontWeight.Bold)
                }
            }

            // System JNI Core Logs Console
            item {
                Text(
                    text = "Active JNI Core Logs",
                    color = TelePhotosTheme.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF09090C)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(systemLogs) { log ->
                            Text(
                                text = log,
                                color = when {
                                    log.contains("fail", ignoreCase = true) || log.contains("error", ignoreCase = true) -> TelePhotosTheme.GoogleRed
                                    log.contains("success", ignoreCase = true) -> Color(0xFF00E676)
                                    else -> TelePhotosTheme.TextSecondary
                                },
                                fontSize = 10.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal popup to select active chat
    if (showChatPickerDialog) {
        Dialog(onDismissRequest = { showChatPickerDialog = false }) {
            var searchQuery by remember { mutableStateOf("") }
            val filteredChats = remember(chats, searchQuery) {
                chats.filter { it.title.contains(searchQuery, ignoreCase = true) }
            }

            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = TelePhotosTheme.Surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.75f)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Change Target Chat",
                        color = TelePhotosTheme.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search chats...", color = TelePhotosTheme.TextSecondary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TelePhotosTheme.AccentBlue,
                            unfocusedBorderColor = TelePhotosTheme.SurfaceVariant,
                            focusedTextColor = TelePhotosTheme.TextPrimary,
                            unfocusedTextColor = TelePhotosTheme.TextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (filteredChats.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No matching chats found.",
                                color = TelePhotosTheme.TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredChats) { chat ->
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = TelePhotosTheme.SurfaceVariant),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            PreferencesManager.saveChatId(context, chat.id)
                                            PreferencesManager.saveChatTitle(context, chat.title)
                                            onResetChat() // Triggers UI redraw in parent
                                            showChatPickerDialog = false
                                        }
                                ) {
                                    Text(
                                        text = chat.title.ifEmpty { "Saved Messages" },
                                        color = TelePhotosTheme.TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(16.dp)
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
