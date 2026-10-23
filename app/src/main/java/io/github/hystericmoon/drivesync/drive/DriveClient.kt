package io.github.hystericmoon.drivesync.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val parentId: String?,
    val md5: String?,
    val modifiedTime: String?,
    val size: Long,
    val trashed: Boolean,
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME

    /** Google Docs/Sheets/shortcuts etc. have no binary content to sync. */
    val isSyncable: Boolean get() = isFolder || !mimeType.startsWith("application/vnd.google-apps.")

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val FIELDS = "id,name,mimeType,parents,md5Checksum,modifiedTime,size,trashed"

        fun fromJson(o: JSONObject) = DriveFile(
            id = o.getString("id"),
            name = o.optString("name"),
            mimeType = o.optString("mimeType"),
            parentId = o.optJSONArray("parents")?.optString(0),
            md5 = o.optString("md5Checksum").ifEmpty { null },
            modifiedTime = o.optString("modifiedTime").ifEmpty { null },
            size = o.optString("size").toLongOrNull() ?: 0L,
            trashed = o.optBoolean("trashed"),
        )
    }
}

data class DriveChange(val fileId: String, val removed: Boolean, val file: DriveFile?)

data class ChangePage(val changes: List<DriveChange>, val newStartPageToken: String)

class DriveHttpException(val code: Int, message: String) : IOException("Drive HTTP $code: $message")

/** Thrown when a saved Changes API token is no longer accepted; caller should fall back to a full scan. */
class InvalidPageTokenException : IOException("Changes token expired")

/**
 * The Drive side of the sync engine. [DriveClient] is the real implementation; the engine tests
 * use an in-memory fake. Nothing here deletes permanently: removal is only ever [trash].
 */
interface DriveApi {
    /** Null if the file doesn't exist (404). */
    suspend fun getFile(id: String): DriveFile?

    /** Direct, non-trashed children of a folder. */
    suspend fun listChildren(folderId: String, foldersOnly: Boolean = false): List<DriveFile>

    suspend fun getStartPageToken(): String

    /** Every change since [startToken], plus the token to save for next time. Throws [InvalidPageTokenException]. */
    suspend fun listChanges(startToken: String): ChangePage

    suspend fun createFolder(name: String, parentId: String): DriveFile

    suspend fun rename(id: String, newName: String): DriveFile

    /** Moves to Drive trash (recoverable for 30 days). */
    suspend fun trash(id: String): DriveFile

    /** Downloads to [dest] and verifies the MD5 Drive reports. Returns the MD5 of the bytes written. */
    suspend fun download(id: String, expectedMd5: String?, dest: File): String

    /**
     * Creates a new file when [existingId] is null, otherwise replaces its content.
     * [open] must return a fresh stream positioned at byte 0 each time it is called.
     */
    suspend fun upload(
        name: String,
        parentId: String?,
        existingId: String?,
        mimeType: String,
        size: Long,
        open: () -> InputStream,
        onProgress: (Long) -> Unit = {},
    ): DriveFile
}

/**
 * Minimal Drive v3 REST client. Plain OkHttp keeps the APK small and gives full control over
 * resumable uploads from content:// streams.
 */
