package com.imou.youtubestreamer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.imou.youtubestreamer.data.BackfillMode
import com.imou.youtubestreamer.data.SettingsValidator
import com.imou.youtubestreamer.update.AppUpdateManager
import com.imou.youtubestreamer.update.UpdateStatus
import com.imou.youtubestreamer.ui.CameraTestState
import com.imou.youtubestreamer.ui.MainViewModel
import com.imou.youtubestreamer.ui.theme.*

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val settings = viewModel.settings
    val testState by viewModel.cameraTestState.collectAsState()
    val updateStatus by viewModel.updateStatus.collectAsState()
    val manualCheckMsg by viewModel.manualCheckMessage.collectAsState()
    val cgiChecking by viewModel.cgiChecking.collectAsState()
    val cgiCheckMsg by viewModel.cgiCheckMessage.collectAsState()

    val (versionName, versionCode) = remember { AppUpdateManager.getCurrentVersionInfo(context) }
    var autoCheckUpdates by remember { mutableStateOf(settings.autoCheckUpdates) }

    var cameraIp by remember { mutableStateOf(settings.cameraIp) }
    var rtspPort by remember { mutableStateOf(settings.rtspPort.toString()) }
    var httpPort by remember { mutableStateOf(settings.httpPort.toString()) }
    var fixedChannel by remember { mutableStateOf(settings.fixedChannel.toString()) }
    var rotatableChannel by remember { mutableStateOf(settings.rotatableChannel.toString()) }
    var username by remember { mutableStateOf(settings.username) }
    var safetyCode by remember { mutableStateOf(settings.safetyCode) }
    var passwordVisible by remember { mutableStateOf(false) }

    var catchupSpeed by remember { mutableStateOf(settings.catchupSpeed) }
    var silentAudio by remember { mutableStateOf(settings.silentAudioEnabled) }
    var autoBoot by remember { mutableStateOf(settings.autoStartOnBoot) }
    var allowCellular by remember { mutableStateOf(settings.allowCellular) }
    var backfillMode by remember { mutableStateOf(settings.backfillMode) }
    var backfillRtmpFixed by remember { mutableStateOf(settings.backfillRtmpFixed) }
    var backfillRtmpRotatable by remember { mutableStateOf(settings.backfillRtmpRotatable) }
    var minGapBackfillSec by remember { mutableStateOf(settings.minGapToBackfillSec.toString()) }
    var sdRetentionHours by remember { mutableStateOf(settings.sdRetentionHours.toString()) }
    var rotationOverrideMin by remember { mutableStateOf(settings.rotationPeriodMinutesOverride.toString()) }

    var manualRtmpFixed by remember { mutableStateOf(settings.manualRtmpFixed) }
    var manualRtmpRotatable by remember { mutableStateOf(settings.manualRtmpRotatable) }

    var hasSavedFeedback by remember { mutableStateOf(false) }
    var validationErrors by remember { mutableStateOf<List<String>>(emptyList()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "IMOU LIFE CAMERA CONFIGURATION",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            letterSpacing = 1.sp
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = cameraIp,
                    onValueChange = { cameraIp = it; settings.cameraIp = it },
                    label = { Text("Camera Local IP Address") },
                    leadingIcon = { Icon(AppIcons.Router, contentDescription = null, tint = LiveCyan) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SurveillanceGreen,
                        unfocusedBorderColor = DarkBorder
                    ),
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rtspPort,
                        onValueChange = {
                            rtspPort = it
                            it.toIntOrNull()?.let { p -> settings.rtspPort = p }
                        },
                        label = { Text("RTSP Port") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SurveillanceGreen,
                            unfocusedBorderColor = DarkBorder
                        ),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = httpPort,
                        onValueChange = {
                            httpPort = it
                            it.toIntOrNull()?.let { p -> settings.httpPort = p }
                        },
                        label = { Text("HTTP Port") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SurveillanceGreen,
                            unfocusedBorderColor = DarkBorder
                        ),
                        singleLine = true
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = fixedChannel,
                        onValueChange = {
                            fixedChannel = it
                            it.toIntOrNull()?.takeIf { c -> c >= 1 }?.let { c -> settings.fixedChannel = c }
                        },
                        label = { Text("Fixed Channel (_F)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = rotatableChannel,
                        onValueChange = {
                            rotatableChannel = it
                            it.toIntOrNull()?.takeIf { c -> c >= 1 }?.let { c -> settings.rotatableChannel = c }
                        },
                        label = { Text("Rotatable Ch (_R)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; settings.username = it },
                    label = { Text("Username") },
                    supportingText = { Text("Imou / Dahua cameras require 'admin'", color = TextMuted, fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SurveillanceGreen,
                        unfocusedBorderColor = DarkBorder
                    ),
                    singleLine = true
                )

                OutlinedTextField(
                    value = safetyCode,
                    onValueChange = { safetyCode = it; settings.safetyCode = it },
                    label = { Text("Device Password / Safety Code") },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) AppIcons.Visibility else AppIcons.VisibilityOff,
                                contentDescription = null,
                                tint = TextMuted
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SurveillanceGreen,
                        unfocusedBorderColor = DarkBorder
                    ),
                    singleLine = true
                )

                // Test Connection Button & Result
                Button(
                    onClick = { viewModel.testCameraConnection() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = testState !is CameraTestState.Testing,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant, contentColor = TextPrimary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (testState is CameraTestState.Testing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = LiveCyan, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Testing Camera Connection...")
                    } else {
                        Icon(AppIcons.NetworkCheck, contentDescription = null, tint = LiveCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Camera Connection (RTSP & ONVIF)")
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.switchCameraToH264() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = testState !is CameraTestState.Testing,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(AppIcons.VideoSettings, contentDescription = null, tint = SurveillanceGreen, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Configure Camera Streams for YouTube (H.264)", color = SurveillanceGreen, fontSize = 12.sp)
                }

                when (val state = testState) {
                    is CameraTestState.Success -> {
                        Text(
                            text = state.message,
                            color = SurveillanceGreen,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    is CameraTestState.Error -> {
                        Text(
                            text = "Error: ${state.error}",
                            color = DangerRed,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    else -> {}
                }
            }
        }

        Text(
            text = "SD CARD BACKLOG CATCH-UP & AUDIO",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            letterSpacing = 1.sp
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "Catch-up Playback Speed: ${catchupSpeed}x",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    text = "Fast-forwards recorded footage directly from the camera SD card into YouTube Live upon Wi-Fi reconnect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2.0f, 3.0f, 4.0f).forEach { speed ->
                        FilterChip(
                            selected = catchupSpeed == speed,
                            onClick = {
                                catchupSpeed = speed
                                settings.catchupSpeed = speed
                            },
                            label = { Text("${speed}x") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SurveillanceGreen,
                                selectedLabelColor = DarkBg
                            )
                        )
                    }
                }

                HorizontalDivider(color = DarkBorder)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Inject Silent Audio (anullsrc)",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "Synthesizes silent AAC stereo audio to bypass Content ID copyright strikes from TV or street music.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = silentAudio,
                        onCheckedChange = {
                            silentAudio = it
                            settings.silentAudioEnabled = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = SurveillanceGreen)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Start Service on Boot",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "Automatically restores 24/7 background streaming when phone reboots.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = autoBoot,
                        onCheckedChange = {
                            autoBoot = it
                            settings.autoStartOnBoot = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = SurveillanceGreen)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Allow Cellular Data Fallback",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "Off (recommended): pauses YouTube upload when Wi-Fi loses WAN instead of burning mobile data.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = allowCellular,
                        onCheckedChange = {
                            allowCellular = it
                            settings.allowCellular = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = SurveillanceGreen)
                    )
                }

                HorizontalDivider(color = DarkBorder)

                Text(
                    text = "Backfill Strategy",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    text = "Separate Key keeps live streaming 24/7 while replaying missed SD footage in parallel. Same Key pauses live during backfill.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackfillMode.entries.forEach { mode ->
                        FilterChip(
                            selected = backfillMode == mode,
                            onClick = {
                                backfillMode = mode
                                settings.backfillMode = mode
                            },
                            label = { Text(mode.label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LiveCyan,
                                selectedLabelColor = DarkBg
                            )
                        )
                    }
                }

                if (backfillMode == BackfillMode.SEPARATE_KEY) {
                    OutlinedTextField(
                        value = backfillRtmpFixed,
                        onValueChange = {
                            backfillRtmpFixed = it
                            settings.backfillRtmpFixed = it
                        },
                        label = { Text("Fixed Backfill Stream Key (Optional)") },
                        placeholder = { Text("Separate key for parallel _F backfill") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = backfillRtmpRotatable,
                        onValueChange = {
                            backfillRtmpRotatable = it
                            settings.backfillRtmpRotatable = it
                        },
                        label = { Text("Rotatable Backfill Stream Key (Optional)") },
                        placeholder = { Text("Separate key for parallel _R backfill") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minGapBackfillSec,
                        onValueChange = {
                            minGapBackfillSec = it
                            it.toIntOrNull()?.let { s -> settings.minGapToBackfillSec = s }
                        },
                        label = { Text("Min Gap (sec)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = sdRetentionHours,
                        onValueChange = {
                            sdRetentionHours = it
                            it.toIntOrNull()?.let { h -> settings.sdRetentionHours = h }
                        },
                        label = { Text("SD Retention (hrs)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }

                OutlinedButton(
                    onClick = { viewModel.runCgiBacklogDiagnostic(hoursBack = 1) },
                    enabled = !cgiChecking,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (cgiChecking) "Checking HTTP CGI..." else "Check Camera HTTP CGI Backlog (Optional)",
                        fontSize = 12.sp,
                        color = LiveCyan
                    )
                }

                cgiCheckMsg?.let { msg ->
                    Text(text = msg, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }

                HorizontalDivider(color = DarkBorder)

                OutlinedTextField(
                    value = rotationOverrideMin,
                    onValueChange = {
                        rotationOverrideMin = it
                        it.toIntOrNull()?.let { m -> settings.rotationPeriodMinutesOverride = m }
                    },
                    label = { Text("Debug: Rotation Period Override (min, 0 = 8h)") },
                    supportingText = { Text("Leave 0 for normal 8-hour parts. Set e.g. 10 to test rotation quickly.", fontSize = 11.sp, color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
        }

        Text(
            text = "YOUTUBE STREAM SETTINGS",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            letterSpacing = 1.sp
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "YouTube Stream Key (No Google Login Required)",
                    style = MaterialTheme.typography.titleSmall,
                    color = LiveCyan,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Copy your Stream Key from YouTube Studio (studio.youtube.com/channel/live) and paste it below. The app will stream directly without needing Google Cloud or OAuth.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                OutlinedTextField(
                    value = manualRtmpFixed,
                    onValueChange = {
                        manualRtmpFixed = it
                        settings.manualRtmpFixed = it
                    },
                    label = { Text("Fixed Lens Stream Key (_F)") },
                    placeholder = { Text("e.g. xxxx-xxxx-xxxx-xxxx") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SurveillanceGreen,
                        unfocusedBorderColor = DarkBorder
                    ),
                    singleLine = true
                )

                OutlinedTextField(
                    value = manualRtmpRotatable,
                    onValueChange = {
                        manualRtmpRotatable = it
                        settings.manualRtmpRotatable = it
                    },
                    label = { Text("Rotatable Lens Stream Key (_R) - Optional") },
                    placeholder = { Text("Leave blank to stream Fixed lens only") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SurveillanceGreen,
                        unfocusedBorderColor = DarkBorder
                    ),
                    singleLine = true
                )
            }
        }

        Text(
            text = "APP UPDATES",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            letterSpacing = 1.sp
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Surveillance v$versionName",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Build $versionCode • GitHub Releases",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }

                    Icon(
                        AppIcons.SystemUpdate,
                        contentDescription = null,
                        tint = LiveCyan,
                        modifier = Modifier.size(28.dp)
                    )
                }

                HorizontalDivider(color = DarkBorder)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Check for Updates",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "Checks GitHub for new releases whenever the app starts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = autoCheckUpdates,
                        onCheckedChange = {
                            autoCheckUpdates = it
                            settings.autoCheckUpdates = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = SurveillanceGreen)
                    )
                }

                Button(
                    onClick = {
                        viewModel.clearManualCheckMessage()
                        viewModel.checkForUpdates(context, manual = true)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = updateStatus !is UpdateStatus.Checking && updateStatus !is UpdateStatus.Downloading,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant, contentColor = TextPrimary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (updateStatus is UpdateStatus.Checking) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = LiveCyan, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Checking GitHub for Updates...")
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = LiveCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Check for Updates Now")
                    }
                }

                manualCheckMsg?.let { msg ->
                    Text(
                        text = msg,
                        color = if (msg.contains("latest", ignoreCase = true)) SurveillanceGreen else WarningAmber,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (validationErrors.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1216)),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DangerRed)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    validationErrors.forEach { err ->
                        Text(text = "• $err", color = DangerRed, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Button(
            onClick = {
                val camVal = SettingsValidator.validateCameraSettings(
                    cameraIp = cameraIp,
                    rtspPort = rtspPort.toIntOrNull() ?: -1,
                    httpPort = httpPort.toIntOrNull() ?: -1,
                    fixedChannel = fixedChannel.toIntOrNull() ?: -1,
                    rotatableChannel = rotatableChannel.toIntOrNull() ?: -1
                )
                val keyVal = SettingsValidator.validateStreamKeys(
                    fixedLive = manualRtmpFixed,
                    rotatableLive = manualRtmpRotatable,
                    fixedBackfill = backfillRtmpFixed,
                    rotatableBackfill = backfillRtmpRotatable,
                    requireAtLeastOneLive = false
                )
                val errs = camVal.errors + keyVal.errors
                validationErrors = errs
                hasSavedFeedback = errs.isEmpty()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = SurveillanceGreen, contentColor = DarkBg),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(AppIcons.Save, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (hasSavedFeedback) "Settings Validated & Saved!" else "Save & Apply Settings",
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
