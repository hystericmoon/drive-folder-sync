package io.github.hystericmoon.drivesync.sync.fakes

import io.github.hystericmoon.drivesync.drive.ChangePage
import io.github.hystericmoon.drivesync.drive.DriveApi
import io.github.hystericmoon.drivesync.drive.DriveChange
import io.github.hystericmoon.drivesync.drive.DriveFile
import io.github.hystericmoon.drivesync.drive.DriveHttpException
import io.github.hystericmoon.drivesync.drive.InvalidPageTokenException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * In-memory Google Drive (My Drive). Like the real thing it is id/parent based, allows duplicate
 * names in one folder, keeps trashed files (no permanent delete exists), and records every change
 * in a feed that [listChanges] replays, including changes made by the engine itself.
 *
 * Test code edits it "as the user in the Drive app" through the path helpers, and can inject
 * failures into individual uploads and downloads.
 */
class FakeDrive : DriveApi {
    class Item(
        val id: String,
        var name: String,
        val isFolder: Boolean,
        var parentId: String?,
        var content: ByteArray = ByteArray(0),
        var mimeType: String = if (isFolder) DriveFile.FOLDER_MIME else "application/octet-stream",
        var trashed: Boolean = false,
        var modified: Long = 0,
    )

    val items = LinkedHashMap<String, Item>()
    private var nextId = 1
    private var tick = 0L
    /** File ids, one per change. A page token is an index into this list. */
    private val feed = mutableListOf<String>()
    /** Tokens below this are "expired" (HTTP 410), like a token Drive no longer accepts. */
    private var oldestValidToken = 0

    val myDriveId = "my-drive"
    val pairRootId: String

    // ---- failure injection ----

    /** Upload of a file with this name fails before Drive stores anything. */
    var failUpload: (name: String) -> Boolean = { false }
    /** Upload is committed in Drive but the response is lost (connection drop after the last byte). */
    var loseUploadResponse: (name: String) -> Boolean = { false }
    /** Download of a file with this name fails. */
    var failDownload: (name: String) -> Boolean = { false }

    // ---- call counters, to check which path the engine took ----

    var listChangesCalls = 0
    /** Every full scan starts with this call; the Changes path never makes it. */
    var startPageTokenCalls = 0

    init {
        items[myDriveId] = Item(myDriveId, "My Drive", isFolder = true, parentId = null)
        pairRootId = newItem("Sync", isFolder = true, parentId = myDriveId).id
    }

    // ---- DriveApi ----

    override suspend fun getFile(id: String): DriveFile? = items[id]?.toDriveFile()

    override suspend fun listChildren(folderId: String, foldersOnly: Boolean): List<DriveFile> {
        return items.values
            .filter { it.parentId == folderId && !isTrashed(it) && (!foldersOnly || it.isFolder) }
            .sortedBy { it.name }
            .map { it.toDriveFile() }
    }

    override suspend fun getStartPageToken(): String {
        startPageTokenCalls++
        return feed.size.toString()
    }

    override suspend fun listChanges(startToken: String): ChangePage {
        listChangesCalls++
        val start = startToken.toIntOrNull()
        if (start == null || start < oldestValidToken || start > feed.size) throw InvalidPageTokenException()
        val changes = feed.subList(start, feed.size).distinct().map { id ->
            DriveChange(fileId = id, removed = false, file = items.getValue(id).toDriveFile())
        }
        return ChangePage(changes, feed.size.toString())
    }

    override suspend fun createFolder(name: String, parentId: String): DriveFile =
        newItem(name, isFolder = true, parentId = parentId).toDriveFile()

    override suspend fun rename(id: String, newName: String): DriveFile {
        val item = existing(id)
        item.name = newName
        touch(item)
        return item.toDriveFile()
    }

    override suspend fun trash(id: String): DriveFile {
        val item = existing(id)
        item.trashed = true
        touch(item)
        return item.toDriveFile()
    }

    override suspend fun download(id: String, expectedMd5: String?, dest: File): String {
        val item = existing(id)
        if (failDownload(item.name)) throw IOException("simulated network error downloading ${item.name}")
        dest.writeBytes(item.content)
        val md5 = md5(item.content)
        if (expectedMd5 != null && !md5.equals(expectedMd5, ignoreCase = true)) {
            throw IOException("Checksum mismatch downloading $id (file changed during download?)")
        }
        return md5
    }

    override suspend fun upload(
        name: String,
        parentId: String?,
        existingId: String?,
        mimeType: String,
        size: Long,
        open: () -> InputStream,
        onProgress: (Long) -> Unit,
    ): DriveFile {
        val bytes = open().use { it.readBytes() }
        if (bytes.size.toLong() != size) throw IOException("Local file changed size during upload")
        if (failUpload(name)) throw IOException("simulated network error uploading $name")
        val item = if (existingId == null) {
            newItem(name, isFolder = false, parentId = parentId, content = bytes, mimeType = mimeType)
        } else {
            existing(existingId).also {
                it.name = name
                it.content = bytes
                touch(it)
            }
        }
        if (loseUploadResponse(name)) throw IOException("simulated connection reset after upload of $name")
        return item.toDriveFile()
    }

