package io.github.hystericmoon.drivesync.sync

import android.content.Context
import android.net.Uri
import io.github.hystericmoon.drivesync.auth.GoogleAuth
import io.github.hystericmoon.drivesync.auth.NeedsUserConsentException
import io.github.hystericmoon.drivesync.data.AppDatabase
import io.github.hystericmoon.drivesync.data.BaselineEntry
import io.github.hystericmoon.drivesync.data.FolderPair
import io.github.hystericmoon.drivesync.drive.DriveClient
import io.github.hystericmoon.drivesync.drive.InvalidPageTokenException
import io.github.hystericmoon.drivesync.local.LocalFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SyncAbortedException(message: String) : IOException(message)

private data class Snapshot(val tree: RemoteTree, val token: String, val fullScan: Boolean)

class SyncEngine(
    private val context: Context,
    private val db: AppDatabase,
    private val auth: GoogleAuth,
) {
    /** One sync at a time across all pairs, whether started by the periodic job or "Sync now". */
    private val mutex = Mutex()

    private val _progress = MutableStateFlow<Map<Long, String>>(emptyMap())
    /** pairId → human-readable status while a sync is running. */
    val progress: StateFlow<Map<Long, String>> = _progress

    fun driveClient() = DriveClient(token = { auth.accessToken() }, invalidateToken = { auth.invalidate(it) })

    suspend fun sync(pairId: Long) = mutex.withLock {
        val pair = db.pairs().get(pairId) ?: return@withLock
        setProgress(pairId, "Starting…")
        try {
            withContext(Dispatchers.IO) { Run(pair).execute() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            db.pairs().get(pairId)?.let {
                db.pairs().update(it.copy(lastSyncAt = System.currentTimeMillis(), lastError = e.message ?: e.toString()))
            }
            if (e is NeedsUserConsentException) throw e
            if (e is IOException && e !is SyncAbortedException) throw e // let WorkManager retry transient failures
        } finally {
            setProgress(pairId, null)
        }
    }

    private fun setProgress(pairId: Long, text: String?) = _progress.update {
        if (text == null) it - pairId else it + (pairId to text)
    }

    /** State for one sync of one pair. */
    private inner class Run(private val pair: FolderPair) {
        private val fs = LocalFs(context, Uri.parse(pair.localTreeUri))
        private val drive = driveClient()
        private val baselineDao = db.baseline()
        private val rootId = pair.driveFolderId
        private val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))

        private val localDirIds = HashMap<String, String>()
        private val remoteDirIds = HashMap<String, String>()
        /** Lower-cased paths that exist on either side, for picking unused conflict names. */
        private val takenPaths = HashSet<String>()

        private var uploaded = 0
        private var downloaded = 0
        private var trashedLocal = 0
        private var trashedRemote = 0
        private var conflicts = 0
        /** Errors: the sync is retried and the Changes cursor is not advanced. */
        private val failures = mutableListOf<String>()
        /** Permanent, by-design skips (duplicate names, type clashes): reported but don't block progress. */
        private val warnings = mutableListOf<String>()

        suspend fun execute() {
            if (!fs.hasPermission()) throw SyncAbortedException("Lost access to the phone folder. Remove and re-add this pair.")
            fs.checkRootAccessible()
            val root = drive.getFile(rootId)
            if (root == null || root.trashed || !root.isFolder) {
                throw SyncAbortedException("Drive folder “${pair.driveFolderName}” is missing or in the trash.")
            }

            val baselineRows = baselineDao.forPair(pair.id)
            val now = System.currentTimeMillis()

            progress("Checking Drive…")
            val (tree, newToken, fullScan) = remoteSnapshot(baselineRows, now)
            val remote = tree.resolve(baselineRows.mapNotNullTo(HashSet()) { it.driveFileId })
            tree.skipped.forEach { warnings += "$it: skipped (duplicate or invalid name in Drive)" }

            progress("Scanning phone folder…")
            val local = fs.listTree()

            guardAgainstMassDeletion(baselineRows, local, remote)

            localDirIds[""] = fs.rootDocId
            remoteDirIds[""] = rootId
            local.values.filter { it.isDir }.forEach { localDirIds[it.path] = it.docId }
            remote.values.filter { it.isDir }.forEach { remoteDirIds[it.path] = it.id }
            (local.keys + remote.keys).mapTo(takenPaths) { it.lowercase() }

            val actions = Planner.plan(
                local = local,
                remote = remote,
                baseline = baselineRows.associate { it.path to it.toBase() },
                allowDeletes = pair.initialSyncDone,
            )

            actions.forEachIndexed { i, action ->
                progress("${verb(action)} ${action.path} (${i + 1}/${actions.size})")
                try {
                    perform(action)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: NeedsUserConsentException) {
                    throw e
                } catch (e: Exception) {
                    failures += "${action.path}: ${e.message ?: e.javaClass.simpleName}"
                }
            }

            runCatching { purgeOldTrash() }

            val clean = failures.isEmpty()
            val latest = db.pairs().get(pair.id) ?: return
            db.pairs().update(
                latest.copy(
                    // Keep the old cursor if anything failed, so the same changes are seen again next time.
                    changesPageToken = if (clean) newToken else latest.changesPageToken,
                    lastFullScanAt = if (clean && fullScan) now else latest.lastFullScanAt,
                    initialSyncDone = latest.initialSyncDone || clean,
                    lastSyncAt = System.currentTimeMillis(),
                    lastSummary = summary(),
                    lastError = (failures + warnings).takeIf { it.isNotEmpty() }?.let {
                        val head = if (failures.isEmpty()) "${it.size} skipped:" else "${failures.size} failed (will retry):"
                        head + "\n" + it.take(10).joinToString("\n")
                    },
                ),
            )
        }

        // ---- remote snapshot ----

        private suspend fun remoteSnapshot(rows: List<BaselineEntry>, now: Long): Snapshot {
            val savedToken = pair.changesPageToken
            val due = !pair.initialSyncDone || savedToken == null || now - pair.lastFullScanAt > FULL_SCAN_INTERVAL_MS
            if (!due) {
                try {
                    val page = drive.listChanges(savedToken!!)
                    val tree = RemoteTree(rootId)
                    rows.filter { it.driveFileId != null }.forEach { tree.put(it.toNode()) }
                    for (change in page.changes) {
                        val file = change.file
                        if (change.removed || file == null) tree.remove(change.fileId) else tree.apply(file)
                    }
                    // A folder created in (or moved into) the pair brings children the Changes feed may not list.
                    val knownIds = rows.mapNotNullTo(HashSet()) { it.driveFileId }
                    tree.resolve(knownIds).values
                        .filter { it.isDir && it.id !in knownIds }
                        .forEach { listRecursive(it.id, tree) }
                    return Snapshot(tree, page.newStartPageToken, fullScan = false)
                } catch (_: InvalidPageTokenException) {
                    // fall through to a full scan
                }
            }
            // Take the token *before* listing so edits made during the scan show up next time.
            val token = drive.getStartPageToken()
            val tree = RemoteTree(rootId)
            listRecursive(rootId, tree)
            return Snapshot(tree, token, fullScan = true)
        }

        private suspend fun listRecursive(folderId: String, tree: RemoteTree) {
            val queue = ArrayDeque(listOf(folderId))
            while (queue.isNotEmpty()) {
                for (child in drive.listChildren(queue.removeFirst())) {
                    tree.apply(child)
                    if (child.isFolder) queue += child.id
                }
            }
        }

        private fun guardAgainstMassDeletion(
            rows: List<BaselineEntry>,
            local: Map<String, LocalEntry>,
            remote: Map<String, RemoteEntry>,
        ) {
            if (!pair.initialSyncDone) return
            val known = rows.count { !it.isDir }
            if (known < MASS_DELETE_MIN_FILES) return
            if (local.values.none { !it.isDir }) {
                throw SyncAbortedException(
                    "The phone folder looks empty but $known files were synced before. " +
                        "Not touching Drive. If you really emptied it, remove and re-add this pair.",
                )
            }
            if (remote.values.none { !it.isDir }) {
                throw SyncAbortedException(
                    "The Drive folder looks empty but $known files were synced before. " +
                        "Not touching the phone. If you really emptied it, remove and re-add this pair.",
                )
            }
        }

        // ---- actions ----

        private suspend fun perform(action: Action) {
            when (action) {
                is Action.Upload -> upload(action.path, action.local, action.replaceId)
                is Action.Download -> download(action.path, action.remote, action.overwrite)
                is Action.TrashRemote -> {
                    drive.trash(action.remote.id)
                    baselineDao.delete(pair.id, action.path)
                    trashedRemote++
                }
                is Action.TrashLocal -> trashLocal(action.path, action.local)
                is Action.Reconcile -> reconcile(action.path, action.local, action.remote)
                is Action.Record -> record(action.path, action.local, action.remote)
                is Action.Forget -> baselineDao.delete(pair.id, action.path)
                is Action.CreateRemoteDir -> { ensureRemoteDir(action.path); recordDir(action.path) }
                is Action.CreateLocalDir -> { ensureLocalDir(action.path); recordDir(action.path) }
                is Action.RemoveRemoteDirIfEmpty -> {
                    if (drive.listChildren(action.remote.id).isEmpty()) {
                        drive.trash(action.remote.id)
                        baselineDao.delete(pair.id, action.path)
                    } else {
                        recordDir(action.path) // something new landed in it; keep both sides
                    }
                }
                is Action.RemoveLocalDirIfEmpty -> {
                    if (fs.children(action.local.docId, action.path).isEmpty()) {
                        fs.delete(action.local.docId)
                        baselineDao.delete(pair.id, action.path)
                    } else {
                        recordDir(action.path)
                    }
                }
                is Action.Skip -> warnings += "${action.path}: ${action.reason}"
            }
        }

        private suspend fun upload(path: String, l: LocalEntry, replaceId: String?) {
            val parentId = ensureRemoteDir(parentOf(path))
            val file = drive.upload(
                name = l.name,
                parentId = parentId,
                existingId = replaceId,
                mimeType = l.mimeType,
                size = l.size,
                open = { fs.openInput(l.docId) },
            )
            // Baseline keeps the local stat from *before* the upload: if the file changed meanwhile,
            // the next sync sees a difference and uploads again.
            baselineDao.upsert(
                BaselineEntry(
                    pairId = pair.id, path = path, isDir = false,
                    localDocId = l.docId, localSize = l.size, localLastModified = l.lastModified,
                    driveFileId = file.id, driveParentId = file.parentId ?: parentId,
                    driveMd5 = file.md5, driveModifiedTime = file.modifiedTime,
                ),
            )
            takenPaths += path.lowercase()
            uploaded++
        }

        private suspend fun download(path: String, r: RemoteEntry, overwrite: LocalEntry?) {
            val tmp = File.createTempFile(".drivesync-tmp", null, context.cacheDir)
            try {
                drive.download(r.id, r.md5, tmp)
                val docId = if (overwrite != null) {
                    val now = fs.stat(overwrite.docId, path)
                    if (now == null || now.size != overwrite.size || now.lastModified != overwrite.lastModified) {
                        throw IOException("changed on phone during sync; will retry")
                    }
                    overwrite.docId
                } else {
                    fs.createFile(ensureLocalDir(parentOf(path)), nameOf(path))
                }
                tmp.inputStream().use { input -> fs.openOutput(docId).use { input.copyTo(it) } }
                val written = fs.stat(docId, path) ?: throw IOException("file vanished after writing")
                if (written.size != tmp.length()) throw IOException("short write (${written.size}/${tmp.length()})")
                baselineDao.upsert(
                    BaselineEntry(
                        pairId = pair.id, path = path, isDir = false,
                        localDocId = docId, localSize = written.size, localLastModified = written.lastModified,
                        driveFileId = r.id, driveParentId = r.parentId,
                        driveMd5 = r.md5, driveModifiedTime = r.modifiedTime,
                    ),
                )
                takenPaths += path.lowercase()
                downloaded++
            } finally {
                tmp.delete()
            }
        }

        private suspend fun trashLocal(path: String, l: LocalEntry) {
            val now = fs.stat(l.docId, path)
            if (now != null && (now.size != l.size || now.lastModified != l.lastModified)) {
                throw IOException("changed on phone during sync; will upload next time")
            }
            if (now != null) {
                val trashParent = ensureLocalDir(listOf(LocalFs.TRASH_DIR, stamp, parentOf(path)).filter { it.isNotEmpty() }.joinToString("/"))
                fs.move(l.docId, localDirIds.getValue(parentOf(path)), trashParent, l.name)
            }
            baselineDao.delete(pair.id, path)
            trashedLocal++
        }

        /**
         * Both sides have this path with possibly different content. Equal bytes → just record.
         * Otherwise keep both: the Drive version is renamed "name (conflict DATE).ext" on both
         * sides and the phone version takes the original name.
         */
        private suspend fun reconcile(path: String, l: LocalEntry, r: RemoteEntry) {
            if (l.size == r.size && r.md5 != null && fs.md5(l.docId).equals(r.md5, ignoreCase = true)) {
                record(path, l, r)
                return
            }
            val parent = parentOf(path)
            val conflictName = ConflictNames.conflictName(nameOf(path), LocalDate.now()) { candidate ->
                join(parent, candidate).lowercase() in takenPaths
            }
            val conflictPath = join(parent, conflictName)
            takenPaths += conflictPath.lowercase()

            // Order matters for crash safety: after the rename, a re-run sees a plain "new on each side" state.
            val renamed = drive.rename(r.id, conflictName)
            download(conflictPath, r.copy(path = conflictPath, md5 = renamed.md5 ?: r.md5, modifiedTime = renamed.modifiedTime), overwrite = null)
            upload(path, l, replaceId = null)
            downloaded--; uploaded--
            conflicts++
        }

        private suspend fun record(path: String, l: LocalEntry, r: RemoteEntry) {
            baselineDao.upsert(
                BaselineEntry(
                    pairId = pair.id, path = path, isDir = l.isDir,
                    localDocId = l.docId, localSize = l.size, localLastModified = l.lastModified,
                    driveFileId = r.id, driveParentId = r.parentId,
                    driveMd5 = r.md5, driveModifiedTime = r.modifiedTime,
                ),
            )
        }

        /** Stores a folder's baseline once both sides have it, so later Drive changes can be placed under it. */
        private suspend fun recordDir(path: String) {
            if (path.isEmpty()) return
            val localId = localDirIds[path] ?: return
            val remoteId = remoteDirIds[path] ?: return
            baselineDao.upsert(
                BaselineEntry(
                    pairId = pair.id, path = path, isDir = true,
                    localDocId = localId, localSize = 0, localLastModified = 0,
                    driveFileId = remoteId, driveParentId = remoteDirIds[parentOf(path)],
                    driveMd5 = null, driveModifiedTime = null,
                ),
            )
        }

        private suspend fun ensureRemoteDir(path: String): String {
            remoteDirIds[path]?.let { return it }
            val parentId = ensureRemoteDir(parentOf(path))
            val id = drive.createFolder(nameOf(path), parentId).id
            remoteDirIds[path] = id
            takenPaths += path.lowercase()
            recordDir(path)
            return id
        }

        private suspend fun ensureLocalDir(path: String): String {
            localDirIds[path]?.let { return it }
            val parentDocId = ensureLocalDir(parentOf(path))
            val name = nameOf(path)
            // The trash folder isn't in the listing, so it may already exist.
            val id = fs.findChild(parentDocId, name)?.takeIf { it.isDir }?.docId
                ?: fs.createDir(parentDocId, name).also {
                    if (path == LocalFs.TRASH_DIR) runCatching { fs.createFile(it, ".nomedia") }
                }
            localDirIds[path] = id
            takenPaths += path.lowercase()
            recordDir(path)
            return id
        }

        /** Deletes .sync-trash/<date>_<time> folders older than [TRASH_RETENTION_DAYS]. */
        private fun purgeOldTrash() {
            val trash = fs.findChild(fs.rootDocId, LocalFs.TRASH_DIR)?.takeIf { it.isDir } ?: return
            val cutoff = LocalDate.now().minusDays(TRASH_RETENTION_DAYS)
            for (batch in fs.children(trash.docId, LocalFs.TRASH_DIR)) {
                if (!batch.isDir) continue
                val day = runCatching { LocalDate.parse(batch.name.take(10)) }.getOrNull() ?: continue
                if (day.isBefore(cutoff)) runCatching { fs.delete(batch.docId) }
            }
        }

        private fun progress(text: String) = setProgress(pair.id, text)

        private fun summary(): String = buildList {
            if (uploaded > 0) add("↑$uploaded")
            if (downloaded > 0) add("↓$downloaded")
            if (conflicts > 0) add("$conflicts conflict${if (conflicts > 1) "s" else ""}")
            if (trashedLocal > 0) add("$trashedLocal to phone trash")
            if (trashedRemote > 0) add("$trashedRemote to Drive trash")
        }.ifEmpty { listOf("Up to date") }.joinToString(" · ")
    }

    companion object {
        const val FULL_SCAN_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val TRASH_RETENTION_DAYS = 30L
        private const val MASS_DELETE_MIN_FILES = 5

        private fun parentOf(path: String) = path.substringBeforeLast('/', "")
        private fun nameOf(path: String) = path.substringAfterLast('/')
        private fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"

        private fun verb(a: Action) = when (a) {
            is Action.Upload -> "Uploading"
            is Action.Download -> "Downloading"
            is Action.TrashLocal, is Action.TrashRemote -> "Trashing"
            is Action.Reconcile -> "Comparing"
            else -> "Updating"
        }

        private fun BaselineEntry.toBase() =
            Base(path, isDir, localSize, localLastModified, driveFileId, driveMd5, driveModifiedTime)

        private fun BaselineEntry.toNode() = RemoteTree.Node(
            id = driveFileId!!,
            name = path.substringAfterLast('/'),
            parentId = driveParentId,
            isDir = isDir,
            md5 = driveMd5,
            modifiedTime = driveModifiedTime,
            size = localSize,
            mimeType = "",
        )
    }
}
