package io.github.hystericmoon.drivesync.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hystericmoon.drivesync.data.FolderPair

@Composable
fun PairCard(
    pair: FolderPair,
    running: String?,
    onSyncNow: () -> Unit,
    onConstraints: (wifiOnly: Boolean, chargingOnly: Boolean) -> Unit,
    onHistory: () -> Unit,
    onExcludes: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    var editExcludes by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Endpoint(Icons.Outlined.PhoneAndroid, "Phone folder", pair.localName) }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options for ${pair.localName}")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Exclude files…") },
                            onClick = { menu = false; editExcludes = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove pair") },
                            onClick = { menu = false; confirmRemove = true },
                        )
                    }
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.CompareArrows,
                contentDescription = "syncs with",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp).size(20.dp),
            )
            Endpoint(Icons.Outlined.Cloud, "Drive folder", pair.driveFolderName)

            Status(pair, running)

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ToggleRow("Wi-Fi only", pair.wifiOnly) { onConstraints(it, pair.chargingOnly) }
            ToggleRow("Only while charging", pair.chargingOnly) { onConstraints(pair.wifiOnly, it) }
            val excludeCount = pair.excludePatterns.lines().count { it.isNotBlank() && !it.trim().startsWith("#") }
            if (excludeCount > 0) {
                Text(
                    "$excludeCount exclude rule${if (excludeCount > 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onHistory) { Text("History") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSyncNow, enabled = running == null) { Text("Sync now") }
            }
        }
    }

    if (editExcludes) {
        ExcludeDialog(
            initial = pair.excludePatterns,
            onDismiss = { editExcludes = false },
            onSave = { editExcludes = false; onExcludes(it) },
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove this pair?") },
            text = { Text("Syncing stops. No files are deleted on the phone or in Drive.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Endpoint(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Status(pair: FolderPair, running: String?) {
    when {
        running != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(running, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        pair.lastSyncAt == 0L -> Text(
            "Waiting for first sync (merge only, nothing is deleted)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> {
            val ago = DateUtils.getRelativeTimeSpanString(pair.lastSyncAt).toString()
            Text(
                "${pair.lastSummary ?: "Synced"} · $ago",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    pair.lastError?.let {
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
            Text(
                it,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

/** Whole row is the touch target (≥48dp) and reads as one switch to TalkBack. */
@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}
