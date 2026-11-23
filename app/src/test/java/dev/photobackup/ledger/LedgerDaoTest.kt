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
}
