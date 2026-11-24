package dev.photobackup.records

import android.database.sqlite.SQLiteDatabase
import dev.photobackup.backup.BackupLock
import dev.photobackup.ledger.AppDatabase
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.media.MediaIdentity
import dev.photobackup.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.File
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * "Import records", in two steps around the confirm dialog:
 * 1. [inspect] copies the file's DB to [RecordsFiles.STAGING], checks it (entries, format, schema not newer than the
 *    app, opens and migrates with Room) and keeps the settings in memory. The live DB is never touched.
 * 2. [apply], under [BackupLock]: drops phone rows whose photo id now points to a different file (name or size), and
 *    gone rows without a drive copy, with their child rows, so the normal backup re-matches them by hash; commits the DB ([RecordsFiles.commit]) and
 *    restores the settings without passwords. The app then restarts and the new DB replaces the old one on open.
 * Any failure leaves the current records as they were. Never throws but cancellation; never logs or shows exception
 * text, paths or file contents. Drive files are never touched.
 */
class RecordsImporter(
    /** The app's databases folder (where `ledger.db` is). */
    private val dir: File,
    /** Opens a DB by name in [dir] with every migration and no WAL ([dev.photobackup.backup.LedgerDatabase.open]). */
    private val openStaged: (String) -> AppDatabase,
    /** What each MediaStore id points to now; throws [SecurityException] without full photo access. */
    private val identities: suspend (Collection<Long>) -> Map<Long, MediaIdentity>,
    private val restoreSettings: suspend (AppSettings) -> Unit,
    private val lock: Mutex = BackupLock.mutex,
) {
    @Volatile private var pending: AppSettings? = null

    suspend fun inspect(input: InputStream): InspectResult = withContext(Dispatchers.IO) {
        if (lock.isLocked) return@withContext InspectResult.Rejected(RecordsMessages.BUSY)
        pending = null
        RecordsFiles.clearStaging(dir)
        val result = try {
            stage(input)
        } catch (e: CancellationException) {
            RecordsFiles.clearStaging(dir)
            throw e
        } catch (_: Exception) {
            InspectResult.Rejected(RecordsMessages.DAMAGED)
        }
        if (result !is InspectResult.Ready) {
            pending = null
            RecordsFiles.clearStaging(dir)
        }
        result
    }

    private suspend fun stage(input: InputStream): InspectResult {
        dir.mkdirs()
        val staging = File(dir, RecordsFiles.STAGING)
        var meta: String? = null
        var settings: String? = null
        var hasDb = false
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    when (e.name) {
                        RecordsFormat.ENTRY_DB -> {
                            staging.outputStream().use {
                                zip.copyTo(it)
                                it.fd.sync()
                            }
                            hasDb = true
                        }
                        RecordsFormat.ENTRY_META -> meta = readSmall(zip)
                        RecordsFormat.ENTRY_SETTINGS -> settings = readSmall(zip)
                    }
                }
            }
        } catch (_: ZipException) {
            return InspectResult.Rejected(RecordsMessages.NOT_RECORDS)
        }
        val metaText = meta
        val settingsText = settings
        if (!hasDb || metaText == null || settingsText == null) return InspectResult.Rejected(RecordsMessages.NOT_RECORDS)
        val m = RecordsMeta.parse(metaText) ?: return InspectResult.Rejected(RecordsMessages.NOT_RECORDS)
        if (m.formatVersion != RecordsFormat.FORMAT_VERSION) return InspectResult.Rejected(RecordsMessages.UNSUPPORTED)
        if (m.schemaVersion > RecordsFormat.SCHEMA_VERSION) return InspectResult.Rejected(RecordsMessages.NEWER)
        val s = try {
            SettingsJson.decode(settingsText)
        } catch (_: JSONException) {
            return InspectResult.Rejected(RecordsMessages.DAMAGED)
        }
        // The file's own version, before Room sees it: 0 would make Room create empty tables in a foreign file.
        val version = try {
            SQLiteDatabase.openDatabase(
                staging.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ).use { it.version }
        } catch (_: Exception) {
            return InspectResult.Rejected(RecordsMessages.DAMAGED)
        }
        if (version > RecordsFormat.SCHEMA_VERSION) return InspectResult.Rejected(RecordsMessages.NEWER)
        if (version < 1) return InspectResult.Rejected(RecordsMessages.DAMAGED)
        val items = try {
            withStaged { countItems(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return InspectResult.Rejected(RecordsMessages.DAMAGED) // migration or schema check failed
        }
        pending = s
        return InspectResult.Ready(items)
    }

    suspend fun apply(): ImportResult = withContext(Dispatchers.IO) {
        val settings = pending
        if (settings == null || !File(dir, RecordsFiles.STAGING).isFile) {
            discard()
            return@withContext ImportResult.Failed(RecordsMessages.IMPORT_FAILED)
        }
        if (!lock.tryLock()) {
            discard()
            return@withContext ImportResult.Failed(RecordsMessages.BUSY)
        }
        try {
            val (items, dropped) = try {
                withStaged { sanitise(it) }
            } catch (_: SecurityException) {
                discard()
                return@withContext ImportResult.Failed(RecordsMessages.MEDIA_ACCESS)
            }
            withContext(NonCancellable) {
                RecordsFiles.commit(dir)
                pending = null
                val restored = try {
                    restoreSettings(settings)
                    true
                } catch (_: Exception) {
                    false
                }
                ImportResult.Done(items, dropped, restored)
            }
        } catch (e: CancellationException) {
            discard()
            throw e
        } catch (_: Exception) {
            discard()
            ImportResult.Failed(RecordsMessages.IMPORT_FAILED)
        } finally {
            lock.unlock()
        }
    }

    /** The confirm dialog was cancelled (or the import failed): forget the checked file. */
    fun discard() {
        pending = null
        RecordsFiles.clearStaging(dir)
    }

    /** Opens the staging DB (migrating and checking its schema), runs [block], closes it. */
    private suspend fun <T> withStaged(block: suspend (AppDatabase) -> T): T {
        val db = openStaged(RecordsFiles.STAGING)
        try {
            db.openHelper.writableDatabase
            return block(db)
        } finally {
            db.close()
        }
    }

    private fun countItems(db: AppDatabase): Int =
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM LedgerEntry").use { it.moveToFirst(); it.getInt(0) }

    /** Returns (rows kept, phone rows dropped). */
    private suspend fun sanitise(db: AppDatabase): Pair<Int, Int> {
        val sdb = db.openHelper.writableDatabase
        val statuses = PHONE_SIDE.joinToString(",") { "'${it.name}'" }
        val rows = sdb.query(
            "SELECT mediaId, displayName, sizeBytes, status, remotePath FROM LedgerEntry WHERE status IN ($statuses)"
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Row(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3), if (c.isNull(4)) null else c.getString(4)))
                }
            }
        }
        val now = if (rows.isEmpty()) emptyMap() else identities(rows.map { it.id })
        val drop = rows.filter { r ->
            when (val m = now[r.id]) {
                // Ids are never reused on the same phone: a gone id is the same item, deleted outside the app. A
                // VERIFIED row with its drive file is then a drive-only item (as the Gallery shows it): kept.
                null -> !(r.status == BackupStatus.VERIFIED.name && r.remotePath != null)
                else -> m.displayName != r.name || m.sizeBytes != r.size
            }
        }.map { it.id }
        sdb.beginTransaction()
        try {
            for (chunk in drop.chunked(500)) {
                val ids = chunk.joinToString(",")
                for (table in TABLES) sdb.execSQL("DELETE FROM `$table` WHERE mediaId IN ($ids)")
            }
            sdb.setTransactionSuccessful()
        } finally {
            sdb.endTransaction()
        }
        return countItems(db) to drop.size
    }

    private fun readSmall(zip: ZipInputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_JSON) throw ZipException("entry too large")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private data class Row(val id: Long, val name: String, val size: Long, val status: String, val remotePath: String?)

    private companion object {
        /** Rows whose item should still be on the phone. Freed (REMOVED_FROM_PHONE) and BINNED rows have no phone file. */
        val PHONE_SIDE = listOf(BackupStatus.PENDING, BackupStatus.UPLOADING, BackupStatus.VERIFIED, BackupStatus.FAILED)

        /** The ledger row and every table keyed by its media id (as RestoreDao's re-key moves them). */
        val TABLES = listOf("LedgerEntry", "FolderAssignment", "ReviewDecision", "ReviewKeep", "SecondCopy")

        const val MAX_JSON = 1024 * 1024
    }
}
