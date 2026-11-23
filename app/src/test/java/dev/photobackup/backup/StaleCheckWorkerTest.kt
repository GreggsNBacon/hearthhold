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
import dev.photobackup.settings.AppSettings
import dev.photobackup.smb.SmbConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
class StaleCheckWorkerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var app: Application
    private lateinit var repo: BackupStatusRepository
    private val day = 24L * 60 * 60 * 1000
    private var now = 100 * day
    private var settings: AppSettings? =
        AppSettings(smb = SmbConfig("h", "s", null, null), destination = "d", staleWarnDays = 3)
    private var waiting: Int? = 4

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        repo = BackupStatusRepository(PreferenceDataStoreFactory.create { File(tmp.root, "status.preferences_pb") })
        BackupStatusRepository.provider = { repo }
        StaleCheckFactory.readSettings = { settings }
        StaleCheckFactory.waiting = { _, _ -> waiting }
        StaleCheckFactory.nowMs = { now }
    }

    @After fun tearDown() {
        BackupStatusRepository.provider = BackupStatusRepository.defaultProvider
        StaleCheckFactory.readSettings = StaleCheckFactory.defaultReadSettings
        StaleCheckFactory.waiting = StaleCheckFactory.defaultWaiting
        StaleCheckFactory.nowMs = StaleCheckFactory.defaultNowMs
    }

    private fun check(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<StaleCheckWorker>(app).build().doWork()
    }

    private fun status() = runBlocking { repo.status.first() }
    private fun posted() = shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .allNotifications.filter { it.channelId == BackupNotifications.CHANNEL_ERRORS }

    @Test fun neverSucceededUsesFirstRunAsBaselineSoUpgradersAreNotWarned() {
        check()
        assertTrue(posted().isEmpty())
        assertEquals(now, status().staleBaselineMs)
        now += 3 * day
        check()
        assertEquals(1, posted().size)
    }

    @Test fun warnsOnceADay() {
        runBlocking { repo.update { it.copy(lastSuccessMs = now - 5 * day) } }
        check()
        assertEquals(1, posted().size)
        assertEquals(now, status().lastStaleWarnMs)
        val first = now
        now += day / 2
        check()
        assertEquals(first, status().lastStaleWarnMs) // not re-warned
        now += day
        check()
        assertEquals(now, status().lastStaleWarnMs)
    }

    @Test fun nothingWaitingMeansNoWarning() {
        waiting = 0
        runBlocking { repo.update { it.copy(lastSuccessMs = now - 5 * day) } }
        check()
        assertTrue(posted().isEmpty())
        assertNull(status().lastStaleWarnMs)
    }

    @Test fun missingPermissionWarnsWithoutCounting() {
        waiting = null
        runBlocking { repo.update { it.copy(lastSuccessMs = now - 5 * day) } }
        check()
        val text = posted().single().extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals(BackupMessages.STALE_NO_COUNT, text)
    }

    @Test fun settingsReadErrorDoesNothing() {
        settings = null
        runBlocking { repo.update { it.copy(lastSuccessMs = now - 5 * day) } }
        assertEquals(ListenableWorker.Result.success(), check())
        assertTrue(posted().isEmpty())
        assertNull(status().lastStaleWarnMs)
    }

    @Test fun notSetUpMeansNoWarning() {
        settings = AppSettings(smb = null, destination = null)
        runBlocking { repo.update { it.copy(lastSuccessMs = now - 5 * day) } }
        check()
        assertTrue(posted().isEmpty())
    }

    @Test fun successfulRunClearsTheWarning() {
        runBlocking { repo.update { it.copy(lastSuccessMs = now - 5 * day) } }
        check()
        assertEquals(1, posted().size)
        BackupRunnerFactory.create = { BackupRunner { _ -> BackupResult.Done(1, 0, 10) } }
        BackupRunnerFactory.notifyOnComplete = { false }
        BackupRunnerFactory.overnightWindow = { null }
        BackupRunnerFactory.secondWaiting = { 0 }
        try {
            runBlocking { TestListenableWorkerBuilder<BackupWorker>(app).build().doWork() }
        } finally {
            BackupRunnerFactory.create = BackupRunnerFactory.defaultCreate
            BackupRunnerFactory.notifyOnComplete = BackupRunnerFactory.defaultNotifyOnComplete
            BackupRunnerFactory.overnightWindow = BackupRunnerFactory.defaultOvernightWindow
            BackupRunnerFactory.secondWaiting = BackupRunnerFactory.defaultSecondWaiting
        }
        assertTrue(posted().isEmpty())
        assertNull(status().lastStaleWarnMs)
        assertNotNull(status().lastSuccessMs)
    }

    private fun runBackup(r: BackupResult) {
        BackupRunnerFactory.create = { BackupRunner { _ -> r } }
        BackupRunnerFactory.notifyOnComplete = { false }
        BackupRunnerFactory.overnightWindow = { null }
        BackupRunnerFactory.secondWaiting = { 0 }
        try {
            runBlocking { TestListenableWorkerBuilder<BackupWorker>(app).build().doWork() }
        } finally {
            BackupRunnerFactory.create = BackupRunnerFactory.defaultCreate
            BackupRunnerFactory.notifyOnComplete = BackupRunnerFactory.defaultNotifyOnComplete
            BackupRunnerFactory.overnightWindow = BackupRunnerFactory.defaultOvernightWindow
            BackupRunnerFactory.secondWaiting = BackupRunnerFactory.defaultSecondWaiting
        }
    }

    private fun warnedThenRun(r: BackupResult) {
        runBlocking { repo.update { it.copy(lastSuccessMs = now - 5 * day) } }
        check()
        assertEquals(1, posted().size)
        runBackup(r)
    }

    @Test fun doneWhereEverythingFailedDoesNotResetTheClock() {
        warnedThenRun(BackupResult.Done(uploaded = 0, skipped = 0, failed = 3))
        assertEquals(now - 5 * day, status().lastSuccessMs)
        assertNotNull(status().lastStaleWarnMs)
        assertEquals(1, posted().filter { it.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("waiting") }.size)
    }

    @Test fun doneWithDriveFullAndNothingUploadedDoesNotResetTheClock() {
        warnedThenRun(BackupResult.Done(uploaded = 0, skipped = 0, failed = 2, noRoom = 2))
        assertEquals(now - 5 * day, status().lastSuccessMs)
    }

    @Test fun doneWithSomeUploadsCountsEvenIfOthersFailed() {
        warnedThenRun(BackupResult.Done(uploaded = 1, skipped = 0, failed = 2))
        assertTrue(status().lastSuccessMs!! > now - 5 * day || status().lastSuccessMs != now - 5 * day)
        assertNull(status().lastStaleWarnMs)
    }

    @Test fun doneWithNothingToDoCounts() {
        warnedThenRun(BackupResult.Done(uploaded = 0, skipped = 4, failed = 0))
        assertNull(status().lastStaleWarnMs)
        assertTrue(posted().isEmpty())
    }

    @Test fun enqueuingTheStaleCheckTwiceLeavesOneWorkItem() {
        BackupScheduler.enqueueStaleCheck(app)
        BackupScheduler.enqueueStaleCheck(app)
        val infos = WorkManager.getInstance(app).getWorkInfosForUniqueWork(BackupScheduler.STALE_CHECK).get()
        assertEquals(1, infos.size)
    }

    @Test fun dailyCheckIsScheduled() {
        BackupScheduler.scheduleAll(app, chargingOnly = false)
        val infos = WorkManager.getInstance(app).getWorkInfosForUniqueWork(BackupScheduler.STALE_CHECK).get()
        assertEquals(1, infos.size)
    }
}
