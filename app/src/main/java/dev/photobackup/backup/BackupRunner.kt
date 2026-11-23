package dev.photobackup.backup

import android.content.Context
import androidx.room.Room
import dev.photobackup.ledger.AppDatabase
import dev.photobackup.ledger.LedgerRules
import dev.photobackup.media.LocalMediaSource
import dev.photobackup.settings.KeystoreCredentialCipher
import dev.photobackup.settings.SettingsStore
import dev.photobackup.smb.SmbjRemoteStore
import kotlinx.coroutines.flow.first
import java.time.ZoneId

/** Thrown by a runner when there is no drive or destination configured yet. */
class SetupIncompleteException : Exception("Finish setup first")

fun interface BackupRunner {
    /** [onProgress] gets (itemsDone, itemsTotal, bytesDone, bytesTotal). */
    suspend fun run(onProgress: (done: Int, total: Int, bytes: Long, totalBytes: Long) -> Unit): BackupResult
}

/** Process-wide runner factory so tests can swap the engine. No DI framework on purpose. */
object BackupRunnerFactory {
    val defaultCreate: (Context) -> BackupRunner = { ctx -> ProductionBackupRunner(ctx.applicationContext) }

    @Volatile var create: (Context) -> BackupRunner = defaultCreate
}

object LedgerDatabase {
    @Volatile private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "ledger.db")
            .build().also { instance = it }
    }
}

class ProductionBackupRunner(private val context: Context) : BackupRunner {
    override suspend fun run(
        onProgress: (done: Int, total: Int, bytes: Long, totalBytes: Long) -> Unit,
    ): BackupResult {
        val settings = SettingsStore(context, KeystoreCredentialCipher()).settings.first()
        val smb = settings.smb
        val destination = settings.destination
        if (smb == null || destination.isNullOrBlank()) throw SetupIncompleteException()

        val ledger = LedgerDatabase.get(context).ledgerDao()
        val media = LocalMediaSource(context)
        val items = media.query(settings.folders) // may throw SecurityException without media permission
        val totalBytes = items
            .filter { LedgerRules.needsUpload(ledger.get(it.mediaId), it) }
            .sumOf { it.sizeBytes }

        val store = SmbjRemoteStore(smb)
        try {
            val engine = BackupEngine(
                store = store,
                ledger = ledger,
                opener = media.opener(),
                settings = BackupSettings(destination, settings.deviceName, ZoneId.systemDefault()),
                clock = System::currentTimeMillis,
            )
            return engine.run(items) { done, total, bytes -> onProgress(done, total, bytes, totalBytes) }
        } finally {
            runCatching { store.close() }
        }
    }
}
