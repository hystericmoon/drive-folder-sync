package io.github.hystericmoon.drivesync.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hystericmoon.drivesync.data.FolderPair
import io.github.hystericmoon.drivesync.data.LogKind
import io.github.hystericmoon.drivesync.data.SyncLogEntry
import io.github.hystericmoon.drivesync.sync.SyncProgress
import io.github.hystericmoon.drivesync.ui.glass.AmbientBackground
import io.github.hystericmoon.drivesync.ui.glass.BottomScrim
import io.github.hystericmoon.drivesync.ui.glass.DriveSyncTheme
import io.github.hystericmoon.drivesync.ui.glass.GlassNavBar
import io.github.hystericmoon.drivesync.ui.glass.NavItem
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the main screens in light and dark. Run with: ./gradlew testDebugUnitTest -Pscreenshots */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h860dp-xhdpi")
class ScreenshotTest {
    /** Skip before the compose rule starts an activity (the test manifest exists only in debug). */
    @get:Rule(order = 0) val onlyWhenAsked = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue(System.getProperty("screenshots") == "true")
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1) val rule = createComposeRule()

    private val now = System.currentTimeMillis()
    private val pairs = listOf(
        FolderPair(1, "content://x", "Notes", "d1", "Notes", lastSyncAt = now - 4 * 60_000, lastSummary = "↑2 · ↓5", initialSyncDone = true),
        FolderPair(2, "content://y", "Camera", "d2", "Phone photos", lastSyncAt = now - 60 * 60_000, lastSummary = "Up to date", initialSyncDone = true),
        FolderPair(
            3, "content://z", "Documents", "d3", "Work docs", lastSyncAt = now - 3 * 3600_000, initialSyncDone = true,
            lastSummary = "↓1", lastError = "1 failed (will retry):\nbudget.xlsx: changed on phone during sync; will retry",
        ),
    )
    private val progress = mapOf(2L to SyncProgress("Uploading IMG_2041.jpg", 12, 31))
    private val auth = AuthState(email = "you@gmail.com", driveReady = true)
    private val history = listOf(
        SyncLogEntry(1, 1, now - 60_000, LogKind.UPLOADED, "Meeting notes.md", "Updated in Drive"),
        SyncLogEntry(2, 1, now - 120_000, LogKind.CONFLICT, "Ideas/todo.txt", "Drive version kept as “todo (conflict 2026-10-23).txt”"),
        SyncLogEntry(3, 2, now - 600_000, LogKind.DOWNLOADED, "Camera/IMG_2040.jpg", "New on phone"),
        SyncLogEntry(4, 3, now - 900_000, LogKind.TRASHED_LOCAL, "old/report-v1.pdf", "Deleted in Drive → moved to .sync-trash"),
        SyncLogEntry(5, 3, now - 1_000_000, LogKind.ERROR, "budget.xlsx", "changed on phone during sync; will retry"),
        SyncLogEntry(6, 1, now - 86_400_000 - 5_000, LogKind.TRASHED_REMOTE, "scratch.txt", "Deleted on phone → moved to Drive trash"),
        SyncLogEntry(7, 2, now - 86_400_000 - 9_000, LogKind.SKIPPED, "Camera/.thumbnails", "duplicate name in Drive"),
    )

    @Composable
    private fun Shell(tab: Int, fab: Boolean = false, content: @Composable (PaddingValues) -> Unit) {
        AmbientBackground {
            content(PaddingValues(start = 16.dp, end = 16.dp, top = 40.dp, bottom = if (fab) 168.dp else 104.dp))
            BottomScrim(if (fab) 170.dp else 110.dp, Modifier.align(Alignment.BottomCenter))
            Column(Modifier.align(Alignment.BottomCenter).padding(16.dp), horizontalAlignment = Alignment.End) {
                if (fab) {
                    ExtendedFloatingActionButton(
                        onClick = {}, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add folder pair") },
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
                GlassNavBar(
                    listOf(
                        NavItem("Folders", Icons.Outlined.Folder, Icons.Filled.Folder),
                        NavItem("History", Icons.Outlined.History, Icons.Filled.History),
                        NavItem("Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
                    ),
                    selected = tab, onSelect = {},
                )
            }
        }
    }

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        rule.setContent { DriveSyncTheme(dark = dark, dynamicColor = false) { Box { content() } } }
        rule.onRoot().captureRoboImage("screenshots/$name-${if (dark) "dark" else "light"}.png")
    }

    private fun folders(dark: Boolean) = shot("1-folders", dark) {
        Shell(0, fab = true) { pad ->
            FoldersScreen(auth, true, pairs, progress, pad, {}, {}, {}, {}, {})
        }
    }

    private fun historyScreen(dark: Boolean) = shot("2-history", dark) {
        Shell(1) { pad -> HistoryScreen(history, pairs, null, {}, HistoryFilter.All, {}, pad) }
    }

    private fun settings(dark: Boolean) = shot("3-settings", dark) {
        Shell(2) { pad ->
            SettingsScreen(
                "you@gmail.com", ThemeMode.System, false, pairs, UpdateUi(true, "0.1.8"), pad,
                {}, {}, {}, { _, _, _ -> }, { _, _ -> }, {}, {}, {},
            )
        }
    }

    private fun signIn(dark: Boolean) = shot("0-sign-in", dark) {
        Shell(0) { pad -> FoldersScreen(AuthState(), true, emptyList(), emptyMap(), pad, {}, {}, {}, {}, {}) }
    }

    @Test fun foldersLight() = folders(false)
    @Test fun foldersDark() = folders(true)
    @Test fun historyLight() = historyScreen(false)
    @Test fun historyDark() = historyScreen(true)
    @Test fun settingsLight() = settings(false)
    @Test fun settingsDark() = settings(true)
    @Test fun signInLight() = signIn(false)
    @Test fun signInDark() = signIn(true)
}
