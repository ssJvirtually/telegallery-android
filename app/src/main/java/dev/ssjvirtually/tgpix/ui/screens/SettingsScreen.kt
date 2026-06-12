package dev.ssjvirtually.tgpix.ui.screens

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.draw.rotate
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
import dev.ssjvirtually.tgpix.MainActivity
import dev.ssjvirtually.tgpix.storage.PreferencesManager
import dev.ssjvirtually.tgpix.storage.MediaStoreScanner
import dev.ssjvirtually.tgpix.storage.UploadDatabase
import dev.ssjvirtually.tgpix.storage.BackupEventEntity
import dev.ssjvirtually.tgpix.telegram.AuthManager
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import dev.ssjvirtually.tgpix.ui.theme.TelePhotosTheme
import dev.ssjvirtually.tgpix.worker.UploadWorker
import androidx.compose.material.icons.filled.QrCode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import org.drinkless.tdlib.TdApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class FolderInfo(
    val id: String,
    val name: String
) {
    val isCamera: Boolean
        get() = name.equals("Camera", ignoreCase = true) || name.equals("DCIM", ignoreCase = true)
}

@Composable
fun SettingsScreen(
    selectedChatTitle: String,
    onResetChat: () -> Unit,
    onBack: () -> Unit,
    onTrashClick: () -> Unit
) {
    val context = LocalContext.current as MainActivity
    val chats by TdlibManager.chats.collectAsState()
    val systemLogs by TdlibManager.logs.collectAsState()
    var activePickerType by remember { mutableStateOf<String?>(null) }
    
    val dbVersion by TdlibManager.dbVersion.collectAsState()
    val db = remember(dbVersion) { UploadDatabase.getDatabase(context) }
    val eventDao = remember(db) { db.eventDao() }
    val backupEvents by eventDao.getAllFlow().collectAsState(initial = emptyList())
    
    var dbChatTitle by remember {
        mutableStateOf(
            PreferencesManager.getDbChatTitle(context) ?: "Private Saved Messages"
        )
    }

    val coroutineScope = rememberCoroutineScope()

    var showFolderSelectionDialog by remember { mutableStateOf(false) }
    var selectedFolderIds by remember { mutableStateOf(PreferencesManager.getBackupFolderIds(context)) }
    var localFolders by remember { mutableStateOf<List<FolderInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val allPhotos = MediaStoreScanner.scan(context)
            val folders = allPhotos.map { photo ->
                FolderInfo(
                    id = photo.bucketId,
                    name = photo.bucketName
                )
            }.filter { it.id.isNotEmpty() && it.name.isNotEmpty() }.distinctBy { it.id }.sortedBy { it.name }
            localFolders = folders
        }
    }

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
            // Vault & Backup Channels Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = TelePhotosTheme.Surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Backup Channels",
                            color = TelePhotosTheme.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // Photos Backup Target
                        Column {
                            Text(
                                text = "Photos Backup Target",
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
                                    activePickerType = "main"
                                }) {
                                    Text("Change", color = TelePhotosTheme.AccentBlue, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = TelePhotosTheme.SurfaceVariant)
                        Spacer(modifier = Modifier.height(12.dp))

                        // Dedicated Backup Channel (Database & Albums)
                        Column {
                            Text(
                                text = "Dedicated Backup Channel",
                                color = TelePhotosTheme.TextSecondary,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Routes SQLite snapshots and album manifests to this channel. A master database backup is still updated once every 24 hours in Saved Messages for safety.",
                                color = TelePhotosTheme.TextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = dbChatTitle,
                                    color = TelePhotosTheme.AccentBlue,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Row {
                                    TextButton(onClick = {
                                        TdlibManager.loadChats()
                                        activePickerType = "db"
                                    }) {
                                        Text("Change", color = TelePhotosTheme.AccentBlue, fontWeight = FontWeight.Bold)
                                    }
                                    if (dbChatTitle != "Private Saved Messages") {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        TextButton(onClick = {
                                            PreferencesManager.saveDbChatId(context, 0L)
                                            PreferencesManager.saveDbChatTitle(context, "Private Saved Messages")
                                            dbChatTitle = "Private Saved Messages"
                                            Toast.makeText(context, "Database backup target reset to Saved Messages", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Text("Reset", color = Color.Gray, fontWeight = FontWeight.Normal)
                                        }
                                    }
                                }
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
                                    dev.ssjvirtually.tgpix.worker.BackupScheduler.schedulePhotoBackup(context)
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
                                    dev.ssjvirtually.tgpix.worker.BackupScheduler.schedulePhotoBackup(context)
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

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = TelePhotosTheme.SurfaceVariant)
                        Spacer(modifier = Modifier.height(14.dp))

                        // Row 4: Select Backup Folders
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showFolderSelectionDialog = true }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Back Up Device Folders", color = TelePhotosTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                val selectedCount = selectedFolderIds.size
                                val foldersText = if (selectedCount == 0) "Camera Roll only" else "Camera Roll + $selectedCount other folders"
                                Text(foldersText, color = TelePhotosTheme.TextSecondary, fontSize = 11.sp)
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Choose Folders",
                                tint = TelePhotosTheme.TextSecondary,
                                modifier = Modifier
                                    .size(20.dp)
                                    .rotate(180f)
                            )
                        }
                    }
                }
            }

            // Trash Management Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = TelePhotosTheme.Surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTrashClick() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Trash",
                                tint = TelePhotosTheme.GoogleRed,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "Trash",
                                    color = TelePhotosTheme.TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Restore or permanently delete photos",
                                    color = TelePhotosTheme.TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = TelePhotosTheme.TextSecondary,
                            modifier = Modifier.rotate(180f)
                        )
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
                var isRestoring by remember { mutableStateOf(false) }
                Button(
                    onClick = {
                        isRestoring = true
                        coroutineScope.launch(Dispatchers.IO) {
                            val success = dev.ssjvirtually.tgpix.storage.BackupManager.restoreDatabaseForce(context)
                            withContext(Dispatchers.Main) {
                                isRestoring = false
                                if (success) {
                                    Toast.makeText(context, "Database & Albums restored successfully! Reloading...", Toast.LENGTH_LONG).show()
                                    onResetChat()
                                } else {
                                    Toast.makeText(context, "Failed to restore backup (check your database backup chat settings or active connection).", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    enabled = !isRestoring,
                    colors = ButtonDefaults.buttonColors(containerColor = TelePhotosTheme.AccentBlue),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(14.dp)
                ) {
                    if (isRestoring) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restore Albums & Database Backup Now", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        val scannerOptions = GmsBarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                            .build()
                        val scanner = GmsBarcodeScanning.getClient(context, scannerOptions)

                        scanner.startScan()
                            .addOnSuccessListener { barcode: Barcode ->
                                val link = barcode.rawValue
                                if (link != null && link.startsWith("tg://login?token=")) {
                                    coroutineScope.launch {
                                        val result = TdlibManager.sendRequest(TdApi.ConfirmQrCodeAuthentication(link))
                                        withContext(Dispatchers.Main) {
                                            if (result is TdApi.Ok || result is TdApi.Session) {
                                                Toast.makeText(context, "Successfully authorized Web Session!", Toast.LENGTH_LONG).show()
                                            } else if (result is TdApi.Error) {
                                                Toast.makeText(context, "Web Login failed: [${result.code}] ${result.message}", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(context, "Successfully authorized Web Session!", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                } else {
                                    Toast.makeText(context, "Invalid QR code. Please scan a Telegram/TGPix Web login QR code.", Toast.LENGTH_LONG).show()
                                }
                            }
                            .addOnFailureListener { e ->
                                if (e is com.google.android.gms.common.api.ApiException && e.statusCode == 16) {
                                    // User canceled, do nothing
                                } else {
                                    Toast.makeText(context, "Scanning failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TelePhotosTheme.AccentBlue),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(14.dp)
                ) {
                    Icon(Icons.Default.QrCode, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Link Web Application (Scan QR)", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            item {
                OutlinedButton(
                    onClick = {
                        PreferencesManager.setManualLogout(context, true)
                        AuthManager.logOut {
                            coroutineScope.launch(Dispatchers.Main) {
                                Toast.makeText(context, "Logged out of Telegram successfully", Toast.LENGTH_LONG).show()
                                TdlibManager.performLogoutCleanup(context)
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

            // Backup Events Observability History
            item {
                Text(
                    text = "Backup & Restore History",
                    color = TelePhotosTheme.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }

            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D12)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                ) {
                    if (backupEvents.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No backup events recorded yet.",
                                color = TelePhotosTheme.TextSecondary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(backupEvents.take(50)) { event ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF16161F), RoundedCornerShape(8.dp))
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val isSuccess = event.eventType.contains("success")
                                    val icon = if (isSuccess) Icons.Default.Cloud else Icons.Default.Info
                                    val iconColor = if (isSuccess) Color(0xFF00E676) else TelePhotosTheme.GoogleRed
                                    
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = iconColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            val title = when (event.eventType) {
                                                "photo_upload_success" -> "Photo Synced"
                                                "photo_upload_failed" -> "Photo Sync Failed"
                                                "photo_upload_permanently_failed" -> "Photo Permanent Failure"
                                                "db_backup_success" -> "Database Backed Up"
                                                "db_backup_failed" -> "Database Backup Failed"
                                                "restore_success" -> "Backup Restored"
                                                "restore_failed" -> "Restore Failed"
                                                else -> event.eventType.replace("_", " ").replaceFirstChar { it.uppercase() }
                                            }
                                            Text(
                                                text = title,
                                                color = TelePhotosTheme.TextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            val timeFormatted = remember(event.timestamp) {
                                                val sdf = java.text.SimpleDateFormat("HH:mm:ss dd/MM", java.util.Locale.getDefault())
                                                sdf.format(java.util.Date(event.timestamp))
                                            }
                                            Text(
                                                text = timeFormatted,
                                                color = TelePhotosTheme.TextSecondary,
                                                fontSize = 10.sp
                                            )
                                        }
                                        event.details?.let {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = it,
                                                color = TelePhotosTheme.TextSecondary,
                                                fontSize = 11.sp,
                                                lineHeight = 14.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // System JNI Core Logs Console
            item {
                Text(
                    text = "Active JNI Core Logs",
                    color = TelePhotosTheme.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
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
    if (activePickerType != null) {
        Dialog(onDismissRequest = { activePickerType = null }) {
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
                        text = if (activePickerType == "main") "Change Photos Target Chat" else "Change Database Target Chat",
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
                                            val selectedTitle = chat.title.ifEmpty { "Saved Messages" }
                                            if (activePickerType == "main") {
                                                PreferencesManager.saveChatId(context, chat.id)
                                                PreferencesManager.saveChatTitle(context, selectedTitle)
                                                onResetChat() // Triggers UI redraw in parent
                                            } else {
                                                PreferencesManager.saveDbChatId(context, chat.id)
                                                PreferencesManager.saveDbChatTitle(context, selectedTitle)
                                                dbChatTitle = selectedTitle
                                            }
                                            activePickerType = null
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

    if (showFolderSelectionDialog) {
        AlertDialog(
            onDismissRequest = { showFolderSelectionDialog = false },
            title = {
                Text(
                    text = "Back up device folders",
                    color = TelePhotosTheme.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                if (localFolders.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No other media folders found.", color = TelePhotosTheme.TextSecondary, fontSize = 14.sp)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        items(localFolders) { folder ->
                            val isCamera = folder.isCamera
                            val isChecked = isCamera || selectedFolderIds.contains(folder.id)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isCamera) {
                                        val newSet = selectedFolderIds.toMutableSet()
                                        if (newSet.contains(folder.id)) {
                                            newSet.remove(folder.id)
                                        } else {
                                            newSet.add(folder.id)
                                        }
                                        selectedFolderIds = newSet
                                        PreferencesManager.setBackupFolderIds(context, newSet)
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = if (isCamera) null else { checked ->
                                        val newSet = selectedFolderIds.toMutableSet()
                                        if (checked) {
                                            newSet.add(folder.id)
                                        } else {
                                            newSet.remove(folder.id)
                                        }
                                        selectedFolderIds = newSet
                                        PreferencesManager.setBackupFolderIds(context, newSet)
                                    },
                                    enabled = !isCamera,
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = TelePhotosTheme.AccentBlue,
                                        checkmarkColor = Color.White
                                    )
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = folder.name,
                                        color = TelePhotosTheme.TextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (isCamera) {
                                        Text(
                                            text = "Camera Roll (Always enabled)",
                                            color = TelePhotosTheme.AccentBlue,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFolderSelectionDialog = false }) {
                    Text("Done", color = TelePhotosTheme.AccentBlue, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = TelePhotosTheme.Surface
        )
    }
}