    // ---- test-side helpers: what a person does in the Drive app ----

    /** Creates or overwrites a file at [path] under the pair root, creating folders as needed. */
    fun write(path: String, text: String): String {
        val existing = find(path)
        if (existing != null) {
            existing.content = text.toByteArray()
            touch(existing)
            return existing.id
        }
        return newItem(nameOf(path), isFolder = false, parentId = mkdirs(parentOf(path)), content = text.toByteArray()).id
    }

    /** Adds a file next to whatever is already there, even if the name is taken (Drive allows that). */
    fun addDuplicate(path: String, text: String): String =
        newItem(nameOf(path), isFolder = false, parentId = mkdirs(parentOf(path)), content = text.toByteArray()).id

    fun mkdirs(path: String): String {
        if (path.isEmpty()) return pairRootId
        find(path)?.let { require(it.isFolder) { "$path is a file" }; return it.id }
        return newItem(nameOf(path), isFolder = true, parentId = mkdirs(parentOf(path))).id
    }

    /** Creates a folder outside the pair (elsewhere in My Drive). */
    fun mkdirOutside(name: String): String = newItem(name, isFolder = true, parentId = myDriveId).id

    fun mkdirIn(parentId: String, name: String): String = newItem(name, isFolder = true, parentId = parentId).id

    fun writeIn(parentId: String, name: String, text: String): String =
        newItem(name, isFolder = false, parentId = parentId, content = text.toByteArray()).id

    fun trashPath(path: String) {
        val item = requireNotNull(find(path)) { "no $path in Drive" }
        item.trashed = true
        touch(item)
    }

    fun renamePath(path: String, newName: String) {
        val item = requireNotNull(find(path)) { "no $path in Drive" }
        item.name = newName
        touch(item)
    }

    fun moveItem(id: String, newParentId: String) {
        val item = existing(id)
        item.parentId = newParentId
        touch(item)
    }

    fun id(path: String): String = requireNotNull(find(path)) { "no $path in Drive" }.id

    /** Makes every saved Changes token invalid, as Drive does after a long time. */
    fun expireTokens() { oldestValidToken = feed.size }

    /** Non-trashed files under the pair root: path → text. Duplicate names get " #id" appended. */
    fun files(): Map<String, String> = walk().filterValues { !it.isFolder }.mapValues { String(it.value.content) }

    fun folders(): Set<String> = walk().filterValues { it.isFolder }.keys

    fun trashedNames(): List<String> = items.values.filter { it.trashed }.map { it.name }

    fun item(id: String): Item = existing(id)

    // ---- internals ----

    private fun walk(): Map<String, Item> {
        val out = LinkedHashMap<String, Item>()
        fun visit(folderId: String, prefix: String) {
            val children = items.values.filter { it.parentId == folderId && !it.trashed }
            val counts = children.groupingBy { it.name }.eachCount()
            for (child in children) {
                val name = if (counts.getValue(child.name) > 1) "${child.name} #${child.id}" else child.name
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                out[path] = child
                if (child.isFolder) visit(child.id, path)
            }
        }
        visit(pairRootId, "")
        return out
    }

    private fun find(path: String): Item? {
        var current = pairRootId
        var found: Item? = null
        for (segment in path.split('/')) {
            found = items.values.firstOrNull { it.parentId == current && it.name == segment && !it.trashed } ?: return null
            current = found.id
        }
        return found
    }

    private fun isTrashed(item: Item): Boolean {
        var cur: Item? = item
        while (cur != null) {
            if (cur.trashed) return true
            cur = cur.parentId?.let { items[it] }
        }
        return false
    }

    private fun existing(id: String): Item = items[id] ?: throw DriveHttpException(404, "File not found: $id")

    private fun newItem(
        name: String,
        isFolder: Boolean,
        parentId: String?,
        content: ByteArray = ByteArray(0),
        mimeType: String? = null,
    ): Item {
        val item = Item("d${nextId++}", name, isFolder, parentId, content)
        if (mimeType != null && !isFolder) item.mimeType = mimeType
        items[item.id] = item
        touch(item)
        return item
    }

    private fun touch(item: Item) {
        item.modified = ++tick
        feed += item.id
    }

    private fun Item.toDriveFile() = DriveFile(
        id = id,
        name = name,
        mimeType = mimeType,
        parentId = parentId,
        md5 = if (isFolder || mimeType.startsWith("application/vnd.google-apps.")) null else md5(content),
        modifiedTime = "2026-01-01T00:00:%06dZ".format(modified),
        size = if (isFolder) 0 else content.size.toLong(),
        // Drive reports descendants of a trashed folder as trashed too.
        trashed = isTrashed(this),
    )

    companion object {
        fun md5(bytes: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun parentOf(path: String) = path.substringBeforeLast('/', "")
        private fun nameOf(path: String) = path.substringAfterLast('/')
    }
}
