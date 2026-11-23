package dev.photobackup.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.photobackup.ledger.AppDatabase
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerDao
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.SecondCopy
import dev.photobackup.ledger.SecondStatus
import dev.photobackup.smb.AccessDeniedException
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.RemoteStore
import dev.photobackup.smb.SmbConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthCheckTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var dao: LedgerDao
    private val main = FakeRemoteStore()
    private val second = FakeRemoteStore()
    private val phone = mutableMapOf<Long, ByteArray>()
    private var now = 1_000_000L

    private val good = "good photo bytes".toByteArray()
    private val bad = "bad! photo bytes".toByteArray() // same size, different content

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.ledgerDao()
    }

    @After fun tearDown() = db.close()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun path(id: Long) = "Backup\\Pixel\\Camera\\2024\\img$id.jpg"

    private fun row(
        id: Long,
        status: BackupStatus = BackupStatus.VERIFIED,
        checked: Long? = null,
        data: ByteArray = good,
    ) = LedgerEntry(
        mediaId = id, localUri = "content://media/$id", displayName = "img$id.jpg", relativePath = "DCIM/Camera/",
        sizeBytes = data.size.toLong(), dateTakenMs = 1_700_000_000_000, dateModifiedSec = 10, isVideo = false,
        localSha256 = sha(data), remotePath = path(id), partPath = null, status = status, lastError = null,
        updatedAtMs = 1, lastCheckedAt = checked,
    )

    /** A ledger row whose drive file holds [onDrive]. */
    private suspend fun put(e: LedgerEntry, onDrive: ByteArray? = good) {
        dao.upsert(e)
        if (onDrive != null) {
            main.mkdirs(e.remotePath!!.substringBeforeLast('\\'))
            main.files[e.remotePath!!] = onDrive
        }
    }

    private fun check(batch: Int = HealthCheck.BATCH_SIZE) = HealthCheck(
        ledger = dao,
        copies = db.secondCopyDao(),
        opener = { m -> phone[m.mediaId]?.let { ByteArrayInputStream(it) } },
        clock = { now },
        batchSize = batch,
    )

    // ---- batch selection ----

    @Test fun batchIsOldestCheckedFirstNullFirstAndLimited() = runBlocking {
        put(row(1, checked = 50))
        put(row(2, checked = null))
        put(row(3, checked = 10))
        put(row(4, status = BackupStatus.REMOVED_FROM_PHONE, checked = null))
        put(row(5, checked = 20))
        val pass = check(batch = 3).checkMain(main, null)
        assertEquals(3, pass.checked)
        assertTrue(pass.completed)
        assertEquals(now, dao.get(2)!!.lastCheckedAt)
        assertEquals(now, dao.get(4)!!.lastCheckedAt)
        assertEquals(now, dao.get(3)!!.lastCheckedAt)
        assertEquals(20L, dao.get(5)!!.lastCheckedAt)
        assertEquals(50L, dao.get(1)!!.lastCheckedAt)
    }

    @Test fun movingPartBinnedAndUnsettledRowsAreSkipped() = runBlocking {
        put(row(1).copy(movingTo = "x"))
        put(row(2).copy(partPath = "y.part"))
        put(row(3, status = BackupStatus.REMOVED_FROM_PHONE).copy(binRequestedAt = 5))
        put(row(4, status = BackupStatus.BINNED).copy(binRequestedAt = 5, binnedAt = 6))
        put(row(5, status = BackupStatus.PENDING))
        put(row(6, status = BackupStatus.FAILED))
        put(row(7).copy(localSha256 = null))
        // All of them are "damaged" on the drive; none may be touched.
        for (id in 1L..7L) main.files[path(id)] = bad
        val pass = check().checkMain(main, null)
        assertEquals(0, pass.checked)
        assertTrue(main.writes.isEmpty())
        for (id in 1L..7L) {
            assertNull(dao.get(id)!!.lastCheckedAt)
            assertNull(dao.get(id)!!.lastError)
        }
        assertEquals(0, dao.healthBatch(200).size)
    }

    // ---- healthy ----

    @Test fun healthyFileGetsLastCheckedAtAndNothingElse() = runBlocking {
        put(row(1).copy(lastError = BackupEngine.MOVE_ERROR))
        val pass = check().checkMain(main, null)
        assertEquals(HealthPass(checked = 1, repaired = 0, damaged = 0, completed = true), pass)
        val e = dao.get(1)!!
        assertEquals(now, e.lastCheckedAt)
        assertEquals(BackupEngine.MOVE_ERROR, e.lastError) // unrelated error left alone
        assertEquals(path(1), e.remotePath)
        assertTrue(main.writes.isEmpty())
        assertTrue(main.deletes.isEmpty())
    }

    @Test fun healthyFileClearsAnEarlierNoGoodCopyError() = runBlocking {
        put(row(1).copy(lastError = HealthCheck.NO_GOOD_COPY_ERROR))
        check().checkMain(main, null)
        assertNull(dao.get(1)!!.lastError)
    }

    // ---- repair ----

    @Test fun damagedWithGoodPhoneCopyIsReUploadedToANewNameAndRowRepointed() = runBlocking {
        put(row(1), onDrive = bad)
        phone[1] = good
        val pass = check().checkMain(main, null)
        assertEquals(HealthPass(checked = 1, repaired = 1, damaged = 0, completed = true), pass)
        val repaired = "Backup\\Pixel\\Camera\\2024\\img1 (1).jpg"
        val e = dao.get(1)!!
        assertEquals(repaired, e.remotePath)
        assertNull(e.lastError)
        assertEquals(now, e.lastCheckedAt)
        assertArrayEquals(good, main.files[repaired])
        assertArrayEquals(bad, main.files[path(1)]) // the bad file stays
        assertTrue(main.deletes.isEmpty())
        assertFalse(main.files.keys.any { it.endsWith(".part") })
    }

    @Test fun wrongSizeFileCountsAsDamaged() = runBlocking {
        put(row(1), onDrive = "short".toByteArray())
        phone[1] = good
        val pass = check().checkMain(main, null)
        assertEquals(1, pass.repaired)
        assertArrayEquals(good, main.files[dao.get(1)!!.remotePath!!])
    }

    @Test fun missingFileIsReMadeWithoutOverwritingAnything() = runBlocking {
        put(row(1), onDrive = null)
        main.mkdirs(path(1).substringBeforeLast('\\'))
        phone[1] = good
        val pass = check().checkMain(main, null)
        assertEquals(1, pass.repaired)
        val e = dao.get(1)!!
        assertArrayEquals(good, main.files[e.remotePath!!])
        assertNull(e.lastError)
    }

    @Test fun damagedWithPhoneGoneIsCopiedFromAVerifiedSecondCopy() = runBlocking {
        put(row(1, status = BackupStatus.REMOVED_FROM_PHONE), onDrive = bad)
        second.mkdirs("B2\\Camera\\2024")
        second.files["B2\\Camera\\2024\\img1.jpg"] = good
        db.secondCopyDao().upsert(
            SecondCopy(1, "B2\\Camera\\2024\\img1.jpg", null, null, SecondStatus.VERIFIED, good.size.toLong(), sha(good), null, 1)
        )
        val pass = check().checkMain(main) { second }
        assertEquals(HealthPass(checked = 1, repaired = 1, damaged = 0, completed = true), pass)
        val e = dao.get(1)!!
        assertEquals("Backup\\Pixel\\Camera\\2024\\img1 (1).jpg", e.remotePath)
        assertArrayEquals(good, main.files[e.remotePath!!])
        assertArrayEquals(bad, main.files[path(1)])
        assertTrue(second.writes.isEmpty())
        assertTrue(second.deletes.isEmpty())
        assertTrue(main.deletes.isEmpty())
    }

    @Test fun editedPhoneCopyIsNotUsedAndSecondOffMeansNoFallback() = runBlocking {
        put(row(1), onDrive = bad)
        phone[1] = "edited on phone!".toByteArray()
        db.secondCopyDao().upsert(
            SecondCopy(1, "B2\\img1.jpg", null, null, SecondStatus.VERIFIED, good.size.toLong(), sha(good), null, 1)
        )
        val pass = check().checkMain(main, null) // second location off
        assertEquals(HealthPass(checked = 1, repaired = 0, damaged = 1, completed = true), pass)
        val e = dao.get(1)!!
        assertEquals(path(1), e.remotePath)
        assertEquals(HealthCheck.NO_GOOD_COPY_ERROR, e.lastError)
        assertEquals(now, e.lastCheckedAt)
        assertEquals(listOf(path(1)), main.files.keys.filter { it.contains("img1") })
        assertTrue(main.deletes.isEmpty())
        assertEquals(1, check().damagedTotal())
    }

    @Test fun damagedSecondCopyIsNoGoodEither() = runBlocking {
        put(row(1, status = BackupStatus.REMOVED_FROM_PHONE), onDrive = bad)
        second.mkdirs("B2")
        second.files["B2\\img1.jpg"] = bad
        db.secondCopyDao().upsert(
            SecondCopy(1, "B2\\img1.jpg", null, null, SecondStatus.VERIFIED, good.size.toLong(), sha(good), null, 1)
        )
        val pass = check().checkMain(main) { second }
        assertEquals(1, pass.damaged)
        assertEquals(HealthCheck.NO_GOOD_COPY_ERROR, dao.get(1)!!.lastError)
        assertEquals(path(1), dao.get(1)!!.remotePath)
    }

    @Test fun damagedTotalLeavesOutBinRows() = runBlocking {
        put(row(1).copy(lastError = HealthCheck.NO_GOOD_COPY_ERROR))
        put(row(2, status = BackupStatus.REMOVED_FROM_PHONE).copy(lastError = HealthCheck.NO_GOOD_COPY_ERROR, binRequestedAt = 5))
        put(row(3, status = BackupStatus.BINNED).copy(lastError = HealthCheck.NO_GOOD_COPY_ERROR, binRequestedAt = 5))
        assertEquals(1, check().damagedTotal())
    }

    @Test fun noGoodCopyRowsStayInTheRotation() = runBlocking {
        put(row(1, checked = 5).copy(lastError = HealthCheck.NO_GOOD_COPY_ERROR), onDrive = bad)
        put(row(2, checked = 7))
        check(batch = 1).checkMain(main, null)
        assertEquals(now, dao.get(1)!!.lastCheckedAt)
        now += 10
        check(batch = 1).checkMain(main, null)
        assertEquals(now, dao.get(2)!!.lastCheckedAt) // row 1 no longer first
    }

    @Test fun rowChangedDuringRepairIsNotRepointed() = runBlocking {
        put(row(1), onDrive = bad)
        phone[1] = good
        main.failOn = { op, _ ->
            if (op == "rename") runBlocking { dao.upsert(dao.get(1)!!.copy(movingTo = "elsewhere")) }
            null
        }
        val pass = check().checkMain(main, null)
        assertEquals(0, pass.repaired)
        val e = dao.get(1)!!
        assertEquals(path(1), e.remotePath)
        assertEquals("elsewhere", e.movingTo)
        assertNull(e.lastError)
        // The unreferenced new copy (this repair's own file) is removed; the original stays.
        assertEquals(listOf("Backup\\Pixel\\Camera\\2024\\img1 (1).jpg"), main.deletes)
        assertArrayEquals(bad, main.files[path(1)])
    }

    // ---- network drops ----

    @Test fun aDropMidReadStopsThePassWithoutMarkingDamage() = runBlocking {
        put(row(1, checked = 1))
        put(row(2, checked = 2))
        put(row(3, checked = 3))
        main.failOn = { op, p -> if (op == "openRead" && p == path(2)) RemoteException.Unreachable() else null }
        val pass = check().checkMain(main, null)
        assertFalse(pass.completed)
        assertEquals(1, pass.checked)
        assertEquals(now, dao.get(1)!!.lastCheckedAt)
        assertEquals(2L, dao.get(2)!!.lastCheckedAt)
        assertNull(dao.get(2)!!.lastError)
        assertEquals(3L, dao.get(3)!!.lastCheckedAt)
    }

    @Test fun aPerFileErrorRotatesThatRowToTheBackAndThePassGoesOn() = runBlocking {
        put(row(1, checked = null), onDrive = bad)
        put(row(2, checked = 5))
        put(row(3, checked = 6))
        main.failOn = { op, p -> if (op == "stat" && p == path(1)) AccessDeniedException() else null }
        val pass = check(batch = 2).checkMain(main, null)
        assertTrue(pass.completed)
        assertEquals(1, pass.checked) // row 2; row 1 was not judged
        val one = dao.get(1)!!
        assertNull(one.lastError) // never marked damaged
        assertEquals(now, one.lastCheckedAt) // rotated to the back
        assertEquals(path(1), one.remotePath)
        assertEquals(now, dao.get(2)!!.lastCheckedAt)
        // Next pass starts with row 3, not row 1.
        now += 10
        check(batch = 1).checkMain(main, null)
        assertEquals(now, dao.get(3)!!.lastCheckedAt)
    }

    @Test fun aPlainIoErrorMidReadAlsoOnlySkipsThatRow() = runBlocking {
        put(row(1, checked = 1))
        put(row(2, checked = 2))
        main.failOn = { op, p -> if (op == "openRead" && p == path(1)) IOException("sharing violation") else null }
        val pass = check().checkMain(main, null)
        assertTrue(pass.completed)
        assertEquals(1, pass.checked)
        assertNull(dao.get(1)!!.lastError)
        assertEquals(now, dao.get(2)!!.lastCheckedAt)
    }

    @Test fun stepRecordsTheGateWhenAPassWithPerFileErrorsFinishes() = runBlocking {
        put(row(1))
        main.failOn = { op, _ -> if (op == "stat") AccessDeniedException() else null }
        val repo = repo()
        step(repo, null, mutableListOf()).runIfDue(main)
        assertEquals(now, repo.status.first().lastHealthCheckMs)
    }

    // ---- failed repairs clean up only what they created ----

    @Test fun aReadBackMismatchRemovesTheNewCopyAndKeepsTheOriginal() = runBlocking {
        put(row(1), onDrive = bad)
        phone[1] = good
        main.corruptReads = true // every read-back differs from what was written
        val pass = check().checkMain(main, null)
        assertEquals(0, pass.repaired)
        assertEquals(0, pass.damaged)
        val newName = "Backup\\Pixel\\Camera\\2024\\img1 (1).jpg"
        assertFalse(newName in main.files)
        assertFalse(main.files.keys.any { it.endsWith(".part") })
        assertArrayEquals(bad, main.files[path(1)])
        assertEquals(listOf(newName), main.deletes)
        assertEquals(path(1), dao.get(1)!!.remotePath)
        assertNull(dao.get(1)!!.lastError)
    }

    @Test fun aSourceThatChangesAfterItsProbeLeavesNoPart() = runBlocking {
        put(row(1), onDrive = bad)
        var opens = 0
        val hc = HealthCheck(
            ledger = dao, copies = db.secondCopyDao(),
            opener = { _ -> opens++; ByteArrayInputStream(if (opens == 1) good else bad) },
            clock = { now },
        )
        val pass = hc.checkMain(main, null)
        assertEquals(0, pass.repaired)
        assertEquals(listOf("Backup\\Pixel\\Camera\\2024\\img1 (1).jpg.part"), main.deletes)
        assertFalse(main.files.keys.any { it.endsWith(".part") })
        assertArrayEquals(bad, main.files[path(1)])
    }

    @Test fun secondDropDuringRepairLeavesTheRowForLater() = runBlocking {
        put(row(1, status = BackupStatus.REMOVED_FROM_PHONE), onDrive = bad)
        second.mkdirs("B2")
        second.files["B2\\img1.jpg"] = good
        second.failOn = { op, _ -> if (op == "openRead") RemoteException.Unreachable() else null }
        db.secondCopyDao().upsert(
            SecondCopy(1, "B2\\img1.jpg", null, null, SecondStatus.VERIFIED, good.size.toLong(), sha(good), null, 1)
        )
        val pass = check().checkMain(main) { second }
        assertEquals(0, pass.damaged)
        val e = dao.get(1)!!
        assertNull(e.lastError)
        assertNull(e.lastCheckedAt)
        assertEquals(path(1), e.remotePath)
    }

    // ---- gate and step ----

    @Test fun gateIsTwentyHours() {
        val h = 60L * 60 * 1000
        assertTrue(HealthGate.isDue(null, now))
        assertFalse(HealthGate.isDue(now - 20 * h + 1, now))
        assertTrue(HealthGate.isDue(now - 20 * h, now))
        assertTrue(HealthGate.isDue(now + h, now)) // clock went back: don't stay stuck
    }

    private fun repo() = BackupStatusRepository(PreferenceDataStoreFactory.create { tmp.newFile("s.preferences_pb").also { it.delete() } })

    private val cfg = SmbConfig(host = "h", share = "s", username = "u", password = "p")

    private fun step(repo: BackupStatusRepository, secondCfg: SmbConfig?, opened: MutableList<SmbConfig>) = HealthStep(
        status = repo,
        clock = { now },
        secondConfig = { secondCfg },
        openStore = { c -> opened += c; second },
        check = check(),
    )

    @Test fun stepRunsWhenDueAndRecordsTheGateAndCounts() = runBlocking {
        put(row(1), onDrive = bad)
        put(row(2))
        val repo = repo()
        val opened = mutableListOf<SmbConfig>()
        step(repo, null, opened).runIfDue(main)
        val s = repo.status.first()
        assertEquals(now, s.lastHealthCheckMs)
        assertEquals(2, s.healthChecked)
        assertEquals(1, s.healthDamaged)
        assertTrue(opened.isEmpty()) // second off: never opened
    }

    @Test fun stepSkipsWhenNotDue() = runBlocking {
        put(row(1))
        val repo = repo()
        repo.update { it.copy(lastHealthCheckMs = now - 1000) }
        step(repo, null, mutableListOf()).runIfDue(main)
        assertNull(dao.get(1)!!.lastCheckedAt)
        assertEquals(now - 1000, repo.status.first().lastHealthCheckMs)
    }

    @Test fun stepCutShortKeepsTheGateOpenAndNeverThrows() = runBlocking {
        put(row(1))
        main.failOn = { op, _ -> if (op == "stat") RemoteException.Unreachable() else null }
        val repo = repo()
        step(repo, null, mutableListOf()).runIfDue(main)
        assertNull(repo.status.first().lastHealthCheckMs)
    }

    @Test fun stepOpensTheSecondStoreOnlyWhenARepairNeedsIt() = runBlocking {
        put(row(1))
        second.mkdirs("B2")
        second.files["B2\\img1.jpg"] = good
        db.secondCopyDao().upsert(
            SecondCopy(1, "B2\\img1.jpg", null, null, SecondStatus.VERIFIED, good.size.toLong(), sha(good), null, 1)
        )
        val repo = repo()
        val opened = mutableListOf<SmbConfig>()
        step(repo, cfg, opened).runIfDue(main)
        assertTrue(opened.isEmpty())
        // Next day, damaged with the phone copy gone: the second store is opened once.
        now += 21L * 60 * 60 * 1000
        main.files[path(1)] = bad
        step(repo, cfg, opened).runIfDue(main)
        assertEquals(listOf(cfg), opened)
    }
}
