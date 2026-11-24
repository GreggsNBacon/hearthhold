package dev.photobackup.records

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import dev.photobackup.backup.LedgerDatabase
import dev.photobackup.ledger.AppDatabase
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.FolderAssignment
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.ReviewDecision
import dev.photobackup.ledger.ReviewKeep
import dev.photobackup.ledger.SecondCopy
import dev.photobackup.ledger.SecondStatus
import dev.photobackup.media.MediaIdentity
import dev.photobackup.media.PartialMediaAccessException
import dev.photobackup.review.ReviewAction
import dev.photobackup.settings.AppSettings
import dev.photobackup.settings.CredentialCipher
import dev.photobackup.settings.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
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
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private class EncCipher : CredentialCipher {
    override fun encrypt(plain: String) = "ENC(" + plain.reversed() + ")"
    override fun decrypt(cipher: String) = cipher.removePrefix("ENC(").removeSuffix(")").reversed()
}

private fun entry(id: Long, status: BackupStatus, name: String = "IMG_$id.jpg", size: Long = 1000 + id) = LedgerEntry(
    mediaId = id, localUri = "content://media/$id", displayName = name, relativePath = "DCIM/Camera/",
    sizeBytes = size, dateTakenMs = 1, dateModifiedSec = 1, isVideo = false, localSha256 = "sha$id",
    remotePath = "Photos/Pixel/Camera/2024/$name", partPath = null, status = status, lastError = null, updatedAtMs = 5,
)

