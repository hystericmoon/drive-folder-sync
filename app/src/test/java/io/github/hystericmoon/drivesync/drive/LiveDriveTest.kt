package io.github.hystericmoon.drivesync.drive

import android.app.Application
import androidx.room.Room
import io.github.hystericmoon.drivesync.data.AppDatabase
import io.github.hystericmoon.drivesync.data.FolderPair
import io.github.hystericmoon.drivesync.sync.SyncEngine
import io.github.hystericmoon.drivesync.sync.fakes.FakeLocalFs
import io.github.hystericmoon.drivesync.sync.fakes.MutableClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import kotlin.random.Random

/**
 * The real DriveClient and SyncEngine against the real Google Drive of a throwaway test account.
 * Catches Google changing behaviour under the fakes. Skipped unless CI has the three secrets
 * (see SETUP.md, "Optional: live Drive tests"). Everything it creates goes to that account's trash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LiveDriveTest {
    private val clientId = System.getenv("DRIVE_TEST_CLIENT_ID").orEmpty()
    private val clientSecret = System.getenv("DRIVE_TEST_CLIENT_SECRET").orEmpty()
    private val refreshToken = System.getenv("DRIVE_TEST_REFRESH_TOKEN").orEmpty()
    private var accessToken: String? = null
    private lateinit var drive: DriveClient
    private lateinit var folderId: String

    @Before fun setUp() = runBlocking {
        assumeTrue("live Drive secrets not set", clientId.isNotEmpty() && clientSecret.isNotEmpty() && refreshToken.isNotEmpty())
        drive = DriveClient(
            token = { accessToken ?: fetchToken().also { accessToken = it } },
            invalidateToken = { accessToken = null },
            apiBase = "https://www.googleapis.com/drive/v3/",
            uploadBase = "https://www.googleapis.com/upload/drive/v3/",
            chunkSize = 256 * 1024, // smallest Drive accepts, so a 700 KB file takes several chunks
            backoffMs = { attempt -> 1000L * attempt },
        )
        folderId = drive.createFolder("drivesync-live-test-${System.currentTimeMillis()}", "root").id
    }

    @After fun tearDown() = runBlocking {
        if (::folderId.isInitialized) runCatching { drive.trash(folderId) }
        Unit
    }

    private fun fetchToken(): String {
        val body = FormBody.Builder()
            .add("client_id", clientId).add("client_secret", clientSecret)
            .add("refresh_token", refreshToken).add("grant_type", "refresh_token")
            .build()
        OkHttpClient().newCall(Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build()).execute().use {
            check(it.isSuccessful) { "token refresh failed: HTTP ${it.code} ${it.body?.string()}" }
            return JSONObject(it.body!!.string()).getString("access_token")
        }
    }

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    private suspend fun DriveClient.put(name: String, bytes: ByteArray, parent: String = folderId, existing: String? = null) =
        upload(name, parent, existing, "application/octet-stream", bytes.size.toLong(), { ByteArrayInputStream(bytes) })

    /** Drive's change feed is eventually consistent; poll a little before failing. */
    private suspend fun changesInclude(token: String, ids: Set<String>): Boolean {
        repeat(15) {
            if (drive.listChanges(token).changes.map { it.fileId }.containsAll(ids)) return true
            delay(2_000)
        }
        return false
    }

    @Test fun clientContract() = runBlocking {
        val token = drive.getStartPageToken()
        val small = drive.put("small.txt", "hello".toByteArray())
        assertEquals(md5("hello".toByteArray()), small.md5)
        assertEquals(folderId, small.parentId)

        val big = Random(1).nextBytes(700 * 1024)
        val bigFile = drive.put("big.bin", big)
        assertEquals("multi-chunk resumable upload", md5(big), bigFile.md5)

        val replaced = drive.put("small.txt", "hello again".toByteArray(), existing = small.id)
        assertEquals(small.id, replaced.id)
        assertEquals(md5("hello again".toByteArray()), replaced.md5)

        val renamed = drive.rename(small.id, "renamed.txt")
        assertEquals("renamed.txt", renamed.name)
        assertEquals(setOf("renamed.txt", "big.bin"), drive.listChildren(folderId).map { it.name }.toSet())
        assertEquals(bigFile.id, drive.findChild(folderId, "big.bin")?.id)

        val dest = File.createTempFile("download", null)
        assertEquals(md5(big), drive.download(bigFile.id, bigFile.md5, dest))
        assertEquals(big.size.toLong(), dest.length())

        assertTrue("changes feed lists our files", changesInclude(token, setOf(small.id, bigFile.id)))

        assertTrue(drive.trash(small.id).trashed)
        assertTrue(drive.getFile(small.id)!!.trashed)
        assertNull("trashed files aren't listed", drive.findChild(folderId, "renamed.txt"))
        assertNull(drive.getFile("no-such-file-id-${System.nanoTime()}"))
    }

    @Test fun expiredTokenIsRefreshed() = runBlocking {
        accessToken = "definitely-not-valid"
        assertNotNull(drive.getFile(folderId))
    }

    @Test fun syncEngineAgainstRealDrive() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val phone = FakeLocalFs()
        val clock = MutableClock()
        val engine = SyncEngine(context, db, newDrive = { drive }, openLocal = { phone }, clock = clock)
        val pairId = db.pairs().insert(
            FolderPair(localTreeUri = "content://live", localName = "Live", driveFolderId = folderId, driveFolderName = "live"),
        )
        // Full scans only: listing is immediately consistent, the change feed may lag a few seconds.
        suspend fun sync() {
            clock.advance(Duration.ofHours(25))
            engine.sync(pairId)
            assertNull(db.pairs().get(pairId)!!.lastError)
        }
        try {
            phone.write("docs/from-phone.txt", "phone")
            val sub = drive.createFolder("photos", folderId).id
            drive.put("from-drive.jpg", "jpeg".toByteArray(), parent = sub)
            sync()
            assertEquals("jpeg", phone.read("photos/from-drive.jpg"))
            val docs = drive.findChild(folderId, "docs")!!
            assertEquals(md5("phone".toByteArray()), drive.findChild(docs.id, "from-phone.txt")!!.md5)

            // Conflict: both edited.
            phone.write("docs/from-phone.txt", "phone v2")
            drive.put("from-phone.txt", "drive v2".toByteArray(), existing = drive.findChild(docs.id, "from-phone.txt")!!.id)
            sync()
            assertEquals("phone v2", phone.read("docs/from-phone.txt"))
            val conflict = drive.listChildren(docs.id).single { it.name.startsWith("from-phone (conflict ") }
            assertEquals(md5("drive v2".toByteArray()), conflict.md5)

            // Delete on the phone goes to Drive trash.
            val jpeg = drive.findChild(sub, "from-drive.jpg")!!
            phone.remove("photos/from-drive.jpg")
            sync()
            assertTrue(drive.getFile(jpeg.id)!!.trashed)
        } finally {
            db.close()
        }
    }
}
