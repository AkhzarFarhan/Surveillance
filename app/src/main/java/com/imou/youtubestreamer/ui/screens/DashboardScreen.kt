package com.imou.youtubestreamer.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.imou.youtubestreamer.data.LensMode
import com.imou.youtubestreamer.data.LensStreamState
import com.imou.youtubestreamer.data.PipelineStatus
import com.imou.youtubestreamer.data.SettingsValidator
import com.imou.youtubestreamer.data.SystemStreamState
import com.imou.youtubestreamer.ui.MainViewModel
import com.imou.youtubestreamer.ui.theme.*

@Composable
fun DashboardScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val systemState by viewModel.systemState.collectAsState()
    val settings = viewModel.settings

    fun checkBatteryExempt(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }

    var isIgnoringBatteryOptimizations by remember { mutableStateOf(checkBatteryExempt()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBatteryOptimizations = checkBatteryExempt()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showStreamKeyDialog by remember { mutableStateOf(false) }
    var fixedKey by remember { mutableStateOf(settings.manualRtmpFixed) }
    var rotatableKey by remember { mutableStateOf(settings.manualRtmpRotatable) }

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
                    Icon(AppIcons.BatteryAlert, contentDescription = null, tint = WarningAmber)
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

        // Crash Loop Guard Banner
        if (systemState.crashLoopDetected) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1216)),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DangerRed)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(AppIcons.ErrorOutline, contentDescription = null, tint = DangerRed)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Crash Loop Detected (${systemState.crashCountRecent} crashes / 10m)",
                            style = MaterialTheme.typography.titleSmall,
                            color = DangerRed,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Auto-restart backed off to 5 minutes to protect battery/stability.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    TextButton(onClick = { viewModel.clearCrashLoopWarning() }) {
                        Text("Reset", color = LiveCyan, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Supervisor Configuration Warning Banner (e.g. duplicate stream keys)
        systemState.lastSupervisorWarning?.let { warning ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF36210C)),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DangerRed)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(AppIcons.ErrorOutline, contentDescription = null, tint = DangerRed)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Master Pipeline Status Card
        PipelineStatusCard(systemState = systemState)

        // System Health & Self-Heal Summary Card
        SystemHealthCard(
            systemState = systemState,
            allowCellular = settings.allowCellular,
            isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
            onRequestBatteryExemption = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            }
        )

        // YouTube Stream Key Ingestion Card (Zero Google Login Required)
        YouTubeStreamKeyCard(
            hasStreamKey = fixedKey.isNotBlank() || rotatableKey.isNotBlank(),
            fixedKey = fixedKey,
            rotatableKey = rotatableKey,
            onConfigureClick = { showStreamKeyDialog = true },
            onQuickSave = { pastedKey ->
                viewModel.saveStreamKeys(pastedKey, rotatableKey)
                fixedKey = pastedKey
                Toast.makeText(context, "Stream Key saved successfully!", Toast.LENGTH_SHORT).show()
            },
            onOpenYouTubeStudio = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://studio.youtube.com/channel/live")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        )

        // Stream Key Configuration Dialog
        if (showStreamKeyDialog) {
            var tempFixed by remember { mutableStateOf(fixedKey) }
            var tempRotatable by remember { mutableStateOf(rotatableKey) }
            val keyValidation = remember(tempFixed, tempRotatable) {
                SettingsValidator.validateStreamKeys(
                    fixedLive = tempFixed,
                    rotatableLive = tempRotatable,
                    fixedBackfill = settings.backfillRtmpFixed,
                    rotatableBackfill = settings.backfillRtmpRotatable
                )
            }

            AlertDialog(
                onDismissRequest = { showStreamKeyDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.VpnKey, contentDescription = null, tint = LiveCyan)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("YouTube Stream Keys")
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Copy two distinct Stream Keys from YouTube Studio -> Manage -> Schedule Stream. No Google sign-in is needed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )

                        OutlinedTextField(
                            value = tempFixed,
                            onValueChange = { tempFixed = it },
                            label = { Text("Fixed Lens Stream Key (_F)") },
                            placeholder = { Text("xxxx-xxxx-xxxx-xxxx") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = {
                                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clip.primaryClip?.getItemAt(0)?.text?.let { text ->
                                        tempFixed = text.toString().trim()
                                    }
                                }) {
                                    Icon(AppIcons.ContentPaste, contentDescription = "Paste", tint = LiveCyan)
                                }
                            }
                        )

                        OutlinedTextField(
                            value = tempRotatable,
                            onValueChange = { tempRotatable = it },
                            label = { Text("Rotatable Lens Key (_R) - Optional") },
                            placeholder = { Text("Leave blank for Fixed lens only") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = {
                                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clip.primaryClip?.getItemAt(0)?.text?.let { text ->
                                        tempRotatable = text.toString().trim()
                                    }
                                }) {
                                    Icon(AppIcons.ContentPaste, contentDescription = "Paste", tint = LiveCyan)
                                }
                            }
                        )

                        if (!keyValidation.isValid) {
                            Text(
                                text = keyValidation.errors.first(),
                                color = DangerRed,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        TextButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://studio.youtube.com/channel/live")).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(AppIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp), tint = LiveCyan)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open YouTube Studio", fontSize = 12.sp, color = LiveCyan)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = keyValidation.isValid,
                        onClick = {
                            viewModel.saveStreamKeys(tempFixed, tempRotatable)
                            fixedKey = tempFixed.trim()
                            rotatableKey = tempRotatable.trim()
                            showStreamKeyDialog = false
                            Toast.makeText(context, "Stream Key(s) saved! Ready to stream.", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SurveillanceGreen, contentColor = DarkBg)
                    ) {
                        Text("Save & Apply", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showStreamKeyDialog = false }) {
                        Text("Cancel", color = TextMuted)
                    }
                },
                containerColor = DarkSurface
            )
        }

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
        val isRunning = systemState.status != PipelineStatus.IDLE && systemState.status != PipelineStatus.ERROR
        Button(
            onClick = {
                com.imou.youtubestreamer.data.AppLogger.enter(
                    "DashboardScreen",
                    "onStartStopClick",
                    "isRunning=$isRunning, status=${systemState.status}, fixedKeySet=${fixedKey.isNotBlank()}, rotKeySet=${rotatableKey.isNotBlank()}"
                )
                if (!isRunning && fixedKey.isBlank() && rotatableKey.isBlank()) {
                    showStreamKeyDialog = true
                    Toast.makeText(context, "Please enter your YouTube Stream Key first.", Toast.LENGTH_LONG).show()
                } else {
                    viewModel.toggleStreaming()
                }
            },
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
                if (isRunning) AppIcons.Stop else Icons.Default.PlayArrow,
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
            Icon(AppIcons.CloudSync, contentDescription = null, tint = LiveCyan)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sync Past 1 Hour SD Footage Now", color = LiveCyan)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun YouTubeStreamKeyCard(
    hasStreamKey: Boolean,
    fixedKey: String,
    rotatableKey: String,
    onConfigureClick: () -> Unit,
    onQuickSave: (String) -> Unit,
    onOpenYouTubeStudio: () -> Unit
) {
    val context = LocalContext.current
    var quickInput by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(if (hasStreamKey) SurveillanceGreen else WarningAmber)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        if (hasStreamKey) Icons.Default.CheckCircle else AppIcons.VpnKey,
                        contentDescription = null,
                        tint = if (hasStreamKey) SurveillanceGreen else WarningAmber,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (hasStreamKey) "YouTube Stream Key Active" else "YouTube Stream Key Required",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (hasStreamKey) {
                                val masked = if (fixedKey.length > 4) "•••• •••• ${fixedKey.takeLast(4)}" else "Configured"
                                "Fixed Lens (_F): $masked" + if (rotatableKey.isNotBlank()) " + Rotatable (_R)" else ""
                            } else {
                                "No Google login needed. Direct RTMPS ingest."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasStreamKey) SurveillanceGreen else TextSecondary
                        )
                    }
                }

                OutlinedButton(
                    onClick = onConfigureClick,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(if (hasStreamKey) "Edit Key" else "Set Key", fontSize = 12.sp, color = LiveCyan)
                }
            }

            if (!hasStreamKey) {
                HorizontalDivider(color = DarkBorder)

                Text(
                    text = "Quick Paste Stream Key:",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = quickInput,
                        onValueChange = { quickInput = it },
                        placeholder = { Text("Paste xxxx-xxxx-xxxx-xxxx here") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = {
                                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clip.primaryClip?.getItemAt(0)?.text?.let { text ->
                                    quickInput = text.toString().trim()
                                }
                            }) {
                                Icon(AppIcons.ContentPaste, contentDescription = "Paste", tint = LiveCyan)
                            }
                        }
                    )

                    Button(
                        onClick = {
                            if (quickInput.isNotBlank()) {
                                onQuickSave(quickInput.trim())
                                quickInput = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SurveillanceGreen, contentColor = DarkBg),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onOpenYouTubeStudio) {
                        Icon(AppIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp), tint = LiveCyan)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Get Key from YouTube Studio", fontSize = 12.sp, color = LiveCyan)
                    }
                }
            }
        }
    }
}

