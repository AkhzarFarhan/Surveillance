package com.imou.youtubestreamer.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.imou.youtubestreamer.data.LensStreamState
import com.imou.youtubestreamer.data.PipelineStatus
import com.imou.youtubestreamer.data.SystemStreamState
import com.imou.youtubestreamer.ui.MainViewModel
import com.imou.youtubestreamer.ui.theme.*

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onLaunchSignIn: () -> Unit
) {
    val context = LocalContext.current
    val systemState by viewModel.systemState.collectAsState()
    val isSignedIn by viewModel.isSignedIn.collectAsState()
    val userEmail by viewModel.userEmail.collectAsState()

    val isIgnoringBatteryOptimizations = remember {
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Battery Optimization Warning Banner
        if (!isIgnoringBatteryOptimizations) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2208)),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(WarningAmber)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.BatteryAlert, contentDescription = null, tint = WarningAmber)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "24/7 Run: Ignore Battery Optimization",
                            style = MaterialTheme.typography.titleSmall,
                            color = WarningAmber,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Allow background run without Android killing streaming.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    TextButton(onClick = {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    }) {
                        Text("Enable", color = WarningAmber, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Master Pipeline Status Card
        PipelineStatusCard(systemState = systemState)

        // 1-Tap Google & YouTube Auth Card
        YouTubeAuthCard(
            isSignedIn = isSignedIn,
            userEmail = userEmail,
            onSignInClick = onLaunchSignIn,
            onSignOutClick = { viewModel.signOutGoogle() }
        )

        // Dual Camera Cards: Fixed & Rotatable
        Text(
            text = "DUAL LENS CCTV STATUS",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            letterSpacing = 1.sp
        )

        LensStatusCard(lensState = systemState.fixedLens)
        LensStatusCard(lensState = systemState.rotatableLens)

        // Catch-up status banner (if catching up)
        AnimatedVisibility(visible = systemState.catchup.isCatchingUp) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2D38)),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(LiveCyan)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = LiveCyan,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "MicroSD Backlog Catch-Up Active",
                            style = MaterialTheme.typography.titleSmall,
                            color = LiveCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = systemState.catchup.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        // Master Action Buttons
        val isRunning = systemState.status != PipelineStatus.IDLE
        Button(
            onClick = { viewModel.toggleStreaming() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) DangerRed else SurveillanceGreen,
                contentColor = if (isRunning) Color.White else DarkBg
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isRunning) "STOP 24/7 SURVEILLANCE PIPELINE" else "START 24/7 SURVEILLANCE PIPELINE",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }

        OutlinedButton(
            onClick = { viewModel.triggerManualSdCatchup(hoursBack = 1) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.CloudSync, contentDescription = null, tint = LiveCyan)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sync Past 1 Hour SD Footage Now", color = LiveCyan)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun PipelineStatusCard(systemState: SystemStreamState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = when (systemState.status) {
                        PipelineStatus.STREAMING -> SurveillanceGreen
                        PipelineStatus.CATCHING_UP -> LiveCyan
                        PipelineStatus.OUTAGE_RECORDING, PipelineStatus.ROTATING -> WarningAmber
                        PipelineStatus.ERROR -> DangerRed
                        else -> TextMuted
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = systemState.status.label.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Wi-Fi Status Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (systemState.isWifiConnected) Color(0xFF1B382B) else Color(0xFF381B1B))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        if (systemState.isWifiConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = if (systemState.isWifiConnected) SurveillanceGreen else DangerRed,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (systemState.isWifiConnected) "Home Wi-Fi" else "Offline",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (systemState.isWifiConnected) SurveillanceGreen else DangerRed
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = DarkBorder)
            Spacer(modifier = Modifier.height(16.dp))

            // 8-Hour Session Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "8-HOUR ARCHIVE BLOCK",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Part ${systemState.activePartIndex} of 3",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    val windowLabel = when (systemState.activePartIndex) {
                        1 -> "00:00 - 08:00 (Session 1)"
                        2 -> "08:00 - 16:00 (Session 2)"
                        else -> "16:00 - 24:00 (Session 3)"
                    }
                    Text(
                        text = windowLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "NEXT ROTATION IN",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val remainingMs = systemState.nextRotationRemainingMs
                    val hours = (remainingMs / (1000 * 3600))
                    val mins = (remainingMs / (1000 * 60)) % 60
                    val secs = (remainingMs / 1000) % 60
                    val timeString = if (remainingMs > 0) "%02dh %02dm %02ds".format(hours, mins, secs) else "00h 00m 00s"

                    Text(
                        text = timeString,
                        style = MaterialTheme.typography.titleLarge,
                        color = LiveCyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Auto-archive break",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun LensStatusCard(lensState: LensStreamState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Videocam,
                        contentDescription = null,
                        tint = if (lensState.isStreaming) SurveillanceGreen else TextMuted
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${lensState.lensName} (${lensState.lensCode})",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Channel Badge
                Text(
                    text = "CH ${lensState.channel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LiveCyan,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(DarkSurfaceVariant)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Active YouTube Broadcast Title
            if (lensState.activeTitle.isNotBlank()) {
                Text(
                    text = "Broadcast: ${lensState.activeTitle}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
            }

            // Metrics row: FPS, Bitrate, Uptime
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricColumn("FPS", "%.1f".format(lensState.fps))
                MetricColumn("BITRATE", "%.0f kbps".format(lensState.bitrateKbps))
                val hours = lensState.uptimeSeconds / 3600
                val mins = (lensState.uptimeSeconds % 3600) / 60
                MetricColumn("UPTIME", "%02d:%02d".format(hours, mins))
            }

            if (lensState.lastError != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Warning: ${lensState.lastError}",
                    style = MaterialTheme.typography.labelSmall,
                    color = DangerRed
                )
            }
        }
    }
}

@Composable
fun MetricColumn(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun YouTubeAuthCard(
    isSignedIn: Boolean,
    userEmail: String,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = if (isSignedIn) SurveillanceGreen else TextMuted,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (isSignedIn) "Connected to YouTube" else "YouTube Not Connected",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isSignedIn) userEmail else "Tap to connect Google Account",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            if (isSignedIn) {
                TextButton(onClick = onSignOutClick) {
                    Text("Disconnect", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                }
            } else {
                Button(
                    onClick = onSignInClick,
                    colors = ButtonDefaults.buttonColors(containerColor = SurveillanceGreen, contentColor = DarkBg),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("1-Tap Sign-In", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}
