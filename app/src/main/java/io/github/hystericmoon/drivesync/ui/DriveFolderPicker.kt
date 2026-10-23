package io.github.hystericmoon.drivesync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.hystericmoon.drivesync.drive.DriveClient
import io.github.hystericmoon.drivesync.ui.glass.AmbientBackground
import io.github.hystericmoon.drivesync.ui.glass.GlassShape
import io.github.hystericmoon.drivesync.ui.glass.glass
import androidx.compose.ui.graphics.Color
import io.github.hystericmoon.drivesync.drive.DriveFile
import kotlinx.coroutines.launch

private data class Crumb(val id: String, val name: String)

/** Full-screen browser over the user's Drive folders. Root "My Drive" itself can't be picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveFolderPicker(
    client: DriveClient,
    localName: String,
    onDismiss: () -> Unit,
    onPick: (id: String, name: String) -> Unit,
) {
    val stack = remember { mutableStateListOf(Crumb("root", "My Drive")) }
    var folders by remember { mutableStateOf<List<DriveFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val current = stack.last()

    LaunchedEffect(current.id, reload) {
        folders = null
        error = null
        runCatching { client.listChildren(current.id, foldersOnly = true) }
            .onSuccess { folders = it }
            .onFailure { error = it.message ?: "Couldn't load folders" }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) { AmbientBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    title = {
                        Column {
                            Text(current.name)
                            Text(
                                "Drive folder for “$localName”",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onDismiss() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { creating = true }) {
                            Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
                        }
                    },
                )
            },
            bottomBar = {
                Box(Modifier.navigationBarsPadding().padding(12.dp).glass(GlassShape, strong = true)) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Button(onClick = { onPick(current.id, current.name) }, enabled = stack.size > 1) {
                            Text(if (stack.size > 1) "Sync with “${current.name}”" else "Open a folder to choose it")
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val list = folders
                when {
                    error != null -> Text(error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    list.isEmpty() -> Text(
                        "No subfolders. Use this folder, or create one with the + folder button.",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(list, key = { it.id }) { f ->
                            ListItem(
                                headlineContent = { Text(f.name) },
                                leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier
                                    .glass(RoundedCornerShape(20.dp))
                                    .heightIn(min = 56.dp)
                                    .clickable { stack.add(Crumb(f.id, f.name)) },
                            )
                        }
                    }
                }
            }
        }
    } }

    if (creating) {
        var name by remember { mutableStateOf(localName) }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New folder in ${current.name}") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        creating = false
                        scope.launch {
                            runCatching { client.createFolder(name.trim(), current.id) }
                                .onSuccess { stack.add(Crumb(it.id, it.name)) }
                                .onFailure { error = it.message }
                        }
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}