@Composable
fun SystemHealthCard(
    systemState: SystemStreamState,
    allowCellular: Boolean,
    isIgnoringBatteryOptimizations: Boolean,
    onRequestBatteryExemption: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SYSTEM HEALTH & SELF-HEAL",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
                val hbText = if (systemState.lastHeartbeatWallMs <= 0L) {
                    "Heartbeat: —"
                } else {
                    val ageSec = ((System.currentTimeMillis() - systemState.lastHeartbeatWallMs) / 1000L).coerceAtLeast(0L)
                    if (ageSec < 12L) "Heartbeat: live (${ageSec}s ago)" else "Heartbeat: ${ageSec}s ago"
                }
                Text(
                    text = hbText,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HealthPill(
                    label = if (systemState.lanUp) "LAN: Camera OK" else "LAN: Offline",
                    ok = systemState.lanUp,
                    modifier = Modifier.weight(1f)
                )
                val wanLabel = when {
                    systemState.isCellularOnly && !allowCellular -> "WAN: Cellular Blocked"
                    systemState.wanUp -> "WAN: YouTube OK"
                    else -> "WAN: Offline"
                }
                HealthPill(
                    label = wanLabel,
                    ok = systemState.wanUp,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pending SD Backfill: _F ${systemState.pendingFixedGapMinutes}m • _R ${systemState.pendingRotatableGapMinutes}m",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (systemState.pendingFixedGapMinutes + systemState.pendingRotatableGapMinutes > 0L) LiveCyan else TextSecondary
                )

                if (isIgnoringBatteryOptimizations) {
                    Text(
                        text = "Battery: Exempt",
                        style = MaterialTheme.typography.labelSmall,
                        color = SurveillanceGreen,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    TextButton(onClick = onRequestBatteryExemption, contentPadding = PaddingValues(0.dp)) {
                        Text("Fix Battery Limit", fontSize = 11.sp, color = WarningAmber, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun HealthPill(label: String, ok: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (ok) Color(0xFF163327) else Color(0xFF381B1B))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (ok) SurveillanceGreen else DangerRed)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (ok) SurveillanceGreen else DangerRed,
            fontWeight = FontWeight.SemiBold
        )
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
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    val statusColor = when (systemState.status) {
                        PipelineStatus.STREAMING -> SurveillanceGreen
                        PipelineStatus.CATCHING_UP -> LiveCyan
                        PipelineStatus.OUTAGE_RECORDING, PipelineStatus.ROTATING -> WarningAmber
                        PipelineStatus.ERROR, PipelineStatus.CAMERA_OFFLINE -> DangerRed
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

                // Network Link Status Pill
                val online = systemState.lanUp && systemState.wanUp
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (online) Color(0xFF1B382B) else Color(0xFF381B1B))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        if (online) AppIcons.Wifi else AppIcons.WifiOff,
                        contentDescription = null,
                        tint = if (online) SurveillanceGreen else DangerRed,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = when {
                            online -> "LAN + WAN"
                            systemState.lanUp -> "WAN Lost"
                            systemState.isWifiConnected -> "LAN Lost"
                            else -> "Offline"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (online) SurveillanceGreen else DangerRed
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
                        AppIcons.Videocam,
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

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val modeColor = when (lensState.mode) {
                        LensMode.LIVE -> SurveillanceGreen
                        LensMode.LIVE_AND_BACKFILL, LensMode.BACKFILL -> LiveCyan
                        LensMode.WAITING -> WarningAmber
                        LensMode.OFF -> TextMuted
                    }
                    Text(
                        text = lensState.mode.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = modeColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(DarkSurfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
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

            if (lensState.isBackfilling || lensState.pendingGapMinutes > 0L) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "SD Backfill: ${lensState.pendingGapMinutes}m queued" +
                        if (lensState.backfillStatus.isNotBlank()) " • ${lensState.backfillStatus}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = LiveCyan
                )
            }

            if (lensState.lastError != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Status: ${lensState.lastError}",
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
