package io.github.hystericmoon.drivesync.sync

/** A file or folder in the picked phone folder. [path] is relative to the pair root. */
data class LocalEntry(
    val path: String,
    val docId: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String,
)

/** A file or folder inside the pair's Drive folder, with its path resolved from the parent chain. */
data class RemoteEntry(
    val path: String,
    val id: String,
    val parentId: String,
    val isDir: Boolean,
    val md5: String?,
    val modifiedTime: String?,
    val size: Long,
    val mimeType: String,
)

/** Last agreed state of a path, as the planner sees it (mirrors the Room row). */
data class Base(
    val path: String,
    val isDir: Boolean,
    val localSize: Long,
    val localLastModified: Long,
    val driveFileId: String?,
    val driveMd5: String?,
    val driveModifiedTime: String?,
)

sealed interface Action {
    val path: String

    /** New file locally, or a local edit: send to Drive. [replaceId] = existing Drive file to overwrite. */
    data class Upload(override val path: String, val local: LocalEntry, val replaceId: String?) : Action

    /** New file in Drive, or a remote edit: fetch it. [overwrite] = existing local file to replace. */
    data class Download(override val path: String, val remote: RemoteEntry, val overwrite: LocalEntry?) : Action

    /** Deleted locally, unchanged in Drive → move the Drive file to Drive trash. */
    data class TrashRemote(override val path: String, val remote: RemoteEntry) : Action

    /** Deleted in Drive, unchanged locally → move the local file into .sync-trash. */
    data class TrashLocal(override val path: String, val local: LocalEntry) : Action

    /**
     * Present on both sides with possibly different content (both edited, or both new).
     * Executor compares MD5s: equal → just record; different → keep both via a conflict copy.
     */
    data class Reconcile(override val path: String, val local: LocalEntry, val remote: RemoteEntry) : Action

    /** Both sides already agree; refresh the baseline row (e.g. new Drive id/md5 after our own upload). */
    data class Record(override val path: String, val local: LocalEntry, val remote: RemoteEntry) : Action

    data class Forget(override val path: String) : Action

    data class CreateRemoteDir(override val path: String) : Action
    data class CreateLocalDir(override val path: String) : Action

    /** Folder vanished on the other side: remove this one only if nothing is left in it after file actions. */
    data class RemoveRemoteDirIfEmpty(override val path: String, val remote: RemoteEntry) : Action
    data class RemoveLocalDirIfEmpty(override val path: String, val local: LocalEntry) : Action

    data class Skip(override val path: String, val reason: String) : Action
}
