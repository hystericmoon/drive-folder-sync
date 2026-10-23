package io.github.hystericmoon.drivesync.ui

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hystericmoon.drivesync.data.FolderPair
import io.github.hystericmoon.drivesync.data.LogKind
import io.github.hystericmoon.drivesync.data.SyncLogEntry
import io.github.hystericmoon.drivesync.ui.glass.GlassCard
import io.github.hystericmoon.drivesync.ui.glass.LocalGlass
import io.github.hystericmoon.drivesync.ui.glass.glass
import java.util.Date

enum class HistoryFilter(val label: String, val kinds: Set<LogKind>) {
    All("Everything", LogKind.entries.toSet()),
    Changes("Changes", setOf(LogKind.UPLOADED, LogKind.DOWNLOADED, LogKind.TRASHED_LOCAL, LogKind.TRASHED_REMOTE, LogKind.CONFLICT)),
    Problems("Problems", setOf(LogKind.CONFLICT, LogKind.ERROR, LogKind.SKIPPED)),
}

/** History across all pairs, filterable by pair and by kind. */
@Composable
fun HistoryScreen(
    entries: List<SyncLogEntry>,
    pairs: List<FolderPair>,
    pairFilter: Long?,
    onPairFilter: (Long?) -> Unit,
    kindFilter: HistoryFilter,
    onKindFilter: (HistoryFilter) -> Unit,
    contentPadding: PaddingValues,
) {
    val names = pairs.associate { it.id to it.localName }
    val shown = entries.filter { (pairFilter == null || it.pairId == pairFilter) && it.kind in kindFilter.kinds }

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { PageHeader("History") }
        if (pairs.size > 1) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Chip("All folders", pairFilter == null) { onPairFilter(null) } }
                    items(pairs, key = { it.id }) { p -> Chip(p.localName, pairFilter == p.id) { onPairFilter(p.id) } }
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(HistoryFilter.entries) { f -> Chip(f.label, kindFilter == f) { onKindFilter(f) } }
            }
        }
        if (shown.isEmpty()) {
            item {
                GlassCard(Modifier.padding(top = 8.dp)) {
                    Text(
                        if (entries.isEmpty()) "Nothing has synced yet. Changes will show up here."
                        else "Nothing matches these filters.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            var lastDay = ""
            shown.forEach { entry ->
                val day = dayLabel(entry.time)
                if (day != lastDay) {
                    lastDay = day
                    item(key = "h-${entry.id}") {
                        Text(
                            day,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp).semantics { heading() },
                        )
                    }
                }
                item(key = entry.id) {
                    HistoryRow(entry, pairName = if (pairFilter == null && pairs.size > 1) names[entry.pairId] else null)
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val glass = LocalGlass.current
    val scheme = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = glass.tint,
            labelColor = scheme.onSurface,
            selectedContainerColor = scheme.primary.copy(alpha = 0.2f),
            selectedLabelColor = scheme.primary,
            selectedLeadingIconColor = scheme.primary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = glass.edgeHighlight.copy(alpha = glass.edgeHighlight.alpha * 0.7f),
            selectedBorderColor = scheme.primary.copy(alpha = 0.45f),
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp,
        ),
    )
}

@Composable
private fun HistoryRow(entry: SyncLogEntry, pairName: String?) {
    val context = LocalContext.current
    val (icon, label, tint) = presentation(entry.kind)
    val name = entry.path.substringAfterLast('/').ifEmpty { "Sync" }
    val folder = entry.path.substringBeforeLast('/', "")
    ListItem(
        modifier = Modifier.glass(RoundedCornerShape(20.dp)),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box(
                Modifier.size(40.dp).background(tint.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // The label in the supporting text carries the meaning; the icon is decorative.
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
        },
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            // Trash entries' details just restate the label; keep the ones that add information.
            val detail = entry.detail.takeUnless {
                entry.kind == LogKind.TRASHED_LOCAL || entry.kind == LogKind.TRASHED_REMOTE
            }
            val where = listOfNotNull(pairName, folder.ifEmpty { null }).joinToString(" › ")
            Text(
                listOfNotNull(label, detail).joinToString(" · ") + if (where.isNotEmpty()) "\n$where" else "",
                maxLines = 4,
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
