package dev.photobackup.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test fun markRemovedIfSettledSetsBinRequestedAtInTheSameWrite() = runBlocking {
        dao.upsert(entry(1, BackupStatus.VERIFIED))
        dao.upsert(entry(2, BackupStatus.VERIFIED).copy(movingTo = "x\\y.jpg"))
        dao.upsert(entry(3, BackupStatus.VERIFIED))
        assertEquals(true, dao.markRemovedIfSettled(1, 99, binRequestedAt = 42))
        assertEquals(false, dao.markRemovedIfSettled(2, 99, binRequestedAt = 42))
        assertEquals(true, dao.markRemovedIfSettled(3, 99))
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, dao.get(1)!!.status)
        assertEquals(42L, dao.get(1)!!.binRequestedAt)
        assertNull(dao.get(2)!!.binRequestedAt)
        assertEquals(BackupStatus.VERIFIED, dao.get(2)!!.status)
        assertNull(dao.get(3)!!.binRequestedAt)
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

    // ---- bin ----

    private fun junk(id: Long, path: String) =
        entry(id, BackupStatus.REMOVED_FROM_PHONE).copy(remotePath = path, binRequestedAt = 7)

    @Test fun withBinRequestListsOnlyWaitingJunkRows() = runBlocking {
        dao.upsert(junk(1, "a\\1.jpg"))
        dao.upsert(entry(2, BackupStatus.REMOVED_FROM_PHONE).copy(remotePath = "a\\2.jpg"))
        dao.upsert(junk(3, "a\\3.jpg").copy(status = BackupStatus.BINNED))
        assertEquals(listOf(1L), dao.withBinRequest().map { it.mediaId })
    }

    @Test fun beginBinMoveRecordsTargetAndOriginalTogether() = runBlocking {
        dao.upsert(junk(1, "a\\1.jpg"))
        dao.upsert(entry(2, BackupStatus.REMOVED_FROM_PHONE).copy(remotePath = "a\\2.jpg")) // no bin request
        dao.upsert(junk(3, "a\\3.jpg").copy(movingTo = "x\\3.jpg"))
        dao.upsert(junk(4, "a\\4.jpg"))
        assertEquals(true, dao.beginBinMove(1, "a\\1.jpg", "b\\1.jpg", 5))
        assertEquals(false, dao.beginBinMove(2, "a\\2.jpg", "b\\2.jpg", 5))
        assertEquals(false, dao.beginBinMove(3, "a\\3.jpg", "b\\3.jpg", 5))
        assertEquals(false, dao.beginBinMove(4, "a\\other.jpg", "b\\4.jpg", 5))
        val e = dao.get(1)!!
        assertEquals("b\\1.jpg", e.movingTo)
        assertEquals("a\\1.jpg", e.binPath)
        assertEquals("a\\1.jpg", e.remotePath)
        assertNull(dao.get(2)!!.binPath)
        assertNull(dao.get(4)!!.movingTo)
    }

    @Test fun markBinnedOnlyWhenSettledAtThatPath() = runBlocking {
        dao.upsert(junk(1, "b\\1.jpg").copy(binPath = "a\\1.jpg"))
        dao.upsert(junk(2, "b\\2.jpg")) // no binPath
        dao.upsert(junk(3, "b\\3.jpg").copy(binPath = "a\\3.jpg", movingTo = "c\\3.jpg"))
        assertEquals(true, dao.markBinned(1, "b\\1.jpg", 8))
        assertEquals(false, dao.markBinned(1, "b\\1.jpg", 8)) // already BINNED
        assertEquals(false, dao.markBinned(2, "b\\2.jpg", 8))
        assertEquals(false, dao.markBinned(3, "b\\3.jpg", 8))
        val e = dao.get(1)!!
        assertEquals(BackupStatus.BINNED, e.status)
        assertEquals(8L, e.binnedAt)
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, dao.get(2)!!.status)
    }

    @Test fun finishRestoreClearsTheBinColumnsInOneWrite() = runBlocking {
        val b = junk(1, "b\\1.jpg").copy(status = BackupStatus.BINNED, binPath = "a\\1.jpg", binnedAt = 3)
        dao.upsert(b.copy(movingTo = "a\\1 (1).jpg", lastError = "e"))
        dao.upsert(b.copy(mediaId = 2))
        assertEquals(true, dao.finishRestore(1, "a\\1 (1).jpg", 9))
        assertEquals(false, dao.finishRestore(2, "a\\1.jpg", 9)) // no restore recorded
        val e = dao.get(1)!!
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, e.status)
        assertEquals("a\\1 (1).jpg", e.remotePath)
        assertNull(e.movingTo); assertNull(e.lastError)
        assertNull(e.binRequestedAt); assertNull(e.binPath); assertNull(e.binnedAt)
        assertEquals(BackupStatus.BINNED, dao.get(2)!!.status)
    }

    @Test fun cancelBinRequestOnlyForWaitingRows() = runBlocking {
        dao.upsert(junk(1, "a\\1.jpg"))
        dao.upsert(junk(2, "a\\2.jpg").copy(movingTo = "b\\2.jpg"))
        dao.upsert(junk(3, "b\\3.jpg").copy(status = BackupStatus.BINNED))
        assertEquals(true, dao.cancelBinRequest(1, 4))
        assertEquals(false, dao.cancelBinRequest(2, 4))
        assertEquals(false, dao.cancelBinRequest(3, 4))
        assertNull(dao.get(1)!!.binRequestedAt)
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, dao.get(1)!!.status)
        assertEquals(7L, dao.get(2)!!.binRequestedAt)
    }

    @Test fun deleteBinnedOnlyDeletesASettledBinnedRowAtThatPath() = runBlocking {
        dao.upsert(junk(1, "b\\1.jpg").copy(status = BackupStatus.BINNED))
        dao.upsert(junk(2, "b\\2.jpg")) // waiting, not BINNED
        dao.upsert(junk(3, "b\\3.jpg").copy(status = BackupStatus.BINNED, movingTo = "a\\3.jpg"))
        assertEquals(false, dao.deleteBinned(1, "b\\other.jpg"))
        assertEquals(true, dao.deleteBinned(1, "b\\1.jpg"))
        assertEquals(false, dao.deleteBinned(2, "b\\2.jpg"))
        assertEquals(false, dao.deleteBinned(3, "b\\3.jpg"))
        assertNull(dao.get(1))
        assertNotNull(dao.get(2)); assertNotNull(dao.get(3))
    }

    /** Bin rows (waiting or BINNED) are neither backed up nor waiting on the home screen. */
    @Test fun observeCountsLeavesBinRowsOut() = runBlocking {
        dao.upsert(entry(1, BackupStatus.VERIFIED))
        dao.upsert(entry(2, BackupStatus.REMOVED_FROM_PHONE))
        dao.upsert(junk(3, "a\\3.jpg"))
        dao.upsert(junk(4, "b\\4.jpg").copy(status = BackupStatus.BINNED))
        assertEquals(
            mapOf(BackupStatus.VERIFIED to 1, BackupStatus.REMOVED_FROM_PHONE to 1),
            dao.observeCounts().first(),
        )
    }
}
