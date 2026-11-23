package dev.photobackup.ledger

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** A photo the user chose to keep on the phone; it stays out of the review deck for a while. */
@Entity
data class ReviewKeep(@PrimaryKey val mediaId: Long, val keptAt: Long)

@Dao
interface ReviewKeepDao {
    @Upsert suspend fun upsert(k: ReviewKeep)

    @Query("SELECT * FROM ReviewKeep WHERE mediaId = :mediaId")
    suspend fun get(mediaId: Long): ReviewKeep?

    @Query("SELECT * FROM ReviewKeep")
    suspend fun all(): List<ReviewKeep>

    /** Keeps made at or after [cutoffMs], i.e. still hidden from the deck. */
    @Query("SELECT * FROM ReviewKeep WHERE keptAt >= :cutoffMs")
    suspend fun since(cutoffMs: Long): List<ReviewKeep>

    @Query("DELETE FROM ReviewKeep WHERE mediaId IN (:ids)")
    suspend fun clear(ids: List<Long>)
}
