package dev.photobackup.backup

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.photobackup.smb.RemoteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** ETA = remaining bytes / average bytes-per-second of the current run; null until 1 s has elapsed. */
object BackupEta {
    fun seconds(startMs: Long, nowMs: Long, bytesDone: Long, totalBytes: Long): Long? {
        val elapsedMs = nowMs - startMs
        if (elapsedMs < 1000 || bytesDone <= 0) return null
        val remaining = (totalBytes - bytesDone).coerceAtLeast(0)
        return remaining * elapsedMs / (bytesDone * 1000)
    }
}

object BackupMessages {
    const val UNREACHABLE = "Drive not reachable — not at home?"
    const val AUTH = "Wrong username or password"
    const val SHARE = "Shared folder not found on the drive"
    const val SMB1 = "Turn on SMB2 on the router: USB Application → Network Place (Samba) Share"
    const val FULL = "The drive is full"
    const val SETUP = "Finish setup first"
    const val PERMISSION = "Photos permission needed"
}

/** Only one backup run per process: a second engine would delete the first one's in-flight .part files. */
object BackupLock {
    val mutex = Mutex()
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private data class Progress(val done: Int, val total: Int, val bytes: Long, val totalBytes: Long)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0, null)

    private fun foregroundInfo(done: Int, total: Int, etaSec: Long?) = ForegroundInfo(
        BackupNotifications.PROGRESS_ID,
        BackupNotifications.progress(applicationContext, done, total, etaSec),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    override suspend fun doWork(): Result {
        val repo = BackupStatusRepository.get(applicationContext)
        val chargingOnly = inputData.getBoolean(BackupScheduler.KEY_CHARGING_ONLY, false)
        val isTrigger = inputData.getBoolean(BackupScheduler.KEY_IS_TRIGGER, false)
        try {
            if (!BackupLock.mutex.tryLock()) return Result.success() // another run is active; leave status alone
            try {
                return runBackup(repo)
            } finally {
                BackupLock.mutex.unlock()
            }
        } finally {
            // Last on purpose: REPLACE on pb_trigger cancels this very worker when it is the trigger.
            try {
                BackupScheduler.enqueueTrigger(applicationContext, chargingOnly, replace = isTrigger)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    private suspend fun trySetForeground(done: Int, total: Int, etaSec: Long?) {
        try {
            setForeground(foregroundInfo(done, total, etaSec))
        } catch (e: Exception) {
            if (e is CancellationException) throw e // foreground not allowed right now: keep working
        }
    }

    private suspend fun runBackup(repo: BackupStatusRepository): Result {
        val startMs = System.currentTimeMillis()
        val runner = BackupRunnerFactory.create(applicationContext)

        // The engine reports progress from a non-suspending callback; hand it to a sequential consumer.
        val updates = Channel<Progress>(Channel.CONFLATED)
        return try {
            coroutineScope {
                val consumer = launch {
                    var lastPublish = 0L
                    for (p in updates) {
                        val now = System.currentTimeMillis()
                        if (now - lastPublish < 1000 && p.done != p.total) continue
                        lastPublish = now
                        val eta = BackupEta.seconds(startMs, now, p.bytes, p.totalBytes)
                        repo.update { it.copy(inProgress = true, done = p.done, total = p.total, etaSec = eta) }
                        trySetForeground(p.done, p.total, eta)
                    }
                }
                repo.update { it.copy(inProgress = true, done = 0, total = 0, etaSec = null) }
                trySetForeground(0, 0, null)
                val r = try {
                    runner.run { done, total, bytes, totalBytes ->
                        updates.trySend(Progress(done, total, bytes, totalBytes))
                    }
                } finally {
                    // Stop the consumer before any final status write so it cannot set inProgress afterwards.
                    updates.close()
                    withContext(NonCancellable) { consumer.cancelAndJoin() }
                }
                finish(repo, r)
            }
        } catch (e: CancellationException) {
            idle(repo) { it }
            throw e
        } catch (_: SetupIncompleteException) {
            idle(repo) { it.copy(lastResult = null, lastError = BackupMessages.SETUP) }
            Result.success()
        } catch (_: SecurityException) {
            idle(repo) { it.copy(lastResult = null, lastError = BackupMessages.PERMISSION) }
            BackupNotifications.error(applicationContext, BackupMessages.PERMISSION)
            Result.success()
        } catch (_: Exception) {
            // Never log the exception text: it could carry connection details.
            idle(repo) { it.copy(lastError = "Backup failed, will retry") }
            Result.retry()
        }
    }

    private suspend fun finish(repo: BackupStatusRepository, r: BackupResult): Result {
        val now = System.currentTimeMillis()
        when (r) {
            is BackupResult.Done -> {
                val err = if (r.failed == 0) null else "${r.failed} files failed — see Home"
                idle(repo) {
                    it.copy(lastRunMs = now, lastResult = "Backed up ${r.uploaded}, failed ${r.failed}", lastError = err)
                }
                BackupNotifications.clearError(applicationContext)
            }
            is BackupResult.Stopped -> when (val e = r.reason) {
                is RemoteException.Unreachable -> {
                    idle(repo) { it.copy(lastRunMs = now, lastResult = BackupMessages.UNREACHABLE, lastError = null) }
                    BackupNotifications.clearError(applicationContext)
                }
                else -> {
                    val text = when (e) {
                        is RemoteException.AuthFailed -> BackupMessages.AUTH
                        is RemoteException.ShareNotFound -> BackupMessages.SHARE
                        is RemoteException.Smb1Only -> BackupMessages.SMB1
                        is RemoteException.DriveFull -> BackupMessages.FULL
                        is RemoteException.Unreachable -> BackupMessages.UNREACHABLE
                    }
                    idle(repo) { it.copy(lastRunMs = now, lastResult = text, lastError = text) }
                    BackupNotifications.error(applicationContext, text)
                }
            }
        }
        return Result.success()
    }

    /** Writes a final status with the progress fields cleared; survives cancellation. */
    private suspend fun idle(repo: BackupStatusRepository, change: (BackupStatusSnapshot) -> BackupStatusSnapshot) =
        withContext(NonCancellable) {
            repo.update { change(it).copy(inProgress = false, done = 0, total = 0, etaSec = null) }
        }
}
