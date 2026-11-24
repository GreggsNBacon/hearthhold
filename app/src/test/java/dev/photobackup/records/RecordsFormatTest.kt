package dev.photobackup.records

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.photobackup.ledger.ALL_MIGRATIONS
import dev.photobackup.ledger.AppDatabase
import dev.photobackup.media.SourceFolders
import dev.photobackup.review.ReviewAction
import dev.photobackup.review.Swipe
import dev.photobackup.settings.AppSettings
import dev.photobackup.settings.CredentialCipher
import dev.photobackup.settings.SecondLocation
import dev.photobackup.settings.SettingsStore
import dev.photobackup.smb.SmbConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.LocalDate

private class TagCipher : CredentialCipher {
    override fun encrypt(plain: String) = "enc:" + plain.reversed()
    override fun decrypt(cipher: String) = cipher.removePrefix("enc:").reversed()
}

/** Every non-default value, plus passwords on both logins. */
internal fun fullSettings() = AppSettings(
    smb = SmbConfig("nas.local", "usb", "greg", "main-secret-pw"),
    destination = "Photos\\Backup",
    deviceName = "Pixel Seven",
    chargingOnly = true,
    freeUpAgeMonths = 9,
    folders = SourceFolders(camera = false, screenshots = true, extraRelativePaths = setOf("Pictures/WhatsApp/")),
    loopVideos = false,
    notifyOnComplete = false,
    parallelUploads = 3,
    overnightOnly = true,
    overnightStartHour = 22,
    overnightEndHour = 5,
    folderRules = mapOf("Pictures/WhatsApp/" to "Chats", "Download/" to "Saved"),
    gestures = mapOf(Swipe.UP to ReviewAction.JUNK, Swipe.RIGHT to null, Swipe.LEFT to ReviewAction.KEEP, Swipe.DOWN to ReviewAction.SKIP),
    second = SecondLocation(SmbConfig("nas2", "copy", "bob", "second-secret-pw"), "Copies"),
    freeUpNeedsBoth = false,
    staleWarnDays = 11,
)

internal fun AppSettings.withoutPasswords() = copy(
    smb = smb?.copy(password = null),
    second = second?.let { it.copy(smb = it.smb.copy(password = null)) },
)

@RunWith(RobolectricTestRunner::class)
// Plain Application: the app's onCreate starts WorkManager threads, which must not outlive a native-SQLite test.
@Config(sdk = [34], application = android.app.Application::class)
// VACUUM INTO needs SQLite 3.27+ (Android 11 ships 3.28); the legacy test SQLite is 3.8.
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RecordsFormatTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun fileNameHasTheDate() {
        assertEquals("hearthhold-records-20261123.hearthhold", RecordsFormat.fileName(LocalDate.of(2026, 11, 23)))
        assertEquals("hearthhold-records-20270102.hearthhold", RecordsFormat.fileName(LocalDate.of(2027, 1, 2)))
    }

    @Test fun schemaVersionMatchesTheDatabase() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getDatabasePath("schema-check.db").delete()
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, "schema-check.db").addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries().build()
        try {
            assertEquals(RecordsFormat.SCHEMA_VERSION, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test fun metaRoundTrips() {
        val m = RecordsMeta(formatVersion = 1, schemaVersion = 5, appVersion = "1.4.1", itemCount = 42)
        assertEquals(m, RecordsMeta.parse(m.toJson()))
        assertNull(RecordsMeta.parse("not json"))
        assertNull(RecordsMeta.parse("{\"formatVersion\":1}"))
    }

    @Test fun settingsJsonRoundTripsEverythingButPasswords() {
        val s = fullSettings()
        val json = SettingsJson.encode(s)
        assertFalse(json.contains("secret"))
        assertFalse(json.contains("password", ignoreCase = true))
        assertEquals(s.withoutPasswords(), SettingsJson.decode(json))
    }

    @Test fun settingsJsonWithoutDriveOrSecond() {
        val s = AppSettings(smb = null, destination = null, deviceName = "Phone")
        assertEquals(s, SettingsJson.decode(SettingsJson.encode(s)))
        val guest = AppSettings(smb = SmbConfig("nas", "usb", null, null), destination = "P", deviceName = "Phone")
        assertEquals(guest, SettingsJson.decode(SettingsJson.encode(guest)))
    }

    @Test fun importSettingsReplacesEverythingAndDropsPasswords() = runBlocking {
        val ds = PreferenceDataStoreFactory.create { File(tmp.root, "s.preferences_pb") }
        val store = SettingsStore(ApplicationProvider.getApplicationContext(), TagCipher(), ds)
        store.update {
            it.copy(smb = SmbConfig("old", "share", "old-user", "old-pw"), destination = "Old", staleWarnDays = 2)
        }
        store.importSettings(fullSettings())
        assertEquals(fullSettings().withoutPasswords(), store.settings.first())
    }
}
