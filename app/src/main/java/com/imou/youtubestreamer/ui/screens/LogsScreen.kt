package com.imou.youtubestreamer.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.LogLevel
import com.imou.youtubestreamer.ui.MainViewModel
import com.imou.youtubestreamer.ui.theme.*

@Composable
fun LogsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val allLogs by viewModel.logs.collectAsState()
    val lastCopiedId by AppLogger.lastCopiedLogId.collectAsState()
    var selectedFilter by remember { mutableStateOf<LogLevel?>(null) }
    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    val filteredLogs = remember(allLogs, selectedFilter) {
        if (selectedFilter == null) allLogs else allLogs.filter { it.level == selectedFilter }
    }

    val newLogs = remember(filteredLogs, lastCopiedId) {
        if (lastCopiedId <= 0L) filteredLogs else filteredLogs.filter { it.id > lastCopiedId }
    }

    LaunchedEffect(filteredLogs.size, autoScroll) {
        if (autoScroll && filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(16.dp)
    ) {
        // Header & Action Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "EXTREME DIAGNOSTICS (${filteredLogs.size}/${allLogs.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
                Text(
                    text = if (lastCopiedId > 0L) {
                        "${newLogs.size} new since last copy • ${filteredLogs.size - newLogs.size} already copied"
                    } else {
                        "Persisted to disk • Function Entry/Exit/Exception Trace"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = SurveillanceGreen,
                    fontSize = 10.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { autoScroll = !autoScroll }) {
                    Icon(
                        if (autoScroll) AppIcons.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Toggle Auto-Scroll",
                        tint = if (autoScroll) SurveillanceGreen else WarningAmber
                    )
                }

                IconButton(onClick = {
                    val logsToShare = if (newLogs.isNotEmpty() && lastCopiedId > 0L) newLogs else allLogs
                    val text = logsToShare.joinToString("\n") { "[${it.timestamp}][${it.level}][${it.tag}] ${it.message}" }
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Surveillance Diagnostic Logs")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Diagnostic Logs"))
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Share Logs", tint = LiveCyan)
                }

                IconButton(onClick = { viewModel.clearLogs() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear Logs", tint = DangerRed)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Incremental Copy Bar ("Copy New Only" vs "Copy All")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    if (newLogs.isEmpty()) {
                        Toast.makeText(context, "No new logs since last copy!", Toast.LENGTH_SHORT).show()
                    } else {
                        val skippedCount = filteredLogs.size - newLogs.size
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = newLogs.joinToString("\n") { "[${it.timestamp}][${it.level}][${it.tag}] ${it.message}" }
                        clipboard.setPrimaryClip(ClipData.newPlainText("Surveillance New Logs", text))
                        AppLogger.markLogsCopied(newLogs.last())
                        val msg = if (skippedCount > 0) {
                            "Copied ${newLogs.size} new logs (skipped $skippedCount already copied)"
                        } else {
                            "Copied ${newLogs.size} logs (marked as copied)"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1.25f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (newLogs.isNotEmpty()) SurveillanceGreen else DarkSurface,
                    contentColor = if (newLogs.isNotEmpty()) DarkBg else TextMuted
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Icon(AppIcons.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Copy New (${newLogs.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = {
                    if (filteredLogs.isEmpty()) {
                        Toast.makeText(context, "No logs to copy", Toast.LENGTH_SHORT).show()
                    } else {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = filteredLogs.joinToString("\n") { "[${it.timestamp}][${it.level}][${it.tag}] ${it.message}" }
                        clipboard.setPrimaryClip(ClipData.newPlainText("Surveillance All Logs", text))
                        AppLogger.markLogsCopied(filteredLogs.last())
                        Toast.makeText(context, "Copied all ${filteredLogs.size} logs to clipboard", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Icon(AppIcons.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp), tint = LiveCyan)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Copy All (${filteredLogs.size})",
                    fontSize = 12.sp,
                    color = LiveCyan
                )
            }

            if (lastCopiedId > 0L) {
                IconButton(
                    onClick = {
                        AppLogger.resetCopyBookmark()
                        Toast.makeText(context, "Copy bookmark reset", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Reset Copy Bookmark",
                        tint = WarningAmber,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Filter chips (Horizontally Scrollable including DEBUG)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = selectedFilter == null,
                onClick = { selectedFilter = null },
                label = { Text("ALL (${allLogs.size})") }
            )
            FilterChip(
                selected = selectedFilter == LogLevel.DEBUG,
                onClick = { selectedFilter = LogLevel.DEBUG },
                label = { Text("TRACE/DEBUG") }
            )
            FilterChip(
                selected = selectedFilter == LogLevel.INFO,
                onClick = { selectedFilter = LogLevel.INFO },
                label = { Text("INFO") }
            )
            FilterChip(
                selected = selectedFilter == LogLevel.WARN,
                onClick = { selectedFilter = LogLevel.WARN },
                label = { Text("WARN") }
            )
            FilterChip(
                selected = selectedFilter == LogLevel.ERROR,
                onClick = { selectedFilter = LogLevel.ERROR },
                label = { Text("ERROR") }
            )
            FilterChip(
                selected = selectedFilter == LogLevel.FFMPEG,
                onClick = { selectedFilter = LogLevel.FFMPEG },
                label = { Text("FFMPEG") }
            )
        }

        // Terminal Log Console
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (filteredLogs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No logs recorded for selected filter.", color = TextMuted)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                ) {
                    items(filteredLogs, key = { it.id }) { log ->
                        val isAlreadyCopied = lastCopiedId > 0L && log.id <= lastCopiedId
                        val levelColor = when (log.level) {
                            LogLevel.DEBUG -> LiveCyan
                            LogLevel.INFO -> SurveillanceGreen
                            LogLevel.WARN -> WarningAmber
                            LogLevel.ERROR -> DangerRed
                            LogLevel.FFMPEG -> TextSecondary
                        }

                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(
                                text = "${log.timestamp} ",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                            Text(
                                text = "[${log.level}] ",
                                style = MaterialTheme.typography.labelSmall,
                                color = levelColor,
                                fontSize = 10.sp
                            )
                            Text(
                                text = "${log.tag}: ",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                            Text(
                                text = log.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    log.level == LogLevel.ERROR -> DangerRed
                                    isAlreadyCopied -> TextMuted
                                    else -> TextPrimary
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }

                        if (lastCopiedId > 0L && log.id == lastCopiedId) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .background(LiveCyan.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                                    .padding(vertical = 3.dp, horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "─── ▲ COPIED UP TO HERE • ▼ ${newLogs.size} NEW LOGS BELOW ───",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LiveCyan,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
