package com.imou.youtubestreamer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.imou.youtubestreamer.ui.MainViewModel
import com.imou.youtubestreamer.ui.theme.*
import com.imou.youtubestreamer.update.AppUpdateManager
import com.imou.youtubestreamer.update.UpdateStatus
import java.util.Locale

@Composable
fun AppUpdateDialog(
    status: UpdateStatus,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var canInstall by remember(status) { mutableStateOf(AppUpdateManager.canInstallPackages(context)) }

    // Refresh unknown-sources permission immediately when returning from Android Settings
    DisposableEffect(lifecycleOwner, status) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canInstall = AppUpdateManager.canInstallPackages(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (status) {
        is UpdateStatus.UpdateAvailable -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissUpdateDialog() },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = LiveCyan)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Update Available: v${status.latestVersion}")
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Installed: v${status.currentVersion} • Latest: v${status.latestVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                        if (status.apkSize > 0) {
                            val sizeMb = status.apkSize / (1024f * 1024f)
                            Text(
                                text = String.format(Locale.US, "Download Size: %.1f MB", sizeMb),
                                style = MaterialTheme.typography.bodySmall,
                                color = LiveCyan,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Text(
                            text = "Release Notes:",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkBg)
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = status.releaseNotes,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                lineHeight = 18.sp
                            )
                        }

                        TextButton(
                            onClick = { AppUpdateManager.openDownloadInBrowser(context, status.downloadUrl) },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp), tint = LiveCyan)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Or Download in Browser", fontSize = 12.sp, color = LiveCyan)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.downloadAndInstallUpdate(context, status.downloadUrl, status.latestVersion) },
                        colors = ButtonDefaults.buttonColors(containerColor = SurveillanceGreen, contentColor = DarkBg),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Update Now", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissUpdateDialog() }) {
                        Text("Later", color = TextMuted)
                    }
                },
                containerColor = DarkSurface
            )
        }

        is UpdateStatus.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = LiveCyan)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Downloading Update...")
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val progress = status.progress
                        if (progress > 0f && status.totalBytes > 0L) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = SurveillanceGreen,
                                trackColor = DarkBorder
                            )
                            val mbDownloaded = status.bytesDownloaded / (1024f * 1024f)
                            val mbTotal = status.totalBytes / (1024f * 1024f)
                            val pct = (progress * 100).toInt().coerceIn(0, 100)
                            Text(
                                text = String.format(Locale.US, "%d%% • %.1f MB / %.1f MB", pct, mbDownloaded, mbTotal),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = LiveCyan,
                                trackColor = DarkBorder
                            )
                            val mbDownloaded = status.bytesDownloaded / (1024f * 1024f)
                            Text(
                                text = if (mbDownloaded > 0.1f) String.format(Locale.US, "Downloading %.1f MB...", mbDownloaded) else "Connecting to GitHub...",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                },
                confirmButton = {},
                containerColor = DarkSurface
            )
        }

        is UpdateStatus.ReadyToInstall -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissUpdateDialog() },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = SurveillanceGreen)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Update Ready to Install")
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "The update APK has been downloaded and verified.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        if (!canInstall) {
                            Text(
                                text = "Permission needed: Tap Grant Permission below, enable 'Allow from this source', then return here to tap Install Now.",
                                style = MaterialTheme.typography.bodySmall,
                                color = WarningAmber
                            )
                        }
                        if (status.downloadUrl.isNotBlank()) {
                            TextButton(
                                onClick = { AppUpdateManager.openDownloadInBrowser(context, status.downloadUrl) },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp), tint = LiveCyan)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Or Install via Browser", fontSize = 12.sp, color = LiveCyan)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (canInstall) {
                                viewModel.installDownloadedApk(context, status.apkFile, status.downloadUrl)
                            } else {
                                AppUpdateManager.openInstallPermissionSettings(context)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SurveillanceGreen, contentColor = DarkBg),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(if (canInstall) "Install Now" else "Grant Permission", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissUpdateDialog() }) {
                        Text("Cancel", color = TextMuted)
                    }
                },
                containerColor = DarkSurface
            )
        }

        is UpdateStatus.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissUpdateDialog() },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = DangerRed)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Update Failed")
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = status.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            AppUpdateManager.openDownloadInBrowser(context, status.fallbackUrl)
                            viewModel.dismissUpdateDialog()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SurveillanceGreen, contentColor = DarkBg),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Download in Browser", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissUpdateDialog() }) {
                        Text("Dismiss", color = TextMuted)
                    }
                },
                containerColor = DarkSurface
            )
        }

        else -> {}
    }
}
