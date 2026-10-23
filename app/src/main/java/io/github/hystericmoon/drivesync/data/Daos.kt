package io.github.hystericmoon.drivesync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PairDao {
    @Query("SELECT * FROM folder_pairs ORDER BY id")
    fun observeAll(): Flow<List<FolderPair>>

    @Query("SELECT * FROM folder_pairs ORDER BY id")
    suspend fun all(): List<FolderPair>

    @Query("SELECT * FROM folder_pairs WHERE id = :id")
    suspend fun get(id: Long): FolderPair?

    @Insert
    suspend fun insert(pair: FolderPair): Long

    @Update
    suspend fun update(pair: FolderPair)

    @Query("DELETE FROM folder_pairs WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface BaselineDao {
    @Query("SELECT * FROM baseline WHERE pairId = :pairId")
    suspend fun forPair(pairId: Long): List<BaselineEntry>

    @Upsert
    suspend fun upsert(entry: BaselineEntry)

    @Query("DELETE FROM baseline WHERE pairId = :pairId AND path = :path")
    suspend fun delete(pairId: Long, path: String)
}

@Dao
interface SyncLogDao {
    @Query("SELECT * FROM sync_log WHERE pairId = :pairId ORDER BY id DESC LIMIT :limit")
    fun observe(pairId: Long, limit: Int = KEEP): Flow<List<SyncLogEntry>>

    @Insert
    suspend fun insert(entry: SyncLogEntry)

    @Query("SELECT * FROM sync_log WHERE pairId = :pairId AND path = :path ORDER BY id DESC LIMIT 1")
    suspend fun latestFor(pairId: Long, path: String): SyncLogEntry?

    /** Skips and errors repeat on every run until resolved; log them only when they change. */
    suspend fun insertUnlessRepeat(entry: SyncLogEntry) {
        val last = latestFor(entry.pairId, entry.path)
        if (last != null && last.kind == entry.kind && last.detail == entry.detail) return
        insert(entry)
    }

    @Query(
        "DELETE FROM sync_log WHERE pairId = :pairId AND id NOT IN " +
            "(SELECT id FROM sync_log WHERE pairId = :pairId ORDER BY id DESC LIMIT :keep)",
    )
    suspend fun prune(pairId: Long, keep: Int = KEEP)

    companion object {
        const val KEEP = 1000
    }
}
