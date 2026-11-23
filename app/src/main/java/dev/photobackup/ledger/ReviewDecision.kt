package dev.photobackup.ledger

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import dev.photobackup.review.ReviewAction

/** A swipe decision waiting for Finish. Survives a crash or kill; nothing is applied to phone or drive until Finish. */
@Entity
data class ReviewDecision(@PrimaryKey val mediaId: Long, val action: ReviewAction, val decidedAt: Long)

@Dao
interface ReviewDecisionDao {
    @Upsert suspend fun upsert(d: ReviewDecision)

    @Query("DELETE FROM ReviewDecision WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: Long)

    /** The most recent decision (what Undo pops), or null. */
    @Query("SELECT * FROM ReviewDecision ORDER BY decidedAt DESC, mediaId DESC LIMIT 1")
    suspend fun lastDecided(): ReviewDecision?

    @Query("SELECT * FROM ReviewDecision ORDER BY decidedAt ASC, mediaId ASC")
    suspend fun all(): List<ReviewDecision>

    @Query("DELETE FROM ReviewDecision WHERE mediaId IN (:ids)")
    suspend fun clear(ids: List<Long>)
}
