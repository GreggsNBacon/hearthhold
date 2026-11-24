package dev.photobackup.backup

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.photobackup.ledger.AppDatabase
import dev.photobackup.ledger.FolderAssignmentDao
import dev.photobackup.ledger.LedgerDao
import dev.photobackup.ledger.ALL_MIGRATIONS
import dev.photobackup.ledger.LedgerRules
import dev.photobackup.media.FolderRules
import dev.photobackup.media.LocalMediaSource
import dev.photobackup.records.RecordsFiles
import dev.photobackup.settings.KeystoreCredentialCipher
import dev.photobackup.settings.SettingsStore
import dev.photobackup.smb.SmbjRemoteStore
import kotlinx.coroutines.flow.first
import java.time.LocalTime
import java.time.ZoneId

/** Thrown by a runner when there is no drive or destination configured yet. */
class SetupIncompleteException : Exception("Finish setup first")

fun interface BackupRunner {
    /** [onProgress] gets (itemsDone, itemsTotal, bytesDone, bytesTotal). */
    suspend fun run(onProgress: (done: Int, total: Int, bytes: Long, totalBytes: Long) -> Unit): BackupResult

    /** Like [run]; [onMoving] gets how many drive files are about to be moved to their folders. */
    suspend fun run(
        onProgress: (done: Int, total: Int, bytes: Long, totalBytes: Long) -> Unit,
        onMoving: (count: Int) -> Unit,
    ): BackupResult = run(onProgress)

    /**
     * Like [run]; [onResult] gets the backup's result as soon as it is final, before any read-only tail (the health
     * check) runs, so a cancel during the tail never loses it. Called at most once; the return value is the same result.
     */
    suspend fun run(
        onProgress: (done: Int, total: Int, bytes: Long, totalBytes: Long) -> Unit,
        onMoving: (count: Int) -> Unit,
        onResult: suspend (BackupResult) -> Unit,
    ): BackupResult = run(onProgress, onMoving).also { onResult(it) }
}

/** Process-wide runner factory so tests can swap the engine. No DI framework on purpose. */
object BackupRunnerFactory {
    val defaultCreate: (Context) -> BackupRunner = { ctx -> ProductionBackupRunner(ctx.applicationContext) }

    @Volatile var create: (Context) -> BackupRunner = defaultCreate

    /** Whether to post "Backup complete" (Settings toggle). */
    val defaultNotifyOnComplete: suspend (Context) -> Boolean = { ctx ->
        SettingsStore(ctx.applicationContext, KeystoreCredentialCipher()).settings.first().notifyOnComplete
    }

    @Volatile var notifyOnComplete: suspend (Context) -> Boolean = defaultNotifyOnComplete

    /** The overnight window when "Only back up overnight" is on, else null. */
    val defaultOvernightWindow: suspend (Context) -> OvernightWindow? = { ctx ->
        SettingsStore(ctx.applicationContext, KeystoreCredentialCipher()).settings.first().overnightWindow()
    }

    @Volatile var overnightWindow: suspend (Context) -> OvernightWindow? = defaultOvernightWindow

    /** Items still without a verified second copy (the status line's "N waiting"). */
    val defaultSecondWaiting: suspend (Context) -> Int = { ctx ->
        val db = LedgerDatabase.get(ctx.applicationContext)
        SecondWaiting.count(db.ledgerDao(), db.secondCopyDao())
    }

    @Volatile var secondWaiting: suspend (Context) -> Int = defaultSecondWaiting

    val defaultNow: () -> LocalTime = { LocalTime.now() }

    @Volatile var now: () -> LocalTime = defaultNow
}

object LedgerDatabase {
    @Volatile private var instance: AppDatabase? = null

    /**
     * The one ledger DB. Before it is first opened in a process, an imported records DB that is ready
     * ([RecordsFiles.READY]) replaces `ledger.db`: nothing has the file open yet, so the swap is a plain rename.
     */
    fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
        instance ?: run {
            try {
                RecordsFiles.promoteIfReady(context.applicationContext.getDatabasePath(RecordsFiles.LIVE).parentFile!!)
            } catch (_: Exception) {
                // Old records stay in use; the ready file is tried again next start.
            }
            open(context, RecordsFiles.LIVE)
        }.also { instance = it }
    }

    /**
     * A Room DB on [name] in the app's databases folder, with every migration. [staging]: an import being checked,
     * opened without WAL so it stays one self-contained file.
     */
    fun open(context: Context, name: String, staging: Boolean = false): AppDatabase =
        Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
            .addMigrations(*ALL_MIGRATIONS)
            .apply { if (staging) setJournalMode(RoomDatabase.JournalMode.TRUNCATE) }
            .build()
}

