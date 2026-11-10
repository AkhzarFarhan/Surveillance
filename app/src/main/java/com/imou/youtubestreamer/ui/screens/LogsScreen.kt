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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.imou.youtubestreamer.data.LogLevel
import com.imou.youtubestreamer.ui.MainViewModel
import com.imou.youtubestreamer.ui.theme.*

@Composable
fun LogsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val allLogs by viewModel.logs.collectAsState()
    var selectedFilter by remember { mutableStateOf<LogLevel?>(null) }
    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    val filteredLogs = remember(allLogs, selectedFilter) {
        if (selectedFilter == null) allLogs else allLogs.filter { it.level == selectedFilter }
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
        // Controls row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "EXTREME DIAGNOSTICS (${filteredLogs.size}/${allLogs.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Persisted to disk • Function Entry/Exit/Exception Trace",
                    style = MaterialTheme.typography.labelSmall,
                    color = SurveillanceGreen,
                    fontSize = 10.sp
                )
            }

            Row {
                IconButton(onClick = { autoScroll = !autoScroll }) {
                    Icon(
                        if (autoScroll) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Toggle Auto-Scroll",
                        tint = if (autoScroll) SurveillanceGreen else WarningAmber
                    )
                }

                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = filteredLogs.joinToString("\n") { "[${it.timestamp}][${it.level}][${it.tag}] ${it.message}" }
                    clipboard.setPrimaryClip(ClipData.newPlainText("Surveillance Logs", text))
                    Toast.makeText(context, "Copied ${filteredLogs.size} logs to clipboard", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Logs", tint = LiveCyan)
                }

                IconButton(onClick = {
                    val text = allLogs.joinToString("\n") { "[${it.timestamp}][${it.level}][${it.tag}] ${it.message}" }
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

        // Filter chips (Horizontally Scrollable including DEBUG)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
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
                                color = if (log.level == LogLevel.ERROR) DangerRed else TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
