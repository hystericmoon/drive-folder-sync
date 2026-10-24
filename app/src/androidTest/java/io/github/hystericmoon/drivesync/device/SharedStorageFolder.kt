package io.github.hystericmoon.drivesync.device

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hystericmoon.drivesync.local.SafLocalFs
import org.junit.rules.ExternalResource

/**
 * A fresh folder in the emulator's shared storage (Download/), reached through Android's real
 * ExternalStorageProvider, the same provider the app gets from the folder picker on the phone.
 *
 * The picker can't be driven reliably from a test, so the test borrows the shell's permissions
 * (which include MANAGE_DOCUMENTS) instead of a user grant. Everything else is the real code path.
 */
class SharedStorageFolder : ExternalResource() {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    private val resolver = context.contentResolver

    lateinit var treeUri: Uri private set
    lateinit var fs: SafLocalFs private set
    private lateinit var folderUri: Uri

    override fun before() {
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
        val download = DocumentsContract.buildDocumentUri(AUTHORITY, "primary:Download")
        folderUri = DocumentsContract.createDocument(resolver, download, Document.MIME_TYPE_DIR, "drivesync-test-${System.nanoTime()}")
            ?: error("could not create a test folder in Download/")
        treeUri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, DocumentsContract.getDocumentId(folderUri))
        fs = SafLocalFs(context, treeUri)
    }

    override fun after() {
        runCatching { DocumentsContract.deleteDocument(resolver, folderUri) }
        instrumentation.uiAutomation.dropShellPermissionIdentity()
    }

    // ---- helpers acting "as the user", through the same provider ----

    fun write(path: String, text: String): String {
        val parent = mkdirs(path.substringBeforeLast('/', ""))
        val name = path.substringAfterLast('/')
        val docId = fs.findChild(parent, name)?.docId ?: fs.createFile(parent, name)
        fs.openOutput(docId).use { it.write(text.toByteArray()) }
        return docId
    }

    fun mkdirs(path: String): String {
        var current = fs.rootDocId
        if (path.isEmpty()) return current
        for (segment in path.split('/')) {
            current = fs.findChild(current, segment)?.docId ?: fs.createDir(current, segment)
        }
        return current
    }

    fun docId(path: String): String? {
        var current = fs.rootDocId
        for (segment in path.split('/')) current = fs.findChild(current, segment)?.docId ?: return null
        return current
    }

    fun read(path: String): String? = docId(path)?.let { id -> fs.openInput(id).use { String(it.readBytes()) } }

    fun remove(path: String) = fs.delete(requireNotNull(docId(path)) { "no $path" })

    /** Every file below the root (including .sync-trash): path → text. */
    fun allFiles(): Map<String, String> {
        val out = sortedMapOf<String, String>()
        fun walk(id: String, prefix: String) {
            for (c in fs.children(id, prefix)) {
                if (c.isDir) walk(c.docId, c.path) else out[c.path] = fs.openInput(c.docId).use { String(it.readBytes()) }
            }
        }
        walk(fs.rootDocId, "")
        return out
    }

    companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
    }
}
