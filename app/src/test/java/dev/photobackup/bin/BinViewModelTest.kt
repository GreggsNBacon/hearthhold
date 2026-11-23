package dev.photobackup.bin

import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BinViewModelTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private val rows = MutableStateFlow<List<LedgerEntry>>(emptyList())
    private var result: BinResult = BinResult.Done(1, 0)
    private var throwing: Exception? = null
    private val calls = mutableListOf<String>()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun binned(id: Long, binnedAt: Long? = now - 3 * day, error: String? = null) = LedgerEntry(
        id, "content://m/$id", "f$id.jpg", "DCIM/Camera/", 100, 0, 0, false, "h", "D\\.Bin\\f$id.jpg", null,
        BackupStatus.BINNED, error, 0, binRequestedAt = now - 4 * day, binPath = "D\\Camera\\f$id.jpg", binnedAt = binnedAt,
    )

    private fun waiting(id: Long) = binned(id, binnedAt = null).copy(status = BackupStatus.REMOVED_FROM_PHONE, binPath = null)

    private fun fake(name: String): BinResult {
        calls += name
        throwing?.let { throw it }
        return result
    }

    private fun vm() = BinViewModel(
        list = rows,
        restore = { fake("restore$it") },
        empty = { fake("empty$it") },
        emptyAll = { fake("emptyAll") },
        clock = { now },
    )

    @Test fun items_show_age_waiting_and_errors() = runTest {
        rows.value = listOf(binned(1), binned(2, binnedAt = now - 1 * day), binned(3, binnedAt = now - 1000),
            waiting(4), binned(5, error = "The file in the bin has changed"))
        val s = vm().state.value
        assertFalse(s.loading)
        assertEquals(listOf("3 days in bin", "1 day in bin", "Less than a day in bin", null, "3 days in bin"), s.items.map { it.age })
        assertEquals(listOf(false, false, false, true, false), s.items.map { it.waiting })
        assertEquals("The file in the bin has changed", s.items[4].error)
        assertEquals(4, s.binnedCount)
    }

    @Test fun restore_reports_count_and_failures() = runTest {
        rows.value = listOf(binned(1))
        val v = vm()
        v.restore(1)
        assertEquals(listOf("restore[1]"), calls)
        assertEquals("Restored 1 item", v.state.value.message)
        result = BinResult.Done(1, 2)
        v.restore(1)
        assertEquals("Restored 1 item · 2 couldn't be restored", v.state.value.message)
        assertFalse(v.state.value.busy)
    }

    @Test fun cancel_uses_restore_and_says_cancelled() = runTest {
        rows.value = listOf(waiting(4))
        val v = vm()
        v.cancel(4)
        assertEquals(listOf("restore[4]"), calls)
        assertEquals(BinViewModel.CANCELLED, v.state.value.message)
        result = BinResult.Done(0, 1)
        v.cancel(4)
        assertEquals(BinViewModel.CANCEL_FAILED, v.state.value.message)
    }

    @Test fun busy_and_offline_messages() = runTest {
        val v = vm()
        result = BinResult.Busy
        v.emptyAll()
        assertEquals("A backup is running — try again in a moment", v.state.value.message)
        result = BinResult.Offline
        v.delete(3)
        assertEquals(listOf("emptyAll", "empty[3]"), calls)
        assertTrue(v.state.value.message!!.startsWith("Connect to home Wi-Fi"))
        assertTrue(v.state.value.message!!.contains("some items may have changed"))
        v.messageShown()
        assertNull(v.state.value.message)
    }

    @Test fun empty_reports_deleted_count() = runTest {
        val v = vm()
        result = BinResult.Done(3, 1)
        v.emptyAll()
        assertEquals("Deleted 3 items for good · 1 couldn't be deleted", v.state.value.message)
    }

    @Test fun errors_never_crash_and_never_show_exception_text() = runTest {
        val v = vm()
        throwing = IOException("secret nas path")
        v.restore(1)
        assertEquals("Something went wrong", v.state.value.message)
        assertFalse(v.state.value.busy)
    }

    @Test fun empty_confirmation_text() {
        assertEquals("Delete 1 item from the drive for good?", emptyConfirmText(1))
        assertEquals("Delete 5 items from the drive for good?", emptyConfirmText(5))
    }
}
