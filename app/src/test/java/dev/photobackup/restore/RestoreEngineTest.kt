package dev.photobackup.restore

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.photobackup.backup.BackupEngine
import dev.photobackup.backup.BackupResult
import dev.photobackup.backup.BackupSettings
import dev.photobackup.gallery.GalleryMerge
import dev.photobackup.gallery.Location
import dev.photobackup.gallery.PhoneMediaWriter
import dev.photobackup.ledger.AppDatabase
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.FolderAssignment
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.SecondCopy
import dev.photobackup.ledger.SecondStatus
import dev.photobackup.ledger.LedgerRules
import dev.photobackup.ledger.ReviewKeep
import dev.photobackup.media.FolderRules
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.RemoteException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.ZoneOffset

/** In-memory phone: MediaStore pending rows, published items, and the existing gallery. */
class FakePhone : RestorePhone {
    class Created(val name: String, val mime: String, val relativePath: String, val isVideo: Boolean)

    var nextId = 5000L
    val created = mutableMapOf<String, Created>()
    val data = mutableMapOf<String, ByteArrayOutputStream>()
    val pending = mutableSetOf<String>()
    val published = mutableListOf<String>()
    val discarded = mutableListOf<String>()
    val dates = mutableMapOf<String, Pair<Long, Long>>()

    /** Relative paths MediaStore refuses for any media type (like a non-standard top folder). */
    val refused = mutableSetOf<String>()

    /** Flip a byte on read-back (the phone stored something else). */
    var corruptReadBack = false
    var free = Long.MAX_VALUE / 4

    /** Items already in the phone gallery (not written by restore). */
    val existing = mutableListOf<Pair<PhoneMedia, ByteArray>>()

    /** Called before each chunk is written; lets a test cancel mid-copy. */
    var onWrite: (String) -> Unit = {}

