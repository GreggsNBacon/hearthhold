package dev.photobackup.ledger

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The hand-picked named folder for a media item. Kept apart from [LedgerEntry] so it survives ledger row rebuilds. */
@Entity
data class FolderAssignment(@PrimaryKey val mediaId: Long, val folder: String)

interface FolderAssignmentStore {
    suspend fun get(mediaId: Long): FolderAssignment?
    suspend fun all(): List<FolderAssignment>
    suspend fun set(a: FolderAssignment)
    suspend fun clear(mediaId: Long)
    fun observeAll(): Flow<List<FolderAssignment>>
}

@Dao
interface FolderAssignmentDao : FolderAssignmentStore {
    @Query("SELECT * FROM FolderAssignment WHERE mediaId = :mediaId")
    override suspend fun get(mediaId: Long): FolderAssignment?

    @Query("SELECT * FROM FolderAssignment")
    override suspend fun all(): List<FolderAssignment>

    @Upsert override suspend fun set(a: FolderAssignment)

    @Query("DELETE FROM FolderAssignment WHERE mediaId = :mediaId")
    override suspend fun clear(mediaId: Long)

    @Query("SELECT * FROM FolderAssignment")
    override fun observeAll(): Flow<List<FolderAssignment>>
}
