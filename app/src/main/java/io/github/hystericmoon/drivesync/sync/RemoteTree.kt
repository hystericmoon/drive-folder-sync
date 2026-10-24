package io.github.hystericmoon.drivesync.sync

import io.github.hystericmoon.drivesync.drive.DriveFile

/**
 * Drive is id/parent based; the sync engine compares by path. This keeps an id → node map
 * (seeded from the baseline, then patched with Changes API results) and resolves paths by
 * walking parents up to the pair root. Anything whose chain doesn't reach the root (moved out,
 * parent trashed) simply drops out of the snapshot, which the planner sees as a remote delete.
 */
class RemoteTree(private val rootId: String) {

    data class Node(
        val id: String,
        val name: String,
        val parentId: String?,
        val isDir: Boolean,
        val md5: String?,
        val modifiedTime: String?,
        val size: Long,
        val mimeType: String,
    )

    private val nodes = HashMap<String, Node>()

    /** A Drive item left out of the snapshot, with a reason the user can act on. */
    data class Skipped(val path: String, val reason: String)

    /** Items we couldn't map to a phone path (duplicate names, names the phone can't hold). */
    val skipped = mutableListOf<Skipped>()

    fun put(node: Node) { nodes[node.id] = node }

    fun remove(id: String) { nodes.remove(id) }

    fun contains(id: String) = nodes.containsKey(id)

    /** Applies a Drive file record: removed/trashed/unsyncable files leave the tree. */
    fun apply(file: DriveFile) {
        if (file.trashed || !file.isSyncable) remove(file.id) else put(file.toNode())
    }

    /**
     * Resolves every node to a path. On path collisions (Drive allows duplicate names; phone
     * storage is case-insensitive) the node already in the baseline wins, then the lowest id.
     */
    fun resolve(preferredIds: Set<String>): Map<String, RemoteEntry> {
        val pathCache = HashMap<String, String?>()

        fun pathOf(id: String, depth: Int): String? {
            if (id == rootId) return ""
            if (depth > MAX_DEPTH) return null
            pathCache[id]?.let { return it }
            if (pathCache.containsKey(id)) return null
            val node = nodes[id] ?: return null
            val parent = node.parentId?.let { pathOf(it, depth + 1) }
            val shown = if (parent.isNullOrEmpty()) node.name else "$parent/${node.name}"
            val result = when {
                parent == null -> null
                !isValidName(node.name) -> null.also {
                    skipped += Skipped(shown, "the name isn't allowed on a phone; rename it in Drive")
                }
                // Our local trash folder is never synced, even if someone creates one in Drive.
                parent.isEmpty() && node.name == ".sync-trash" -> null.also {
                    skipped += Skipped(shown, "this name is reserved for the app's phone trash; rename it in Drive")
                }
                parent.isEmpty() -> node.name
                else -> "$parent/${node.name}"
            }
            pathCache[id] = result
            return result
        }

        val byKey = HashMap<String, Pair<String, Node>>()
        val ordered = nodes.values.sortedWith(compareBy({ it.id !in preferredIds }, { it.id }))
        for (node in ordered) {
            val path = pathOf(node.id, 0) ?: continue
            val key = path.lowercase()
            val winner = byKey[key]?.first
            if (winner != null) {
                skipped += Skipped(
                    path,
                    if (winner == path) "another item in this Drive folder has the same name; rename or remove one in Drive"
                    else "“${winner.substringAfterLast('/')}” is in the same Drive folder and differs only in upper/lower case, " +
                        "which a phone can't keep apart; rename one in Drive",
                )
                continue
            }
            byKey[key] = path to node
        }
        // Children of a folder that lost a collision would otherwise resolve under the winner's
        // path and silently merge two different folders. Keep only nodes whose whole chain won.
        val winnerIds = byKey.values.mapTo(HashSet()) { it.second.id }
        val accepted = HashMap<String, Boolean>()
        fun isAccepted(id: String, depth: Int): Boolean {
            if (id == rootId) return true
            if (depth > MAX_DEPTH || id !in winnerIds) return false
            return accepted.getOrPut(id) { nodes[id]?.parentId?.let { isAccepted(it, depth + 1) } ?: false }
        }
        return byKey.values
            .filter { (path, node) ->
                isAccepted(node.id, 0).also { ok ->
                    if (!ok) skipped += Skipped(path, "a folder above it was skipped because of its name")
                }
            }
            .associate { (path, node) ->
                path to RemoteEntry(
                    path = path,
                    id = node.id,
                    parentId = node.parentId!!,
                    isDir = node.isDir,
                    md5 = node.md5,
                    modifiedTime = node.modifiedTime,
                    size = node.size,
                    mimeType = node.mimeType,
                )
            }
    }

    companion object {
        private const val MAX_DEPTH = 64

        fun isValidName(name: String) =
            name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\u0000' }

        fun DriveFile.toNode() = Node(id, name, parentId, isFolder, md5, modifiedTime, size, mimeType)
    }
}
