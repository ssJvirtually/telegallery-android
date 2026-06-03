package dev.ssjvirtually.tgpix.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.ssjvirtually.tgpix.BuildConfig
import dev.ssjvirtually.tgpix.ui.theme.TelePhotosTheme
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var downloadProgress by remember { mutableStateOf(0f) }
    var updateState by remember { mutableStateOf(UpdateState.IDLE) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Helper function to start the download
    val startDownload = {
        updateState = UpdateState.DOWNLOADING
        downloadProgress = 0f
        errorMessage = null
        coroutineScope.launch {
            val file = UpdateManager.downloadApk(context, updateInfo.apkUrl) { progress ->
                downloadProgress = progress
            }
            if (file != null) {
                downloadedFile = file
                if (UpdateManager.canInstallPackages(context)) {
                    UpdateManager.installApk(context, file)
                    if (!updateInfo.forceUpdate) {
                        onDismiss()
                    } else {
                        updateState = UpdateState.READY_TO_INSTALL
                    }
                } else {
                    updateState = UpdateState.PERMISSION_REQUIRED
                }
            } else {
                updateState = UpdateState.ERROR
                errorMessage = "Failed to download update. Please try again."
            }
        }
    }

    // Automatically check permission state when returning to the app
    DisposableEffect(lifecycleOwner, updateState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (updateState == UpdateState.PERMISSION_REQUIRED) {
                    if (UpdateManager.canInstallPackages(context)) {
                        downloadedFile?.let {
                            UpdateManager.installApk(context, it)
                            if (!updateInfo.forceUpdate) {
                                onDismiss()
                            } else {
                                updateState = UpdateState.READY_TO_INSTALL
                            }
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Dialog(
        onDismissRequest = {
            if (!updateInfo.forceUpdate && updateState != UpdateState.DOWNLOADING) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = !updateInfo.forceUpdate && updateState != UpdateState.DOWNLOADING,
            dismissOnClickOutside = !updateInfo.forceUpdate && updateState != UpdateState.DOWNLOADING
        )
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = TelePhotosTheme.Surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            color = TelePhotosTheme.AccentBlue.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(16.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = "Update Available",
                        tint = TelePhotosTheme.AccentBlue,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Title
                Text(
                    text = "Update Available",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TelePhotosTheme.TextPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Version comparison
                Text(
                    text = "v${BuildConfig.VERSION_NAME} → v${updateInfo.versionName}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TelePhotosTheme.TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Content state switcher
                when (updateState) {
                    UpdateState.IDLE -> {
                        if (updateInfo.releaseNotes.isNotEmpty()) {
                            Text(
                                text = "What's New:",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TelePhotosTheme.TextPrimary,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 120.dp)
                                    .background(
                                        color = TelePhotosTheme.Background,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(12.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = updateInfo.releaseNotes,
                                    fontSize = 13.sp,
                                    color = TelePhotosTheme.TextSecondary,
                                    lineHeight = 18.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }

                        // Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (!updateInfo.forceUpdate) {
                                TextButton(
                                    onClick = onDismiss,
                                    colors = ButtonDefaults.textButtonColors(contentColor = TelePhotosTheme.TextSecondary)
                                ) {
                                    Text("Later")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Button(
                                onClick = { startDownload() },
                                colors = ButtonDefaults.buttonColors(containerColor = TelePhotosTheme.AccentBlue),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Update Now", color = Color.White)
                            }
                        }
                    }

                    UpdateState.DOWNLOADING -> {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            color = TelePhotosTheme.AccentBlue,
                            trackColor = TelePhotosTheme.SurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .background(Color.Transparent, RoundedCornerShape(4.dp))
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Downloading... ${(downloadProgress * 100).toInt()}%",
                            fontSize = 14.sp,
                            color = TelePhotosTheme.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }

                    UpdateState.PERMISSION_REQUIRED -> {
                        Text(
                            text = "To install the update, TGPix requires permission to install apps from unknown sources. Please enable 'Allow from this source' in settings.",
                            fontSize = 14.sp,
                            color = TelePhotosTheme.TextSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Button(
                                onClick = { UpdateManager.launchUnknownAppSourcesSettings(context) },
                                colors = ButtonDefaults.buttonColors(containerColor = TelePhotosTheme.AccentBlue),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Open Settings", color = Color.White)
                            }
                        }
                    }

                    UpdateState.READY_TO_INSTALL -> {
                        Text(
                            text = "Update downloaded. Ready to install.",
                            fontSize = 14.sp,
                            color = TelePhotosTheme.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Button(
                                onClick = { downloadedFile?.let { UpdateManager.installApk(context, it) } },
                                colors = ButtonDefaults.buttonColors(containerColor = TelePhotosTheme.AccentBlue),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Install Now", color = Color.White)
                            }
                        }
                    }

                    UpdateState.ERROR -> {
                        Text(
                            text = errorMessage ?: "An error occurred during update.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (!updateInfo.forceUpdate) {
                                TextButton(
                                    onClick = onDismiss,
                                    colors = ButtonDefaults.textButtonColors(contentColor = TelePhotosTheme.TextSecondary)
                                ) {
                                    Text("Close")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Button(
                                onClick = { startDownload() },
                                colors = ButtonDefaults.buttonColors(containerColor = TelePhotosTheme.AccentBlue),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Retry", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

enum class UpdateState {
    IDLE,
    DOWNLOADING,
    PERMISSION_REQUIRED,
    READY_TO_INSTALL,
    ERROR
}