class DriveClient(
    private val token: suspend () -> String,
    private val invalidateToken: suspend (String) -> Unit,
) : DriveApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    // ---- metadata ----

    override suspend fun getFile(id: String): DriveFile? = try {
        DriveFile.fromJson(json(get(api("files/$id").addQueryParameter("fields", DriveFile.FIELDS))))
    } catch (e: DriveHttpException) {
        if (e.code == 404) null else throw e
    }

    override suspend fun listChildren(folderId: String, foldersOnly: Boolean): List<DriveFile> {
        val out = mutableListOf<DriveFile>()
        var pageToken: String? = null
        val q = buildString {
            append("'").append(folderId.replace("'", "\\'")).append("' in parents and trashed = false")
            if (foldersOnly) append(" and mimeType = '${DriveFile.FOLDER_MIME}'")
        }
        do {
            val url = api("files")
                .addQueryParameter("q", q)
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("orderBy", "name")
                .addQueryParameter("fields", "nextPageToken,files(${DriveFile.FIELDS})")
            pageToken?.let { url.addQueryParameter("pageToken", it) }
            val o = json(get(url))
            o.optJSONArray("files")?.forEachObject { out += DriveFile.fromJson(it) }
            pageToken = o.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        return out
    }

    override suspend fun getStartPageToken(): String =
        json(get(api("changes/startPageToken"))).getString("startPageToken")

    /** Follows nextPageToken to the end; returns every change plus the token to save for next time. */
    override suspend fun listChanges(startToken: String): ChangePage {
        val out = mutableListOf<DriveChange>()
        var pageToken = startToken
        while (true) {
            val url = api("changes")
                .addQueryParameter("pageToken", pageToken)
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("includeRemoved", "true")
                .addQueryParameter("spaces", "drive")
                .addQueryParameter(
                    "fields",
                    "nextPageToken,newStartPageToken,changes(fileId,removed,file(${DriveFile.FIELDS}))",
                )
            val o = try {
                json(get(url))
            } catch (e: DriveHttpException) {
                if (e.code == 400 || e.code == 404 || e.code == 410) throw InvalidPageTokenException()
                throw e
            }
            o.optJSONArray("changes")?.forEachObject { c ->
                out += DriveChange(
                    fileId = c.getString("fileId"),
                    removed = c.optBoolean("removed"),
                    file = c.optJSONObject("file")?.let(DriveFile::fromJson),
                )
            }
            val next = o.optString("nextPageToken").ifEmpty { null }
            if (next == null) return ChangePage(out, o.getString("newStartPageToken"))
            pageToken = next
        }
    }

    override suspend fun createFolder(name: String, parentId: String): DriveFile {
        val body = JSONObject()
            .put("name", name)
            .put("mimeType", DriveFile.FOLDER_MIME)
            .put("parents", JSONArray().put(parentId))
        return DriveFile.fromJson(
            json(execute { Request.Builder().url(api("files").fields().build()).post(body.toJsonBody()) }),
        )
    }

    override suspend fun rename(id: String, newName: String): DriveFile = patch(id, JSONObject().put("name", newName))

    /** Moves to Drive trash (recoverable for 30 days). This client never deletes permanently. */
    override suspend fun trash(id: String): DriveFile = patch(id, JSONObject().put("trashed", true))

    private suspend fun patch(id: String, body: JSONObject): DriveFile = DriveFile.fromJson(
        json(execute { Request.Builder().url(api("files/$id").fields().build()).patch(body.toJsonBody()) }),
    )

    // ---- content ----

    /** Downloads to [dest] and verifies the MD5 Drive reports. Returns the MD5 of the bytes written. */
    override suspend fun download(id: String, expectedMd5: String?, dest: File): String = withContext(Dispatchers.IO) {
        val url = api("files/$id").addQueryParameter("alt", "media").build()
        execute { Request.Builder().url(url).get() }.use { resp ->
            val digest = MessageDigest.getInstance("MD5")
            dest.outputStream().use { out ->
                resp.body!!.byteStream().use { input -> input.copyWithDigest(out, digest) }
            }
            val md5 = digest.hex()
            if (expectedMd5 != null && !md5.equals(expectedMd5, ignoreCase = true)) {
                throw IOException("Checksum mismatch downloading $id (file changed during download?)")
            }
            md5
        }
    }

    /**
     * Resumable upload. Creates a new file when [existingId] is null, otherwise replaces its content.
     * [open] must return a fresh stream positioned at byte 0 each time it is called (used to resume).
     * Verifies Drive's MD5 against the bytes actually sent.
     */
    override suspend fun upload(
        name: String,
        parentId: String?,
        existingId: String?,
        mimeType: String,
        size: Long,
        open: () -> InputStream,
        onProgress: (Long) -> Unit,
    ): DriveFile = withContext(Dispatchers.IO) {
        val session = startUploadSession(name, parentId, existingId, mimeType, size)
        val digest = MessageDigest.getInstance("MD5")
        var offset = 0L
        var stream: InputStream? = null
        var failures = 0
        val buffer = ByteArray(CHUNK_SIZE)
        try {
            while (true) {
                if (stream == null) {
                    // (Re)open and fast-forward through the digest so the checksum covers the whole file.
                    digest.reset()
                    stream = open()
                    stream.skipThroughDigest(offset, digest, buffer)
                }
                val chunkLen = minOf(CHUNK_SIZE.toLong(), size - offset).toInt()
                val read = stream.readFully(buffer, chunkLen)
                if (read != chunkLen) throw IOException("Local file shrank during upload")
                val range = if (size == 0L) "bytes */0" else "bytes $offset-${offset + chunkLen - 1}/$size"
                val result = runCatching { putChunk(session, buffer, chunkLen, range) }
                val resp = result.getOrNull()
                when {
                    resp != null && (resp.code == 200 || resp.code == 201) -> {
                        digest.update(buffer, 0, chunkLen)
                        val file = DriveFile.fromJson(JSONObject(resp.body!!.string()))
                        val sent = digest.hex()
                        if (file.md5 != null && !file.md5.equals(sent, ignoreCase = true)) {
                            throw IOException("Upload checksum mismatch for $name")
                        }
                        return@withContext file
                    }
                    resp != null && resp.code == 308 -> {
                        val committed = resp.rangeEnd()
                        resp.close()
                        if (committed == offset + chunkLen) {
                            digest.update(buffer, 0, chunkLen)
                            offset = committed
                            failures = 0
                            onProgress(offset)
                        } else {
                            // Server kept less than we sent: resume from what it has.
                            offset = committed
                            stream.close(); stream = null
                        }
                    }
                    else -> {
                        val code = resp?.code
                        resp?.close()
                        if (code == 404 || code == 410) throw IOException("Upload session expired for $name")
                        if (code != null && code in 400..499 && code != 408 && code != 429) {
                            throw DriveHttpException(code, "upload of $name rejected")
                        }
                        if (++failures > MAX_RETRIES) {
                            throw result.exceptionOrNull() ?: DriveHttpException(code ?: -1, "upload failed")
                        }
                        delay(backoffMs(failures))
                        offset = queryUploadStatus(session, size) ?: run {
                            // Status call says it's complete: fetch the file via one more empty PUT.
                            return@withContext finishedFile(session, size)
                        }
                        stream.close(); stream = null
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            stream?.close()
        }
    }

    private suspend fun startUploadSession(
        name: String,
        parentId: String?,
        existingId: String?,
        mimeType: String,
        size: Long,
    ): String {
        val meta = JSONObject().put("name", name)
        if (existingId == null && parentId != null) meta.put("parents", JSONArray().put(parentId))
        val url = (UPLOAD_BASE + if (existingId == null) "files" else "files/$existingId").toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "resumable")
            .fields()
            .build()
        val resp = execute {
            Request.Builder().url(url)
                .header("X-Upload-Content-Type", mimeType)
                .header("X-Upload-Content-Length", size.toString())
                .method(if (existingId == null) "POST" else "PATCH", meta.toJsonBody())
        }
        return resp.use { it.header("Location") ?: throw IOException("No upload session URL") }
    }

    private suspend fun putChunk(session: String, buf: ByteArray, len: Int, range: String): Response {
        val body = object : RequestBody() {
            override fun contentType() = null
            override fun contentLength() = len.toLong()
            override fun writeTo(sink: BufferedSink) { sink.write(buf, 0, len) }
        }
        return http.newCall(
            Request.Builder().url(session).header("Content-Range", range).put(body).build(),
        ).execute()
    }

    /** Returns the next byte offset to send, or null if the upload is already complete. */
    private fun queryUploadStatus(session: String, size: Long): Long? {
        http.newCall(
            Request.Builder().url(session)
                .header("Content-Range", "bytes */$size")
                .put(ByteArray(0).toRequestBody())
                .build(),
        ).execute().use { resp ->
            return when (resp.code) {
                308 -> resp.rangeEnd()
                200, 201 -> null
                else -> throw DriveHttpException(resp.code, "upload status query failed")
            }
        }
    }

    private fun finishedFile(session: String, size: Long): DriveFile = http.newCall(
        Request.Builder().url(session)
            .header("Content-Range", "bytes */$size")
            .put(ByteArray(0).toRequestBody())
            .build(),
    ).execute().use { DriveFile.fromJson(JSONObject(it.body!!.string())) }

    // ---- plumbing ----

    private fun api(path: String): HttpUrl.Builder = (API_BASE + path).toHttpUrl().newBuilder()

    private fun HttpUrl.Builder.fields() = addQueryParameter("fields", DriveFile.FIELDS)

    private suspend fun get(url: HttpUrl.Builder): Response = execute { Request.Builder().url(url.build()).get() }

    private fun json(resp: Response): JSONObject = resp.use { JSONObject(it.body!!.string()) }

    /** Adds auth, retries 401 once with a fresh token and 429/5xx/IO errors with backoff. */
    private suspend fun execute(build: () -> Request.Builder): Response = withContext(Dispatchers.IO) {
        var attempt = 0
        var refreshedToken = false
        while (true) {
            val accessToken = token()
            val resp = try {
                http.newCall(build().header("Authorization", "Bearer $accessToken").build()).execute()
            } catch (e: IOException) {
                if (++attempt > MAX_RETRIES) throw e
                delay(backoffMs(attempt))
                continue
            }
            when {
                resp.isSuccessful -> return@withContext resp
                resp.code == 401 && !refreshedToken -> {
                    resp.close()
                    invalidateToken(accessToken)
                    refreshedToken = true
                }
                (resp.code == 429 || resp.code >= 500 || resp.isRateLimited()) && attempt < MAX_RETRIES -> {
                    resp.close()
                    delay(backoffMs(++attempt))
                }
                else -> {
                    val msg = resp.use { it.body?.string()?.take(300) ?: "" }
                    throw DriveHttpException(resp.code, msg)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun Response.isRateLimited(): Boolean =
        code == 403 && peekBody(4096).string().let { "rateLimitExceeded" in it || "userRateLimitExceeded" in it }

    companion object {
        private const val API_BASE = "https://www.googleapis.com/drive/v3/"
        private const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3/"
        private const val CHUNK_SIZE = 8 * 1024 * 1024 // must be a multiple of 256 KiB
        private const val MAX_RETRIES = 5
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun JSONObject.toJsonBody() = toString().toRequestBody(JSON)
        private fun backoffMs(attempt: Int) = (1000L shl (attempt - 1).coerceAtMost(5)) + (0..500).random()
    }
}

private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
    for (i in 0 until length()) block(getJSONObject(i))
}

/** Parses "Range: bytes=0-N" from a 308 response into the next offset (N+1); absent means 0. */
private fun Response.rangeEnd(): Long =
    header("Range")?.substringAfter('-')?.toLongOrNull()?.plus(1) ?: 0L

private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

private fun InputStream.copyWithDigest(out: java.io.OutputStream, digest: MessageDigest) {
    val buf = ByteArray(256 * 1024)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        digest.update(buf, 0, n)
        out.write(buf, 0, n)
    }
}

private fun InputStream.skipThroughDigest(count: Long, digest: MessageDigest, buf: ByteArray) {
    var left = count
    while (left > 0) {
        val n = read(buf, 0, minOf(buf.size.toLong(), left).toInt())
        if (n < 0) throw IOException("Local file shrank during upload")
        digest.update(buf, 0, n)
        left -= n
    }
}

private fun InputStream.readFully(buf: ByteArray, len: Int): Int {
    var total = 0
    while (total < len) {
        val n = read(buf, total, len - total)
        if (n < 0) break
        total += n
    }
    return total
}
