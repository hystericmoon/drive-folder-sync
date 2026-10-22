package io.github.hystericmoon.drivesync.data

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
