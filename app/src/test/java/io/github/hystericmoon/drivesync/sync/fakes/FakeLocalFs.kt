package io.github.hystericmoon.drivesync.sync.fakes

import io.github.hystericmoon.drivesync.local.LocalFs
import io.github.hystericmoon.drivesync.sync.LocalEntry
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * In-memory phone folder behaving like Android's external storage through SAF: names are
 * case-insensitive (a clashing create fails, as [io.github.hystericmoon.drivesync.local.SafLocalFs]
 * turns the provider's silent "a (1).txt" rename into an error), every write bumps lastModified,
 * and `delete` is permanent. Every permanent delete the engine makes is recorded in [deletes].
 */
class FakeLocalFs : LocalFs {
    class Node(
        val id: String,
        var name: String,
        var parentId: String?,
        val isDir: Boolean,
        var content: ByteArray = ByteArray(0),
        var lastModified: Long = 0,
    )

    val nodes = LinkedHashMap<String, Node>()
    private var nextId = 1
    private var tick = 1_000L

    override val rootDocId = "root"
    var permission = true

    /** Permanent deletes done through [delete]: path at the time, and whether it held anything. */
    val deletes = mutableListOf<Deleted>()
    data class Deleted(val path: String, val isDir: Boolean, val fileCount: Int)

    /** Writes to a file with this name stop with "No space left on device" after [failWriteAfterBytes] bytes. */
    var failWrite: (name: String) -> Boolean = { false }
    var failWriteAfterBytes = 3

    init {
        nodes[rootDocId] = Node(rootDocId, "", null, isDir = true)
    }

    // ---- LocalFs ----

    override fun hasPermission() = permission

    override fun children(parentDocId: String, parentPath: String): List<LocalEntry> {
        if (nodes[parentDocId]?.isDir != true) throw IOException("Cannot list local folder")
        return nodes.values.filter { it.parentId == parentDocId }.sortedBy { it.name }.map {
            it.toEntry(if (parentPath.isEmpty()) it.name else "$parentPath/${it.name}")
        }
    }

    override fun stat(docId: String, path: String): LocalEntry? = nodes[docId]?.toEntry(path)

    override fun createDir(parentDocId: String, name: String): String = create(parentDocId, name, isDir = true)

    override fun createFile(parentDocId: String, name: String): String = create(parentDocId, name, isDir = false)

    private fun create(parentDocId: String, name: String, isDir: Boolean): String {
        val parent = nodes[parentDocId]
        if (parent == null || !parent.isDir) throw FileNotFoundException(parentDocId)
        if (nodes.values.any { it.parentId == parentDocId && it.name.equals(name, ignoreCase = true) }) {
            throw IOException("Provider created '${name.substringBeforeLast('.')} (1)' instead of '$name'")
        }
        val node = Node("l${nextId++}", name, parentDocId, isDir, lastModified = ++tick)
        nodes[node.id] = node
        return node.id
    }

    override fun openInput(docId: String): InputStream {
        val node = nodes[docId] ?: throw FileNotFoundException(docId)
        return ByteArrayInputStream(node.content.copyOf())
    }

    override fun openOutput(docId: String): OutputStream {
        val node = nodes[docId] ?: throw FileNotFoundException(docId)
        val failing = failWrite(node.name)
        // "wt": truncated as soon as it's opened.
        node.content = ByteArray(0)
        node.lastModified = ++tick
        return object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                var n = len
                if (failing) n = minOf(len, failWriteAfterBytes - node.content.size).coerceAtLeast(0)
                node.content += b.copyOfRange(off, off + n)
                node.lastModified = ++tick
                if (n < len) throw IOException("No space left on device")
            }
        }
    }

    override fun delete(docId: String) {
        val node = nodes[docId] ?: throw IOException("Delete failed")
        val doomed = subtree(docId)
        deletes += Deleted(pathOf(node), node.isDir, doomed.count { !it.isDir })
        doomed.forEach { nodes.remove(it.id) }
    }

    override fun move(docId: String, sourceParentDocId: String, targetParentDocId: String, name: String) {
        val node = nodes[docId] ?: throw FileNotFoundException(docId)
        check(node.parentId == sourceParentDocId) { "wrong source parent" }
        if (nodes.values.any { it.parentId == targetParentDocId && it.name.equals(name, ignoreCase = true) }) {
            throw IOException("Target exists")
        }
        node.parentId = targetParentDocId
    }

    // ---- test-side helpers: what a person does in a file manager ----

    fun write(path: String, text: String): String {
        val existing = find(path)
        if (existing != null) {
            existing.content = text.toByteArray()
            existing.lastModified = ++tick
            return existing.id
        }
        return createFile(mkdirs(path.substringBeforeLast('/', "")), path.substringAfterLast('/'))
            .also { nodes.getValue(it).content = text.toByteArray() }
    }

    fun mkdirs(path: String): String {
        if (path.isEmpty()) return rootDocId
        find(path)?.let { return it.id }
        return createDir(mkdirs(path.substringBeforeLast('/', "")), path.substringAfterLast('/'))
    }

    /** The user deletes something (not counted in [deletes]). */
    fun remove(path: String) {
        val node = requireNotNull(find(path)) { "no $path on phone" }
        subtree(node.id).forEach { nodes.remove(it.id) }
    }

    fun rename(path: String, newName: String) {
        requireNotNull(find(path)) { "no $path on phone" }.name = newName
    }

    fun read(path: String): String? = find(path)?.let { String(it.content) }

    fun exists(path: String) = find(path) != null

    /** Files outside .sync-trash: path → text. */
    fun files(): Map<String, String> = all().filter { (p, n) -> !n.isDir && !p.isTrash() }.mapValues { String(it.value.content) }

    fun folders(): Set<String> = all().filter { (p, n) -> n.isDir && !p.isTrash() }.keys

    /** Files inside .sync-trash: path below the trash batch folder → text. */
    fun trashed(): Map<String, String> = all()
        .filter { (p, n) -> !n.isDir && p.startsWith("${LocalFs.TRASH_DIR}/") && !p.endsWith("/.nomedia") && p.count { it == '/' } >= 2 }
        .mapKeys { it.key.split('/', limit = 3)[2] }
        .mapValues { String(it.value.content) }

    /** Names of the dated batch folders in .sync-trash. */
    fun trashBatches(): List<String> =
        find(LocalFs.TRASH_DIR)?.let { t -> nodes.values.filter { it.parentId == t.id && it.isDir }.map { it.name }.sorted() } ?: emptyList()

    fun pathOf(node: Node): String {
        val parts = ArrayDeque<String>()
        var cur: Node? = node
        while (cur != null && cur.id != rootDocId) {
            parts.addFirst(cur.name)
            cur = cur.parentId?.let { nodes[it] }
        }
        return parts.joinToString("/")
    }

    fun find(path: String): Node? {
        var current = rootDocId
        var found: Node? = null
        for (segment in path.split('/')) {
            found = nodes.values.firstOrNull { it.parentId == current && it.name == segment } ?: return null
            current = found.id
        }
        return found
    }

    private fun all(): Map<String, Node> =
        nodes.values.filter { it.id != rootDocId }.associateBy { pathOf(it) }.toSortedMap()

    private fun subtree(id: String): List<Node> {
        val out = mutableListOf(nodes.getValue(id))
        var i = 0
        while (i < out.size) {
            val parent = out[i++].id
            out += nodes.values.filter { it.parentId == parent }
        }
        return out
    }

    private fun String.isTrash() = this == LocalFs.TRASH_DIR || startsWith("${LocalFs.TRASH_DIR}/")

    private fun Node.toEntry(path: String) = LocalEntry(
        path = path,
        docId = id,
        name = name,
        isDir = isDir,
        size = if (isDir) 0 else content.size.toLong(),
        lastModified = lastModified,
        mimeType = if (isDir) "vnd.android.document/directory" else "application/octet-stream",
    )
}
