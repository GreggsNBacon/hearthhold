package dev.photobackup.backup

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import dev.photobackup.media.PartialMediaAccessException
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
    const val SMB1 = dev.photobackup.smb.MSG_SMB_VERSION
    const val FULL = "The drive is full"
    const val SETUP = "Finish setup first"
    const val PERMISSION = "Photos permission needed"
    const val PERMISSION_ALL = "Allow access to all photos"
    const val TIME_LIMIT = "Paused by Android's background time limit — will continue later"

    /** Files bigger than all the drive's free space: the run went on with the rest. */
    fun noRoom(n: Int): String =
        if (n == 1) "1 file doesn't fit on the drive — free up space" else "$n files don't fit on the drive — free up space"

    /** Shown while drive files are being moved to their folders. */
    fun moving(n: Int): String = if (n == 1) "Moving 1 file" else "Moving $n files"

    /** The "last result" line: uploads and failures, plus moved files when there were any. */
    fun summary(uploaded: Int, failed: Int, moved: Int): String =
        "Backed up $uploaded, failed $failed" + if (moved > 0) " · $moved moved" else ""

    /** "N files failed": the reason for each is on its ! badge in the Gallery. */
    fun failed(n: Int): String = if (n == 1) "1 file failed — tap its ! for why" else "$n files failed — tap ! for why"
}

/**
 * Why a stopped run stopped, for the status line; null when it needs no explanation (cancelled by the user, by
 * REPLACE or by a constraint). Android 15+ limits a dataSync foreground service to 6 h a day: WorkManager 2.10+
 * stops the worker on `Service.onTimeout` with [WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT] instead of
 * letting the app crash. Rows still UPLOADING are cleaned by the next run as after any cancellation. Pure.
 */
object BackupStop {
    fun message(sdk: Int, stopReason: () -> Int): String? =
        if (sdk >= 35 && stopReason() == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT) BackupMessages.TIME_LIMIT
        else null
}

