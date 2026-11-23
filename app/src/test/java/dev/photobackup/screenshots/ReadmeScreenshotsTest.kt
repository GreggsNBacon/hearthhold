package dev.photobackup.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Looper
import coil.Coil
import coil.ImageLoader
import coil.map.Mapper
import kotlinx.coroutines.Dispatchers
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import dev.photobackup.backup.BackupStatusSnapshot
import dev.photobackup.gallery.GalleryScreen
import dev.photobackup.gallery.GalleryViewModel
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.media.PhoneMedia
import dev.photobackup.settings.AppSettings
import dev.photobackup.settings.CredentialCipher
import dev.photobackup.settings.SettingsStore
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.SmbConfig
import dev.photobackup.gallery.SmbRoot
import dev.photobackup.ui.home.HomeUiState
import dev.photobackup.ui.home.HomeViewModel
import dev.photobackup.ui.home.Reachability
import dev.photobackup.ui.home.StatusSheetContent
import dev.photobackup.ui.settings.SettingsScreen
import dev.photobackup.ui.settings.SettingsViewModel
import dev.photobackup.ui.setup.FakeBackupControl
import dev.photobackup.ui.theme.PhotoBackupTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

private class PlainCipher : CredentialCipher {
    override fun encrypt(plain: String) = "x$plain"
    override fun decrypt(cipher: String) = cipher.removePrefix("x")
}