class ProductionBackupRunner(private val context: Context) : BackupRunner {
    override suspend fun run(
        onProgress: (done: Int, total: Int, bytes: Long, totalBytes: Long) -> Unit,
    ): BackupResult = run(onProgress) { }

    override suspend fun run(
        onProgress: (done: Int, total: Int, bytes: Long, totalBytes: Long) -> Unit,
        onMoving: (count: Int) -> Unit,
    ): BackupResult = run(onProgress, onMoving) { }

    override suspend fun run(
        onProgress: (done: Int, total: Int, bytes: Long, totalBytes: Long) -> Unit,
        onMoving: (count: Int) -> Unit,
        onResult: suspend (BackupResult) -> Unit,
    ): BackupResult {
        val settings = SettingsStore(context, KeystoreCredentialCipher()).settings.first()
        val smb = settings.smb
        val destination = settings.destination
        if (smb == null || destination.isNullOrBlank()) throw SetupIncompleteException()

        val ledger = LedgerDatabase.get(context).ledgerDao()
        val assignments = LedgerDatabase.get(context).folderAssignmentDao()
        val media = LocalMediaSource(context)
        val items = media.query(settings.folders) // may throw SecurityException without media permission
        val totalBytes = items
            .filter { LedgerRules.needsUpload(ledger.get(it.mediaId), it) }
            .sumOf { it.sizeBytes }

        // One store (one SMB session) shared by all parallel workers: smbj handles concurrent requests on a session.
        val store = SmbjRemoteStore(smb)
        try {
            val engine = BackupEngine(
                store = store,
                ledger = ledger,
                opener = media.opener(),
                settings = BackupSettings(destination, settings.deviceName, ZoneId.systemDefault()),
                clock = System::currentTimeMillis,
                parallelUploads = settings.parallelUploads,
                folderFor = { id, rel -> FolderRules.desired(rel, assignments.get(id)?.folder, settings.folderRules) },
                assignments = assignments,
            )
            val result = secondStep(media, ledger, assignments).around(smb, store) {
                engine.run(items, onMoving) { done, total, bytes -> onProgress(done, total, bytes, totalBytes) }
            }
            // The result is final: publish it before the health check, so a cancel during the check never loses it.
            onResult(result)
            // Health check last, in the same BackupLock hold, on the main store (still open after a Done run).
            if (result is BackupResult.Done) healthStep(media, ledger).runIfDue(store)
            return result
        } finally {
            runCatching { store.close() }
        }
    }

    /** The daily health check. The second location is re-read: off (or removed meanwhile) = no second-drive fallback. */
    private fun healthStep(media: LocalMediaSource, ledger: LedgerDao) = HealthStep(
        status = BackupStatusRepository.get(context),
        clock = System::currentTimeMillis,
        secondConfig = { SettingsStore(context, KeystoreCredentialCipher()).settings.first().second?.smb },
        openStore = { cfg -> SmbjRemoteStore(cfg) },
        check = HealthCheck(
            ledger = ledger,
            copies = LedgerDatabase.get(context).secondCopyDao(),
            opener = media.opener(),
            clock = System::currentTimeMillis,
        ),
    )

    /** The optional second step. It re-reads the settings itself, so a second location removed meanwhile is skipped. */
    private fun secondStep(media: LocalMediaSource, ledger: LedgerDao, assignments: FolderAssignmentDao) = SecondStep(
        readSettings = { SettingsStore(context, KeystoreCredentialCipher()).settings.first() },
        openStore = { cfg -> SmbjRemoteStore(cfg) },
        runEngine = { env ->
            SecondBackupEngine(
                store = env.store,
                mainStore = env.mainStore,
                ledger = ledger,
                copies = LedgerDatabase.get(context).secondCopyDao(),
                opener = media.opener(),
                settings = BackupSettings(env.second.destination, env.settings.deviceName, ZoneId.systemDefault()),
                clock = System::currentTimeMillis,
                folderFor = { id, rel -> FolderRules.desired(rel, assignments.get(id)?.folder, env.settings.folderRules) },
            ).run()
        },
    )
}
