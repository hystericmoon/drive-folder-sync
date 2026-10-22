package io.github.hystericmoon.drivesync.sync

/**
 * Three-way compare of local vs baseline vs Drive. Pure function, no I/O, unit-tested.
 *
 * Data-safety rules encoded here:
 * - An edit always beats a delete (the edited copy is propagated, never trashed).
 * - Deletes are only ever "move to trash" (local .sync-trash or Drive trash).
 * - When [allowDeletes] is false (first sync of a pair) nothing is trashed: missing files are copied back.
 */
object Planner {

    fun plan(
        local: Map<String, LocalEntry>,
        remote: Map<String, RemoteEntry>,
        baseline: Map<String, Base>,
        allowDeletes: Boolean,
    ): List<Action> {
        val dirActions = mutableListOf<Action>()
        val fileActions = mutableListOf<Action>()
        val removals = mutableListOf<Action>()

        val paths = (local.keys + remote.keys + baseline.keys).toSortedSet()
        for (path in paths) {
            val l = local[path]
            val r = remote[path]
            val b = baseline[path]

            if (l != null && r != null && l.isDir != r.isDir) {
                fileActions += Action.Skip(path, "is a folder on one side and a file on the other")
                continue
            }
            val isDir = l?.isDir ?: r?.isDir ?: b!!.isDir
            if (b != null && b.isDir != isDir) {
                // Type changed since last sync (file replaced by folder or vice versa): start over for this path.
                if (l == null && r == null) fileActions += Action.Forget(path)
                else fileActions += Action.Skip(path, "changed between file and folder; resolve by hand")
                continue
            }

            if (isDir) planDir(path, l, r, b, allowDeletes, dirActions, removals)
            else planFile(path, l, r, b, allowDeletes)?.let { fileActions += it }
        }

        // Parents before children for creation, children before parents for removal.
        dirActions.sortBy { depth(it.path) }
        removals.sortByDescending { depth(it.path) }
        return dirActions + fileActions + removals
    }

    private fun planDir(
        path: String,
        l: LocalEntry?,
        r: RemoteEntry?,
        b: Base?,
        allowDeletes: Boolean,
        dirActions: MutableList<Action>,
        removals: MutableList<Action>,
    ) {
        when {
            l != null && r != null -> if (b == null || b.driveFileId != r.id) dirActions += Action.Record(path, l, r)
            l != null && b == null -> dirActions += Action.CreateRemoteDir(path)
            r != null && b == null -> dirActions += Action.CreateLocalDir(path)
            // Known folder now missing on one side.
            l != null -> if (allowDeletes) removals += Action.RemoveLocalDirIfEmpty(path, l)
                         else dirActions += Action.CreateRemoteDir(path)
            r != null -> if (allowDeletes) removals += Action.RemoveRemoteDirIfEmpty(path, r)
                         else dirActions += Action.CreateLocalDir(path)
            else -> dirActions += Action.Forget(path)
        }
    }

    private fun planFile(path: String, l: LocalEntry?, r: RemoteEntry?, b: Base?, allowDeletes: Boolean): Action? {
        if (b == null) {
            return when {
                l != null && r != null -> Action.Reconcile(path, l, r)
                l != null -> Action.Upload(path, l, replaceId = null)
                r != null -> Action.Download(path, r, overwrite = null)
                else -> null
            }
        }

        val localChanged = l != null && localChanged(l, b)
        val remoteChanged = r != null && remoteChanged(r, b)

        return when {
            l != null && r != null -> when {
                localChanged && remoteChanged -> Action.Reconcile(path, l, r)
                localChanged -> Action.Upload(path, l, replaceId = r.id)
                remoteChanged -> Action.Download(path, r, overwrite = l)
                else -> null
            }
            // Gone from Drive.
            l != null -> when {
                !allowDeletes || localChanged -> Action.Upload(path, l, replaceId = null)
                else -> Action.TrashLocal(path, l)
            }
            // Gone locally.
            r != null -> when {
                !allowDeletes || remoteChanged -> Action.Download(path, r, overwrite = null)
                else -> Action.TrashRemote(path, r)
            }
            else -> Action.Forget(path)
        }
    }

    fun localChanged(l: LocalEntry, b: Base): Boolean =
        l.size != b.localSize || l.lastModified != b.localLastModified

    fun remoteChanged(r: RemoteEntry, b: Base): Boolean {
        if (r.id != b.driveFileId) return true
        if (r.md5 != null && b.driveMd5 != null) return !r.md5.equals(b.driveMd5, ignoreCase = true)
        return r.modifiedTime != b.driveModifiedTime
    }

    private fun depth(path: String) = path.count { it == '/' }
}