/**
 * Renders the real screens with made-up demo data into PNGs for the README. Skipped unless run with
 * `-PscreenshotsDir=docs/screenshots` (see app/build.gradle.kts). No real photos: thumbnails are drawn here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h860dp-xhdpi")
class ReadmeScreenshotsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()

    private val outDir: File? = System.getProperty("hh.screenshots.dir")?.let(::File)

    @Before fun onlyWhenAsked() = assumeTrue(outDir != null)

    private fun save(name: String) {
        // Spinners never go idle, so drive the clock by hand; the sleeps let Coil decode the thumbnails.
        repeat(30) {
            Thread.sleep(100)
            compose.mainClock.advanceTimeBy(100)
            shadowOf(Looper.getMainLooper()).idle()
        }
        // captureToImage waits for a frame callback that never comes with a paused clock: draw the window directly.
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        outDir!!.mkdirs()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show(content: @Composable () -> Unit) {
        // Synchronous loader: decode on the calling thread, and serve drive (smb://) thumbnails from drawn files.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Coil.setImageLoader(
            ImageLoader.Builder(ctx)
                .dispatcher(Dispatchers.Unconfined)
                .components {
                    add(Mapper<String, File> { data, _ ->
                        if (data.startsWith("smb://")) thumb(data.hashCode().and(0xff)) else null
                    })
                }
                .build(),
        )
        compose.mainClock.autoAdvance = false
        compose.setContent { PhotoBackupTheme { Surface { content() } } }
    }

    /** A simple landscape: gradient sky, sun, two hills. Different palette per index. */
    private fun thumb(i: Int): File {
        val skies = listOf(
            0xFFFFB86B to 0xFFFF6F61, 0xFF8EC5FC to 0xFFE0C3FC, 0xFF43C6AC to 0xFF191654,
            0xFFF6D365 to 0xFFFDA085, 0xFF89F7FE to 0xFF66A6FF, 0xFFFBC2EB to 0xFFA6C1EE,
            0xFF2B5876 to 0xFF4E4376, 0xFFFFE29F to 0xFFFF719A,
        )
        val (top, bottom) = skies[i % skies.size]
        val s = 256f
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, s, top.toInt(), bottom.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s, s, p)
        p.shader = null
        p.color = 0xEEFFF6D5.toInt()
        c.drawCircle(60f + (i * 37) % 140, 80f + (i * 23) % 50, 26f, p)
        p.color = 0xFF2E4A3A.toInt()
        c.drawOval(-60f, 170f + (i % 3) * 8, 200f, 360f, p)
        p.color = 0xFF1F3329.toInt()
        c.drawOval(90f, 190f - (i % 4) * 6, 340f, 380f, p)
        return File(tmp.root, "t$i.png").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private val day = 86_400_000L
    private val now = 1_795_420_000_000L

    @Test fun gallery() {
        val phone = (0 until 21).map { i ->
            PhoneMedia(
                mediaId = i.toLong(), uri = Uri.fromFile(thumb(i)).toString(), displayName = "PXL_$i.jpg",
                relativePath = "DCIM/Camera/", sizeBytes = 3_000_000L + i, dateTakenMs = now - i * day,
                dateAddedSec = 0, dateModifiedSec = 0, isVideo = i % 7 == 3, mimeType = "image/jpeg",
            )
        }
        fun entry(m: PhoneMedia, status: BackupStatus) = LedgerEntry(
            m.mediaId, m.uri, m.displayName, m.relativePath, m.sizeBytes, m.dateTakenMs, 0, m.isVideo, "h",
            "Pixel 9\\Camera\\2025\\${m.displayName}", null, status, if (status == BackupStatus.FAILED) "Drive error" else null, 0,
        )
        // Newest 3 waiting, one failed, the rest backed up.
        val onPhone = phone.drop(3).map { entry(it, if (it.mediaId == 9L) BackupStatus.FAILED else BackupStatus.VERIFIED) }
        // Freed up from the phone earlier: shown with the cloud badge, thumbnails come from the drive.
        val freed = (100 until 104).map { i ->
            LedgerEntry(
                i.toLong(), "content://gone/$i", "PXL_$i.jpg", "DCIM/Camera/", 4_000_000, now - (i - 96) * day, 0,
                false, "h", "Pixel 9\\Camera\\2025\\PXL_$i.jpg", null, BackupStatus.REMOVED_FROM_PHONE, null, 0,
            )
        }
        val ledger = onPhone + freed
        val gallery = GalleryViewModel(
            queryPhone = { phone }, ledger = flowOf(ledger), checkDrive = {}, driveRoot = { SmbRoot("router", "Photos") },
        )
        val status = HomeViewModel(
            settings = flowOf(AppSettings(SmbConfig("router", "Photos", "me", "x"), "Hearthhold")),
            status = flowOf(BackupStatusSnapshot(now, null, null, true, 12, 40, 90, 512L shl 30)),
            counts = flowOf(mapOf(BackupStatus.VERIFIED to 17, BackupStatus.PENDING to 3, BackupStatus.FAILED to 1)),
            checkDrive = {}, reenterPassword = {}, freeUpBytes = { 6L shl 30 }, backup = FakeBackupControl(),
        )
        show { GalleryScreen(gallery, status, onOpen = { _, _ -> }, onFreeUp = {}, onBrowse = {}, onSettings = {}) }
        save("gallery")
    }

    @Test fun status() {
        val settings = AppSettings(SmbConfig("router", "Photos", "me", "x"), "Hearthhold")
        show {
            StatusSheetContent(
                state = HomeUiState(
                    settings = settings,
                    status = BackupStatusSnapshot(now, null, null, true, 12, 40, 90, 512L shl 30),
                    waiting = 28, backedUp = 1_204, failed = 0, reach = Reachability.YES, freeUpBytes = 6L shl 30,
                ),
                reach = Reachability.YES, reachMessage = null, onBackUpNow = {}, onFreeUp = {}, onReenter = {},
            )
        }
        save("status")
    }

    @Test fun settings() {
        val ds = PreferenceDataStoreFactory.create { File(tmp.root, "s.preferences_pb") }
        val store = SettingsStore(ApplicationProvider.getApplicationContext(), PlainCipher(), ds)
        runBlocking {
            store.update {
                it.copy(
                    smb = SmbConfig("router", "Photos", "me", "pw"), destination = "Hearthhold", deviceName = "Pixel 9",
                    folders = it.folders.copy(extraRelativePaths = setOf("Pictures/WhatsApp/", "Pictures/Reddit/")),
                )
            }
        }
        val vm = SettingsViewModel(store, { FakeRemoteStore() }, FakeBackupControl())
        show { SettingsScreen(vm, driveStore = { FakeRemoteStore() }, onBack = {}) }
        save("settings")
    }
}
