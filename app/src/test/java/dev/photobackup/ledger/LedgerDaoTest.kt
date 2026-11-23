package dev.photobackup.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
class LedgerDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: LedgerDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.ledgerDao()
    }

    @After fun tearDown() = db.close()

    private fun entry(id: Long, status: BackupStatus = BackupStatus.PENDING, partPath: String? = null) = LedgerEntry(
        id, "content://media/$id", "$id.jpg", "DCIM/Camera/", 100, 1000, 10, false,
        null, null, partPath, status, null, 0
    )

    @Test fun upsertThenGetRoundTrips() = runBlocking {
        val e = entry(1, BackupStatus.VERIFIED, "x.part").copy(localSha256 = "abc", lastError = "boom")
        dao.upsert(e)
        assertEquals(e, dao.get(1))
        assertNull(dao.get(2))
        dao.upsert(e.copy(status = BackupStatus.FAILED))
        assertEquals(BackupStatus.FAILED, dao.get(1)?.status)
    }

    @Test fun byStatusFilters() = runBlocking {
        dao.upsert(entry(1, BackupStatus.PENDING))
        dao.upsert(entry(2, BackupStatus.VERIFIED))
        dao.upsert(entry(3, BackupStatus.VERIFIED))
        assertEquals(listOf(2L, 3L), dao.byStatus(BackupStatus.VERIFIED).map { it.mediaId }.sorted())
    }

    @Test fun withPartPathOnlyReturnsRowsWithPartPath() = runBlocking {
        dao.upsert(entry(1))
        dao.upsert(entry(2, partPath = "p.part"))
        assertEquals(listOf(2L), dao.withPartPath().map { it.mediaId })
    }

    @Test fun deleteAndObserve() = runBlocking {
        dao.upsert(entry(1, BackupStatus.VERIFIED))
        dao.upsert(entry(2, BackupStatus.VERIFIED))
        dao.upsert(entry(3, BackupStatus.FAILED))
        assertEquals(mapOf(BackupStatus.VERIFIED to 2, BackupStatus.FAILED to 1), dao.observeCounts().first())
        assertEquals(3, dao.observeAll().first().size)
        dao.delete(1)
        assertNull(dao.get(1))
    }

    @Test fun markRemovedIfSettledOnlyTouchesVerifiedRowsWithoutAMove() = runBlocking {
        dao.upsert(entry(1, BackupStatus.VERIFIED))
        dao.upsert(entry(2, BackupStatus.VERIFIED).copy(movingTo = "x\\y.jpg"))
        dao.upsert(entry(3, BackupStatus.FAILED))
        assertEquals(true, dao.markRemovedIfSettled(1, 99))
        assertEquals(false, dao.markRemovedIfSettled(2, 99))
        assertEquals(false, dao.markRemovedIfSettled(3, 99))
        assertEquals(false, dao.markRemovedIfSettled(4, 99))
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, dao.get(1)!!.status)
        assertEquals(99L, dao.get(1)!!.updatedAtMs)
        assertEquals(BackupStatus.VERIFIED, dao.get(2)!!.status)
        assertEquals("x\\y.jpg", dao.get(2)!!.movingTo)
        assertEquals(BackupStatus.FAILED, dao.get(3)!!.status)
    }

    @Test fun setLastErrorTouchesOnlyTheErrorColumn() = runBlocking {
        dao.upsert(entry(1, BackupStatus.REMOVED_FROM_PHONE).copy(remotePath = "a\\b.jpg", localSha256 = "s"))
        dao.setLastError(1, "oops", 77)
        val e = dao.get(1)!!
        assertEquals("oops", e.lastError)
        assertEquals(77L, e.updatedAtMs)
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, e.status)
        assertEquals("a\\b.jpg", e.remotePath)
        assertEquals("s", e.localSha256)
    }

    @Test fun beginMoveOnlyWhenNoMoveIsRecordedAndThePathIsUnchanged() = runBlocking {
        dao.upsert(entry(1, BackupStatus.VERIFIED).copy(remotePath = "a\\x.jpg"))
        dao.upsert(entry(2, BackupStatus.VERIFIED).copy(remotePath = "a\\y.jpg", movingTo = "c\\y.jpg"))
        dao.upsert(entry(3, BackupStatus.VERIFIED).copy(remotePath = "a\\z (1).jpg"))
        assertEquals(true, dao.beginMove(1, "a\\x.jpg", "c\\x.jpg", 5))
        assertEquals(false, dao.beginMove(2, "a\\y.jpg", "d\\y.jpg", 5))
        assertEquals(false, dao.beginMove(3, "a\\z.jpg", "c\\z.jpg", 5))
        assertEquals(false, dao.beginMove(4, "a\\w.jpg", "c\\w.jpg", 5))
        assertEquals("c\\x.jpg", dao.get(1)!!.movingTo)
        assertEquals("c\\y.jpg", dao.get(2)!!.movingTo)
        assertNull(dao.get(3)!!.movingTo)
    }

    @Test fun endMoveSetsOnlyPathMarkerAndError() = runBlocking {
        dao.upsert(entry(1, BackupStatus.VERIFIED).copy(remotePath = "a\\x.jpg", movingTo = "c\\x.jpg", lastError = "e"))
        dao.markRemovedIfSettledCount(1, 1) // refused: a move is recorded
        dao.upsert(dao.get(1)!!.copy(status = BackupStatus.REMOVED_FROM_PHONE))
        dao.endMove(1, "c\\x.jpg", null, null, 9)
        val e = dao.get(1)!!
        assertEquals("c\\x.jpg", e.remotePath)
        assertNull(e.movingTo)
        assertNull(e.lastError)
        assertEquals(9L, e.updatedAtMs)
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, e.status)
    }
}
