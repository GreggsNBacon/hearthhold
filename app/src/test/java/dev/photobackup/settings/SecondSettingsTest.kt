package dev.photobackup.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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

private class RevCipher(var failDecrypt: Boolean = false) : CredentialCipher {
    override fun encrypt(plain: String) = "enc:" + plain.reversed()
    override fun decrypt(cipher: String): String {
        if (failDecrypt) error("keystore")
        return cipher.removePrefix("enc:").reversed()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecondSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = RevCipher()

    private fun newStore(): Pair<SettingsStore, DataStore<Preferences>> {
        val ds = PreferenceDataStoreFactory.create { File(tmp.root, "s.preferences_pb") }
        return SettingsStore(ApplicationProvider.getApplicationContext(), cipher, ds) to ds
    }

    @Test fun defaultsAreOff() = runBlocking {
        val s = newStore().first.settings.first()
        assertNull(s.second)
        assertTrue(s.freeUpNeedsBoth)
        assertNull(AppSettings(null, null).second)
    }

    @Test fun roundTripsWithEncryptedPasswordAndLeavesMainLoginAlone() = runBlocking {
        val (store, ds) = newStore()
        store.update { it.copy(smb = SmbConfig("nas", "main", "u", "mainpw")) }
        store.update { it.copy(second = SecondLocation(SmbConfig("nas2", "usb", "bob", "pw2"), "Hearthhold2"), freeUpNeedsBoth = false) }
        val s = store.settings.first()
        assertEquals(SecondLocation(SmbConfig("nas2", "usb", "bob", "pw2"), "Hearthhold2"), s.second)
        assertFalse(s.freeUpNeedsBoth)
        assertEquals(SmbConfig("nas", "main", "u", "mainpw"), s.smb)
        val raw = ds.data.first()[stringPreferencesKey("second_smb_password")]
        assertNotEquals("pw2", raw)
        assertEquals("enc:2wp", raw)
        assertEquals("enc:wpniam", ds.data.first()[stringPreferencesKey("smb_password")])
    }

    @Test fun clearingRemovesAllSecondKeys() = runBlocking {
        val (store, ds) = newStore()
        store.update { it.copy(second = SecondLocation(SmbConfig("h", "s", "u", "p"), "D")) }
        store.update { it.copy(second = null) }
        assertNull(store.settings.first().second)
        val keys = ds.data.first().asMap().keys.map { it.name }
        assertTrue(keys.none { it.startsWith("second_") })
    }

    @Test fun decryptFailureGivesNullPasswordNotAThrow() = runBlocking {
        val (store, ds) = newStore()
        store.update { it.copy(second = SecondLocation(SmbConfig("h", "s", "u", "p"), "D")) }
        cipher.failDecrypt = true
        assertEquals(SecondLocation(SmbConfig("h", "s", "u", null), "D"), store.settings.first().second)
        // an unrelated edit during the hiccup keeps the stored ciphertext
        store.update { it.copy(chargingOnly = true) }
        assertEquals("enc:p", ds.data.first()[stringPreferencesKey("second_smb_password")])
        cipher.failDecrypt = false
        assertEquals("p", store.settings.first().second?.smb?.password)
    }

    @Test fun toStringNeverShowsThePassword() {
        val text = SecondLocation(SmbConfig("nas", "usb", "bob", "hunter2"), "D").toString()
        assertFalse(text.contains("hunter2"))
        assertTrue(text.contains("password=***"))
        assertFalse(AppSettings(null, null, second = SecondLocation(SmbConfig("n", "u", "b", "hunter2"), "D")).toString().contains("hunter2"))
    }
}
