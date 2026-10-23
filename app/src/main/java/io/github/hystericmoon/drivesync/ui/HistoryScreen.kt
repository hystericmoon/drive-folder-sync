package io.github.hystericmoon.drivesync.ui

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hystericmoon.drivesync.data.FolderPair
import io.github.hystericmoon.drivesync.data.LogKind
import io.github.hystericmoon.drivesync.data.SyncLogEntry
import kotlinx.coroutines.flow.Flow
import java.util.Date

private enum class HistoryFilter(val label: String, val kinds: Set<LogKind>) {
    All("All", LogKind.entries.toSet()),
    Changes("Changes", setOf(LogKind.UPLOADED, LogKind.DOWNLOADED, LogKind.TRASHED_LOCAL, LogKind.TRASHED_REMOTE, LogKind.CONFLICT)),
    Problems("Problems", setOf(LogKind.CONFLICT, LogKind.ERROR, LogKind.SKIPPED)),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(pair: FolderPair, entries: Flow<List<SyncLogEntry>>, onDismiss: () -> Unit) {
    val all by entries.collectAsStateWithLifecycle(initialValue = null)
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.All) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("History · ${pair.localName}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            val shown = all?.filter { it.kind in filter.kinds }
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HistoryFilter.entries.forEach { f ->
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                        }
                    }
                }
                when {
                    shown == null -> Unit
                    shown.isEmpty() -> item {
                        Text(
                            if (filter == HistoryFilter.All) "Nothing has synced yet." else "Nothing here.",
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        var lastDay = ""
                        shown.forEach { entry ->
                            val day = dayLabel(entry.time)
                            if (day != lastDay) {
                                lastDay = day
                                item(key = "h-$day-${entry.id}") { DayHeader(day) }
                            }
                            item(key = entry.id) { HistoryRow(entry) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun HistoryRow(entry: SyncLogEntry) {
    val context = LocalContext.current
    val (icon, label, tint) = presentation(entry.kind)
    val name = entry.path.substringAfterLast('/').ifEmpty { "Sync" }
    val folder = entry.path.substringBeforeLast('/', "")
    ListItem(
        leadingContent = {
            Box(
                Modifier.size(40.dp).background(tint.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // The text label below carries the meaning; the icon is decorative for TalkBack.
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
        },
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(label, entry.detail?.takeIf { it != label }, folder.ifEmpty { null }).joinToString(" · "),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Text(DateFormat.getTimeFormat(context).format(Date(entry.time)), style = MaterialTheme.typography.labelSmall)
        },
    )
}

@Composable
private fun presentation(kind: LogKind): Triple<ImageVector, String, Color> {
    val c = MaterialTheme.colorScheme
    return when (kind) {
        LogKind.UPLOADED -> Triple(Icons.Outlined.CloudUpload, "Uploaded", c.primary)
        LogKind.DOWNLOADED -> Triple(Icons.Outlined.CloudDownload, "Downloaded", c.primary)
        LogKind.CONFLICT -> Triple(Icons.Outlined.ContentCopy, "Conflict", c.tertiary)
        LogKind.TRASHED_LOCAL -> Triple(Icons.Outlined.Delete, "Moved to phone trash", c.onSurfaceVariant)
        LogKind.TRASHED_REMOTE -> Triple(Icons.Outlined.Delete, "Moved to Drive trash", c.onSurfaceVariant)
        LogKind.SKIPPED -> Triple(Icons.Outlined.Block, "Skipped", c.onSurfaceVariant)
        LogKind.ERROR -> Triple(Icons.Outlined.ErrorOutline, "Error", c.error)
    }
}

private fun dayLabel(time: Long): String = when {
    DateUtils.isToday(time) -> "Today"
    DateUtils.isToday(time + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
    else -> DateFormat.format("EEEE, d MMM yyyy", time).toString()
}
