package io.github.hystericmoon.drivesync.sync

import android.app.Application
import io.github.hystericmoon.drivesync.data.LogKind
import io.github.hystericmoon.drivesync.sync.fakes.SyncHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * End-to-end runs of [SyncEngine] against in-memory phone and Drive fakes and a real (in-memory)
 * Room database. Each test does several sync runs and checks both sides plus the baseline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SyncScenarioTest {
    private var h = SyncHarness()

    @After fun tearDown() = h.close()

    private fun withExcludes(patterns: String) { h.close(); h = SyncHarness(excludes = patterns) }

    /** A synced pair with a few nested files, already past its first sync. */
    private fun seeded() {
        h.phone.write("a.txt", "A")
        h.phone.write("docs/b.txt", "B")
        h.phone.write("docs/deep/c.txt", "C")
        h.drive.write("d.txt", "D")
        h.drive.write("docs/e.txt", "E")
        h.drive.write("photos/f.jpg", "F")
        h.syncViaFullScan()
        h.assertInSync()
        assertTrue(h.pair().initialSyncDone)
    }

    // ---- new, edited, deleted ----

    @Test fun firstSyncMergesBothSidesIncludingNestedFolders() {
        seeded()
        assertEquals(
            mapOf("a.txt" to "A", "docs/b.txt" to "B", "docs/deep/c.txt" to "C", "d.txt" to "D", "docs/e.txt" to "E", "photos/f.jpg" to "F"),
            h.phone.files(),
        )
        assertEquals(setOf("docs", "docs/deep", "photos"), h.phone.folders())
        assertNotNull(h.pair().changesPageToken)
        // A second run with nothing changed does nothing.
        h.syncViaChanges()
        h.assertInSync()
        assertEquals("Up to date", h.pair().lastSummary)
    }

    @Test fun newFilesOnEachSideIncludingNewNestedFolders() {
        seeded()
        h.phone.write("docs/new/n1.txt", "N1")
        h.phone.write("music/m.mp3", "M")
        h.drive.write("photos/2026/p.jpg", "P")
        h.drive.write("videos/v.mp4", "V")
        h.drive.mkdirs("empty-in-drive")
        h.phone.mkdirs("empty-on-phone")
        h.syncViaChanges()
        h.assertInSync()
        assertEquals("P", h.phone.read("photos/2026/p.jpg"))
        assertEquals("N1", h.drive.files()["docs/new/n1.txt"])
        assertTrue("empty-on-phone" in h.drive.folders())
        assertTrue("empty-in-drive" in h.phone.folders())
    }

    @Test fun editsOnEachSidePropagate() {
        seeded()
        h.phone.write("docs/deep/c.txt", "C edited on phone, now longer")
        h.drive.write("photos/f.jpg", "F2")
        h.syncViaChanges()
        h.assertInSync()
        assertEquals("C edited on phone, now longer", h.drive.files()["docs/deep/c.txt"])
        assertEquals("F2", h.phone.read("photos/f.jpg"))
        // Edit the same files again, the other way round.
        h.drive.write("docs/deep/c.txt", "short")
        h.phone.write("photos/f.jpg", "F3")
        h.syncViaChanges()
        h.assertInSync()
        assertEquals("short", h.phone.read("docs/deep/c.txt"))
        assertEquals("F3", h.drive.files()["photos/f.jpg"])
    }

    @Test fun deleteOnPhoneMovesDriveFileToDriveTrash() {
        seeded()
        val id = h.drive.id("docs/b.txt")
        h.phone.remove("docs/b.txt")
        h.syncViaChanges()
        h.assertInSync()
        val item = h.drive.item(id)
        assertTrue("Drive copy must be in Drive trash, not gone", item.trashed)
        assertEquals("B", String(item.content))
        h.assertNoPermanentDeletes()
    }

    @Test fun deleteInDriveMovesPhoneFileToSyncTrash() {
        seeded()
        h.drive.trashPath("docs/deep/c.txt")
        h.syncViaChanges()
        h.assertInSync()
        assertEquals(mapOf("docs/deep/c.txt" to "C"), h.phone.trashed())
        assertTrue(h.phone.trashBatches().single().startsWith("2026-10-23_"))
        h.assertNoPermanentDeletes()
    }

    @Test fun deletingWholeFoldersOnEachSide() {
        seeded()
        h.phone.remove("docs") // b.txt, e.txt, deep/c.txt
        h.syncViaChanges()
        h.assertInSync()
        assertEquals(setOf("photos"), h.drive.folders())
        assertTrue(h.drive.trashedNames().containsAll(listOf("b.txt", "e.txt", "c.txt")))

        h.drive.trashPath("photos")
        h.syncViaChanges()
        h.assertInSync()
        assertEquals("F", h.phone.trashed()["photos/f.jpg"])
        assertFalse(h.phone.exists("photos"))
        h.assertNoPermanentDeletes()
    }

    // ---- conflicts ----

    @Test fun editedOnBothSidesKeepsBothWithConflictNameOnBothSides() {
        seeded()
        h.phone.write("docs/b.txt", "phone version")
        h.drive.write("docs/b.txt", "drive version")
        h.syncViaChanges()
        h.assertInSync()
        assertEquals("phone version", h.phone.read("docs/b.txt"))
        assertEquals("drive version", h.phone.read("docs/b (conflict 2026-10-23).txt"))
        assertEquals("phone version", h.drive.files()["docs/b.txt"])
        assertEquals("drive version", h.drive.files()["docs/b (conflict 2026-10-23).txt"])
        assertEquals(1, h.log().count { it.kind == LogKind.CONFLICT && it.path == "docs/b.txt" })
        // Settles: the next runs change nothing.
        h.syncViaChanges()
        h.syncViaChanges()
        h.assertInSync()
        assertEquals(2, h.phone.files().keys.count { it.startsWith("docs/b") })
    }

    @Test fun secondConflictTheSameDayGetsANumberedName() {
        seeded()
        repeat(2) { i ->
            h.phone.write("a.txt", "phone $i")
            h.drive.write("a.txt", "drive $i")
            h.syncViaChanges()
            h.assertInSync()
        }
        assertEquals("drive 0", h.phone.read("a (conflict 2026-10-23).txt"))
        assertEquals("drive 1", h.phone.read("a (conflict 2026-10-23 2).txt"))
        assertEquals("phone 1", h.drive.files()["a.txt"])
    }

    @Test fun firstSyncWithSameNameOnBothSides() {
        h.phone.write("same.txt", "identical")
        h.drive.write("same.txt", "identical")
        h.phone.write("differs.txt", "phone")
        h.drive.write("differs.txt", "drive")
        h.syncViaFullScan()
        h.assertInSync()
        assertEquals(setOf("same.txt", "differs.txt", "differs (conflict 2026-10-23).txt"), h.phone.files().keys)
        assertEquals(1, h.log().count { it.kind == LogKind.CONFLICT })
    }

    // ---- edit vs delete ----

    @Test fun phoneEditBeatsDriveDelete() {
        seeded()
        h.phone.write("docs/b.txt", "edited")
        h.drive.trashPath("docs/b.txt")
        h.syncViaChanges()
        h.assertInSync()
        assertEquals("edited", h.drive.files()["docs/b.txt"])
        assertTrue(h.phone.trashed().isEmpty())
    }

    @Test fun driveEditBeatsPhoneDelete() {
        seeded()
        h.drive.write("docs/e.txt", "edited in drive")
        h.phone.remove("docs/e.txt")
        h.syncViaChanges()
        h.assertInSync()
        assertEquals("edited in drive", h.phone.read("docs/e.txt"))
        assertTrue(h.drive.trashedNames().isEmpty())
    }

    @Test fun phoneEditBeatsDriveFolderTrash() {
        seeded()
        h.phone.write("docs/deep/c.txt", "edited")
        h.drive.trashPath("docs")
        h.syncViaChanges()
        h.assertInSync()
        // The edited file survives (re-uploaded into a fresh docs/deep), the untouched ones go to .sync-trash.
        assertEquals(mapOf("docs/deep/c.txt" to "edited"), h.drive.files().filterKeys { it.startsWith("docs") })
        assertEquals(mapOf("docs/b.txt" to "B", "docs/e.txt" to "E"), h.phone.trashed())
        h.assertNoPermanentDeletes()
    }

    // ---- first sync is merge-only ----

    @Test fun firstSyncNeverDeletesEvenAcrossRuns() {
        h.phone.write("p1.txt", "P1")
        h.phone.write("p2.txt", "P2")
        h.drive.write("d1.txt", "D1")
        h.drive.write("d2.txt", "D2")
        h.drive.failDownload = { it == "d2.txt" }
        h.sync()
        assertFalse("a run with a failure is not a finished first sync", h.pair().initialSyncDone)
        assertNotNull(h.pair().lastError)

        // Before the first clean sync, files that disappear are copied back instead of trashed.
        h.phone.remove("p1.txt")
        h.drive.trashPath("d1.txt")
        h.drive.failDownload = { false }
        h.sync()
        assertTrue(h.pair().initialSyncDone)
        h.assertInSync()
        assertEquals(setOf("p1.txt", "p2.txt", "d1.txt", "d2.txt"), h.phone.files().keys)
        assertTrue(h.phone.trashed().isEmpty())
        h.assertNoPermanentDeletes()
    }

    // ---- trash only, and the 30-day purge ----

    @Test fun syncTrashIsPurgedAfter30DaysOnly() {
        seeded()
        h.drive.trashPath("a.txt")
        h.sync()
        val firstBatch = h.phone.trashBatches().single()
        h.clock.advance(Duration.ofDays(20))
        h.drive.trashPath("d.txt")
        h.sync()
        assertEquals(2, h.phone.trashBatches().size)
        assertEquals(mapOf("a.txt" to "A", "d.txt" to "D"), h.phone.trashed())

        h.clock.advance(Duration.ofDays(11)) // first batch is now 31 days old, second 11
        h.sync()
        assertFalse(firstBatch in h.phone.trashBatches())
        assertEquals(mapOf("d.txt" to "D"), h.phone.trashed())
        // The only permanent delete is the expired batch.
        assertEquals(listOf(".sync-trash/$firstBatch"), h.phone.deletes.filter { it.fileCount > 0 }.map { it.path })
        h.assertNoPermanentDeletes()
        h.assertInSync()
    }

    // ---- folders renamed or moved in Drive ----

    @Test fun driveFolderRename() {
        seeded()
        h.drive.renamePath("docs", "papers")
        h.syncViaChanges()
        h.assertInSync()
        assertEquals(setOf("papers", "papers/deep", "photos"), h.phone.folders())
        assertEquals("C", h.phone.read("papers/deep/c.txt"))
        // Known limit: a remote rename is delete + new; the old copies sit in .sync-trash.
        assertEquals(setOf("docs/b.txt", "docs/e.txt", "docs/deep/c.txt"), h.phone.trashed().keys)
        assertTrue("nothing trashed in Drive", h.drive.trashedNames().isEmpty())
        h.assertNoPermanentDeletes()
    }

    @Test fun driveFolderMovedWithinThePair() {
        seeded()
        h.drive.moveItem(h.drive.id("docs/deep"), h.drive.id("photos"))
        h.syncViaChanges()
        h.assertInSync()
        assertEquals("C", h.phone.read("photos/deep/c.txt"))
        assertFalse(h.phone.exists("docs/deep"))
        assertTrue(h.drive.trashedNames().isEmpty())
    }

    @Test fun folderMovedIntoThePairBringsItsWholeTree() {
        seeded()
        val outside = h.drive.mkdirOutside("Archive")
        h.drive.writeIn(outside, "x.txt", "X")
        h.drive.writeIn(h.drive.mkdirIn(outside, "nested"), "y.txt", "Y")
        h.drive.moveItem(outside, h.drive.pairRootId)
        h.syncViaChanges()
        h.assertInSync()
        assertEquals("X", h.phone.read("Archive/x.txt"))
        assertEquals("Y", h.phone.read("Archive/nested/y.txt"))
    }

    @Test fun folderMovedOutOfThePairLeavesDriveAlone() {
        seeded()
        val docsId = h.drive.id("docs")
        h.drive.moveItem(docsId, h.drive.myDriveId)
        h.syncViaChanges()
        h.assertInSync()
        assertFalse(h.phone.exists("docs"))
        assertEquals(setOf("docs/b.txt", "docs/e.txt", "docs/deep/c.txt"), h.phone.trashed().keys)
        // The moved folder and its files are untouched in Drive.
        assertTrue(h.drive.trashedNames().isEmpty())
        assertFalse(h.drive.item(docsId).trashed)
    }

    @Test fun trashedPairFolderStopsTheSync() {
        seeded()
        h.drive.item(h.drive.pairRootId).trashed = true
        h.phone.write("new.txt", "N")
        h.sync()
        assertTrue(h.pair().lastError!!.contains("missing or in the trash"))
        assertEquals(7, h.phone.files().size)
        assertTrue(h.phone.trashed().isEmpty())
    }

    // ---- Changes API vs full rescan ----

    @Test fun changesPathAndFullRescanAgree() {
        seeded()
        fun changeThings(round: Int) {
            h.phone.write("a.txt", "A$round")
            h.drive.write("docs/e.txt", "E$round")
            h.drive.write("new/r$round.txt", "R")
            h.phone.write("local/l$round.txt", "L")
        }
        changeThings(1)
        h.syncViaChanges()
        h.assertInSync()
        val afterChanges = h.phone.files()

        h.clock.advance(Duration.ofHours(25)) // daily safety-net scan is due
        changeThings(2)
        h.syncViaFullScan()
        h.assertInSync()
        assertEquals(afterChanges.size + 2, h.phone.files().size)
        assertEquals("E2", h.phone.read("docs/e.txt"))
        // After the full scan the Changes path resumes.
        h.drive.write("after.txt", "!")
        h.syncViaChanges()
        h.assertInSync()
    }

    @Test fun expiredChangesTokenFallsBackToFullScan() {
        seeded()
        h.drive.write("docs/e.txt", "E2")
        h.drive.expireTokens()
        h.drive.write("late.txt", "L")
        h.sync()
        h.assertInSync()
        assertEquals("E2", h.phone.read("docs/e.txt"))
        assertEquals("L", h.phone.read("late.txt"))
    }

    @Test fun changesTokenDoesNotAdvanceAfterAFailedRun() {
        seeded()
        val token = h.pair().changesPageToken
        h.drive.write("docs/e.txt", "E2")
        h.drive.write("ok.txt", "OK")
        h.drive.failDownload = { it == "e.txt" }
        h.syncViaChanges()
        assertEquals("token must stay put after a failure", token, h.pair().changesPageToken)
        assertEquals("OK", h.phone.read("ok.txt"))
        assertEquals("E", h.phone.read("docs/e.txt"))

        // Still within the 24 h window, so only the replayed Changes feed can deliver the edit.
        h.drive.failDownload = { false }
        h.syncViaChanges()
        h.assertInSync()
        assertEquals("E2", h.phone.read("docs/e.txt"))
        assertTrue(h.pair().changesPageToken != token)
    }

    // ---- mass-delete guard ----

    @Test fun phoneSuddenlyEmptyAbortsWithoutTouchingDrive() {
        seeded()
        for (p in h.phone.files().keys) h.phone.remove(p)
        h.sync()
        assertTrue(h.pair().lastError!!.contains("phone folder looks empty"))
        assertTrue(h.drive.trashedNames().isEmpty())
        assertEquals(6, h.drive.files().size)
    }

    @Test fun driveSuddenlyEmptyAbortsWithoutTouchingPhone() {
        seeded()
        val token = h.pair().changesPageToken
        for (p in h.drive.files().keys) h.drive.trashPath(p)
        h.syncViaChanges()
        assertTrue(h.pair().lastError!!.contains("Drive folder looks empty"))
        assertEquals(6, h.phone.files().size)
        assertTrue(h.phone.trashed().isEmpty())
        assertEquals(token, h.pair().changesPageToken)
        // Also on a full scan.
        h.clock.advance(Duration.ofDays(2))
        h.syncViaFullScan()
        assertEquals(6, h.phone.files().size)
    }

    // ---- exclude rules ----

    @Test fun excludedPathsAreNeverSyncedOrDeleted() {
        withExcludes("*.tmp\nbuild/\nphotos/raw")
        h.phone.write("keep.txt", "K")
        h.phone.write("scratch.tmp", "phone tmp")
        h.phone.write("build/out.bin", "B")
        h.phone.write("photos/raw/1.dng", "RAW")
        h.drive.write("cache.tmp", "drive tmp")
        h.drive.write("photos/raw/2.dng", "RAW2")
        h.drive.write("photos/ok.jpg", "OK")
        h.syncViaFullScan()
        assertEquals(setOf("keep.txt", "photos/ok.jpg"), h.drive.files().keys - setOf("cache.tmp", "photos/raw/2.dng"))
        assertFalse(h.phone.exists("cache.tmp"))
        assertFalse(h.phone.exists("photos/raw/2.dng"))
        assertEquals(setOf("keep.txt", "photos/ok.jpg"), h.baseline().filterValues { !it.isDir }.keys)

        // Deleting or editing excluded things on one side never reaches the other.
        h.drive.trashPath("cache.tmp")
        h.phone.remove("build")
        h.drive.write("photos/raw/2.dng", "RAW2 edited")
        h.syncViaChanges()
        assertEquals("phone tmp", h.phone.read("scratch.tmp"))
        assertEquals("RAW", h.phone.read("photos/raw/1.dng"))
        assertFalse(h.phone.exists("photos/raw/2.dng"))
        assertTrue(h.phone.trashed().isEmpty())
        h.assertNoPermanentDeletes()
    }

    @Test fun excludingAnAlreadySyncedPathForgetsItWithoutDeleting() {
        seeded()
        h.updatePair { it.withExcludePatterns("docs/") }
        h.phone.remove("docs/b.txt")
        h.drive.trashPath("docs/e.txt")
        h.syncViaFullScan() // changed rules force one
        assertFalse(h.baseline().keys.any { it.startsWith("docs") })
        assertEquals("C", h.drive.files()["docs/deep/c.txt"])
        assertEquals("B", String(h.drive.item(h.drive.id("docs/b.txt")).content))
        assertFalse(h.drive.item(h.drive.id("docs/b.txt")).trashed)
        assertEquals("E", h.phone.read("docs/e.txt"))
        assertTrue(h.phone.trashed().isEmpty())
    }

    @Test fun removingAnExcludeDoesNotDuplicateFilesInDrive() {
        withExcludes("*.log")
        h.phone.write("a.txt", "A")
        h.phone.write("app.log", "same log")
        h.drive.write("app.log", "same log")
        h.drive.write("drive-only.log", "D")
        h.syncViaFullScan()

        // Still inside the 24 h window: only a forced full scan can see the formerly excluded Drive files.
        h.updatePair { it.withExcludePatterns("") }
        h.syncViaFullScan()
        h.syncViaChanges()
        h.assertInSync()
        assertEquals(setOf("a.txt", "app.log", "drive-only.log"), h.drive.files().keys)
        assertEquals("D", h.phone.read("drive-only.log"))
        assertTrue(h.log().none { it.kind == LogKind.CONFLICT })
    }

    // ---- duplicate and case-colliding names in Drive ----

    @Test fun duplicateNamesInDriveAreSkippedNotDeleted() {
        seeded()
        val dup = h.drive.addDuplicate("docs/e.txt", "second e")
        h.drive.write("x.txt", "X") // something else changes too
        h.syncViaChanges()
        assertTrue(h.pair().lastError!!.startsWith("1 skipped"))
        assertTrue(h.pair().lastError!!.contains("same name"))
        assertTrue(h.log().any { it.kind == LogKind.SKIPPED && it.path == "docs/e.txt" })
        // The note stays on quick runs, which can't see the duplicate again, and the winner keeps syncing.
        h.drive.write("docs/e.txt", "E2")
        h.syncViaChanges()
        assertTrue(h.pair().lastError!!.contains("same name"))
        assertEquals("E2", h.phone.read("docs/e.txt"))
        h.clock.advance(Duration.ofHours(25))
        h.syncViaFullScan()
        assertTrue(h.pair().lastError!!.contains("same name"))
        assertEquals("X", h.phone.read("x.txt"))
        assertFalse(h.drive.item(dup).trashed)
        assertTrue(h.drive.trashedNames().isEmpty())
        assertTrue(h.phone.trashed().isEmpty())

        // Fixed in Drive: the note clears with the next full scan at the latest.
        val synced = h.drive.id("docs/e.txt")
        h.drive.trashPath("docs/e.txt") // the synced one; the duplicate takes its place
        h.clock.advance(Duration.ofHours(25))
        h.syncViaFullScan()
        h.syncViaChanges()
        h.assertInSync()
        // Seen as a Drive edit: the phone gets the other file, and the old bytes are safe in Drive's trash.
        assertEquals("second e", h.phone.read("docs/e.txt"))
        assertTrue(h.drive.item(synced).trashed)
        assertEquals("E2", String(h.drive.item(synced).content))
    }

    @Test fun driveNamesThePhoneCantHoldAreSkippedWithoutBlockingTheSync() {
        seeded()
        h.drive.write("Meeting 10:30.txt", "m")
        h.drive.write("docs/What?.pdf", "w")
        h.drive.write("no-ext.", "n")
        h.drive.write("ok.txt", "ok")
        val token = h.pair().changesPageToken
        h.syncViaChanges()
        assertEquals("ok", h.phone.read("ok.txt"))
        val error = h.pair().lastError!!
        assertTrue(error, error.startsWith("3 skipped"))
        assertTrue(error, error.contains("Meeting 10:30.txt: the name contains “:”, which a phone folder can't hold; rename it in Drive"))
        assertTrue(error, error.contains("no-ext.: the name ends with a dot"))
        // A skip isn't a failure: the Changes token moves on and later runs stay clean.
        assertTrue(h.pair().changesPageToken != token)
        h.drive.write("later.txt", "L")
        h.syncViaChanges()
        assertEquals("L", h.phone.read("later.txt"))
        assertTrue(h.pair().lastError!!.startsWith("3 skipped"))
        // Renamed in Drive: it syncs.
        h.drive.renamePath("Meeting 10:30.txt", "Meeting 10.30.txt")
        h.syncViaChanges()
        assertEquals("m", h.phone.read("Meeting 10.30.txt"))
    }

    @Test fun caseCollidingNamesInDrive() {
        h.drive.write("Report.txt", "upper")
        h.drive.write("report.txt", "lower")
        h.drive.write("Dir/a.txt", "1")
        h.drive.write("dir/b.txt", "2")
        h.phone.write("other.txt", "O")
        h.sync()
        assertTrue(h.pair().lastError!!.contains("skipped"))
        repeat(2) { h.sync() }
        // One of each pair lands on the phone; the other is reported, and nothing is deleted anywhere.
        assertEquals(1, h.phone.files().keys.count { it.equals("report.txt", ignoreCase = true) })
        assertEquals(1, h.phone.folders().count { it.equals("dir", ignoreCase = true) })
        assertEquals(5, h.drive.files().size)
        assertTrue(h.drive.trashedNames().isEmpty())
        assertTrue(h.pair().initialSyncDone)
        assertTrue(h.log().count { it.kind == LogKind.SKIPPED } >= 2)
    }

    @Test fun phoneAndDriveNamesDifferingOnlyByCase() {
        seeded()
        h.phone.write("Notes.txt", "phone")
        h.drive.write("notes.txt", "drive")
        h.syncViaChanges()
        h.syncViaChanges() // can't see notes.txt in Drive any more, and must still not upload Notes.txt
        h.syncViaChanges()
        // Neither is copied (that would put both names on one side); each side keeps its own.
        assertEquals("phone", h.phone.read("Notes.txt"))
        assertEquals(mapOf("notes.txt" to "drive"), h.drive.files().filterKeys { it.equals("notes.txt", ignoreCase = true) })
        val error = h.pair().lastError!!
        assertTrue(error, error.startsWith("2 skipped"))
        assertTrue(error, error.contains("“Notes.txt” on the phone and “notes.txt” in Drive differ only in upper/lower case"))
        // Everything else keeps syncing, and the run counts as clean.
        h.drive.write("later.txt", "L")
        h.syncViaChanges()
        assertEquals("L", h.phone.read("later.txt"))

        // The user renames the phone copy: it syncs, and the Drive one arrives with the next full scan.
        h.phone.rename("Notes.txt", "Notes (phone).txt")
        h.syncViaChanges()
        assertEquals("phone", h.drive.files()["Notes (phone).txt"])
        h.clock.advance(Duration.ofHours(25))
        h.syncViaFullScan()
        h.assertInSync()
        assertEquals("drive", h.phone.read("notes.txt"))
        assertTrue(h.drive.trashedNames().isEmpty())
        h.assertNoPermanentDeletes()
    }

    @Test fun caseClashingNewFoldersAreLeftAlone() {
        seeded()
        h.phone.write("Music/a.mp3", "A")
        h.drive.write("music/b.mp3", "B")
        h.syncViaChanges()
        h.phone.write("Music/new.mp3", "N") // added later, inside the clashing folder
        h.syncViaChanges()
        // Nothing crosses in either direction, so neither side ends up with both folder names.
        assertEquals(setOf("music/b.mp3"), h.drive.files().keys.filter { it.startsWith("music", ignoreCase = true) }.toSet())
        assertFalse(h.drive.folders().contains("Music"))
        assertEquals(setOf("Music/a.mp3", "Music/new.mp3"), h.phone.files().keys.filter { it.startsWith("music", ignoreCase = true) }.toSet())
        assertTrue(h.pair().lastError!!.contains("upper/lower case"))
    }

    @Test fun renameThatOnlyChangesCaseInDrive() {
        seeded()
        h.drive.renamePath("a.txt", "A.txt")
        h.syncViaChanges()
        h.assertInSync()
        assertEquals("A", h.phone.read("A.txt"))
        assertEquals(mapOf("a.txt" to "A"), h.phone.trashed())
    }

    @Test fun renameThatOnlyChangesCaseOnThePhone() {
        seeded()
        h.phone.rename("d.txt", "D.txt")
        h.syncViaChanges()
        h.assertInSync()
        assertEquals("D", h.drive.files()["D.txt"])
        assertTrue("d.txt" in h.drive.trashedNames())
    }

    // ---- failures in the middle of a run ----

    @Test fun uploadFailureMidRunIsRetriedNextRun() {
        seeded()
        h.phone.write("u1.txt", "1")
        h.phone.write("u2.txt", "2")
        h.phone.write("a.txt", "A edited")
        h.phone.write("u3.txt", "3")
        h.drive.failUpload = { it == "u2.txt" || it == "a.txt" }
        h.syncViaChanges()
        assertEquals("1", h.drive.files()["u1.txt"])
        assertEquals("3", h.drive.files()["u3.txt"])
        assertNull(h.drive.files()["u2.txt"])
        assertEquals("A", h.drive.files()["a.txt"])
        assertTrue(h.pair().lastError!!.startsWith("2 failed"))

        h.drive.failUpload = { false }
        h.syncViaChanges()
        h.assertInSync()
        assertEquals("2", h.drive.files()["u2.txt"])
        assertEquals("A edited", h.drive.files()["a.txt"])
    }

    @Test fun lostUploadResponseDoesNotDuplicate() {
        seeded()
        h.phone.write("new.txt", "N")
        h.phone.write("a.txt", "A2")
        h.drive.loseUploadResponse = { true }
        h.syncViaChanges()
        h.drive.loseUploadResponse = { false }
        h.syncViaChanges()
        h.assertInSync()
        assertEquals(1, h.drive.files().keys.count { it.startsWith("new.txt") })
        assertEquals("A2", h.drive.files()["a.txt"])
        assertTrue(h.log().none { it.kind == LogKind.CONFLICT })
    }

    @Test fun downloadFailureMidRunIsRetriedNextRun() {
        seeded()
        h.drive.write("n1.txt", "1")
        h.drive.write("n2.txt", "2")
        h.drive.write("docs/e.txt", "E2")
        h.drive.failDownload = { it == "n2.txt" || it == "e.txt" }
        h.syncViaChanges()
        assertEquals("1", h.phone.read("n1.txt"))
        assertFalse(h.phone.exists("n2.txt"))
        assertEquals("E", h.phone.read("docs/e.txt"))

        h.drive.failDownload = { false }
        h.syncViaChanges()
        h.assertInSync()
        assertEquals("E2", h.phone.read("docs/e.txt"))
    }

    @Test fun phoneFullWhileReplacingAFileRecoversWithoutAConflict() {
        seeded()
        h.drive.write("docs/e.txt", "new drive content")
        h.phone.failWrite = { it == "e.txt" }
        h.syncViaChanges()
        assertNotNull(h.pair().lastError)

        h.phone.failWrite = { false }
        h.syncViaChanges()
        h.syncViaChanges()
        h.assertInSync()
        assertEquals("new drive content", h.phone.read("docs/e.txt"))
        assertEquals("new drive content", h.drive.files()["docs/e.txt"])
        assertTrue("half-written file must not be treated as an edit", h.log().none { it.kind == LogKind.CONFLICT })
    }

    @Test fun phoneFullWhileDownloadingANewFileRecoversWithoutAConflict() {
        seeded()
        h.drive.write("big.bin", "0123456789")
        h.phone.failWrite = { it == "big.bin" }
        h.syncViaChanges()

        h.phone.failWrite = { false }
        h.syncViaChanges()
        h.syncViaChanges()
        h.assertInSync()
        assertEquals("0123456789", h.phone.read("big.bin"))
        assertEquals("0123456789", h.drive.files()["big.bin"])
        assertTrue(h.log().none { it.kind == LogKind.CONFLICT })
    }
}
