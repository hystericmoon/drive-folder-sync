package io.github.hystericmoon.drivesync.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    historyRequest: Long? = null,
    onHistoryShown: () -> Unit = {},
    vm: MainViewModel = viewModel(),
) {
    val auth by vm.auth.collectAsStateWithLifecycle()
    val pairs by vm.pairs.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val snack by vm.snackbar.collectAsStateWithLifecycle()
    val launchIntent by vm.launch.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    var historyFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(historyRequest) {
        if (historyRequest != null) { historyFor = historyRequest; onHistoryShown() }
    }
    LaunchedEffect(snack) {
        snack?.let { vm.snackbar.value = null; snackbarHost.showSnackbar(it) }
    }
    LaunchedEffect(launchIntent) {
        launchIntent?.let { vm.launch.value = null; runCatching { context.startActivity(it) } }
    }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onConsentResult(it.data)
    }
    LaunchedEffect(auth.consent) {
        auth.consent?.let { consentLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
    }

    // Add-pair flow: pick phone folder → pick Drive folder.
    var pickedTree by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            pickedTree = uri to displayName(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Drive Folder Sync")
                        auth.email?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (update.checking) "Checking…" else "Check for updates") },
                            enabled = !update.checking,
                            leadingIcon = { Icon(Icons.Outlined.SystemUpdate, contentDescription = null) },
                            onClick = { menu = false; vm.checkForUpdates() },
                        )
                        DropdownMenuItem(
                            text = { Text("Auto-update") },
                            modifier = Modifier.semantics {
                                role = Role.Checkbox
                                stateDescription = if (update.autoUpdate) "On" else "Off"
                            },
                            leadingIcon = {
                                Checkbox(checked = update.autoUpdate, onCheckedChange = null)
                            },
                            onClick = { vm.setAutoUpdate(!update.autoUpdate) },
                        )
                        if (auth.email != null) {
                            DropdownMenuItem(
                                text = { Text("Sign out") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                                onClick = { menu = false; vm.signOut() },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Version ${update.currentVersion}", style = MaterialTheme.typography.bodySmall) },
                            enabled = false,
                            onClick = {},
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (auth.driveReady) {
                ExtendedFloatingActionButton(
                    onClick = { treeLauncher.launch(null) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add folder pair") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !vm.webClientConfigured -> Centered(
                    title = "Not configured",
                    body = "This build has no Google Web client ID. Set the GOOGLE_WEB_CLIENT_ID repo variable " +
                        "and let the workflow build a new APK (SETUP.md, step 6).",
                )
                auth.email == null || !auth.driveReady -> Centered(
                    title = if (auth.email == null) "Sign in to start" else "Allow Drive access",
                    body = if (auth.email == null) {
                        "Sync phone folders with Google Drive folders, both ways."
                    } else {
                        "The app needs full Drive access to see files you add from other devices."
                    },
                    message = auth.message,
                    busy = auth.busy,
                    action = if (auth.email == null) "Sign in with Google" else "Grant access",
                    onAction = {
                        if (auth.email == null) vm.signIn(context as Activity) else vm.requestDriveAccess()
                    },
                )
                pairs.isEmpty() -> Centered(
                    title = "No folders yet",
                    body = "Add a pair to keep a phone folder and a Drive folder in sync. " +
                        "The first sync only merges: nothing is deleted on either side.",
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(pairs, key = { it.id }) { pair ->
                        PairCard(
                            pair = pair,
                            running = progress[pair.id],
                            onSyncNow = { vm.syncNow(pair) },
                            onConstraints = { wifi, charging -> vm.updateConstraints(pair, wifi, charging) },
                            onHistory = { historyFor = pair.id },
                            onExcludes = { vm.updateExcludes(pair, it) },
                            onRemove = { vm.removePair(pair) },
                        )
                    }
                }
            }
        }
    }

    historyFor?.let { id ->
        val pair = pairs.firstOrNull { it.id == id }
        if (pair == null) {
            if (pairs.isNotEmpty()) historyFor = null
        } else {
            HistoryScreen(pair, remember(id) { vm.history(id) }, onDismiss = { historyFor = null })
        }
    }

    pickedTree?.let { (uri, name) ->
        DriveFolderPicker(
            client = vm.driveClient(),
            localName = name,
            onDismiss = { pickedTree = null },
            onPick = { id, folderName ->
                vm.addPair(uri, name, id, folderName)
                pickedTree = null
            },
        )
    }
}

@Composable
private fun Centered(
    title: String,
    body: String,
    message: String? = null,
    busy: Boolean = false,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.CloudSync,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        if (action != null) {
            if (busy) CircularProgressIndicator()
            else Button(onClick = onAction, modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp)) { Text(action) }
        }
    }
}

/** "primary:Documents/Notes" → "Notes" */
private fun displayName(tree: Uri): String =
    DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/').ifEmpty { "Phone storage" }
