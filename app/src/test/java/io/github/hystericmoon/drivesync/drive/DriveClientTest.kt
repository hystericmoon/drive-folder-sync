package io.github.hystericmoon.drivesync.drive

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The real Drive REST client against a scripted fake server: paging, token refresh, retries,
 * checksums and resumable uploads, including the awkward responses Google really sends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DriveClientTest {
    private val server = MockWebServer()
    private val tokens = ArrayDeque(listOf("t1", "t2", "t3"))
    private val invalidated = mutableListOf<String>()
    private var currentToken = "t1"

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.shutdown()

    private fun client(chunkSize: Int = 1024) = DriveClient(
        token = { currentToken },
        invalidateToken = { invalidated += it; currentToken = tokens.removeFirst().let { tokens.firstOrNull() ?: it } },
        apiBase = server.url("/drive/v3/").toString(),
        uploadBase = server.url("/upload/drive/v3/").toString(),
        chunkSize = chunkSize,
        backoffMs = { 0 },
    )

    private fun fileJson(id: String, name: String = "f.txt", md5: String? = null, parent: String = "p", trashed: Boolean = false) =
        JSONObject().put("id", id).put("name", name).put("mimeType", "text/plain")
            .put("parents", JSONArray().put(parent)).put("trashed", trashed).put("size", "10")
            .apply { if (md5 != null) put("md5Checksum", md5) }

    private fun ok(json: JSONObject) = MockResponse().setBody(json.toString())
    private fun status(code: Int, body: String = "") = MockResponse().setResponseCode(code).setBody(body)

    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---- metadata ----

    @Test fun getFileSendsTheTokenAndParsesTheFile() = runBlocking {
        server.enqueue(ok(fileJson("abc", name = "x.pdf", md5 = "ff")))
        val f = client().getFile("abc")!!
        assertEquals("x.pdf", f.name)
        assertEquals("ff", f.md5)
        assertEquals("p", f.parentId)
        val r = server.takeRequest()
        assertEquals("Bearer t1", r.getHeader("Authorization"))
        assertEquals("/drive/v3/files/abc", r.requestUrl!!.encodedPath)
    }

    @Test fun missingFileIsNull() = runBlocking {
        server.enqueue(status(404, """{"error":{"code":404}}"""))
        assertNull(client().getFile("gone"))
    }

    @Test fun listChildrenFollowsEveryPage() = runBlocking {
        server.enqueue(ok(JSONObject().put("files", JSONArray().put(fileJson("1")).put(fileJson("2"))).put("nextPageToken", "pg2")))
        server.enqueue(ok(JSONObject().put("files", JSONArray().put(fileJson("3")))))
        val ids = client().listChildren("fold'er", foldersOnly = false).map { it.id }
        assertEquals(listOf("1", "2", "3"), ids)
        val first = server.takeRequest().requestUrl!!
        assertEquals("'fold\\'er' in parents and trashed = false", first.queryParameter("q"))
        assertNull(first.queryParameter("pageToken"))
        assertEquals("pg2", server.takeRequest().requestUrl!!.queryParameter("pageToken"))
    }

    @Test fun listChildrenCanAskForFoldersOnly() = runBlocking {
        server.enqueue(ok(JSONObject().put("files", JSONArray())))
        client().listChildren("f", foldersOnly = true)
        assertTrue(server.takeRequest().requestUrl!!.queryParameter("q")!!.endsWith("and mimeType = '${DriveFile.FOLDER_MIME}'"))
    }

    @Test fun listChangesFollowsEveryPageAndReturnsTheNewToken() = runBlocking {
        server.enqueue(
            ok(
                JSONObject().put("nextPageToken", "p2").put(
                    "changes",
                    JSONArray().put(JSONObject().put("fileId", "a").put("removed", false).put("file", fileJson("a"))),
                ),
            ),
        )
        server.enqueue(
            ok(
                JSONObject().put("newStartPageToken", "next-token").put(
                    "changes",
                    JSONArray().put(JSONObject().put("fileId", "b").put("removed", true)),
                ),
            ),
        )
        val page = client().listChanges("start")
        assertEquals("next-token", page.newStartPageToken)
        assertEquals(listOf("a", "b"), page.changes.map { it.fileId })
        assertTrue(page.changes[1].removed)
        assertNull(page.changes[1].file)
        assertEquals("start", server.takeRequest().requestUrl!!.queryParameter("pageToken"))
        assertEquals("p2", server.takeRequest().requestUrl!!.queryParameter("pageToken"))
    }

    @Test fun rejectedChangesTokenMeansFullScan() {
        for (code in listOf(400, 404, 410)) {
            server.enqueue(status(code))
            assertThrows(InvalidPageTokenException::class.java) { runBlocking { client().listChanges("old") } }
        }
    }

    @Test fun trashIsAPatchNeverADelete() = runBlocking {
        server.enqueue(ok(fileJson("x", trashed = true)))
        assertTrue(client().trash("x").trashed)
        val r = server.takeRequest()
        assertEquals("PATCH", r.method)
        assertEquals(JSONObject().put("trashed", true).toString(), r.body.readUtf8())
    }

    @Test fun renameAndCreateFolder() = runBlocking {
        server.enqueue(ok(fileJson("x", name = "new.txt")))
        client().rename("x", "new.txt")
        assertEquals("""{"name":"new.txt"}""", server.takeRequest().body.readUtf8())
        server.enqueue(ok(fileJson("d", name = "Dir").put("mimeType", DriveFile.FOLDER_MIME)))
        assertTrue(client().createFolder("Dir", "parent").isFolder)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        val body = JSONObject(r.body.readUtf8())
        assertEquals(DriveFile.FOLDER_MIME, body.getString("mimeType"))
        assertEquals("parent", body.getJSONArray("parents").getString(0))
    }

    // ---- auth and retries ----

    @Test fun expiredTokenIsRefreshedOnce() = runBlocking {
        server.enqueue(status(401))
        server.enqueue(ok(fileJson("a")))
        client().getFile("a")
        assertEquals(listOf("t1"), invalidated)
        assertEquals("Bearer t1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer t2", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun secondRejectedTokenFails() {
        server.enqueue(status(401))
        server.enqueue(status(401))
        val e = assertThrows(DriveHttpException::class.java) { runBlocking { client().getFile("a") } }
        assertEquals(401, e.code)
        assertEquals(1, invalidated.size)
    }

    @Test fun rateLimitsAndServerErrorsAreRetried() = runBlocking {
        server.enqueue(status(429))
        server.enqueue(status(503))
        server.enqueue(status(403, """{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}"""))
        server.enqueue(ok(fileJson("a")))
        assertEquals("a", client().getFile("a")!!.id)
        assertEquals(4, server.requestCount)
    }

    @Test fun givesUpAfterFiveRetries() {
        repeat(10) { server.enqueue(status(500)) }
        val e = assertThrows(DriveHttpException::class.java) { runBlocking { client().getFile("a") } }
        assertEquals(500, e.code)
        assertEquals(6, server.requestCount)
    }

    @Test fun otherForbiddenErrorsFailAtOnce() {
        server.enqueue(status(403, """{"error":{"errors":[{"reason":"insufficientFilePermissions"}]}}"""))
        val e = assertThrows(DriveHttpException::class.java) { runBlocking { client().getFile("a") } }
        assertEquals(403, e.code)
        assertEquals(1, server.requestCount)
    }

    // ---- download ----

    @Test fun downloadWritesTheFileAndChecksTheChecksum() = runBlocking {
        val dest = File.createTempFile("download", null)
        server.enqueue(MockResponse().setBody("hello"))
        assertEquals(md5("hello".toByteArray()), client().download("a", md5("hello".toByteArray()).uppercase(), dest))
        assertEquals("hello", dest.readText())
        assertEquals("media", server.takeRequest().requestUrl!!.queryParameter("alt"))
    }

    @Test fun downloadWithWrongChecksumFails() {
        val dest = File.createTempFile("download", null)
        server.enqueue(MockResponse().setBody("changed meanwhile"))
        assertThrows(IOException::class.java) { runBlocking { client().download("a", md5("hello".toByteArray()), dest) } }
    }

    // ---- resumable upload ----

    /** A Drive upload endpoint that stores what it receives and can be told to misbehave per chunk. */
    private inner class UploadServer(private val total: ByteArray, val misbehave: (start: Long, attempt: Int) -> MockResponse? = { _, _ -> null }) : Dispatcher() {
        val stored = java.io.ByteArrayOutputStream()
        val sessionRequests = mutableListOf<RecordedRequest>()
        val chunkRanges = mutableListOf<String>()
        private val attempts = HashMap<Long, Int>()
        var md5Reply: String = md5(total)

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl!!.encodedPath
            if (path.startsWith("/upload/")) {
                sessionRequests += request
                return MockResponse().setHeader("Location", server.url("/session/1").toString())
            }
            val range = request.getHeader("Content-Range")!!
            chunkRanges += range
            val have = stored.size().toLong()
            if (range.startsWith("bytes */")) { // status query
                return if (have == total.size.toLong()) done() else rangeReply(have)
            }
            val start = range.removePrefix("bytes ").substringBefore('-').toLong()
            val attempt = attempts.merge(start, 1, Int::plus)!!
            misbehave(start, attempt)?.let { return it }
            check(start == have) { "client sent $start but server has $have" }
            stored.write(request.body.readByteArray())
            return if (stored.size() == total.size) done() else rangeReply(stored.size().toLong())
        }

        fun keepOnly(n: Int) {
            val kept = stored.toByteArray().copyOf(n)
            stored.reset(); stored.write(kept)
        }

        private fun rangeReply(have: Long) =
            MockResponse().setResponseCode(308).apply { if (have > 0) setHeader("Range", "bytes=0-${have - 1}") }

        private fun done() = ok(fileJson("new-id", name = "f.txt", md5 = md5Reply))
    }

    private suspend fun DriveClient.uploadBytes(bytes: ByteArray, existingId: String? = null) = upload(
        name = "f.txt", parentId = "parent", existingId = existingId, mimeType = "text/plain",
        size = bytes.size.toLong(), open = { ByteArrayInputStream(bytes) },
    )

    @Test fun newFileUploadsInOneChunk() = runBlocking {
        val data = "0123456789".toByteArray()
        val up = UploadServer(data).also { server.dispatcher = it }
        val file = client().uploadBytes(data)
        assertEquals("new-id", file.id)
        val session = up.sessionRequests.single()
        assertEquals("POST", session.method)
        assertEquals("resumable", session.requestUrl!!.queryParameter("uploadType"))
        assertEquals("10", session.getHeader("X-Upload-Content-Length"))
        assertEquals("parent", JSONObject(session.body.readUtf8()).getJSONArray("parents").getString(0))
        assertEquals(listOf("bytes 0-9/10"), up.chunkRanges)
        assertEquals("0123456789", up.stored.toString())
    }

    @Test fun replacingAFileUsesPatchWithoutMovingIt() = runBlocking {
        val data = "abc".toByteArray()
        val up = UploadServer(data).also { server.dispatcher = it }
        client().uploadBytes(data, existingId = "old-id")
        val session = up.sessionRequests.single()
        assertEquals("PATCH", session.method)
        assertEquals("/upload/drive/v3/files/old-id", session.requestUrl!!.encodedPath)
        assertFalse(JSONObject(session.body.readUtf8()).has("parents"))
    }

    @Test fun uploadResumesAfterAFailedChunkFromWhatDriveKept() = runBlocking {
        val data = "0123456789".toByteArray()
        lateinit var up: UploadServer
        up = UploadServer(data) { start, attempt ->
            if (start == 4L && attempt == 1) {
                // The connection broke mid-chunk: Drive kept "45" of "4567", then the request failed.
                up.stored.write("45".toByteArray())
                status(503)
            } else null
        }
        server.dispatcher = up
        val file = client(chunkSize = 4).uploadBytes(data)
        assertEquals("new-id", file.id)
        assertEquals("0123456789", up.stored.toString())
        assertEquals(listOf("bytes 0-3/10", "bytes 4-7/10", "bytes */10", "bytes 6-9/10"), up.chunkRanges)
    }

    @Test fun uploadResendsWhenDriveKeptLessThanAChunk() = runBlocking {
        val data = "0123456789".toByteArray()
        lateinit var up: UploadServer
        up = UploadServer(data) { start, attempt ->
            if (start == 4L && attempt == 1) {
                up.stored.write("4".toByteArray())
                MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-4")
            } else null
        }
        server.dispatcher = up
        client(chunkSize = 4).uploadBytes(data)
        assertEquals("0123456789", up.stored.toString())
        assertEquals(listOf("bytes 0-3/10", "bytes 4-7/10", "bytes 5-8/10", "bytes 9-9/10"), up.chunkRanges)
    }

    @Test fun uploadSurvivesAConnectionDropMidChunk() = runBlocking {
        val data = "0123456789".toByteArray()
        val up = UploadServer(data) { start, attempt ->
            // The connection drops after the chunk is sent, before Drive stores or answers it.
            if (start == 4L && attempt == 1) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else null
        }
        server.dispatcher = up
        val result = runCatching { client(chunkSize = 4).uploadBytes(data) }
        assertEquals("ranges=${up.chunkRanges} sessions=${up.sessionRequests.size} err=${result.exceptionOrNull()}", "new-id", result.getOrNull()?.id)
        assertEquals("0123456789", up.stored.toString())
    }

    @Test fun uploadThatDriveFinishedDuringAFailureIsNotSentAgain() = runBlocking {
        val data = "0123".toByteArray()
        lateinit var up: UploadServer
        up = UploadServer(data) { _, attempt ->
            if (attempt == 1) { up.stored.write(data); status(502) } else null // stored it, then the reply was lost
        }
        server.dispatcher = up
        assertEquals("new-id", client(chunkSize = 4).uploadBytes(data).id)
        assertEquals("0123", up.stored.toString())
    }

    @Test fun uploadChecksumMismatchFails() {
        val data = "0123456789".toByteArray()
        server.dispatcher = UploadServer(data).apply { md5Reply = md5("something else".toByteArray()) }
        val e = assertThrows(IOException::class.java) { runBlocking { client(chunkSize = 4).uploadBytes(data) } }
        assertTrue(e.message!!.contains("checksum", ignoreCase = true))
    }

    @Test fun expiredUploadSessionFails() {
        val data = "0123".toByteArray()
        server.dispatcher = UploadServer(data) { _, _ -> status(410) }
        val e = assertThrows(IOException::class.java) { runBlocking { client().uploadBytes(data) } }
        assertTrue(e.message!!.contains("expired"))
    }

    @Test fun rejectedUploadFailsWithoutRetrying() {
        val data = "0123".toByteArray()
        val up = UploadServer(data) { _, _ -> status(400, "bad") }
        server.dispatcher = up
        assertThrows(DriveHttpException::class.java) { runBlocking { client().uploadBytes(data) } }
        assertEquals(1, up.chunkRanges.size)
    }

    @Test fun fileThatShrankDuringUploadFails() {
        val data = "0123456789".toByteArray()
        server.dispatcher = UploadServer(data)
        assertThrows(IOException::class.java) {
            runBlocking {
                client(chunkSize = 4).upload(
                    name = "f.txt", parentId = "p", existingId = null, mimeType = "text/plain",
                    size = 10, open = { ByteArrayInputStream("01234".toByteArray()) },
                )
            }
        }
    }

    @Test fun emptyFileUploads() = runBlocking {
        val up = UploadServer(ByteArray(0)).also { server.dispatcher = it }
        client().uploadBytes(ByteArray(0))
        assertEquals(listOf("bytes */0"), up.chunkRanges)
    }
}
