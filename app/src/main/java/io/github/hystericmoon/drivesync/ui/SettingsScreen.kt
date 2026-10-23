package io.github.hystericmoon.drivesync.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hystericmoon.drivesync.data.FolderPair
import io.github.hystericmoon.drivesync.ui.glass.GlassCard

@Composable
fun SettingsScreen(
    email: String?,
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    pairs: List<FolderPair>,
    update: UpdateUi,
    contentPadding: PaddingValues,
    onSignOut: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onConstraints: (FolderPair, wifiOnly: Boolean, chargingOnly: Boolean) -> Unit,
    onExcludes: (FolderPair, String) -> Unit,
    onRemove: (FolderPair) -> Unit,
    onAutoUpdate: (Boolean) -> Unit,
    onCheckUpdates: () -> Unit,
) {
    val context = LocalContext.current
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { PageHeader("Settings") }

        item { Section("Account") }
        item {
            GlassCard(contentPadding = 4.dp) {
                SettingRow(Icons.Outlined.AccountCircle, email ?: "Not signed in", supporting = if (email != null) "Google account used for Drive" else null)
                if (email != null) SettingRow(Icons.AutoMirrored.Outlined.Logout, "Sign out", onClick = onSignOut)
            }
        }

        item { Section("Appearance") }
        item {
            GlassCard(contentPadding = 4.dp) {
                SettingRow(Icons.Outlined.Palette, "Theme")
                SingleChoiceSegmentedButtonRow(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                ) {
                    ThemeMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = { onThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        ) { Text(mode.label) }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SwitchRow(
                        Icons.Outlined.Palette,
                        "Use wallpaper colours",
                        "Material You colours instead of the app's palette",
                        dynamicColor,
                        onDynamicColor,
                    )
                }
            }
        }

        item { Section("Folder pairs") }
        if (pairs.isEmpty()) {
            item {
                GlassCard { Text("No folder pairs yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        items(pairs, key = { it.id }) { pair ->
            PairSettings(pair, onConstraints, onExcludes, onRemove)
        }

        item { Section("Updates") }
        item {
            GlassCard(contentPadding = 4.dp) {
                SwitchRow(
                    Icons.Outlined.Update,
                    "Auto-update",
                    "Check GitHub Releases twice a day on Wi-Fi",
                    update.autoUpdate,
                    onAutoUpdate,
                )
                SettingRow(
                    Icons.Outlined.SystemUpdate,
                    if (update.checking) "Checking…" else "Check for updates",
                    supporting = "Installed: ${update.currentVersion}",
                    trailing = if (update.checking) ({ CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }) else null,
                    onClick = if (update.checking) null else onCheckUpdates,
                )
            }
        }

        item { Section("About") }
        item {
            GlassCard(contentPadding = 4.dp) {
                SettingRow(
                    Icons.Outlined.Policy, "Privacy policy",
                    trailing = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Opens in browser") },
                    onClick = { open("https://hystericmoon.github.io/drive-folder-sync/privacy.html") },
                )
                SettingRow(
                    Icons.Outlined.Code, "Source code",
                    trailing = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Opens in browser") },
                    onClick = { open("https://github.com/hystericmoon/drive-folder-sync") },
                )
                SettingRow(Icons.Outlined.Info, "Drive Folder Sync ${update.currentVersion}", supporting = "Two-way phone ⇄ Google Drive sync")
            }
        }
    }
}

@Composable
private fun PairSettings(
    pair: FolderPair,
    onConstraints: (FolderPair, Boolean, Boolean) -> Unit,
    onExcludes: (FolderPair, String) -> Unit,
    onRemove: (FolderPair) -> Unit,
) {
    var expanded by rememberSaveable(pair.id) { mutableStateOf(false) }
    var editExcludes by rememberSaveable(pair.id) { mutableStateOf(false) }
    var confirmRemove by rememberSaveable(pair.id) { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val excludeCount = pair.excludePatterns.lines().count { it.isNotBlank() && !it.trim().startsWith("#") }

    GlassCard(contentPadding = 4.dp) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(pair.localName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    "⇄ Drive: ${pair.driveFolderName}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.rotate(chevron))
            },
            modifier = Modifier
                .toggleable(value = expanded, role = Role.Button, onValueChange = { expanded = it })
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
        )
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                SwitchRow(Icons.Outlined.Wifi, "Wi-Fi only", "Background syncs wait for an unmetered network", pair.wifiOnly) {
                    onConstraints(pair, it, pair.chargingOnly)
                }
                SwitchRow(Icons.Outlined.BatteryChargingFull, "Only while charging", null, pair.chargingOnly) {
                    onConstraints(pair, pair.wifiOnly, it)
                }
                SettingRow(
                    Icons.Outlined.Block, "Exclude files",
                    supporting = if (excludeCount == 0) "Nothing excluded" else "$excludeCount rule${if (excludeCount > 1) "s" else ""}",
                    onClick = { editExcludes = true },
                )
                SettingRow(
                    Icons.Outlined.DeleteOutline, "Remove pair",
                    supporting = "Stops syncing. Files stay where they are.",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = { confirmRemove = true },
                )
            }
        }
    }

    if (editExcludes) {
        ExcludeDialog(
            initial = pair.excludePatterns,
            onDismiss = { editExcludes = false },
            onSave = { editExcludes = false; onExcludes(pair, it) },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove “${pair.localName}”?") },
            text = { Text("Syncing stops. No files are deleted on the phone or in Drive.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove(pair) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp).semantics { heading() },
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    supporting: String? = null,
    tint: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null, tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint) },
        headlineContent = { Text(title, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = trailing,
        modifier = if (onClick != null) Modifier.clickableRow(onClick) else Modifier,
    )
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    supporting: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        // Whole row is the target and reads as one switch.
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

private fun Modifier.clickableRow(onClick: () -> Unit) = clickable(role = Role.Button, onClick = onClick)
