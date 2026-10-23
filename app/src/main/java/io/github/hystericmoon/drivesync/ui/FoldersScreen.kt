package io.github.hystericmoon.drivesync.ui

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.hystericmoon.drivesync.data.FolderPair
import io.github.hystericmoon.drivesync.sync.SyncProgress
import io.github.hystericmoon.drivesync.ui.glass.GlassCard
import io.github.hystericmoon.drivesync.ui.glass.glass

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    auth: AuthState,
    webClientConfigured: Boolean,
    pairs: List<FolderPair>,
    progress: Map<Long, SyncProgress>,
    contentPadding: PaddingValues,
    onSignIn: () -> Unit,
    onGrantAccess: () -> Unit,
    onSyncAll: () -> Unit,
    onSyncNow: (FolderPair) -> Unit,
    onHistory: (FolderPair) -> Unit,
) {
    val ready = webClientConfigured && auth.email != null && auth.driveReady
    PullToRefreshBox(
        isRefreshing = false, // the orb and cards already show progress
        onRefresh = { if (ready) onSyncAll() },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { PageHeader("Folders", auth.email) }
            when {
                !webClientConfigured -> item {
                    Onboarding(
                        title = "Not configured",
                        body = "This build has no Google Web client ID. Set the GOOGLE_WEB_CLIENT_ID repo variable " +
                            "and let the workflow build a new APK (SETUP.md, step 6).",
                    )
                }
                auth.email == null -> item {
                    Onboarding(
                        title = "Sign in to start",
                        body = "Keep phone folders and Google Drive folders in sync, both ways. " +
                            "Nothing is ever deleted permanently.",
                        message = auth.message, busy = auth.busy, action = "Sign in with Google", onAction = onSignIn,
                    )
                }
                !auth.driveReady -> item {
                    Onboarding(
                        title = "Allow Drive access",
                        body = "Full Drive access lets the app see files you add from other devices.",
                        message = auth.message, busy = auth.busy, action = "Grant access", onAction = onGrantAccess,
                    )
                }
                pairs.isEmpty() -> item {
                    Onboarding(
                        title = "No folders yet",
                        body = "Tap “Add folder pair” to link a phone folder with a Drive folder. " +
                            "The first sync only merges, so nothing is deleted on either side.",
                    )
                }
                else -> {
                    item { Summary(pairs, progress) }
                    items(pairs, key = { it.id }) { pair ->
                        PairCard(
                            pair = pair,
                            progress = progress[pair.id],
                            onSyncNow = { onSyncNow(pair) },
                            onHistory = { onHistory(pair) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PageHeader(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Overall state at a glance, with the big orb. */
@Composable
private fun Summary(pairs: List<FolderPair>, progress: Map<Long, SyncProgress>) {
    val syncing = progress.size
    val problems = pairs.count { it.hasProblem && it.id !in progress }
    val last = pairs.maxOfOrNull { it.lastSyncAt } ?: 0L
    val state = when {
        syncing > 0 -> OrbState.Syncing
        problems > 0 -> OrbState.Problem
        else -> OrbState.Idle
    }
    GlassCard(Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SyncOrb(state, size = 64.dp)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = when {
                        syncing > 0 -> "Syncing ${if (syncing == 1) "1 folder" else "$syncing folders"}…"
                        problems > 0 -> "$problems folder${if (problems > 1) "s need" else " needs"} attention"
                        else -> "Everything is in sync"
                    },
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "summary",
                ) { Text(it, style = MaterialTheme.typography.titleLarge) }
                Text(
                    buildString {
                        append("${pairs.size} folder pair${if (pairs.size > 1) "s" else ""}")
                        if (last > 0) append(" · last sync ${DateUtils.getRelativeTimeSpanString(last)}")
                        append("\nPull down to sync all")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Onboarding(
    title: String,
    body: String,
    message: String? = null,
    busy: Boolean = false,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    GlassCard(Modifier.fillMaxWidth().padding(top = 24.dp), contentPadding = 28.dp) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(72.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.CloudSync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            if (action != null) {
                if (busy) {
                    CircularProgressIndicator()
                } else {
                    Button(onClick = onAction, modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp).height(52.dp)) {
                        Text(action)
                    }
                }
            }
        }
    }
}
