package dev.photobackup.gallery

import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
class BrowseViewModelTest {
    private val store = FakeRemoteStore().apply {
        dirs += "Photos"
        dirs += "Photos\\zeta"
        dirs += "Photos\\Alpha"
        files["Photos\\b.MP4"] = ByteArray(5)
        files["Photos\\a.jpg"] = ByteArray(3)
        files["Photos\\notes.txt"] = ByteArray(1)
    }
    private val copies = mutableListOf<Pair<String, Boolean>>()
    private var copyError: Exception? = null
    private var copyGate: CompletableDeferred<Unit>? = null

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(path: String = "Photos") = BrowseViewModel(path, store) { p, v ->
        copyGate?.await()
        copyError?.let { throw it }
        copies += p to v
    }

    @Test fun mediaKindByExtension() {
        assertEquals(MediaKind.IMAGE, MediaKind.of("x.JPG"))
        assertEquals(MediaKind.IMAGE, MediaKind.of("x.heic"))
        assertEquals(MediaKind.VIDEO, MediaKind.of("clip.mov"))
        assertEquals(MediaKind.VIDEO, MediaKind.of("clip.mp4"))
        assertEquals(MediaKind.OTHER, MediaKind.of("notes.txt"))
        assertEquals(MediaKind.OTHER, MediaKind.of("jpg"))
        assertEquals(MediaKind.OTHER, MediaKind.of("a.jpg.part"))
    }

    @Test fun listsFoldersFirstThenFilesByName() {
        val vm = vm()
        vm.refresh()
        val s = vm.state.value
        assertFalse(s.loading)
        assertNull(s.error)
        assertEquals(listOf("Alpha", "zeta", "a.jpg", "b.MP4", "notes.txt"), s.entries.map { it.name })
        assertEquals(
            listOf(MediaKind.DIR, MediaKind.DIR, MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.OTHER),
            s.entries.map { it.kind },
        )
    }

    @Test fun viewerListHoldsOnlyMediaAsDriveItems() {
        val vm = vm()
        vm.refresh()
        val items = vm.state.value.viewerItems
        assertEquals(listOf("a.jpg", "b.MP4"), items.map { it.displayName })
        assertTrue(items.all { it.location == Location.DRIVE && it.phoneUri == null })
        assertEquals(listOf("Photos\\a.jpg", "Photos\\b.MP4"), items.map { it.remotePath })
        assertEquals(listOf(false, true), items.map { it.isVideo })
        val video = vm.state.value.entries.first { it.name == "b.MP4" }
        assertEquals(1, vm.state.value.viewerIndexOf(video))
        val txt = vm.state.value.entries.first { it.name == "notes.txt" }
        assertEquals(-1, vm.state.value.viewerIndexOf(txt))
    }

    @Test fun listErrorShowsMessage() {
        store.failOn = { op, _ -> if (op == "list") RemoteException.Unreachable() else null }
        val vm = vm()
        vm.refresh()
        assertEquals("Can't reach the drive — check you're on home Wi-Fi", vm.state.value.error)
        assertTrue(vm.state.value.entries.isEmpty())
    }

    @Test fun copyToPhoneReportsDone() {
        val vm = vm()
        vm.refresh()
        copyGate = CompletableDeferred()
        val e = vm.state.value.entries.first { it.name == "b.MP4" }
        vm.copyToPhone(e)
        assertTrue("Photos\\b.MP4" in vm.state.value.copying)
        copyGate!!.complete(Unit)
        assertEquals(listOf("Photos\\b.MP4" to true), copies)
        assertTrue(vm.state.value.copying.isEmpty())
        assertEquals("Copied b.MP4 to the phone", vm.state.value.message)
        vm.messageShown()
        assertNull(vm.state.value.message)
    }

    @Test fun copyToPhoneFailureShowsMessage() {
        copyError = IOException("boom")
        val vm = vm()
        vm.refresh()
        vm.copyToPhone(vm.state.value.entries.first { it.name == "a.jpg" })
        assertEquals("Couldn't copy a.jpg: Can't reach the drive — check you're on home Wi-Fi", vm.state.value.message)
        assertTrue(vm.state.value.copying.isEmpty())
    }

    @Test fun nonMediaIsNotCopied() {
        val vm = vm()
        vm.refresh()
        vm.copyToPhone(vm.state.value.entries.first { it.name == "notes.txt" })
        assertTrue(copies.isEmpty())
    }
}
