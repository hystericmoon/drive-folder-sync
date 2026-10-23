package io.github.hystericmoon.drivesync.local

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import io.github.hystericmoon.drivesync.sync.LocalEntry
import java.security.MessageDigest

/**
 * The phone side of a pair, as the sync engine sees it. [SafLocalFs] is the real implementation;
 * the engine tests use an in-memory fake.
 */
interface LocalFs {
    val rootDocId: String

    fun hasPermission(): Boolean

    /** Throws if the tree is gone (SD card removed, folder deleted, permission revoked). */
    fun checkRootAccessible() {
        val root = stat(rootDocId, "") ?: throw FileNotFoundException("Local folder is not accessible")
        if (!root.isDir) throw IOException("Local root is not a folder")
    }

    /** Recursive listing keyed by relative path. Skips the trash folder and Android's transient files. */
    fun listTree(): Map<String, LocalEntry> {
        val out = LinkedHashMap<String, LocalEntry>()
        fun walk(parentDocId: String, prefix: String) {
            for (child in children(parentDocId, prefix)) {
                if (prefix.isEmpty() && child.name == TRASH_DIR) continue
                if (isIgnoredName(child.name)) continue
                out[child.path] = child
                if (child.isDir) walk(child.docId, child.path)
            }
        }
        walk(rootDocId, "")
        return out
    }

    fun children(parentDocId: String, parentPath: String): List<LocalEntry>

    /** The document's current state with [path] filled in, or null if it's gone. */
    fun stat(docId: String, path: String): LocalEntry?

    fun findChild(parentDocId: String, name: String): LocalEntry? =
        children(parentDocId, "").firstOrNull { it.name == name }

    /** Creates a folder named exactly [name] and returns its document id, or throws. */
    fun createDir(parentDocId: String, name: String): String

    /** Creates an empty file named exactly [name] and returns its document id, or throws. */
    fun createFile(parentDocId: String, name: String): String

    fun openInput(docId: String): InputStream

    /** Write + truncate, so a shorter new version doesn't leave old bytes at the end. */
    fun openOutput(docId: String): OutputStream

    fun delete(docId: String)

    fun move(docId: String, sourceParentDocId: String, targetParentDocId: String, name: String)

    fun md5(docId: String): String {
        val digest = MessageDigest.getInstance("MD5")
        openInput(docId).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val TRASH_DIR = ".sync-trash"

        /** MediaStore's pending/trashed placeholders and our own temp files. */
        fun isIgnoredName(name: String) =
            name.startsWith(".pending-") || name.startsWith(".trashed-") || name.startsWith(".drivesync-tmp")
    }
}

/**
 * Storage Access Framework view of one picked folder tree. Talks to DocumentsContract directly
 * (DocumentFile does one query per property, which is far too slow for whole-tree listings).
 */
class SafLocalFs(context: Context, val treeUri: Uri) : LocalFs {
    private val resolver: ContentResolver = context.contentResolver
    override val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    override fun hasPermission(): Boolean = resolver.persistedUriPermissions.any {
        it.uri == treeUri && it.isReadPermission && it.isWritePermission
    }

    override fun children(parentDocId: String, parentPath: String): List<LocalEntry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val list = mutableListOf<LocalEntry>()
        resolver.query(uri, COLUMNS, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                list += LocalEntry(
                    path = if (parentPath.isEmpty()) name else "$parentPath/$name",
                    docId = c.getString(0),
                    name = name,
                    isDir = c.getString(2) == Document.MIME_TYPE_DIR,
                    size = if (c.isNull(4)) 0L else c.getLong(4),
                    lastModified = if (c.isNull(3)) 0L else c.getLong(3),
                    mimeType = c.getString(2) ?: "application/octet-stream",
                )
            }
        } ?: throw IOException("Cannot list local folder")
        return list
    }

    override fun stat(docId: String, path: String): LocalEntry? = try {
        resolver.query(docUri(docId), COLUMNS, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            LocalEntry(
                path = path,
                docId = c.getString(0),
                name = c.getString(1) ?: "",
                isDir = c.getString(2) == Document.MIME_TYPE_DIR,
                size = if (c.isNull(4)) 0L else c.getLong(4),
                lastModified = if (c.isNull(3)) 0L else c.getLong(3),
                mimeType = c.getString(2) ?: "application/octet-stream",
            )
        }
    } catch (_: Exception) {
        null
    }

    override fun createDir(parentDocId: String, name: String): String = create(parentDocId, Document.MIME_TYPE_DIR, name)

    override fun createFile(parentDocId: String, name: String): String = create(parentDocId, mimeFor(name), name)

    /**
     * Providers silently rename on collision ("a (1).txt") or append extensions. Either would make
     * the paths diverge from Drive and cause endless re-syncs, so undo and fail instead.
     */
    private fun create(parentDocId: String, mime: String, name: String): String {
        val uri = DocumentsContract.createDocument(resolver, docUri(parentDocId), mime, name)
            ?: throw IOException("Could not create $name")
        val docId = DocumentsContract.getDocumentId(uri)
        val actual = stat(docId, "")?.name
        if (actual != name) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw IOException("Provider created '$actual' instead of '$name'")
        }
        return docId
    }

    override fun openInput(docId: String): InputStream =
        resolver.openInputStream(docUri(docId)) ?: throw FileNotFoundException(docId)

    override fun openOutput(docId: String): OutputStream =
        resolver.openOutputStream(docUri(docId), "wt") ?: throw FileNotFoundException(docId)

    override fun delete(docId: String) {
        if (!DocumentsContract.deleteDocument(resolver, docUri(docId))) throw IOException("Delete failed")
    }

    fun rename(docId: String, newName: String): String {
        val uri = DocumentsContract.renameDocument(resolver, docUri(docId), newName)
            ?: throw IOException("Rename failed")
        return DocumentsContract.getDocumentId(uri)
    }

    /** Moves a document; falls back to copy + delete when the provider doesn't support move. */
    override fun move(docId: String, sourceParentDocId: String, targetParentDocId: String, name: String) {
        val moved = runCatching {
            DocumentsContract.moveDocument(resolver, docUri(docId), docUri(sourceParentDocId), docUri(targetParentDocId))
        }.getOrNull()
        if (moved != null) return
        val copyId = createFile(targetParentDocId, name)
        openInput(docId).use { input -> openOutput(copyId).use { input.copyTo(it) } }
        delete(docId)
    }

    companion object {
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
        )

        fun mimeFor(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
