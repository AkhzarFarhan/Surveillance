package com.imou.youtubestreamer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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

    val (versionName, versionCode) = remember { AppUpdateManager.getCurrentVersionInfo(context) }
    var autoCheckUpdates by remember { mutableStateOf(settings.autoCheckUpdates) }

    var cameraIp by remember { mutableStateOf(settings.cameraIp) }
    var rtspPort by remember { mutableStateOf(settings.rtspPort.toString()) }
    var httpPort by remember { mutableStateOf(settings.httpPort.toString()) }
    var username by remember { mutableStateOf(settings.username) }
    var safetyCode by remember { mutableStateOf(settings.safetyCode) }
    var passwordVisible by remember { mutableStateOf(false) }

    var catchupSpeed by remember { mutableStateOf(settings.catchupSpeed) }
    var silentAudio by remember { mutableStateOf(settings.silentAudioEnabled) }
    var autoBoot by remember { mutableStateOf(settings.autoStartOnBoot) }

    var manualRtmpFixed by remember { mutableStateOf(settings.manualRtmpFixed) }
    var manualRtmpRotatable by remember { mutableStateOf(settings.manualRtmpRotatable) }

    var hasSavedFeedback by remember { mutableStateOf(false) }

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
                    leadingIcon = { Icon(Icons.Default.Router, contentDescription = null, tint = LiveCyan) },
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

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; settings.username = it },
                    label = { Text("Username") },
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
                    label = { Text("Device Safety Code (Sticker Password)") },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
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
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant, contentColor = TextPrimary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (testState is CameraTestState.Testing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = LiveCyan, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Testing Connection...")
                    } else {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, tint = LiveCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Camera & MicroSD CGI Connection")
                    }
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
                        Icons.Default.SystemUpdate,
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

                if (manualCheckMsg != null) {
                    Text(
                        text = manualCheckMsg ?: "",
                        color = if (manualCheckMsg!!.contains("latest", ignoreCase = true)) SurveillanceGreen else WarningAmber,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Button(
            onClick = { hasSavedFeedback = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = SurveillanceGreen, contentColor = DarkBg),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (hasSavedFeedback) "Settings Saved Successfully!" else "Save & Apply Settings", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