/** Only one backup run per process: a second engine would delete the first one's in-flight .part files. */
object BackupLock {
    val mutex = Mutex()
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private data class Progress(val done: Int, val total: Int, val bytes: Long, val totalBytes: Long, val moving: Int = 0)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0, null)

    private fun foregroundInfo(done: Int, total: Int, etaSec: Long?, moving: Int = 0) = ForegroundInfo(
        BackupNotifications.PROGRESS_ID,
        BackupNotifications.progress(applicationContext, done, total, etaSec, moving),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    override suspend fun doWork(): Result {
        val repo = BackupStatusRepository.get(applicationContext)
        val chargingOnly = inputData.getBoolean(BackupScheduler.KEY_CHARGING_ONLY, false)
        val isTrigger = inputData.getBoolean(BackupScheduler.KEY_IS_TRIGGER, false)
        val manual = inputData.getBoolean(BackupScheduler.KEY_MANUAL, false)
        val isNight = inputData.getBoolean(BackupScheduler.KEY_IS_NIGHT, false)
        // Read once up front. Unknown (settings unreadable) counts as "no window": better to back up than not.
        val windowRead = if (manual) null else runCatching { BackupRunnerFactory.overnightWindow(applicationContext) }
        val window = windowRead?.getOrNull()
        try {
            if (!BackupLock.mutex.tryLock()) return Result.success() // another run is active; leave status alone
            try {
                // Automatic runs wait for the overnight window; "Back up now" always runs. No drive access here.
                if (window != null && !window.contains(BackupRunnerFactory.now())) {
                    idle(repo) { it.copy(lastResult = BackupWindow.waitingMessage(window)) }
                    return Result.success()
                }
                return runBackup(repo)
            } finally {
                BackupLock.mutex.unlock()
            }
        } finally {
            // Last on purpose: REPLACE on pb_trigger / pb_night cancels this very worker when it is that job.
            try {
                BackupScheduler.enqueueTrigger(applicationContext, chargingOnly, replace = isTrigger)
                if (isNight && windowRead != null && windowRead.isSuccess) {
                    // Next night (or cancel, if the window was switched off meanwhile).
                    val next = window?.let { BackupWindow.untilNextStart(BackupRunnerFactory.now(), it.start) }
                    BackupScheduler.enqueueNight(applicationContext, chargingOnly, window, next)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    private suspend fun trySetForeground(done: Int, total: Int, etaSec: Long?, moving: Int = 0) {
        try {
            setForeground(foregroundInfo(done, total, etaSec, moving))
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
                        repo.update { it.copy(inProgress = true, done = p.done, total = p.total, etaSec = eta, moving = p.moving) }
                        trySetForeground(p.done, p.total, eta, p.moving)
                    }
                }
                // A new run starts: the previous run's error is stale (lastResult stays until this run ends).
                repo.update { it.copy(inProgress = true, done = 0, total = 0, etaSec = null, moving = 0, lastError = null) }
                trySetForeground(0, 0, null)
                val r = try {
                    runner.run({ done, total, bytes, totalBytes ->
                        updates.trySend(Progress(done, total, bytes, totalBytes))
                    }) { moving -> updates.trySend(Progress(0, 0, 0, 0, moving)) }
                } finally {
                    // Stop the consumer before any final status write so it cannot set inProgress afterwards.
                    updates.close()
                    withContext(NonCancellable) { consumer.cancelAndJoin() }
                }
                finish(repo, r)
            }
        } catch (e: CancellationException) {
            val why = if (isStopped) BackupStop.message(Build.VERSION.SDK_INT) { stopReason } else null
            idle(repo) { if (why != null) it.copy(lastResult = why) else it }
            throw e
        } catch (_: SetupIncompleteException) {
            idle(repo) { it.copy(lastResult = null, lastError = BackupMessages.SETUP) }
            Result.success()
        } catch (e: SecurityException) {
            // No (or only "Select photos") access: MediaStore would quietly return nothing, so this must be loud.
            val text = if (e is PartialMediaAccessException) BackupMessages.PERMISSION_ALL else BackupMessages.PERMISSION
            idle(repo) { it.copy(lastResult = null, lastError = text) }
            BackupNotifications.error(applicationContext, text)
            Result.success()
        } catch (e: Exception) {
            // Class name only, never the message: it could carry connection details.
            Log.w("Hearthhold", "backup failed: " + e.javaClass.simpleName)
            idle(repo) { it.copy(lastError = "Backup failed, will retry") }
            Result.retry()
        }
    }

    private suspend fun finish(repo: BackupStatusRepository, r: BackupResult): Result {
        val now = System.currentTimeMillis()
        when (r) {
            is BackupResult.Done -> {
                val err = when {
                    r.noRoom > 0 -> BackupMessages.noRoom(r.noRoom)
                    r.failed > 0 -> BackupMessages.failed(r.failed)
                    else -> null
                }
                idle(repo) {
                    it.copy(
                        lastRunMs = now,
                        lastResult = BackupMessages.summary(r.uploaded, r.failed, r.moved),
                        lastError = err,
                        driveFreeBytes = r.driveFreeBytes ?: it.driveFreeBytes,
                    )
                }
                // A full drive is a persistent problem (spec: Home + notification); other item failures show in-app.
                if (r.noRoom > 0) {
                    BackupNotifications.error(applicationContext, BackupMessages.noRoom(r.noRoom))
                } else {
                    BackupNotifications.clearError(applicationContext)
                }
                if (r.uploaded > 0 && notifyEnabled()) {
                    BackupNotifications.complete(applicationContext, r.uploaded, r.bytesUploaded)
                }
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
                    BackupNotifications.error(applicationContext, text, reauth = e is RemoteException.AuthFailed)
                }
            }
        }
        return Result.success()
    }

    /** The Settings toggle; a settings read failure must not fail a finished run, so it defaults to on. */
    private suspend fun notifyEnabled(): Boolean = try {
        BackupRunnerFactory.notifyOnComplete(applicationContext)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        true
    }

    /** Writes a final status with the progress fields cleared; survives cancellation. */
    private suspend fun idle(repo: BackupStatusRepository, change: (BackupStatusSnapshot) -> BackupStatusSnapshot) =
        withContext(NonCancellable) {
            repo.update { change(it).copy(inProgress = false, done = 0, total = 0, etaSec = null, moving = 0) }
        }
}
