package dev.photobackup.freeup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.test.core.app.ApplicationProvider
import dev.photobackup.backup.Hashing
import dev.photobackup.backup.LocalOpener
import dev.photobackup.backup.MemoryLedger
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.RemoteStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreeUpViewModelTest {
    private val now = 1_800_000_000_000L // far later than the 2020 item dates
    private lateinit var ledger: MemoryLedger
    private lateinit var store: FakeRemoteStore
    private val requests = mutableListOf<List<Long>>()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ledger = MemoryLedger(); store = FakeRemoteStore(); requests.clear()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun bytes(id: Long) = ByteArray(5) { (id + it).toByte() }
    private fun entry(id: Long) = LedgerEntry(
        id, "content://media/$id", "f$id.jpg", "DCIM/", 5, 1_600_000_000_000L, 10, false,
        Hashing.sha256(ByteArrayInputStream(bytes(id))), "r\\f$id.jpg", null, BackupStatus.VERIFIED, null, 0,
    )
    private fun phone(e: LedgerEntry) = PhoneMedia(e.mediaId, e.localUri, e.displayName, e.relativePath, e.sizeBytes,
        e.dateTakenMs, 0, e.dateModifiedSec, false, "image/jpeg")

    private suspend fun seed(vararg ids: Long): List<LedgerEntry> = ids.map { entry(it) }.onEach {
        ledger.upsert(it); store.files[it.remotePath!!] = bytes(it.mediaId)
    }

    private fun sender(): IntentSender {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return PendingIntent.getActivity(ctx, 0, Intent(), PendingIntent.FLAG_IMMUTABLE).intentSender
    }

    private var gate: CompletableDeferred<Unit>? = null
    private val phoneBytes = mutableMapOf<Long, ByteArray>()

    private fun vm(open: suspend () -> RemoteStore? = { store }) =
        FreeUpViewModel(
            repo = FreeUpRepository(ledger),
            queryPhone = { ledger.rows.value.values.map { phone(it) } },
            openStore = { gate?.await(); open() },
            ageMonths = { 6 },
            opener = LocalOpener { m -> ByteArrayInputStream(phoneBytes[m.mediaId] ?: bytes(m.mediaId)) },
            makeDeleteRequest = { items -> requests.add(items.map { it.mediaId }); sender() },
            clock = { now },
            zone = ZoneOffset.UTC,
        )

    /** The integrity check hashes on Dispatchers.IO, so wait for the removal to publish or finish. */
    private fun FreeUpViewModel.removeAndWait() {
        removeSelected()
        val end = System.currentTimeMillis() + 10_000
        while (state.value.pendingDelete == null && state.value.busy && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    private fun FreeUpViewModel.loadedAndSelected(): FreeUpViewModel {
        refresh(); setAllSelected(true); return this
    }

    @Test fun canceledDialogMarksNothing() = kotlinx.coroutines.runBlocking {
        seed(1, 2)
        val v = vm().loadedAndSelected()
        v.removeAndWait()
        assertNotNull(v.state.value.pendingDelete)
        v.onDeleteLaunched()
        v.onDeleteResult(false)
        assertEquals(2, ledger.byStatus(BackupStatus.VERIFIED).size)
        assertFalse(v.state.value.busy)
    }

    @Test fun okMarksOnlyTheRecheckedVerifiedList() = kotlinx.coroutines.runBlocking {
        seed(1, 2)
        store.files.remove("r\\f2.jpg") // drive copy of #2 vanished -> must not be deleted
        val v = vm().loadedAndSelected()
        v.removeAndWait()
        assertEquals(listOf(listOf(1L)), requests)
        v.onDeleteResult(true)
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, ledger.get(1)!!.status)
        assertEquals(BackupStatus.FAILED, ledger.get(2)!!.status)
    }

    @Test fun resultWithNothingPendingIsIgnored() = kotlinx.coroutines.runBlocking {
        seed(1)
        val v = vm().loadedAndSelected()
        v.onDeleteResult(true)
        assertEquals(BackupStatus.VERIFIED, ledger.get(1)!!.status)
    }

    @Test fun pendingDeleteIsConsumedOnLaunch() = kotlinx.coroutines.runBlocking {
        seed(1)
        val v = vm().loadedAndSelected()
        v.removeAndWait()
        assertNotNull(v.state.value.pendingDelete)
        v.onDeleteLaunched()
        assertNull(v.state.value.pendingDelete)
    }

    @Test fun overlappingRefreshDoesNotReEnableButtonAndSecondTapIsIgnored() = kotlinx.coroutines.runBlocking {
        seed(1)
        val v = vm().loadedAndSelected()
        // removal in flight on v (dialog published, not yet answered)
        v.removeAndWait()
        v.refresh()
        assertTrue(v.state.value.busy)
        v.removeSelected()
        assertEquals(1, requests.size)
        v.onDeleteLaunched()
        v.onDeleteResult(false)
        assertFalse(v.state.value.busy)
    }

    @Test fun cancelCheckProducesNoDeleteRequest() = kotlinx.coroutines.runBlocking {
        seed(1)
        val v = vm().loadedAndSelected()
        val g = CompletableDeferred<Unit>().also { gate = it }
        v.removeSelected() // suspends opening the store
        assertTrue(v.state.value.busy)
        v.cancelCheck()
        g.complete(Unit)
        assertTrue(requests.isEmpty())
        assertNull(v.state.value.pendingDelete)
        assertFalse(v.state.value.busy)
        assertEquals(BackupStatus.VERIFIED, ledger.get(1)!!.status)
    }

    @Test fun failureSummarySurvivesTheReload() = kotlinx.coroutines.runBlocking {
        seed(1, 2, 3)
        store.files["r\\f2.jpg"] = ByteArray(5) { 9 } // damaged drive copy
        phoneBytes[3] = ByteArray(5) { 8 } // phone file changed
        val v = vm().loadedAndSelected()
        v.removeAndWait()
        assertEquals(listOf(listOf(1L)), requests)
        val msg = v.state.value.error!!
        assertTrue(msg, msg.contains("2 items failed the check"))
        assertTrue(msg, msg.contains("1 drive copy damaged"))
        assertTrue(msg, msg.contains("1 phone file changed"))
        v.onDeleteLaunched(); v.onDeleteResult(false) // reload happens
        assertEquals(msg, v.state.value.error)
        v.dismissError()
        assertNull(v.state.value.error)
    }

    @Test fun allItemsFailingStillShowsTheSummaryAfterReload() = kotlinx.coroutines.runBlocking {
        seed(1)
        store.corruptReads = true
        val v = vm().loadedAndSelected()
        v.removeAndWait()
        assertTrue(requests.isEmpty())
        assertTrue(v.state.value.error!!.contains("1 item failed the check"))
        assertFalse(v.state.value.busy)
    }

    @Test fun exceptionsBecomeErrorStateNotCrash() = kotlinx.coroutines.runBlocking {
        seed(1)
        val v = vm(open = { throw IllegalStateException("boom") })
        v.refresh()
        assertEquals("Something went wrong", v.state.value.error) // never the exception text
        assertFalse(v.state.value.busy)
    }
}
