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
    private var runner: BackupRunner = BackupRunner { _ -> BackupResult.Done(0, 0, 0) }

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        repo = BackupStatusRepository(PreferenceDataStoreFactory.create { File(tmp.root, "status.preferences_pb") })
        BackupStatusRepository.provider = { repo }
        BackupRunnerFactory.create = { runner }
    }

    @After fun tearDown() {
        BackupStatusRepository.provider = BackupStatusRepository.defaultProvider
        BackupRunnerFactory.create = BackupRunnerFactory.defaultCreate
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

    @Test fun smb1OnlyMessageMentionsRouterSetting() {
        runner = BackupRunner { _ -> BackupResult.Stopped(RemoteException.Smb1Only()) }
        runWorker()
        assertEquals(
            "Turn on SMB2 on the router: USB Application → Network Place (Samba) Share",
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

    @Test fun failedFilesAreReported() {
        runner = BackupRunner { _ -> BackupResult.Done(3, 0, 2) }
        runWorker()
        val s = status()
        assertEquals("Backed up 3, failed 2", s.lastResult)
        assertEquals("2 files failed — see Home", s.lastError)
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
        BackupScheduler.runNow(app)
        assertEquals(1, wm.getWorkInfosForUniqueWork("pb_now").get().size)
    }
}
