package io.github.hystericmoon.drivesync.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "folder_pairs")
data class FolderPair(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localTreeUri: String,
    val localName: String,
    val driveFolderId: String,
    val driveFolderName: String,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    /** False until one sync finishes with zero errors; until then sync is merge-only (no deletes). */
    val initialSyncDone: Boolean = false,
    /** Drive Changes API cursor, saved only after a sync with zero errors. */
    val changesPageToken: String? = null,
    val lastFullScanAt: Long = 0,
    val lastSyncAt: Long = 0,
    val lastSummary: String? = null,
    val lastError: String? = null,
    /** Newline-separated ignore patterns, see [io.github.hystericmoon.drivesync.sync.ExcludeRules]. */
    @ColumnInfo(defaultValue = "")
    val excludePatterns: String = "",
)

/**
 * What both sides looked like the last time this path was known to be in sync.
 * Paths are relative to the pair root, '/'-separated, no leading slash.
 */
@Entity(
    tableName = "baseline",
    primaryKeys = ["pairId", "path"],
    foreignKeys = [
        ForeignKey(
            entity = FolderPair::class,
            parentColumns = ["id"],
            childColumns = ["pairId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pairId"), Index("pairId", "driveFileId")],
)
data class BaselineEntry(
    val pairId: Long,
    val path: String,
    val isDir: Boolean,
    val localDocId: String?,
    val localSize: Long,
    val localLastModified: Long,
    val driveFileId: String?,
    val driveParentId: String?,
    val driveMd5: String?,
    val driveModifiedTime: String?,
)

/** One line in a pair's sync history. Kept to the most recent [SyncLogDao.KEEP] per pair. */
@Entity(
    tableName = "sync_log",
    foreignKeys = [
        ForeignKey(
            entity = FolderPair::class,
            parentColumns = ["id"],
            childColumns = ["pairId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pairId")],
)
data class SyncLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pairId: Long,
    val time: Long,
    val kind: LogKind,
    /** Relative path, or empty for run-level entries. */
    val path: String,
    val detail: String? = null,
)

enum class LogKind { UPLOADED, DOWNLOADED, CONFLICT, TRASHED_LOCAL, TRASHED_REMOTE, SKIPPED, ERROR }
