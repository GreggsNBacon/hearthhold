package dev.photobackup.restore

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.photobackup.backup.BackupLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreWorkerTest {
    private lateinit var app: Application
    private var ran: RestoreSelection? = null
    private var lockedDuringRun = false
    private var runner = RestoreRunner { sel, onProgress ->
        ran = sel
        lockedDuringRun = BackupLock.mutex.isLocked
        onProgress(RestoreProgress(0, 2, RestoreResult()))
        RestoreResult(restored = 1, alreadyThere = 1, failed = listOf(RestoreFailure("a.jpg", RestoreEngine.NO_GOOD_COPY)))
    }

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        RestoreFactory.create = { runner }
        RestoreResults.get(app).clear()
    }

    @After fun tearDown() {
        RestoreFactory.create = RestoreFactory.defaultCreate
    }

    private fun worker(sel: RestoreSelection) = TestListenableWorkerBuilder<RestoreWorker>(app)
        .setInputData(RestoreScheduler.input(sel)).build()

    @Test fun selectionRoundTripsThroughInputData() {
        val sel = RestoreSelection(setOf(RestoreGroupKey("Camera", "2024"), RestoreGroupKey.OTHER))
        assertEquals(sel, RestoreScheduler.selection(RestoreScheduler.input(sel)))
        assertEquals(RestoreSelection.EVERYTHING, RestoreScheduler.selection(RestoreScheduler.input(RestoreSelection.EVERYTHING)))
    }

    @Test fun runsUnderBackupLockAndPersistsTheSummary() = runBlocking {
        val sel = RestoreSelection(setOf(RestoreGroupKey("Camera", "2024")))
        val result = worker(sel).doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(sel, ran)
        assertTrue(lockedDuringRun)
        assertFalse(BackupLock.mutex.isLocked)
        val saved = RestoreResults.get(app).load()!!
        assertEquals(1, saved.result.restored)
        assertEquals(1, saved.result.alreadyThere)
        assertEquals(RestoreEngine.NO_GOOD_COPY, saved.result.failed.single().reason)
        assertNull(saved.result.stopped)
    }

    @Test fun busyWhenABackupHoldsTheLock() = runBlocking {
        assertTrue(BackupLock.mutex.tryLock())
        try {
            worker(RestoreSelection.EVERYTHING).doWork()
        } finally {
            BackupLock.mutex.unlock()
        }
        assertNull(ran)
        assertEquals(RestoreWorker.BUSY, RestoreResults.get(app).load()!!.result.stopped)
    }

    @Test fun cancelKeepsWhatWasDone() = runBlocking {
        runner = RestoreRunner { _, onProgress ->
            onProgress(RestoreProgress(1, 3, RestoreResult(restored = 1)))
            throw CancellationException()
        }
        try {
            worker(RestoreSelection.EVERYTHING).doWork()
        } catch (_: CancellationException) {
        }
        val saved = RestoreResults.get(app).load()!!
        assertEquals(1, saved.result.restored)
        assertEquals(RestoreWorker.CANCELLED, saved.result.stopped)
        assertFalse(BackupLock.mutex.isLocked)
    }

    @Test fun anUnexpectedErrorIsSavedWithoutItsText() = runBlocking {
        runner = RestoreRunner { _, _ -> throw IllegalStateException("smb://user:secret@host") }
        worker(RestoreSelection.EVERYTHING).doWork()
        val saved = RestoreResults.get(app).load()!!
        assertEquals(RestoreWorker.FAILED, saved.result.stopped)
        assertFalse(saved.toString().contains("secret"))
    }
}
