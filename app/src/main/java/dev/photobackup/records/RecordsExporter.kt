package dev.photobackup.records

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.photobackup.backup.BackupLock
import dev.photobackup.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A consistent single-file copy of [db] at [target] (`VACUUM INTO`: one read transaction, so a write meanwhile is
 * either wholly in or wholly out, and the copy has no WAL). [target] must not exist.
 */
fun vacuumInto(db: SupportSQLiteDatabase, target: File) {
    db.execSQL("VACUUM INTO '" + target.absolutePath.replace("'", "''") + "'")
}

/**
 * Settings → "Export records": the ledger (every table) and the settings without passwords, zipped to [export]'s
 * stream. Runs under [BackupLock] (refused while a backup or restore runs). Never throws but cancellation; never
 * shows or logs exception text.
 */
class RecordsExporter(
    /** Writes a consistent copy of the live ledger DB to the given (new) file: [vacuumInto] in production. */
    private val snapshot: suspend (File) -> Unit,
    private val readSettings: suspend () -> AppSettings,
    private val appVersion: String,
    /** For the temporary snapshot (app cache); it is deleted afterwards. */
    private val tempDir: File,
    private val lock: Mutex = BackupLock.mutex,
) {
    suspend fun export(out: OutputStream): ExportResult {
        if (!lock.tryLock()) return ExportResult.Failed(RecordsMessages.BUSY)
        val snap = File(tempDir, "records-export.db")
        return try {
            withContext(Dispatchers.IO) {
                val settings = readSettings()
                tempDir.mkdirs()
                deleteDb(snap)
                snapshot(snap)
                val (schema, items) = SQLiteDatabase.openDatabase(
                    snap.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                ).use { db ->
                    db.version to db.rawQuery("SELECT COUNT(*) FROM LedgerEntry", null).use { it.moveToFirst(); it.getInt(0) }
                }
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry(RecordsFormat.ENTRY_META))
                    zip.write(RecordsMeta(RecordsFormat.FORMAT_VERSION, schema, appVersion, items).toJson().toByteArray())
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry(RecordsFormat.ENTRY_SETTINGS))
                    zip.write(SettingsJson.encode(settings).toByteArray())
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry(RecordsFormat.ENTRY_DB))
                    snap.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                ExportResult.Done(items)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ExportResult.Failed(RecordsMessages.EXPORT_FAILED)
        } finally {
            runCatching { deleteDb(snap) }
            lock.unlock()
        }
    }

    private fun deleteDb(f: File) {
        f.delete()
        for (s in listOf("-wal", "-shm", "-journal")) File(f.path + s).delete()
    }
}
