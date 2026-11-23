package dev.photobackup.ui.setup

import dev.photobackup.smb.FakeRemoteStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class FolderPickerModelTest {
    private val store = FakeRemoteStore().apply {
        dirs += listOf("Photos", "Photos\\2024", "music")
        files["readme.txt"] = ByteArray(1)
    }

    @Test fun listsDirectoriesOnly() = runTest {
        val m = FolderPickerModel(store, this, "")
        m.open("").join()
        assertEquals(listOf("music", "Photos"), m.state.value.dirs.map { it.name })
        m.open("Photos").join()
        assertEquals(listOf("Photos\\2024"), m.state.value.dirs.map { it.path })
        m.up()!!.join()
        assertEquals("", m.state.value.path)
    }

    @Test fun newFolderIsCreatedAndOpened() = runTest {
        val m = FolderPickerModel(store, this, "Photos")
        m.open("Photos").join()
        m.createFolder("Phone backup")!!.join()
        assertEquals("Photos\\Phone backup", m.state.value.path)
        assert("Photos\\Phone backup" in store.dirs)
    }

    @Test fun invalidNamesAreRejected() = runTest {
        val m = FolderPickerModel(store, this, "")
        assertEquals(null, m.createFolder("a/b"))
        assertNotNull(m.state.value.error)
        assertFalse(FolderPickerModel.isValidFolderName(".."))
    }
}
