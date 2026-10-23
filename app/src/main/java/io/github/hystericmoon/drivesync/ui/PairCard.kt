package io.github.hystericmoon.drivesync.ui

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hystericmoon.drivesync.data.FolderPair
import io.github.hystericmoon.drivesync.sync.SyncProgress
import io.github.hystericmoon.drivesync.ui.glass.GlassCard
import io.github.hystericmoon.drivesync.ui.glass.LocalReducedMotion
import io.github.hystericmoon.drivesync.ui.glass.glass

/** Folder pair: phone ⇄ Drive with a live sync animation, status, and the two everyday actions. */
@Composable
fun PairCard(
    pair: FolderPair,
    progress: SyncProgress?,
    onSyncNow: () -> Unit,
    onHistory: () -> Unit,
) {
    val syncing = progress != null
    val failed = !syncing && pair.hasProblem
    val still = LocalReducedMotion.current
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.55f, if (still) 0.55f else 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow",
    )
    val glow by animateFloatAsState(if (syncing) pulse else 0f, tween(400), label = "cardGlow")

    GlassCard(glow = glow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Endpoint(Icons.Outlined.PhoneAndroid, "Phone", pair.localName, Modifier.weight(1f))
            Box(Modifier.width(104.dp).height(56.dp), contentAlignment = Alignment.Center) {
                SyncConnector(syncing, Modifier.fillMaxWidth().height(24.dp))
                SyncOrb(
                    when {
                        syncing -> OrbState.Syncing
                        failed -> OrbState.Problem
                        else -> OrbState.Idle
                    },
                    size = 44.dp,
                )
            }
            Endpoint(Icons.Outlined.Cloud, "Google Drive", pair.driveFolderName, Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
        Status(pair, progress)

        AnimatedVisibility(
            visible = pair.lastError != null && !syncing,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Text(
                pair.lastError.orEmpty(),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
                    .padding(12.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onHistory) {
                Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("History")
            }
            FilledTonalButton(
                onClick = onSyncNow,
                enabled = !syncing,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (syncing) "Syncing…" else "Sync now")
            }
        }
    }
}

@Composable
private fun Endpoint(icon: ImageVector, side: String, name: String, modifier: Modifier) {
    Column(
        modifier.semantics(mergeDescendants = true) { contentDescription = "$side folder: $name" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Status(pair: FolderPair, progress: SyncProgress?) {
    // Announce state changes (start/finish) to TalkBack without reading every file name.
    val summary = when {
        progress != null -> "Syncing"
        pair.lastSyncAt == 0L -> "Waiting for first sync"
        else -> pair.lastSummary ?: "Synced"
    }
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = summary }) {
        AnimatedContent(
            targetState = progress != null,
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
            label = "status",
        ) { running ->
            if (running && progress != null) {
                Column {
                    val fraction by animateFloatAsState(progress.fraction, tween(500), label = "progress")
                    if (progress.total > 0) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            strokeCap = StrokeCap.Round,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), strokeCap = StrokeCap.Round, gapSize = 0.dp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        progress.text + if (progress.total > 0) "  ·  ${progress.done + 1} of ${progress.total}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                val text = when {
                    pair.lastSyncAt == 0L -> "Waiting for first sync · merge only, nothing is deleted"
                    else -> "${pair.lastSummary ?: "Synced"} · ${DateUtils.getRelativeTimeSpanString(pair.lastSyncAt)}"
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** lastError holds either real failures or just "N skipped:" warnings; only the former is a problem. */
val FolderPair.hasProblem: Boolean
    get() = lastError != null && !Regex("^\\d+ skipped:").containsMatchIn(lastError)
