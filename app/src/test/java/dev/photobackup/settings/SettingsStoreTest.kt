package dev.photobackup.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import dev.photobackup.smb.SmbConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

private class ReverseCipher : CredentialCipher {
    override fun encrypt(plain: String) = "enc:" + plain.reversed()
    override fun decrypt(cipher: String) = cipher.removePrefix("enc:").reversed()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun newStore(): Pair<SettingsStore, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>> {
        val ds = PreferenceDataStoreFactory.create { File(tmp.root, "s.preferences_pb") }
        return SettingsStore(ApplicationProvider.getApplicationContext(), ReverseCipher(), ds) to ds
    }

    @Test fun defaultsAreSpecValues() = runBlocking {
        val s = newStore().first.settings.first()
        assertEquals(6, s.freeUpAgeMonths)
        assertTrue(s.loopVideos)
        assertFalse(s.chargingOnly)
        assertNull(s.smb)
        assertNull(s.destination)
        assertTrue(s.folders.camera)
        assertTrue(s.notifyOnComplete)
        assertEquals(1, s.parallelUploads)
        assertFalse(s.overnightOnly)
        assertEquals(1, s.overnightStartHour)
        assertEquals(6, s.overnightEndHour)
        assertNull(s.overnightWindow())
    }

    @Test fun overnightWindowPersists() = runBlocking {
        val (store, _) = newStore()
        store.update { it.copy(overnightOnly = true, overnightStartHour = 23, overnightEndHour = 5) }
        val s = store.settings.first()
        assertEquals(dev.photobackup.backup.OvernightWindow(23, 5), s.overnightWindow())
    }

    @Test fun parallelUploadsPersistsAndIsClampedOnRead() = runBlocking {
        val (store, ds) = newStore()
        store.update { it.copy(parallelUploads = 3) }
        assertEquals(3, store.settings.first().parallelUploads)
        ds.edit { it[intPreferencesKey("parallel_uploads")] = 9 }
        assertEquals(4, store.settings.first().parallelUploads)
        ds.edit { it[intPreferencesKey("parallel_uploads")] = 0 }
        assertEquals(1, store.settings.first().parallelUploads)
    }

    @Test fun notifyOnCompletePersists() = runBlocking {
        val (store, _) = newStore()
        store.update { it.copy(notifyOnComplete = false) }
        assertFalse(store.settings.first().notifyOnComplete)
    }

    @Test fun passwordStoredEncrypted() = runBlocking {
        val (store, ds) = newStore()
        store.update { it.copy(smb = SmbConfig("nas", "share", "u", "pw")) }
        val raw = ds.data.first()[stringPreferencesKey("smb_password")]
        assertNotEquals("pw", raw)
        assertEquals("enc:wp", raw)
        assertEquals("pw", store.settings.first().smb?.password)
    }

    @Test fun nullPasswordRemovesKey() = runBlocking {
        val (store, ds) = newStore()
        store.update { it.copy(smb = SmbConfig("nas", "share", "u", "pw")) }
        store.update { it.copy(smb = SmbConfig("nas", "share", null, null)) }
        assertNull(ds.data.first()[stringPreferencesKey("smb_password")])
        assertNull(store.settings.first().smb?.password)
    }

    @Test fun roundTripsOtherFields() = runBlocking {
        val (store, _) = newStore()
        store.update {
            it.copy(destination = "Backup", deviceName = "Pixel", chargingOnly = true, freeUpAgeMonths = 3,
                loopVideos = false, folders = it.folders.copy(camera = false, extraRelativePaths = setOf("Movies/")))
        }
        val s = store.settings.first()
        assertEquals("Backup", s.destination)
        assertEquals("Pixel", s.deviceName)
        assertTrue(s.chargingOnly)
        assertEquals(3, s.freeUpAgeMonths)
        assertFalse(s.loopVideos)
        assertFalse(s.folders.camera)
        assertEquals(setOf("Movies/"), s.folders.extraRelativePaths)
    }

    private class ThrowingCipher : CredentialCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(cipher: String): String = throw java.security.GeneralSecurityException("bad tag")
    }

    @Test fun decryptFailureYieldsNullPasswordAndAllowsUpdate() = runBlocking {
        val ds = PreferenceDataStoreFactory.create { File(tmp.root, "t.preferences_pb") }
        val store = SettingsStore(ApplicationProvider.getApplicationContext(), ThrowingCipher(), ds)
        store.update { it.copy(smb = SmbConfig("nas", "share", "u", "pw")) }
        val s = store.settings.first()
        assertEquals("nas", s.smb?.host)
        assertNull(s.smb?.password)
        store.update { it.copy(smb = SmbConfig("nas", "share", "u", "new")) }
        assertEquals("enc:new", ds.data.first()[stringPreferencesKey("smb_password")])
    }
}
