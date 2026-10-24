package io.github.hystericmoon.drivesync.health

import android.app.Application
import androidx.room.Room
import io.github.hystericmoon.drivesync.data.AppDatabase
import io.github.hystericmoon.drivesync.data.FolderPair
import io.github.hystericmoon.drivesync.data.LogKind
import io.github.hystericmoon.drivesync.data.SyncLogEntry
import io.github.hystericmoon.drivesync.sync.fakes.FakeDrive
import io.github.hystericmoon.drivesync.sync.fakes.MutableClock
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HealthReporterTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val drive = FakeDrive()
    private val clock = MutableClock()

    @After fun tearDown() = db.close()

    private fun reporter(versionCode: Int = 21) =
        HealthReporter(context, db, { drive }, clock, "0.1.$versionCode", versionCode, parentId = drive.myDriveId)

    private fun healthFiles() = drive.items.values.filter { it.name == HealthReporter.FILE_NAME && !it.trashed }

    private fun report(): JSONObject = JSONObject(String(healthFiles().single().content))

    @Test fun writesOneFileAtTheTopOfMyDriveAndKeepsUpdatingIt() = runBlocking {
        val r = reporter()
        assertTrue(r.publish())
        clock.advance(Duration.ofMinutes(15))
        assertTrue(r.publish())
        val file = healthFiles().single()
        assertEquals(drive.myDriveId, file.parentId)
        assertFalse("must stay out of the synced folder", drive.files().keys.any { it.endsWith(HealthReporter.FILE_NAME) })
    }

    @Test fun reportsVersionPairsRunsEventsAndProblems() = runBlocking {
        val pairId = db.pairs().insert(
            FolderPair(
                localTreeUri = "content://x", localName = "Camera", driveFolderId = "d", driveFolderName = "Photos",
                lastSyncAt = 1, lastSummary = "↑2", lastError = "1 failed (will retry):\na.jpg: boom", initialSyncDone = true,
            ),
        )
        db.log().insert(SyncLogEntry(pairId = pairId, time = 5, kind = LogKind.ERROR, path = "a.jpg", detail = "boom"))
        db.log().insert(SyncLogEntry(pairId = pairId, time = 6, kind = LogKind.UPLOADED, path = "b.jpg"))
        val r = reporter()
        r.noteRun(pairId, "scheduled", "↑2 | 1 failed (will retry):")
        r.noteEvent("Update 0.1.21 downloaded, verified and handed to the installer")
        r.publish()

        val json = report()
        assertEquals("0.1.21", json.getJSONObject("app").getString("version"))
        val pair = json.getJSONArray("pairs").getJSONObject(0)
        assertEquals("Camera", pair.getString("phoneFolder"))
        assertTrue(pair.getString("lastError").contains("boom"))
        assertEquals("scheduled", json.getJSONArray("runs").getJSONObject(0).getString("trigger"))
        assertTrue(json.getJSONArray("events").getJSONObject(0).getString("event").startsWith("Update 0.1.21"))
        val problems = json.getJSONArray("problems")
        assertEquals("only errors and conflicts", 1, problems.length())
        assertEquals("a.jpg", problems.getJSONObject(0).getString("path"))
        assertTrue(json.getJSONObject("phone").has("batteryUnrestricted"))
    }

    @Test fun anUpdateShowsUpAsAnEvent() = runBlocking {
        reporter(versionCode = 20).noteAppStart()
        reporter(versionCode = 20).noteAppStart() // same version again: nothing new
        reporter(versionCode = 21).noteAppStart()
        reporter(versionCode = 21).publish()
        val events = report().getJSONArray("events")
        assertEquals(2, events.length())
        assertEquals("Installed 0.1.20", events.getJSONObject(0).getString("event"))
        assertEquals("Updated from 0.1.20 to 0.1.21", events.getJSONObject(1).getString("event"))
    }

    @Test fun writesAtMostEveryTenMinutesUnlessTheVersionChanged() = runBlocking {
        assertTrue(reporter().publish())
        clock.advance(Duration.ofMinutes(3))
        assertFalse(reporter().publish())
        assertTrue("a new version is reported right away", reporter(versionCode = 22).publish())
        clock.advance(Duration.ofMinutes(10))
        assertTrue(reporter(versionCode = 22).publish())
    }

    @Test fun recreatesTheFileIfTheUserTrashedIt() = runBlocking {
        reporter().publish()
        healthFiles().single().trashed = true
        clock.advance(Duration.ofMinutes(15))
        assertTrue(reporter().publish())
        assertEquals(1, healthFiles().size)
    }

    @Test fun findsAnExistingFileInsteadOfMakingASecond() = runBlocking {
        drive.writeIn(drive.myDriveId, HealthReporter.FILE_NAME, "{}") // e.g. written before a reinstall
        reporter().publish()
        assertEquals(1, healthFiles().size)
        assertTrue(report().has("app"))
    }

    @Test fun driveTroubleNeverThrowsAndNeverDuplicates() = runBlocking {
        reporter().publish()
        drive.failUpload = { true }
        clock.advance(Duration.ofMinutes(15))
        assertFalse(reporter().publish())
        drive.failUpload = { false }
        assertTrue(reporter().publish())
        assertEquals(1, healthFiles().size)
    }

    @Test fun keepsOnlyRecentRunsAndEvents() = runBlocking {
        val r = reporter()
        repeat(60) { r.noteRun(1, "scheduled", "run $it") }
        repeat(30) { r.noteEvent("event $it") }
        r.publish()
        val json = report()
        assertEquals(40, json.getJSONArray("runs").length())
        assertEquals("run 59", json.getJSONArray("runs").getJSONObject(39).getString("outcome"))
        assertEquals(20, json.getJSONArray("events").length())
    }
}
