package dev.photobackup.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.photobackup.review.ReviewAction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun decisionsRoundTripAndUndoOrder() = runBlocking {
        val dao = db.reviewDecisionDao()
        assertNull(dao.lastDecided())
        dao.upsert(ReviewDecision(1, ReviewAction.KEEP, 100))
        dao.upsert(ReviewDecision(2, ReviewAction.JUNK, 200))
        dao.upsert(ReviewDecision(3, ReviewAction.DRIVE_ONLY, 150))
        assertEquals(2L, dao.lastDecided()!!.mediaId)
        assertEquals(listOf(1L, 3L, 2L), dao.all().map { it.mediaId })

        dao.delete(2)
        assertEquals(3L, dao.lastDecided()!!.mediaId)
        dao.upsert(ReviewDecision(3, ReviewAction.SKIP, 300)) // re-decide replaces
        assertEquals(ReviewDecision(3, ReviewAction.SKIP, 300), dao.lastDecided())
        assertEquals(2, dao.all().size)

        dao.clear(listOf(1, 3))
        assertEquals(emptyList<ReviewDecision>(), dao.all())
        dao.clear(emptyList())
    }

    @Test fun keepsRoundTripAndWindow() = runBlocking {
        val dao = db.reviewKeepDao()
        dao.upsert(ReviewKeep(1, 1000))
        dao.upsert(ReviewKeep(2, 5000))
        assertEquals(ReviewKeep(1, 1000), dao.get(1))
        assertNull(dao.get(9))
        dao.upsert(ReviewKeep(1, 2000))
        assertEquals(listOf(1L, 2L), dao.all().map { it.mediaId }.sorted())
        assertEquals(listOf(2L), dao.since(3000).map { it.mediaId })
        dao.clear(listOf(1))
        assertEquals(listOf(2L), dao.all().map { it.mediaId })
    }

    @Test fun binColumnsAndStatusRoundTrip() = runBlocking {
        val e = LedgerEntry(
            1, "content://media/1", "a.jpg", "DCIM/Camera/", 100, 1000, 10, false,
            null, null, null, BackupStatus.BINNED, null, 0,
            binRequestedAt = 5, binPath = "B/P/.Bin/Camera/2024/a.jpg", binnedAt = 9,
        )
        db.ledgerDao().upsert(e)
        assertEquals(e, db.ledgerDao().get(1))
        assertEquals(listOf(1L), db.ledgerDao().byStatus(BackupStatus.BINNED).map { it.mediaId })
    }
}
