package io.github.hystericmoon.drivesync.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hystericmoon.drivesync.ui.glass.AmbientBackground
import io.github.hystericmoon.drivesync.ui.glass.BottomScrim
import io.github.hystericmoon.drivesync.ui.glass.GlassNavBar
import io.github.hystericmoon.drivesync.ui.glass.LocalReducedMotion
import io.github.hystericmoon.drivesync.ui.glass.NavItem

private enum class Tab { Folders, History, Settings }

private val NavItems = listOf(
    NavItem("Folders", Icons.Outlined.Folder, Icons.Filled.Folder),
    NavItem("History", Icons.Outlined.History, Icons.Filled.History),
    NavItem("Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun AppScreen(
    historyRequest: Long? = null,
    onHistoryShown: () -> Unit = {},
    vm: MainViewModel = viewModel(),
) {
    val auth by vm.auth.collectAsStateWithLifecycle()
    val pairs by vm.pairs.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val history by vm.allHistory.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val themeMode by vm.settings.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by vm.settings.dynamicColor.collectAsStateWithLifecycle()
    val snack by vm.snackbar.collectAsStateWithLifecycle()
    val launchIntent by vm.launch.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    val snackbarHost = remember { SnackbarHostState() }

    var tab by rememberSaveable { mutableStateOf(Tab.Folders) }
    var historyPair by rememberSaveable { mutableStateOf<Long?>(null) }
    var historyKind by rememberSaveable { mutableStateOf(HistoryFilter.All) }

    BackHandler(enabled = tab != Tab.Folders) { tab = Tab.Folders }

    LaunchedEffect(historyRequest) {
        if (historyRequest != null) {
            historyPair = historyRequest
            historyKind = HistoryFilter.All
            tab = Tab.History
            onHistoryShown()
        }
    }
    LaunchedEffect(snack) { snack?.let { vm.snackbar.value = null; snackbarHost.showSnackbar(it) } }
    LaunchedEffect(launchIntent) { launchIntent?.let { vm.launch.value = null; runCatching { context.startActivity(it) } } }

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

    // Leave room for the status bar above and the floating nav bar (+ FAB) below.
    val sys = WindowInsets.statusBars.asPaddingValues()
    val nav = WindowInsets.navigationBars.asPaddingValues()
    val contentPadding = PaddingValues(
        start = 16.dp,
        end = 16.dp,
        top = sys.calculateTopPadding() + 16.dp,
        bottom = nav.calculateBottomPadding() + if (tab == Tab.Folders) 168.dp else 104.dp,
    )

    AmbientBackground {
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                if (reduced) {
                    fadeIn(tween(1)) togetherWith fadeOut(tween(1))
                } else {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (fadeIn(tween(260)) + slideInHorizontally(tween(320)) { it / 8 * dir }) togetherWith
                        (fadeOut(tween(180)) + slideOutHorizontally(tween(320)) { -it / 8 * dir })
                }
            },
            label = "tabs",
            modifier = Modifier.fillMaxSize(),
        ) { current ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 640.dp)) {
                    when (current) {
                        Tab.Folders -> FoldersScreen(
                            auth = auth,
                            webClientConfigured = vm.webClientConfigured,
                            pairs = pairs,
                            progress = progress,
                            contentPadding = contentPadding,
                            onSignIn = { vm.signIn(context as Activity) },
                            onGrantAccess = { vm.requestDriveAccess() },
                            onSyncAll = { vm.syncAll() },
                            onSyncNow = { vm.syncNow(it) },
                            onHistory = { historyPair = it.id; tab = Tab.History },
                        )
                        Tab.History -> HistoryScreen(
                            entries = history,
                            pairs = pairs,
                            pairFilter = historyPair?.takeIf { id -> pairs.any { it.id == id } },
                            onPairFilter = { historyPair = it },
                            kindFilter = historyKind,
                            onKindFilter = { historyKind = it },
                            contentPadding = contentPadding,
                        )
                        Tab.Settings -> SettingsScreen(
                            email = auth.email,
                            themeMode = themeMode,
                            dynamicColor = dynamicColor,
                            pairs = pairs,
                            update = update,
                            contentPadding = contentPadding,
                            onSignOut = { vm.signOut() },
                            onThemeMode = vm.settings::setThemeMode,
                            onDynamicColor = vm.settings::setDynamicColor,
                            onConstraints = { p, wifi, charging -> vm.updateConstraints(p, wifi, charging) },
                            onExcludes = { p, text -> vm.updateExcludes(p, text) },
                            onRemove = { vm.removePair(it) },
                            onAutoUpdate = vm::setAutoUpdate,
                            onCheckUpdates = { vm.checkForUpdates() },
                        )
                    }
                }
            }
        }

        BottomScrim(
            height = nav.calculateBottomPadding() + if (tab == Tab.Folders && auth.driveReady) 170.dp else 110.dp,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // Bottom chrome: snackbar, FAB, floating glass nav bar.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 640.dp)
                .padding(start = 16.dp, end = 16.dp, bottom = nav.calculateBottomPadding() + 12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            SnackbarHost(snackbarHost)
            AnimatedVisibility(
                visible = tab == Tab.Folders && auth.driveReady,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { treeLauncher.launch(null) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add folder pair") },
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            GlassNavBar(
                items = NavItems,
                selected = tab.ordinal,
                onSelect = { tab = Tab.entries[it] },
            )
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

/** "primary:Documents/Notes" → "Notes" */
private fun displayName(tree: Uri): String =
    DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/').ifEmpty { "Phone storage" }
