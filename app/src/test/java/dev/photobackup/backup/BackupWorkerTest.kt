package dev.photobackup.backup

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.photobackup.smb.RemoteException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupWorkerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var app: Application
    private lateinit var repo: BackupStatusRepository
    private var notifyEnabled = true
    private var window: OvernightWindow? = null
    private var clockTime: java.time.LocalTime = java.time.LocalTime.of(12, 0)
    private var runner: BackupRunner = BackupRunner { _ -> BackupResult.Done(0, 0, 0) }
    private var secondWaiting = 0

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        repo = BackupStatusRepository(PreferenceDataStoreFactory.create { File(tmp.root, "status.preferences_pb") })
        BackupStatusRepository.provider = { repo }
        BackupRunnerFactory.create = { runner }
        BackupRunnerFactory.notifyOnComplete = { notifyEnabled }
        BackupRunnerFactory.overnightWindow = { window }
        BackupRunnerFactory.now = { clockTime }
        BackupRunnerFactory.secondWaiting = { secondWaiting }
    }

    @After fun tearDown() {
        BackupStatusRepository.provider = BackupStatusRepository.defaultProvider
        BackupRunnerFactory.create = BackupRunnerFactory.defaultCreate
        BackupRunnerFactory.notifyOnComplete = BackupRunnerFactory.defaultNotifyOnComplete
        BackupRunnerFactory.overnightWindow = BackupRunnerFactory.defaultOvernightWindow
        BackupRunnerFactory.now = BackupRunnerFactory.defaultNow
        BackupRunnerFactory.secondWaiting = BackupRunnerFactory.defaultSecondWaiting
    }

    private fun runWorker(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<BackupWorker>(app).build().doWork()
    }

    private fun status() = runBlocking { repo.status.first() }

    private fun errorNotifications(): List<Notification> {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return shadowOf(nm).allNotifications.filter { it.channelId == BackupNotifications.CHANNEL_ERRORS }
    }

    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test fun unreachableRecordsStatusWithoutErrorNotification() {
        runner = BackupRunner { _ -> BackupResult.Stopped(RemoteException.Unreachable()) }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        val s = status()
        assertEquals("Drive not reachable — not at home?", s.lastResult)
        assertNull(s.lastError)
        assertFalse(s.inProgress)
        assertTrue(errorNotifications().isEmpty())
    }

    @Test fun authFailedPostsErrorNotification() {
        runner = BackupRunner { _ -> BackupResult.Stopped(RemoteException.AuthFailed()) }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        val n = errorNotifications()
        assertEquals(1, n.size)
        assertEquals("Wrong username or password", text(n[0]))
        assertEquals("Wrong username or password", status().lastError)
    }

    @Test fun authFailedNotificationOpensReenterPasswordDialog() {
        runner = BackupRunner { _ -> BackupResult.Stopped(RemoteException.AuthFailed()) }
        runWorker()
        val pi = errorNotifications().single().contentIntent
        assertNotNull(pi)
        assertTrue(shadowOf(pi).isActivityIntent)
        assertTrue(shadowOf(pi).flags and android.app.PendingIntent.FLAG_IMMUTABLE != 0)
        val intent = shadowOf(pi).savedIntent
        assertEquals(dev.photobackup.MainActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(dev.photobackup.MainActivity.EXTRA_REAUTH, false))
    }

    @Test fun otherErrorNotificationsHaveNoReauthIntent() {
        runner = BackupRunner { _ -> BackupResult.Stopped(RemoteException.DriveFull()) }
        runWorker()
        val pi = errorNotifications().single().contentIntent
        if (pi != null) assertFalse(shadowOf(pi).savedIntent.getBooleanExtra(dev.photobackup.MainActivity.EXTRA_REAUTH, false))
    }

    @Test fun smb1OnlyMessageMentionsRouterSetting() {
        runner = BackupRunner { _ -> BackupResult.Stopped(RemoteException.Smb1Only()) }
        runWorker()
        assertEquals(
            "Couldn't agree a file-sharing version with the drive. On the router, make sure SMB2 is enabled: USB Application → Network Place (Samba) Share",
            text(errorNotifications().single()),
        )
    }

    @Test fun otherStopReasonsHaveVerbatimText() {
        runner = BackupRunner { _ -> BackupResult.Stopped(RemoteException.ShareNotFound()) }
        runWorker()
        assertEquals("Shared folder not found on the drive", text(errorNotifications().single()))
        runner = BackupRunner { _ -> BackupResult.Stopped(RemoteException.DriveFull()) }
        runWorker()
        assertEquals("The drive is full", status().lastError)
    }

    // ---- Second backup location ----

    @Test fun mainDoneWithSecondUnreachableIsStillSuccessWithSecondStatus() {
        runner = BackupRunner { _ -> BackupResult.Done(2, 0, 0, second = SecondResult.Unreachable) }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        val s = status()
        assertEquals("Backed up 2, failed 0", s.lastResult)
        assertNull(s.lastError)
        assertEquals("Not reachable", s.secondStatus)
        assertTrue(errorNotifications().isEmpty()) // like an unreachable main drive: status only
    }

    @Test fun mainUnreachableWithSecondDoneRecordsBoth() {
        runner = BackupRunner { _ ->
            BackupResult.Stopped(RemoteException.Unreachable(), second = SecondResult.Done(4, 0, 0, 0))
        }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        val s = status()
        assertEquals("Drive not reachable — not at home?", s.lastResult)
        assertEquals("Up to date", s.secondStatus)
    }

    @Test fun waitingComesFromTheLedgerCount() {
        secondWaiting = 7
        runner = BackupRunner { _ -> BackupResult.Done(0, 0, 0, second = SecondResult.Done(0, 0, 0, 0)) }
        runWorker()
        assertEquals("7 waiting", status().secondStatus)
    }

    @Test fun failedCopiesShowAsFailed() {
        runner = BackupRunner { _ -> BackupResult.Done(0, 0, 0, second = SecondResult.Done(0, 0, 2, 0)) }
        runWorker()
        assertEquals("2 failed", status().secondStatus)
    }

    @Test fun secondAuthFailurePostsItsOwnErrorAndKeepsMainOne() {
        runner = BackupRunner { _ ->
            BackupResult.Done(1, 0, 0, noRoom = 1, second = SecondResult.AuthFailed)
        }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals("Re-enter second location password", status().secondStatus)
        val texts = errorNotifications().map { text(it) }.toSet()
        assertEquals(
            setOf("1 file doesn't fit on the drive — free up space", "Re-enter second location password"), texts,
        )
    }

    @Test fun secondNotificationsStayQuietWhenFine() {
        runner = BackupRunner { _ -> BackupResult.Done(1, 0, 0, second = SecondResult.Done(1, 0, 0, 0)) }
        runWorker()
        assertTrue(errorNotifications().isEmpty())
        assertEquals("Up to date", status().secondStatus)
    }

    @Test fun secondAuthNotificationClearsOnceFixed() {
        runner = BackupRunner { _ -> BackupResult.Done(0, 0, 0, second = SecondResult.AuthFailed) }
        runWorker()
        assertEquals(1, errorNotifications().size)
        runner = BackupRunner { _ -> BackupResult.Done(0, 0, 0, second = SecondResult.Done(0, 0, 0, 0)) }
        runWorker()
        assertTrue(errorNotifications().isEmpty())
    }

    @Test fun offLeavesNoSecondStatusAndClearsAnOldOne() {
        runner = BackupRunner { _ -> BackupResult.Done(0, 0, 0, second = SecondResult.Unreachable) }
        runWorker()
        assertEquals("Not reachable", status().secondStatus)
        runner = BackupRunner { _ -> BackupResult.Done(0, 0, 0) } // second removed
        runWorker()
        assertNull(status().secondStatus)
    }

    @Test fun cancellationKeepsTheSecondStatus() {
        runner = BackupRunner { _ -> BackupResult.Done(0, 0, 0, second = SecondResult.Unreachable) }
        runWorker()
        runner = BackupRunner { _ -> throw kotlinx.coroutines.CancellationException("window closed") }
        try { runWorker() } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals("Not reachable", status().secondStatus)
    }

    @Test fun unexpectedExceptionRetries() {
        runner = BackupRunner { _ -> throw IllegalStateException("boom") }
        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertFalse(status().inProgress)
    }

    @Test fun successUpdatesLastRun() {
        runner = BackupRunner { progress ->
            progress(1, 2, 10L, 20L)
            BackupResult.Done(uploaded = 2, skipped = 1, failed = 0)
        }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        val s = status()
        assertNotNull(s.lastRunMs)
        assertEquals("Backed up 2, failed 0", s.lastResult)
        assertNull(s.lastError)
        assertFalse(s.inProgress)
        assertTrue(errorNotifications().isEmpty())
    }

    @Test fun movedFilesAreMentionedInSummary() {
        runner = BackupRunner { _ -> BackupResult.Done(0, 0, 0, moved = 3) }
        runWorker()
        assertEquals("Backed up 0, failed 0 · 3 moved", status().lastResult)
        assertEquals("Moving 1 file", BackupMessages.moving(1))
        assertEquals("Moving 4 files", BackupMessages.moving(4))
    }

    @Test fun failedFilesAreReported() {
        runner = BackupRunner { _ -> BackupResult.Done(3, 0, 2) }
        runWorker()
        val s = status()
        assertEquals("Backed up 3, failed 2", s.lastResult)
        assertEquals("2 files failed — tap ! for why", s.lastError) // the Home screen is gone
        assertEquals("1 file failed — tap its ! for why", BackupMessages.failed(1))
    }

    @Test fun filesTooBigForFreeSpaceAreReportedAndNotified() {
        runner = BackupRunner { _ -> BackupResult.Done(3, 0, 2, noRoom = 1) }
        runWorker()
        assertEquals("1 file doesn't fit on the drive — free up space", status().lastError)
        assertEquals("1 file doesn't fit on the drive — free up space", text(errorNotifications().single()))
    }

    @Test fun finishedRunRecordsDriveFreeSpace() {
        runner = BackupRunner { _ -> BackupResult.Done(1, 0, 0, driveFreeBytes = 123_456) }
        runWorker()
        assertEquals(123_456L, status().driveFreeBytes)
        runner = BackupRunner { _ -> BackupResult.Done(0, 0, 0) } // unknown: keep the last figure
        runWorker()
        assertEquals(123_456L, status().driveFreeBytes)
    }

    @Test fun partialPhotoAccessAsksForAllPhotos() {
        runner = BackupRunner { _ -> throw dev.photobackup.media.PartialMediaAccessException() }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals("Allow access to all photos", status().lastError)
        assertEquals("Allow access to all photos", text(errorNotifications().single()))
    }

    @Test fun foregroundServiceTimeoutExplainsThePause() {
        val timeout = androidx.work.WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT
        assertEquals(BackupMessages.TIME_LIMIT, BackupStop.message(35) { timeout })
        assertNull(BackupStop.message(34) { timeout }) // no such limit before Android 15
        assertNull(BackupStop.message(35) { androidx.work.WorkInfo.STOP_REASON_CANCELLED_BY_APP })
    }

    @Test fun staleInProgressIsClearedOnlyWhenNoRunHoldsTheLock() = runBlocking {
        repo.update { it.copy(inProgress = true, done = 3, total = 9, etaSec = 60) }
        repo.clearStaleProgress { true } // a run is active: leave it
        assertTrue(status().inProgress)
        repo.clearStaleProgress { false }
        val s = status()
        assertFalse(s.inProgress)
        assertEquals(0, s.total)
        assertNull(s.etaSec)
    }

    @Test fun missingSetupIsQuietlyReported() {
        runner = BackupRunner { _ -> throw SetupIncompleteException() }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals("Finish setup first", status().lastError)
        assertTrue(errorNotifications().isEmpty())
    }

    @Test fun missingPermissionPostsNotification() {
        runner = BackupRunner { _ -> throw SecurityException("no media permission") }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals("Photos permission needed", status().lastError)
        assertEquals("Photos permission needed", text(errorNotifications().single()))
    }

    @Test fun noNotificationPermissionDoesNotCrash() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runner = BackupRunner { _ -> BackupResult.Stopped(RemoteException.AuthFailed()) }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertTrue(errorNotifications().isEmpty())
    }

    @Test fun etaIsNullUntilOneSecondThenUsesAverageRate() {
        assertNull(BackupEta.seconds(startMs = 0, nowMs = 900, bytesDone = 100, totalBytes = 1000))
        assertNull(BackupEta.seconds(startMs = 0, nowMs = 5000, bytesDone = 0, totalBytes = 1000))
        // 100 bytes in 2 s = 50 B/s; 900 left -> 18 s
        assertEquals(18L, BackupEta.seconds(startMs = 0, nowMs = 2000, bytesDone = 100, totalBytes = 1000))
        assertEquals(0L, BackupEta.seconds(startMs = 0, nowMs = 2000, bytesDone = 1000, totalBytes = 1000))
    }

    @Test fun schedulerEnqueuesUniqueWork() {
        BackupScheduler.scheduleAll(app, chargingOnly = true)
        val wm = WorkManager.getInstance(app)
        for (name in listOf("pb_trigger", "pb_periodic")) {
            assertEquals(name, 1, wm.getWorkInfosForUniqueWork(name).get().size)
        }
        BackupScheduler.runNow(app, chargingOnly = false)
        assertEquals(1, wm.getWorkInfosForUniqueWork("pb_now").get().size)
    }

    private fun workerFor(chargingOnly: Boolean, isTrigger: Boolean) =
        TestListenableWorkerBuilder<BackupWorker>(app)
            .setInputData(
                androidx.work.workDataOf(
                    BackupScheduler.KEY_CHARGING_ONLY to chargingOnly,
                    BackupScheduler.KEY_IS_TRIGGER to isTrigger,
                )
            ).build()

    private fun triggerId() =
        WorkManager.getInstance(app).getWorkInfosForUniqueWork("pb_trigger").get().single().id

    @Test fun concurrentWorkersRunEngineOnce() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        runner = BackupRunner { _ ->
            calls.incrementAndGet()
            started.countDown()
            release.await()
            BackupResult.Done(1, 0, 0)
        }
        runBlocking {
            val first = async(kotlinx.coroutines.Dispatchers.Default) {
                workerFor(false, false).doWork()
            }
            assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val before = status()
            assertEquals(ListenableWorker.Result.success(), workerFor(false, false).doWork())
            assertEquals(1, calls.get())
            assertEquals(before, status()) // busy worker leaves status alone
            release.complete(Unit)
            assertEquals(ListenableWorker.Result.success(), first.await())
        }
        assertEquals(1, calls.get())
    }

    @Test fun nonTriggerWorkerDoesNotReplaceTrigger() {
        BackupScheduler.scheduleAll(app, chargingOnly = false)
        val id = triggerId()
        runBlocking { workerFor(false, isTrigger = false).doWork() }
        assertEquals(id, triggerId())
    }

    @Test fun triggerWorkerReplacesItselfAndKeepsChargingFlag() {
        BackupScheduler.scheduleAll(app, chargingOnly = false)
        val id = triggerId()
        runBlocking { workerFor(true, isTrigger = true).doWork() }
        assertTrue(id != triggerId())
        val info = WorkManager.getInstance(app).getWorkInfosForUniqueWork("pb_trigger").get().single()
        assertTrue(info.constraints.requiresCharging())
    }

    @Test fun runNowCarriesChargingFlagButIsNotConstrainedByIt() {
        BackupScheduler.runNow(app, chargingOnly = true)
        val info = WorkManager.getInstance(app).getWorkInfosForUniqueWork("pb_now").get().single()
        assertFalse(info.constraints.requiresCharging())
        val impl = WorkManager.getInstance(app) as androidx.work.impl.WorkManagerImpl
        val spec = impl.workDatabase.workSpecDao().getWorkSpec(info.id.toString())!!
        assertTrue(spec.input.getBoolean(BackupScheduler.KEY_CHARGING_ONLY, false))
        // A worker built from that input re-enqueues the trigger with the charging requirement.
        runBlocking { workerFor(true, isTrigger = false).doWork() }
        val trig = WorkManager.getInstance(app).getWorkInfosForUniqueWork("pb_trigger").get().single()
        assertTrue(trig.constraints.requiresCharging())
    }

    @Test fun unreachableClearsStaleErrorNotification() {
        runner = BackupRunner { _ -> BackupResult.Stopped(RemoteException.AuthFailed()) }
        runWorker()
        assertEquals(1, errorNotifications().size)
        runner = BackupRunner { _ -> BackupResult.Stopped(RemoteException.Unreachable()) }
        runWorker()
        assertTrue(errorNotifications().isEmpty())
    }

    private fun nowIds() =
        WorkManager.getInstance(app).getWorkInfosForUniqueWork("pb_now").get().map { it.id }

    @Test fun backUpNowReplacesPendingRetry() {
        BackupScheduler.runNow(app, chargingOnly = false)
        val first = nowIds().single()
        // Still ENQUEUED (unmetered constraint unmet in tests), like a run waiting in retry backoff.
        BackupScheduler.runNow(app, chargingOnly = false)
        val infos = WorkManager.getInstance(app).getWorkInfosForUniqueWork("pb_now").get()
        val live = infos.filter { !it.state.isFinished }
        assertEquals(1, live.size)
        assertTrue(live.single().id != first)
    }

    @Test fun backUpNowKeepsRunningWork() {
        assertEquals(androidx.work.ExistingWorkPolicy.KEEP,
            BackupScheduler.nowPolicy(listOf(androidx.work.WorkInfo.State.RUNNING)))
        assertEquals(androidx.work.ExistingWorkPolicy.KEEP,
            BackupScheduler.nowPolicy(listOf(androidx.work.WorkInfo.State.CANCELLED, androidx.work.WorkInfo.State.RUNNING)))
        for (st in listOf(androidx.work.WorkInfo.State.ENQUEUED, androidx.work.WorkInfo.State.BLOCKED,
            androidx.work.WorkInfo.State.SUCCEEDED)) {
            assertEquals(androidx.work.ExistingWorkPolicy.REPLACE, BackupScheduler.nowPolicy(listOf(st)))
        }
        assertEquals(androidx.work.ExistingWorkPolicy.REPLACE, BackupScheduler.nowPolicy(emptyList()))
    }

    @Test fun newRunClearsStaleError() {
        runBlocking { repo.update { it.copy(lastError = "Backup failed, will retry", lastResult = "Backed up 1, failed 0") } }
        var during: BackupStatusSnapshot? = null
        runner = BackupRunner { _ ->
            during = repo.status.first()
            BackupResult.Done(1, 0, 0)
        }
        runWorker()
        assertTrue(during!!.inProgress)
        assertNull(during!!.lastError)
        assertEquals("Backed up 1, failed 0", during!!.lastResult)
    }

    private fun doneNotifications(): List<Notification> {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return shadowOf(nm).allNotifications.filter { it.channelId == BackupNotifications.CHANNEL_DONE }
    }

    @Test fun completionNotificationWhenFilesUploaded() {
        runner = BackupRunner { _ -> BackupResult.Done(3, 1, 0, bytesUploaded = 5L * 1024 * 1024) }
        runWorker()
        val n = doneNotifications().single()
        assertEquals("Backup complete", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("3 files backed up (5 MB)", text(n))
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(BackupNotifications.CHANNEL_DONE).importance)
    }

    @Test fun completionNotificationSingularAndGigabytes() {
        runner = BackupRunner { _ -> BackupResult.Done(1, 0, 0, bytesUploaded = 3L * 1024 * 1024 * 1024 / 2) }
        runWorker()
        assertEquals("1 file backed up (1.5 GB)", text(doneNotifications().single()))
    }

    @Test fun noCompletionNotificationWhenNothingUploaded() {
        runner = BackupRunner { _ -> BackupResult.Done(0, 4, 0) }
        runWorker()
        assertTrue(doneNotifications().isEmpty())
    }

    @Test fun noCompletionNotificationWhenDisabled() {
        notifyEnabled = false
        runner = BackupRunner { _ -> BackupResult.Done(2, 0, 0, bytesUploaded = 100) }
        runWorker()
        assertTrue(doneNotifications().isEmpty())
    }

    @Test fun noCompletionNotificationWithoutPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runner = BackupRunner { _ -> BackupResult.Done(2, 0, 0, bytesUploaded = 100) }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertTrue(doneNotifications().isEmpty())
    }

    // --- Overnight window ---

    private fun counting(): java.util.concurrent.atomic.AtomicInteger {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        runner = BackupRunner { _ -> calls.incrementAndGet(); BackupResult.Done(1, 0, 0) }
        return calls
    }

    private fun worker(vararg pairs: Pair<String, Any>) =
        TestListenableWorkerBuilder<BackupWorker>(app).setInputData(androidx.work.workDataOf(*pairs)).build()

    @Test fun automaticRunOutsideWindowWaitsWithoutTouchingTheDrive() {
        val calls = counting()
        window = OvernightWindow(1, 6); clockTime = java.time.LocalTime.of(14, 0)
        runBlocking { repo.update { it.copy(lastRunMs = 42, lastError = "old problem") } }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(0, calls.get())
        val s = status()
        assertEquals("Waiting for overnight window (1:00–6:00)", s.lastResult)
        assertEquals(42L, s.lastRunMs)
        assertEquals("old problem", s.lastError) // a real problem stays visible
        assertFalse(s.inProgress)
        // The new-media trigger too.
        runBlocking { workerFor(false, isTrigger = true).doWork() }
        assertEquals(0, calls.get())
    }

    @Test fun automaticRunInsideWindowRuns() {
        val calls = counting()
        window = OvernightWindow(23, 5); clockTime = java.time.LocalTime.of(2, 0)
        runWorker()
        assertEquals(1, calls.get())
    }

    @Test fun backUpNowAlwaysRuns() {
        val calls = counting()
        window = OvernightWindow(1, 6); clockTime = java.time.LocalTime.of(14, 0)
        runBlocking { worker(BackupScheduler.KEY_MANUAL to true).doWork() }
        assertEquals(1, calls.get())
        // and runNow marks its work as manual
        BackupScheduler.runNow(app, chargingOnly = false)
        val info = WorkManager.getInstance(app).getWorkInfosForUniqueWork("pb_now").get().single()
        val impl = WorkManager.getInstance(app) as androidx.work.impl.WorkManagerImpl
        val spec = impl.workDatabase.workSpecDao().getWorkSpec(info.id.toString())!!
        assertTrue(spec.input.getBoolean(BackupScheduler.KEY_MANUAL, false))
    }

    @Test fun windowOffRunsAnyTime() {
        val calls = counting()
        window = null; clockTime = java.time.LocalTime.of(14, 0)
        runWorker()
        assertEquals(1, calls.get())
    }

    private fun nightSpec(): androidx.work.impl.model.WorkSpec? {
        val info = WorkManager.getInstance(app).getWorkInfosForUniqueWork(BackupScheduler.NIGHT).get()
            .singleOrNull { !it.state.isFinished } ?: return null
        val impl = WorkManager.getInstance(app) as androidx.work.impl.WorkManagerImpl
        return impl.workDatabase.workSpecDao().getWorkSpec(info.id.toString())
    }

    @Test fun scheduleAllEnqueuesNightJobAtNextWindowStart() {
        BackupScheduler.scheduleAll(
            app, chargingOnly = true, night = OvernightWindow(1, 6), now = java.time.LocalTime.of(22, 0),
        )
        val spec = nightSpec()!!
        assertEquals(java.time.Duration.ofHours(3).toMillis(), spec.initialDelay)
        assertTrue(spec.input.getBoolean(BackupScheduler.KEY_IS_NIGHT, false))
        assertTrue(spec.constraints.requiresCharging())
        // Turning the window off cancels it.
        BackupScheduler.scheduleAll(app, chargingOnly = true, night = null)
        assertNull(nightSpec())
    }

    @Test fun nightJobReschedulesItselfForTheNextNight() {
        window = OvernightWindow(1, 6); clockTime = java.time.LocalTime.of(1, 30)
        val calls = counting()
        runBlocking {
            worker(BackupScheduler.KEY_IS_NIGHT to true, BackupScheduler.KEY_CHARGING_ONLY to false).doWork()
        }
        assertEquals(1, calls.get())
        assertEquals(java.time.Duration.ofMinutes(23 * 60 + 30).toMillis(), nightSpec()!!.initialDelay)
    }
}
