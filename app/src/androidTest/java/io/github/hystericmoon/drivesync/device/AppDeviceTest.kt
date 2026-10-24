package io.github.hystericmoon.drivesync.device

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.hystericmoon.drivesync.MainActivity
import io.github.hystericmoon.drivesync.Notifier
import io.github.hystericmoon.drivesync.data.FolderPair
import io.github.hystericmoon.drivesync.sync.SyncScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The app on real Android: launch and navigation, notifications, background scheduling. */
@RunWith(AndroidJUnit4::class)
class AppDeviceTest {
    @get:Rule(order = 0) val notifications: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun launchesAndNavigatesBetweenTabs() {
        // Signed out on a fresh emulator; "Not configured" when built without a web client id.
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Sign in to start").or(hasText("Not configured"))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Settings").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Not signed in").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Folders").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Sign in to start").or(hasText("Not configured"))).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun conflictAndSignInNotificationsAreShown() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancelAll()
        Notifier.createChannels(context)
        Notifier.conflicts(context, pairId = 4242, pairName = "Camera", conflictNames = listOf("a (conflict 2026-10-23).jpg"))
        Notifier.needsSignIn(context)
        // Posting is asynchronous; give the system a moment to list both.
        val expected = setOf("Conflict in Camera", "Drive sync is paused")
        var titles = emptySet<String>()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            titles = nm.activeNotifications.mapTo(HashSet()) { it.notification.extras.getCharSequence("android.title").toString() }
            if (titles.containsAll(expected)) break
            Thread.sleep(100)
        }
        assertTrue(titles.toString(), titles.containsAll(expected))
        nm.cancelAll()
    }

    @Test fun pairsAreScheduledWithTheirConstraints() {
        val scheduler = SyncScheduler(context)
        val wm = WorkManager.getInstance(context)
        val pair = FolderPair(id = 9_999, localTreeUri = "content://none", localName = "x", driveFolderId = "none", driveFolderName = "x", wifiOnly = true, chargingOnly = true)
        scheduler.schedule(pair)
        var info = wm.getWorkInfosForUniqueWork("sync-periodic-9999").get().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
        assertTrue(info.constraints.requiresCharging())

        // Changing the constraints updates the same job instead of adding a second one.
        scheduler.schedule(pair.copy(wifiOnly = false, chargingOnly = false))
        info = wm.getWorkInfosForUniqueWork("sync-periodic-9999").get().single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)

        scheduler.cancel(pair.id)
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfosForUniqueWork("sync-periodic-9999").get().single().state)
    }
}