@RunWith(RobolectricTestRunner::class)
// Plain Application: the app's onCreate starts WorkManager threads, which must not outlive a native-SQLite test.
@Config(sdk = [34], application = android.app.Application::class)
// VACUUM INTO needs SQLite 3.27+ (Android 11 ships 3.28); the legacy test SQLite is 3.8.
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RecordsImportTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dir: File
    private val open = mutableListOf<AppDatabase>()
    private val lock = Mutex()

    @Before fun setUp() {
        dir = ctx.getDatabasePath(RecordsFiles.LIVE).parentFile!!
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
    }

    @After fun tearDown() {
        open.forEach { runCatching { it.close() } }
    }

    private fun room(name: String): AppDatabase = LedgerDatabase.open(ctx, name).also { open += it }

    private fun store(name: String): SettingsStore {
        val ds = PreferenceDataStoreFactory.create { File(tmp.root, "$name.preferences_pb") }
        return SettingsStore(ctx, EncCipher(), ds)
    }

    /** Ids 1..6: 1-3 on the phone, 4 freed from the phone, 5 binned, 6 failed; children on 1 and 2. */
    private suspend fun fill(db: AppDatabase) {
        val l = db.ledgerDao()
        l.upsert(entry(1, BackupStatus.VERIFIED))
        l.upsert(entry(2, BackupStatus.VERIFIED))
        l.upsert(entry(3, BackupStatus.PENDING))
        l.upsert(entry(4, BackupStatus.REMOVED_FROM_PHONE))
        l.upsert(entry(5, BackupStatus.BINNED).copy(binRequestedAt = 7, binPath = "Photos/x.jpg", binnedAt = 8))
        l.upsert(entry(6, BackupStatus.FAILED))
        for (id in listOf(1L, 2L)) {
            db.folderAssignmentDao().set(FolderAssignment(id, "Dog$id"))
            db.reviewDecisionDao().upsert(ReviewDecision(id, ReviewAction.JUNK, 9))
            db.reviewKeepDao().upsert(ReviewKeep(id, 10))
            db.secondCopyDao().upsert(SecondCopy(id, "B/$id.jpg", null, null, SecondStatus.VERIFIED, 1000 + id, "sha$id", null, 11))
        }
    }

    /** The phone as it is now: every id in [ids] matches its ledger row. */
    private fun phoneMatching(vararg ids: Long): suspend (Collection<Long>) -> Map<Long, MediaIdentity> = { asked ->
        asked.filter { it in ids }.associateWith { MediaIdentity("IMG_$it.jpg", 1000 + it) }
    }

    private fun exporter(db: AppDatabase, settings: SettingsStore, l: Mutex = lock) = RecordsExporter(
        snapshot = { file -> vacuumInto(db.openHelper.writableDatabase, file) },
        readSettings = { settings.settings.first() },
        appVersion = "1.4.1",
        tempDir = tmp.newFolder(),
        lock = l,
    )

    private fun importer(
        settings: SettingsStore,
        phone: suspend (Collection<Long>) -> Map<Long, MediaIdentity> = phoneMatching(1, 2, 3, 6),
        l: Mutex = lock,
    ) = RecordsImporter(
        dir = dir,
        openStaged = { name -> LedgerDatabase.open(ctx, name, staging = true) },
        identities = phone,
        restoreSettings = { settings.importSettings(it) },
        lock = l,
    )

    private suspend fun exportedBytes(): ByteArray {
        val src = room("source.db")
        fill(src)
        val settings = store("a")
        settings.update { fullSettings() }
        val out = ByteArrayOutputStream()
        assertEquals(ExportResult.Done(6), exporter(src, settings).export(out))
        return out.toByteArray()
    }

    private fun entries(zip: ByteArray): Map<String, ByteArray> {
        val m = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                m[e.name] = z.readBytes()
            }
        }
        return m
    }

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Opens the live DB the way the app does at start (promoting a ready import first). */
    private fun liveAfterRestart(): AppDatabase {
        RecordsFiles.promoteIfReady(dir)
        return room(RecordsFiles.LIVE)
    }

    @Test fun exportHasTheThreeEntriesAndMeta() = runBlocking {
        val e = entries(exportedBytes())
        assertEquals(setOf("ledger.db", "settings.json", "meta.json"), e.keys)
        assertEquals(RecordsMeta(1, RecordsFormat.SCHEMA_VERSION, "1.4.1", 6), RecordsMeta.parse(String(e.getValue("meta.json"))))
    }

    @Test fun exportedFileContainsNoPasswordPlainOrEncrypted() = runBlocking {
        val zip = exportedBytes()
        val needles = listOf("main-secret-pw", "second-secret-pw").flatMap { listOf(it, EncCipher().encrypt(it)) }
        for ((name, bytes) in entries(zip)) {
            val text = String(bytes, Charsets.ISO_8859_1)
            for (n in needles) assertFalse("$name contains a password", text.contains(n))
        }
        val whole = String(zip, Charsets.ISO_8859_1)
        for (n in needles) assertFalse(whole.contains(n))
    }

    @Test fun roundTripRestoresEveryTableAndSettings() = runBlocking {
        val zip = exportedBytes()
        val target = store("b")
        val imp = importer(target, phone = phoneMatching(1, 2, 3, 6))
        assertEquals(InspectResult.Ready(6), imp.inspect(ByteArrayInputStream(zip)))
        assertEquals(ImportResult.Done(items = 6, dropped = 0, settingsRestored = true), imp.apply())

        val db = liveAfterRestart()
        assertEquals((1L..6L).toList(), db.ledgerDao().observeAll().first().map { it.mediaId }.sorted())
        assertEquals(entry(5, BackupStatus.BINNED).copy(binRequestedAt = 7, binPath = "Photos/x.jpg", binnedAt = 8), db.ledgerDao().get(5))
        assertEquals(listOf(1L, 2L), db.folderAssignmentDao().all().map { it.mediaId }.sorted())
        assertEquals(listOf(1L, 2L), db.reviewDecisionDao().all().map { it.mediaId }.sorted())
        assertEquals(listOf(1L, 2L), db.reviewKeepDao().all().map { it.mediaId }.sorted())
        assertEquals(listOf(1L, 2L), db.secondCopyDao().all().map { it.mediaId }.sorted())
        assertEquals(fullSettings().withoutPasswords(), target.settings.first())
    }

    @Test fun importReplacesExistingRecords() = runBlocking {
        val zip = exportedBytes()
        val live = room(RecordsFiles.LIVE)
        live.ledgerDao().upsert(entry(99, BackupStatus.VERIFIED))
        live.close()
        val imp = importer(store("b"))
        imp.inspect(ByteArrayInputStream(zip))
        imp.apply()
        val db = liveAfterRestart()
        assertNull(db.ledgerDao().get(99))
        assertEquals(6, db.ledgerDao().observeAll().first().size)
    }

    @Test fun sanitiseDropsMismatchedPhoneRowsWithChildrenAndKeepsFreedAndBinned() = runBlocking {
        val zip = exportedBytes()
        // 1 matches; 2 now points to another file (other name); 3 is gone; 6 has another size; 4 and 5 are not on the phone.
        val phone: suspend (Collection<Long>) -> Map<Long, MediaIdentity> = { asked ->
            mapOf(1L to MediaIdentity("IMG_1.jpg", 1001), 2L to MediaIdentity("OTHER.jpg", 1002), 6L to MediaIdentity("IMG_6.jpg", 5))
                .filterKeys { it in asked }
        }
        val imp = importer(store("b"), phone)
        imp.inspect(ByteArrayInputStream(zip))
        assertEquals(ImportResult.Done(items = 3, dropped = 3, settingsRestored = true), imp.apply())

        val db = liveAfterRestart()
        assertEquals(listOf(1L, 4L, 5L), db.ledgerDao().observeAll().first().map { it.mediaId }.sorted())
        assertEquals(listOf(1L), db.folderAssignmentDao().all().map { it.mediaId })
        assertEquals(listOf(1L), db.reviewDecisionDao().all().map { it.mediaId })
        assertEquals(listOf(1L), db.reviewKeepDao().all().map { it.mediaId })
        assertEquals(listOf(1L), db.secondCopyDao().all().map { it.mediaId })
    }

    @Test fun noPhotoAccessFailsAndChangesNothing() = runBlocking {
        val zip = exportedBytes()
        val before = liveSnapshot()
        val imp = importer(store("b"), phone = { throw PartialMediaAccessException() })
        imp.inspect(ByteArrayInputStream(zip))
        assertEquals(ImportResult.Failed(RecordsMessages.MEDIA_ACCESS), imp.apply())
        assertUnchanged(before)
    }

    /** A live DB with one row, and its bytes. */
    private suspend fun liveSnapshot(): ByteArray {
        val live = room(RecordsFiles.LIVE)
        live.ledgerDao().upsert(entry(77, BackupStatus.VERIFIED))
        live.close()
        open.remove(live)
        return File(dir, RecordsFiles.LIVE).readBytes()
    }

    private suspend fun assertUnchanged(before: ByteArray) {
        assertFalse(File(dir, RecordsFiles.READY).exists())
        assertFalse(File(dir, RecordsFiles.STAGING).exists())
        assertArrayEquals(before, File(dir, RecordsFiles.LIVE).readBytes())
        val db = liveAfterRestart()
        assertEquals(listOf(77L), db.ledgerDao().observeAll().first().map { it.mediaId })
    }

    private suspend fun assertRejected(bytes: ByteArray, message: String) {
        val before = liveSnapshot()
        val target = store("b")
        val imp = importer(target)
        assertEquals(InspectResult.Rejected(message), imp.inspect(ByteArrayInputStream(bytes)))
        assertEquals(ImportResult.Failed(RecordsMessages.IMPORT_FAILED), imp.apply())
        assertUnchanged(before)
        assertEquals(AppSettings(null, null).smb, target.settings.first().smb)
    }

    @Test fun rejectsNotAZip() = runBlocking {
        assertRejected("hello, not a zip at all".toByteArray(), RecordsMessages.NOT_RECORDS)
    }

    @Test fun rejectsMissingEntries() = runBlocking {
        val e = entries(exportedBytes())
        assertRejected(zipOf(e - "settings.json"), RecordsMessages.NOT_RECORDS)
    }

    @Test fun rejectsMissingDb() = runBlocking {
        val e = entries(exportedBytes())
        assertRejected(zipOf(e - "ledger.db"), RecordsMessages.NOT_RECORDS)
    }

    @Test fun rejectsOtherFormatVersion() = runBlocking {
        val e = entries(exportedBytes())
        val meta = RecordsMeta(2, RecordsFormat.SCHEMA_VERSION, "9.0", 6).toJson().toByteArray()
        assertRejected(zipOf(e + ("meta.json" to meta)), RecordsMessages.UNSUPPORTED)
    }

    @Test fun rejectsSchemaNewerThanTheApp() = runBlocking {
        val e = entries(exportedBytes())
        val meta = RecordsMeta(1, RecordsFormat.SCHEMA_VERSION + 1, "9.0", 6).toJson().toByteArray()
        assertRejected(zipOf(e + ("meta.json" to meta)), RecordsMessages.NEWER)
    }

    @Test fun rejectsDbWhoseOwnVersionIsNewer() = runBlocking {
        val e = entries(exportedBytes())
        val f = File(tmp.root, "newer.db")
        f.writeBytes(e.getValue("ledger.db"))
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 99 }
        assertRejected(zipOf(e + ("ledger.db" to f.readBytes())), RecordsMessages.NEWER)
    }

    @Test fun rejectsDbThatFailsToOpen() = runBlocking {
        val e = entries(exportedBytes())
        assertRejected(zipOf(e + ("ledger.db" to ByteArray(4096) { 7 })), RecordsMessages.DAMAGED)
    }

    @Test fun rejectsForeignSqliteFile() = runBlocking {
        val e = entries(exportedBytes())
        val f = File(tmp.root, "foreign.db")
        SQLiteDatabase.openOrCreateDatabase(f, null).use {
            it.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT)")
            it.version = 3
        }
        assertRejected(zipOf(e + ("ledger.db" to f.readBytes())), RecordsMessages.DAMAGED)
    }

    @Test fun rejectsBadSettingsJson() = runBlocking {
        val e = entries(exportedBytes())
        assertRejected(zipOf(e + ("settings.json" to "{{{".toByteArray())), RecordsMessages.DAMAGED)
    }

    @Test fun schema4FileMigrates() = runBlocking {
        val f = File(tmp.root, "v4.db")
        SQLiteDatabase.openOrCreateDatabase(f, null).use { v4 ->
            v4.execSQL(
                "CREATE TABLE IF NOT EXISTS `LedgerEntry` (`mediaId` INTEGER NOT NULL, `localUri` TEXT NOT NULL, " +
                    "`displayName` TEXT NOT NULL, `relativePath` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, " +
                    "`dateTakenMs` INTEGER NOT NULL, `dateModifiedSec` INTEGER NOT NULL, `isVideo` INTEGER NOT NULL, " +
                    "`localSha256` TEXT, `remotePath` TEXT, `partPath` TEXT, `status` TEXT NOT NULL, `lastError` TEXT, " +
                    "`updatedAtMs` INTEGER NOT NULL, `movingTo` TEXT, `binRequestedAt` INTEGER, `binPath` TEXT, " +
                    "`binnedAt` INTEGER, PRIMARY KEY(`mediaId`))"
            )
            v4.execSQL("CREATE TABLE IF NOT EXISTS `FolderAssignment` (`mediaId` INTEGER NOT NULL, `folder` TEXT NOT NULL, PRIMARY KEY(`mediaId`))")
            v4.execSQL("CREATE TABLE IF NOT EXISTS `ReviewDecision` (`mediaId` INTEGER NOT NULL, `action` TEXT NOT NULL, `decidedAt` INTEGER NOT NULL, PRIMARY KEY(`mediaId`))")
            v4.execSQL("CREATE TABLE IF NOT EXISTS `ReviewKeep` (`mediaId` INTEGER NOT NULL, `keptAt` INTEGER NOT NULL, PRIMARY KEY(`mediaId`))")
            v4.execSQL(
                "CREATE TABLE IF NOT EXISTS `SecondCopy` (`mediaId` INTEGER NOT NULL, `remotePath` TEXT, `partPath` TEXT, " +
                    "`movingTo` TEXT, `status` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `sha256` TEXT, " +
                    "`lastError` TEXT, `updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`mediaId`))"
            )
            v4.execSQL(
                "INSERT INTO LedgerEntry VALUES (1, 'content://media/1', 'IMG_1.jpg', 'DCIM/Camera/', 1001, 1, 1, 0, " +
                    "'sha1', 'Photos/a.jpg', NULL, 'VERIFIED', NULL, 5, NULL, NULL, NULL, NULL)"
            )
            v4.execSQL("INSERT INTO SecondCopy VALUES (1, 'B/a.jpg', NULL, NULL, 'VERIFIED', 1001, 'sha1', NULL, 6)")
            v4.version = 4
        }
        val zip = zipOf(
            mapOf(
                "ledger.db" to f.readBytes(),
                "settings.json" to SettingsJson.encode(fullSettings()).toByteArray(),
                "meta.json" to RecordsMeta(1, 4, "1.3.0", 1).toJson().toByteArray(),
            )
        )
        val imp = importer(store("b"), phone = phoneMatching(1))
        assertEquals(InspectResult.Ready(1), imp.inspect(ByteArrayInputStream(zip)))
        assertEquals(ImportResult.Done(1, 0, true), imp.apply())
        val db = liveAfterRestart()
        assertEquals("Photos/a.jpg", db.ledgerDao().get(1)!!.remotePath)
        assertNull(db.ledgerDao().get(1)!!.lastCheckedAt)
        assertEquals(SecondStatus.VERIFIED, db.secondCopyDao().get(1)!!.status)
    }

    @Test fun importRefusedWhileLockHeld() = runBlocking {
        val zip = exportedBytes()
        val before = liveSnapshot()
        val held = Mutex()
        val imp = importer(store("b"), l = held)
        assertEquals(InspectResult.Ready(6), imp.inspect(ByteArrayInputStream(zip)))
        held.lock()
        assertEquals(ImportResult.Failed(RecordsMessages.BUSY), imp.apply())
        assertUnchanged(before)
    }

    @Test fun inspectRefusedWhileLockHeld() = runBlocking {
        val zip = exportedBytes()
        val held = Mutex().also { it.lock() }
        assertEquals(InspectResult.Rejected(RecordsMessages.BUSY), importer(store("b"), l = held).inspect(ByteArrayInputStream(zip)))
        assertFalse(File(dir, RecordsFiles.STAGING).exists())
    }

    @Test fun exportRefusedWhileLockHeld() = runBlocking {
        val src = room("source.db")
        fill(src)
        val held = Mutex().also { it.lock() }
        val out = ByteArrayOutputStream()
        assertEquals(ExportResult.Failed(RecordsMessages.BUSY), exporter(src, store("a"), held).export(out))
        assertEquals(0, out.size())
    }

    @Test fun exportFailsCleanlyWhenSettingsUnreadable() = runBlocking {
        val src = room("source.db")
        fill(src)
        val ex = RecordsExporter(
            snapshot = { file -> vacuumInto(src.openHelper.writableDatabase, file) },
            readSettings = { throw java.io.IOException("nope") },
            appVersion = "1.4.1",
            tempDir = tmp.newFolder(),
            lock = lock,
        )
        assertEquals(ExportResult.Failed(RecordsMessages.EXPORT_FAILED), ex.export(ByteArrayOutputStream()))
        assertFalse(lock.isLocked)
    }

    @Test fun discardRemovesStaging() = runBlocking {
        val imp = importer(store("b"))
        assertTrue(imp.inspect(ByteArrayInputStream(exportedBytes())) is InspectResult.Ready)
        imp.discard()
        assertFalse(File(dir, RecordsFiles.STAGING).exists())
        assertEquals(ImportResult.Failed(RecordsMessages.IMPORT_FAILED), imp.apply())
        assertFalse(File(dir, RecordsFiles.READY).exists())
    }
}
