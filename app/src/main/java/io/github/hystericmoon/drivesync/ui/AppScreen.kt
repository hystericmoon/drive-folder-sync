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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(vm: MainViewModel = viewModel()) {
    val auth by vm.auth.collectAsStateWithLifecycle()
    val pairs by vm.pairs.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
                    if (auth.email != null) {
                        IconButton(onClick = { vm.signOut() }) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign out")
                        }
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
                            onRemove = { vm.removePair(pair) },
                        )
                    }
                }
            }
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