    override val writer: PhoneMediaWriter = object : PhoneMediaWriter {
        override fun createPending(displayName: String, mimeType: String, relativePath: String, isVideo: Boolean): String {
            if (relativePath in refused) throw IllegalArgumentException("Primary directory not allowed")
            val uri = "content://media/external/${if (isVideo) "video" else "images"}/media/${nextId++}"
            created[uri] = Created(displayName, mimeType, relativePath, isVideo)
            pending += uri
            return uri
        }

        override fun openOutput(uri: String): OutputStream {
            val buf = ByteArrayOutputStream().also { data[uri] = it }
            return object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    onWrite(uri)
                    buf.write(b, off, len)
                }
            }
        }

        override fun publish(uri: String) {
            pending -= uri
            published += uri
        }

        override fun discard(uri: String) {
            pending -= uri
            published -= uri
            data -= uri
            discarded += uri
        }
    }

    override fun setDates(uri: String, dateTakenMs: Long, dateModifiedSec: Long) {
        dates[uri] = dateTakenMs to dateModifiedSec
    }

    override fun openInput(uri: String): InputStream? {
        existing.firstOrNull { it.first.uri == uri }?.let { return ByteArrayInputStream(it.second) }
        val bytes = data[uri]?.toByteArray() ?: return null
        if (corruptReadBack && bytes.isNotEmpty()) bytes[0] = (bytes[0].toInt() xor 0xFF).toByte()
        return ByteArrayInputStream(bytes)
    }

    override fun item(uri: String, isVideo: Boolean): PhoneMedia? {
        if (uri !in published) return null
        val c = created.getValue(uri)
        return PhoneMedia(
            uri.substringAfterLast('/').toLong(), uri, c.name, c.relativePath, data.getValue(uri).size().toLong(),
            0, PUBLISHED_SEC, PUBLISHED_SEC, c.isVideo, c.mime,
        )
    }

    /** Thrown by [findSame] (e.g. SecurityException without photo access). */
    var findSameError: Exception? = null

    override fun findSame(displayName: String, sizeBytes: Long, isVideo: Boolean): List<PhoneMedia> =
        findSameError?.let { throw it } ?: existing.map { it.first }.filter { it.displayName == displayName && it.sizeBytes == sizeBytes }

    override fun freeBytes(): Long = free

    /** What the next backup run would see on the phone. */
    fun allItems(): List<PhoneMedia> = existing.map { it.first } + published.mapNotNull { item(it, created.getValue(it).isVideo) }

    companion object {
        const val PUBLISHED_SEC = 1_800_000_000L
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreEngineTest {
    private lateinit var db: AppDatabase
    private val main = FakeRemoteStore()
    private val second = FakeRemoteStore()
    private val phone = FakePhone()
    private var secondOn = true
    private val root = "Backup\\Pixel"
    private val now = 1_000_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun bytes(id: Long) = "photo bytes of item $id".toByteArray()

    private fun path(id: Long, folder: String = "Camera", year: String = "2024") = "$root\\$folder\\$year\\img$id.jpg"

    private fun row(id: Long, remote: String = path(id), data: ByteArray = bytes(id)) = LedgerEntry(
        mediaId = id, localUri = "content://media/external/images/media/$id", displayName = "img$id.jpg",
        relativePath = "DCIM/Camera/", sizeBytes = data.size.toLong(), dateTakenMs = 1_700_000_000_000,
        dateModifiedSec = 1_700_000_000, isVideo = false, localSha256 = sha(data), remotePath = remote, partPath = null,
        status = BackupStatus.REMOVED_FROM_PHONE, lastError = null, updatedAtMs = 1, lastCheckedAt = 77,
    )

    /** A drive-only row whose main drive file holds [onMain] (null = missing). */
    private suspend fun put(e: LedgerEntry, onMain: ByteArray? = bytes(e.mediaId)) {
        db.ledgerDao().upsert(e)
        if (onMain != null) {
            main.mkdirs(e.remotePath!!.substringBeforeLast('\\'))
            main.files[e.remotePath!!] = onMain
        }
    }

    private suspend fun putSecond(id: Long, data: ByteArray = bytes(id), status: SecondStatus = SecondStatus.VERIFIED) {
        val p = "Second\\Pixel\\Camera\\2024\\img$id.jpg"
        db.secondCopyDao().upsert(SecondCopy(id, p, null, null, status, data.size.toLong(), sha(bytes(id)), null, 5, 66))
        second.mkdirs(p.substringBeforeLast('\\'))
        second.files[p] = data
    }

    private fun engine() = RestoreEngine(
        ledger = db.restoreDao(),
        phone = phone,
        root = root,
        openMain = { main.also { it.connect() } },
        openSecond = if (secondOn) ({ second.also { it.connect() } }) else null,
        clock = { now },
    )

    private val all = RestoreSelection.EVERYTHING

    // ---- plan ----

    @Test fun planGroupsCandidatesByFolderAndYearWithOtherForOddPaths() = runBlocking {
        put(row(1))
        put(row(2, remote = path(2, year = "2023")))
        put(row(3, remote = path(3, folder = "WhatsApp")))
        put(row(4, remote = "Old\\2019\\05\\img4.jpg"))
        put(row(5))
        // Not candidates: binned/bin request, move or .part pending, no hash, still on phone.
        put(row(6).copy(binRequestedAt = 9))
        put(row(7).copy(status = BackupStatus.BINNED))
        put(row(8).copy(movingTo = "x"))
        put(row(9).copy(partPath = "y.part"))
        put(row(10).copy(localSha256 = null))
        put(row(11).copy(status = BackupStatus.VERIFIED))
        val plan = engine().plan(all)
        val groups = plan.groups.associate { it.key to it.count }
        assertEquals(
            mapOf(
                RestoreGroupKey("Camera", "2024") to 2,
                RestoreGroupKey("Camera", "2023") to 1,
                RestoreGroupKey("WhatsApp", "2024") to 1,
                RestoreGroupKey.OTHER to 1,
            ),
            groups,
        )
        assertEquals(5, plan.selectedCount)
        assertEquals((1L..5).sumOf { bytes(it).size.toLong() }, plan.selectedBytes)
        assertTrue(plan.fits)

        val one = engine().plan(RestoreSelection(setOf(RestoreGroupKey("Camera", "2024"))))
        assertEquals(2, one.selectedCount)
    }

    @Test fun freeSpaceRefusalWritesNothing() = runBlocking {
        put(row(1))
        phone.free = bytes(1).size + RestoreEngine.MARGIN_BYTES - 1
        val plan = engine().plan(all)
        assertFalse(plan.fits)
        val r = engine().run(all) {}
        assertNotNull(r.stopped)
        assertTrue(r.stopped!!.startsWith("Not enough space on the phone"))
        assertEquals(0, r.restored)
        assertTrue(phone.created.isEmpty())
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, db.ledgerDao().get(1)!!.status)
    }

    // ---- happy path ----

    @Test fun happyPathWritesOriginalFolderNameAndDateThenReKeys() = runBlocking {
        put(row(1))
        db.folderAssignmentDao().set(FolderAssignment(1, "Holiday"))
        putSecond(1)
        val r = engine().run(all) {}
        assertEquals(1, r.restored)
        assertTrue(r.failed.isEmpty())
        assertNull(r.stopped)

        val uri = phone.published.single()
        val c = phone.created.getValue(uri)
        assertEquals("img1.jpg", c.name)
        assertEquals("DCIM/Camera/", c.relativePath)
        assertEquals("image/jpeg", c.mime)
        assertArrayEquals(bytes(1), phone.data.getValue(uri).toByteArray())
        assertEquals(1_700_000_000_000L, phone.dates.getValue(uri).first)
        assertTrue(phone.pending.isEmpty())

        val newId = uri.substringAfterLast('/').toLong()
        assertNull(db.ledgerDao().get(1))
        val e = db.ledgerDao().get(newId)!!
        assertEquals(BackupStatus.VERIFIED, e.status)
        assertEquals(uri, e.localUri)
        assertEquals(path(1), e.remotePath)
        assertEquals(sha(bytes(1)), e.localSha256)
        assertEquals(FakePhone.PUBLISHED_SEC, e.dateModifiedSec)
        assertEquals(77L, e.lastCheckedAt)
        assertNull(db.folderAssignmentDao().get(1))
        assertEquals("Holiday", db.folderAssignmentDao().get(newId)!!.folder)
        assertNull(db.secondCopyDao().get(1))
        val sc = db.secondCopyDao().get(newId)!!
        assertEquals(SecondStatus.VERIFIED, sc.status)
        assertEquals(66L, sc.lastCheckedAt)
        // Drive untouched.
        assertTrue(main.deletes.isEmpty())
        assertTrue(main.writes.isEmpty())
        // The Gallery shows it once, on phone and drive.
        val items = GalleryMerge.merge(phone.allItems(), allRows())
        assertEquals(1, items.size)
        assertEquals(Location.BOTH, items.single().location)
    }

    private suspend fun allRows(): List<LedgerEntry> = BackupStatus.entries.flatMap { db.ledgerDao().byStatus(it) }

    @Test fun nextBackupRunDoesNotReUpload() = runBlocking {
        put(row(1))
        engine().run(all) {}
        val backup = BackupEngine(
            store = main, ledger = db.ledgerDao(), opener = { m -> phone.openInput(m.uri) },
            settings = BackupSettings("Backup", "Pixel", ZoneOffset.UTC), clock = { now }, itemPauseMs = 0,
        )
        val r = backup.run(phone.allItems()) { _, _, _ -> } as BackupResult.Done
        assertEquals(0, r.uploaded)
        assertTrue(main.writes.isEmpty())
        assertEquals(1, main.files.size)
    }

    @Test fun refusedFolderFallsBackToRestoredAndReports() = runBlocking {
        put(row(1).copy(relativePath = "Odd/Place/"))
        put(row(2).copy(relativePath = "Odd/Place/", isVideo = true, displayName = "clip2.mp4"))
        phone.refused += "Odd/Place/"
        val r = engine().run(all) {}
        assertEquals(2, r.restored)
        val paths = phone.published.map { phone.created.getValue(it).relativePath }.toSet()
        assertEquals(setOf(RestoreEngine.PHOTO_FALLBACK, RestoreEngine.VIDEO_FALLBACK), paths)
        assertEquals(setOf("img1.jpg", "clip2.mp4"), r.redirected.toSet())
        // The ledger keeps the original phone folder, so folder rules never move the drive file.
        val ids = phone.published.map { it.substringAfterLast('/').toLong() }
        assertTrue(ids.all { db.ledgerDao().get(it)!!.relativePath == "Odd/Place/" })
    }

    // ---- failures ----

    @Test fun readBackMismatchDiscardsAndReports() = runBlocking {
        put(row(1))
        phone.corruptReadBack = true
        val r = engine().run(all) {}
        assertEquals(0, r.restored)
        assertEquals(RestoreEngine.PHONE_MISMATCH, r.failed.single().reason)
        assertEquals("img1.jpg", r.failed.single().name)
        assertTrue(phone.published.isEmpty())
        assertTrue(phone.pending.isEmpty())
        assertEquals(1, phone.discarded.size)
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, db.ledgerDao().get(1)!!.status)
    }

    @Test fun mainMissingFallsBackToVerifiedSecond() = runBlocking {
        put(row(1), onMain = null)
        putSecond(1)
        val r = engine().run(all) {}
        assertEquals(1, r.restored)
        val uri = phone.published.single()
        assertArrayEquals(bytes(1), phone.data.getValue(uri).toByteArray())
        val e = db.ledgerDao().get(uri.substringAfterLast('/').toLong())!!
        assertEquals(path(1), e.remotePath) // still the main drive path
    }

    @Test fun mainDamagedFallsBackToSecondAndBadCopyNeverPublished() = runBlocking {
        val bad = bytes(1).copyOf().also { it[0] = 'X'.code.toByte() }
        put(row(1), onMain = bad)
        putSecond(1)
        val r = engine().run(all) {}
        assertEquals(1, r.restored)
        assertArrayEquals(bytes(1), phone.data.getValue(phone.published.single()).toByteArray())
        assertTrue(main.deletes.isEmpty())
    }

    @Test fun noGoodCopyWhenSecondNotVerifiedOrBad() = runBlocking {
        put(row(1), onMain = null)
        putSecond(1, status = SecondStatus.FAILED)
        put(row(2), onMain = null)
        putSecond(2, data = "wrong".toByteArray())
        val r = engine().run(all) {}
        assertEquals(0, r.restored)
        assertEquals(listOf(RestoreEngine.NO_GOOD_COPY, RestoreEngine.NO_GOOD_COPY), r.failed.map { it.reason })
        assertTrue(phone.published.isEmpty())
        assertTrue(phone.pending.isEmpty())
    }

    @Test fun secondLocationOffMeansNoFallbackAttempt() = runBlocking {
        secondOn = false
        put(row(1), onMain = null)
        putSecond(1)
        second.failOn = { _, _ -> throw AssertionError("second drive must not be touched") }
        val r = engine().run(all) {}
        assertEquals(RestoreEngine.NO_GOOD_COPY, r.failed.single().reason)
    }

    @Test fun mainUnreachableUsesSecondPerItem() = runBlocking {
        put(row(1))
        putSecond(1)
        main.failOn = { op, _ -> if (op == "openRead" || op == "stat") RemoteException.Unreachable() else null }
        val r = engine().run(all) {}
        assertEquals(1, r.restored)
        assertNull(r.stopped)
    }

    @Test fun bothUnreachableStopsKeepingFinishedItems() = runBlocking {
        put(row(1))
        put(row(2))
        putSecond(2)
        var reads = 0
        main.failOn = { op, _ -> if (op == "openRead" && ++reads > 1) RemoteException.Unreachable() else null }
        second.failOn = { _, _ -> RemoteException.Unreachable() }
        val r = engine().run(all) {}
        assertEquals(1, r.restored)
        assertEquals(RestoreEngine.UNREACHABLE, r.stopped)
        assertTrue(phone.pending.isEmpty())
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, db.ledgerDao().get(2)!!.status)
    }

    @Test fun mainUnreachableWithSecondOffStops() = runBlocking {
        secondOn = false
        put(row(1))
        main.failOn = { op, _ -> if (op == "connect") RemoteException.Unreachable() else null }
        val r = engine().run(all) {}
        assertEquals(RestoreEngine.UNREACHABLE, r.stopped)
        assertTrue(phone.created.isEmpty())
    }

    @Test fun rowChangedMeanwhileDiscardsPhoneCopy() = runBlocking {
        put(row(1))
        // Free up / review / bin touched the row while the copy was in flight.
        phone.onWrite = { runBlocking { db.ledgerDao().upsert(row(1).copy(binRequestedAt = 3)) } }
        val r = engine().run(all) {}
        assertEquals(0, r.restored)
        assertEquals(RestoreEngine.CHANGED, r.failed.single().reason)
        assertTrue(phone.published.isEmpty())
        assertTrue(phone.pending.isEmpty())
        assertEquals(3L, db.ledgerDao().get(1)!!.binRequestedAt)
    }

    // ---- already on the phone ----

    @Test fun alreadyOnPhoneIsReKeyedToTheExistingItemWithoutWriting() = runBlocking {
        put(row(1))
        val m = PhoneMedia(
            900, "content://media/external/images/media/900", "img1.jpg", "DCIM/Camera/", bytes(1).size.toLong(),
            0, 10, 1_750_000_000, false, "image/jpeg",
        )
        phone.existing += m to bytes(1)
        // Same name and size but different bytes: not the same photo.
        val other = m.copy(mediaId = 901, uri = "content://media/external/images/media/901")
        phone.existing.add(0, other to bytes(1).copyOf().also { it[1] = 'Q'.code.toByte() })
        val r = engine().run(all) {}
        assertEquals(0, r.restored)
        assertEquals(1, r.alreadyThere)
        assertTrue(phone.created.isEmpty())
        assertNull(db.ledgerDao().get(1))
        val e = db.ledgerDao().get(900)!!
        assertEquals(BackupStatus.VERIFIED, e.status)
        assertEquals(1_750_000_000L, e.dateModifiedSec)
        assertEquals(path(1), e.remotePath)
    }

    @Test fun alreadyOnPhoneWithItsOwnRowIsLeftAlone() = runBlocking {
        put(row(1))
        val m = PhoneMedia(
            900, "content://media/external/images/media/900", "img1.jpg", "DCIM/Camera/", bytes(1).size.toLong(),
            0, 10, 1_750_000_000, false, "image/jpeg",
        )
        phone.existing += m to bytes(1)
        db.ledgerDao().upsert(row(900).copy(status = BackupStatus.VERIFIED, remotePath = "elsewhere"))
        val r = engine().run(all) {}
        assertEquals(1, r.alreadyThere)
        assertTrue(phone.created.isEmpty())
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, db.ledgerDao().get(1)!!.status)
        assertEquals("elsewhere", db.ledgerDao().get(900)!!.remotePath)
    }

    @Test fun alreadyOnPhoneWithTheSameIdAsTheRowReKeysInPlace() = runBlocking {
        put(row(1))
        val m = PhoneMedia(
            1, "content://media/external/images/media/1", "img1.jpg", "DCIM/Camera/", bytes(1).size.toLong(),
            0, 10, 1_750_000_000, false, "image/jpeg",
        )
        phone.existing += m to bytes(1)
        val r = engine().run(all) {}
        assertEquals(1, r.alreadyThere)
        assertTrue(phone.created.isEmpty())
        val e = db.ledgerDao().get(1)!!
        assertEquals(BackupStatus.VERIFIED, e.status)
        assertEquals(1_750_000_000L, e.dateModifiedSec)
    }

    // ---- cancel ----

    @Test fun cancelKeepsFinishedItemsAndDiscardsTheOneInFlight() = runBlocking {
        put(row(1))
        put(row(2))
        put(row(3))
        lateinit var job: Job
        var last: RestoreProgress? = null
        phone.onWrite = { uri -> if (phone.published.size == 1 && uri !in phone.published) job.cancel() }
        job = launch { engine().run(all) { last = it } }
        job.join()
        assertEquals(1, phone.published.size)
        assertTrue(phone.pending.isEmpty())
        assertEquals(1, phone.discarded.size)
        assertEquals(1, last!!.result.restored)
        val newId = phone.published.single().substringAfterLast('/').toLong()
        assertEquals(BackupStatus.VERIFIED, db.ledgerDao().get(newId)!!.status)
        assertEquals(2, listOf(1L, 2L, 3L).count { db.ledgerDao().get(it)?.status == BackupStatus.REMOVED_FROM_PHONE })
    }

    @Test fun progressCountsEveryItem() = runBlocking {
        put(row(1))
        put(row(2), onMain = null)
        secondOn = false
        val seen = mutableListOf<Pair<Int, Int>>()
        engine().run(all) { seen += it.done to it.total }
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), seen)
    }

    // ---- main drive dropping partway ----

    /** Main openRead fails from call [from] on, for [count] calls (default: for good). */
    private fun mainReadsFail(from: Int, count: Int = 1_000_000) {
        var reads = 0
        main.failOn = { op, _ ->
            if (op == "openRead") {
                reads++
                if (reads >= from && reads < from + count) RemoteException.Unreachable() else null
            } else null
        }
    }

    @Test fun mainDropsPartwayAndItemHasNoSecondCopyStopsTheJob() = runBlocking {
        put(row(1))
        put(row(2))
        put(row(3))
        putSecond(3)
        mainReadsFail(from = 2)
        val r = engine().run(all) {}
        assertEquals(1, r.restored)
        assertEquals(RestoreEngine.UNREACHABLE, r.stopped)
        assertTrue(r.failed.isEmpty())
        assertTrue(phone.pending.isEmpty())
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, db.ledgerDao().get(2)!!.status)
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, db.ledgerDao().get(3)!!.status)
    }

    @Test fun mainDropsPartwaySecondServesCoveredItemsThenStops() = runBlocking {
        put(row(1))
        put(row(2))
        put(row(3))
        put(row(4))
        putSecond(2)
        putSecond(3)
        mainReadsFail(from = 2)
        val r = engine().run(all) {}
        assertEquals(3, r.restored)
        assertEquals(RestoreEngine.UNREACHABLE, r.stopped)
        assertTrue(r.failed.isEmpty())
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, db.ledgerDao().get(4)!!.status)
    }

    @Test fun mainBlipIsReopenedOnceAndTheRunCarriesOn() = runBlocking {
        secondOn = false
        put(row(1))
        put(row(2))
        put(row(3))
        var connects = 0
        var reads = 0
        main.failOn = { op, _ ->
            when (op) {
                "connect" -> { connects++; null }
                "openRead" -> if (++reads == 2) RemoteException.Unreachable() else null
                else -> null
            }
        }
        val r = engine().run(all) {}
        assertEquals(3, r.restored)
        assertNull(r.stopped)
        assertEquals(2, connects)
    }

    // ---- fallback folder vs. the next backup ----

    @Test fun fallbackFolderItemIsNotReUploadedNorMovedByFolderRules() = runBlocking {
        val remote = path(1, folder = "Odd")
        put(row(1, remote = remote).copy(relativePath = "Pictures/Odd/"))
        phone.refused += "Pictures/Odd/"
        engine().run(all) {}
        val item = phone.allItems().single()
        assertEquals(RestoreEngine.PHOTO_FALLBACK, item.relativePath)
        val e = db.ledgerDao().get(item.mediaId)!!
        assertFalse(LedgerRules.needsUpload(e, item))
        assertFalse(LedgerRules.changed(e, item))
        assertTrue(LedgerRules.isSafeToRemove(e, item))
        // A rule for the fallback folder must not pull the drive file into another folder.
        val rules = mapOf("DCIM/Restored" to "Restored")
        val backup = BackupEngine(
            store = main, ledger = db.ledgerDao(), opener = { m -> phone.openInput(m.uri) },
            settings = BackupSettings("Backup", "Pixel", ZoneOffset.UTC), clock = { now }, itemPauseMs = 0,
            folderFor = { _, rel -> FolderRules.desired(rel, null, rules) },
        )
        val r = backup.run(phone.allItems()) { _, _, _ -> } as BackupResult.Done
        assertEquals(0, r.uploaded)
        assertEquals(0, r.moved)
        assertTrue(main.writes.isEmpty())
        assertEquals(setOf(remote), main.files.keys)
        assertEquals(remote, db.ledgerDao().get(item.mediaId)!!.remotePath)
    }

    // ---- minors ----

    @Test fun reviewRowsMoveWithTheReKey() = runBlocking {
        put(row(1))
        db.reviewKeepDao().upsert(ReviewKeep(1, 42))
        engine().run(all) {}
        val newId = phone.published.single().substringAfterLast('/').toLong()
        assertEquals(42L, db.reviewKeepDao().get(newId)!!.keptAt)
        assertNull(db.reviewKeepDao().get(1))
    }

    @Test fun noPhotoAccessForTheAlreadyThereCheckWritesNothing() = runBlocking {
        put(row(1))
        phone.findSameError = SecurityException("no access")
        val r = engine().run(all) {}
        assertEquals(RestoreEngine.NO_PHOTO_ACCESS, r.failed.single().reason)
        assertTrue(phone.created.isEmpty())
    }

    @Test fun unexpectedDriveErrorOnOpenStillDiscardsThePendingItem() = runBlocking {
        put(row(1))
        main.failOn = { op, _ -> if (op == "openRead") throw IllegalStateException("boom") else null }
        val r = engine().run(all) {}
        assertEquals(RestoreEngine.DRIVE_READ, r.failed.single().reason)
        assertTrue(phone.pending.isEmpty())
        assertEquals(1, phone.discarded.size)
    }

}
