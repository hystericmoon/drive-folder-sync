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
