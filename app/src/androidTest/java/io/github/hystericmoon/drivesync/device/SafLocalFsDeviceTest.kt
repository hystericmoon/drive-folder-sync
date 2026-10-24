package io.github.hystericmoon.drivesync.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.hystericmoon.drivesync.local.LocalFs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.MessageDigest

/** [io.github.hystericmoon.drivesync.local.SafLocalFs] against Android's real storage provider. */
@RunWith(AndroidJUnit4::class)
class SafLocalFsDeviceTest {
    @get:Rule val folder = SharedStorageFolder()
    private val fs get() = folder.fs

    @Test fun createListReadAndHash() {
        folder.write("docs/deep/a.txt", "hello")
        val tree = fs.listTree()
        assertEquals(setOf("docs", "docs/deep", "docs/deep/a.txt"), tree.keys)
        val a = tree.getValue("docs/deep/a.txt")
        assertFalse(a.isDir)
        assertEquals(5L, a.size)
        assertTrue(a.lastModified > 0)
        assertTrue(tree.getValue("docs").isDir)
        assertEquals("hello", folder.read("docs/deep/a.txt"))
        val md5 = MessageDigest.getInstance("MD5").digest("hello".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(md5, fs.md5(a.docId))
    }

    @Test fun rewritingTruncates() {
        folder.write("a.txt", "a much longer first version")
        folder.write("a.txt", "short")
        assertEquals("short", folder.read("a.txt"))
        assertEquals(5L, fs.stat(folder.docId("a.txt")!!, "a.txt")!!.size)
    }

    /** Names must come out exactly as asked, or paths drift from Drive and every sync re-copies them. */
    @Test fun awkwardNamesAreCreatedExactly() {
        val names = listOf(
            "README", "photo.JPG", "archive.tar.gz", "notes.md", "data.weird-ext", "Spaces and ünïcode.txt",
            "file #1 (final).pdf", ".hidden", "no-ext.", "IMG_0001.HEIC", "song.mp3", "movie.mkv",
        )
        for (name in names) fs.createFile(fs.rootDocId, name)
        assertEquals(names.toSortedSet(), fs.children(fs.rootDocId, "").map { it.name }.toSortedSet())
    }

    @Test fun nameClashFailsWithoutLeavingACopyBehind() {
        folder.write("Report.txt", "x")
        folder.mkdirs("docs")
        assertThrows(IOException::class.java) { fs.createFile(fs.rootDocId, "Report.txt") }
        // Phone storage ignores case, so these clash too.
        assertThrows(IOException::class.java) { fs.createFile(fs.rootDocId, "report.TXT") }
        assertThrows(IOException::class.java) { fs.createDir(fs.rootDocId, "Docs") }
        assertEquals(setOf("Report.txt", "docs"), fs.children(fs.rootDocId, "").map { it.name }.toSet())
    }

    @Test fun moveAndDelete() {
        folder.write("a.txt", "A")
        val target = folder.mkdirs("x/y")
        fs.move(folder.docId("a.txt")!!, fs.rootDocId, target, "a.txt")
        assertNull(folder.docId("a.txt"))
        assertEquals("A", folder.read("x/y/a.txt"))
        fs.delete(folder.docId("x")!!)
        assertTrue(fs.listTree().isEmpty())
    }

    @Test fun listingSkipsTheSyncTrash() {
        folder.write("${LocalFs.TRASH_DIR}/2026-01-01_000000/old.txt", "old")
        folder.write("keep.txt", "k")
        assertEquals(setOf("keep.txt"), fs.listTree().keys)
    }

    @Test fun goneDocumentsAndRoot() {
        val id = folder.write("a.txt", "A")
        fs.delete(id)
        assertNull(fs.stat(id, "a.txt"))
        fs.checkRootAccessible()
    }
}
