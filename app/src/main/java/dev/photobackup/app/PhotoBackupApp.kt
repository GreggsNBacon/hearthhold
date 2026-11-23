package dev.photobackup.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import dev.photobackup.backup.BackupLock
import dev.photobackup.backup.BackupNotifications
import dev.photobackup.bin.BinRepository
import dev.photobackup.bin.BinViewModel
import dev.photobackup.backup.BackupScheduler
import dev.photobackup.backup.BackupStatusRepository
import dev.photobackup.backup.LedgerDatabase
import dev.photobackup.backup.OvernightWindow
import dev.photobackup.discovery.HostDiscovery
import dev.photobackup.discovery.ShareLister
import dev.photobackup.freeup.FreeUpRepository
import dev.photobackup.freeup.FreeUpViewModel
import dev.photobackup.freeup.SecondGateOpener
import dev.photobackup.freeup.SuggestionRule
import dev.photobackup.gallery.BrowseViewModel
import dev.photobackup.gallery.CopyToPhone
import dev.photobackup.gallery.GalleryViewModel
import dev.photobackup.gallery.secondCopiesFlow
import dev.photobackup.gallery.SmbCoilFetcher
import dev.photobackup.gallery.SmbRoot
import dev.photobackup.gallery.ViewerListHolder
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerDao
import dev.photobackup.ledger.SecondCopy
import dev.photobackup.media.LocalMediaSource
import dev.photobackup.player.PlayerSession
import dev.photobackup.review.DEFAULT_GESTURES
import dev.photobackup.review.RealReviewFinisher
import dev.photobackup.review.ReviewAction
import dev.photobackup.review.ReviewViewModel
import dev.photobackup.review.Swipe
import dev.photobackup.settings.AppSettings
import dev.photobackup.settings.KeystoreCredentialCipher
import dev.photobackup.settings.SettingsStore
import dev.photobackup.smb.RemoteStore
import dev.photobackup.smb.SmbConfig
import dev.photobackup.smb.SmbjRemoteStore
import dev.photobackup.ui.auth.AuthPrompt
import dev.photobackup.ui.auth.AuthRetryingStore
import dev.photobackup.ui.home.HomeViewModel
import dev.photobackup.ui.home.passwordUnreadable
import dev.photobackup.ui.settings.SettingsViewModel
import dev.photobackup.ui.setup.SetupMode
import dev.photobackup.ui.setup.SetupViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** What the UI needs from the background scheduler; tests record the calls. */
interface BackupControl {
    /** [night]: the overnight window when "Only back up overnight" is on, else null. */
    fun scheduleAll(chargingOnly: Boolean, night: OvernightWindow?)
    fun runNow(chargingOnly: Boolean)
}

class PhotoBackupApp : Application(), ImageLoaderFactory {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // A killed process (OOM, force-stop) can leave "in progress" saved: clear it unless a run holds the lock.
        container.appScope.launch(Dispatchers.IO) {
            try {
                container.statusRepository.clearStaleProgress { BackupLock.mutex.isLocked }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Status is cosmetic here; the next run rewrites it.
            }
        }
    }

    /** Coil: phone video frames, drive thumbnails (smb://), and a thumbnail cache the OS won't clear. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(
                SmbCoilFetcher.Factory(
                    store = { container.thumbnailStore },
                    fullSizeStore = { container.viewerPhotoStore },
                    savedRoot = { container.driveRoot() },
                )
            )
            add(VideoFrameDecoder.Factory())
        }
        .diskCache {
            DiskCache.Builder()
                .directory(noBackupFilesDir.resolve("thumbnails"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .build()
}

/** Manual DI: the app's singletons and ViewModel factories. Everything is lazy so process start stays cheap. */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    /** Outlives screens; used for work started from a notification tap. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsStore: SettingsStore by lazy { SettingsStore(app, KeystoreCredentialCipher()) }

    /**
     * The saved swipe gestures, kept current for the review deck (which reads them synchronously on every swipe) and
     * its direction hints. Null until the settings have been read: review ignores swipes and hides hints until then.
     * Collected from app start; a settings read error falls back to the defaults instead of crashing [appScope].
     */
    val gestures: StateFlow<Map<Swipe, ReviewAction?>?> =
        settingsStore.settings.map<AppSettings, Map<Swipe, ReviewAction?>?> { it.gestures }
            .catch { e -> if (e is CancellationException) throw e else emit(DEFAULT_GESTURES) }
            .stateIn(appScope, SharingStarted.Eagerly, null)
    val statusRepository: BackupStatusRepository get() = BackupStatusRepository.get(app)
    val ledger: LedgerDao get() = LedgerDatabase.get(app).ledgerDao()
    val media: LocalMediaSource by lazy { LocalMediaSource(app) }
    private val discovery: HostDiscovery by lazy { HostDiscovery(app) }
    private val shareLister: ShareLister by lazy { ShareLister() }
    val storeFactory: (SmbConfig) -> RemoteStore = { SmbjRemoteStore(it) }

    val backup: BackupControl = object : BackupControl {
        override fun scheduleAll(chargingOnly: Boolean, night: OvernightWindow?) =
            BackupScheduler.scheduleAll(app, chargingOnly, night)
        override fun runNow(chargingOnly: Boolean) = BackupScheduler.runNow(app, chargingOnly)
    }

    val authPrompt: AuthPrompt by lazy {
        AuthPrompt(
            load = { settingsStore.settings.first().smb },
            save = { user, pass ->
                settingsStore.update { s -> s.copy(smb = s.smb?.copy(username = user, password = pass)) }
            },
            onReauthSaved = { onReauthSaved() },
        )
    }

    /**
     * "Re-enter second location password": the main flow's [AuthPrompt], for the second location's own login. Saves
     * only into `second` (never the main login); a missing second location reads as not configured.
     */
    val secondAuthPrompt: AuthPrompt by lazy {
        AuthPrompt(
            load = { settingsStore.settings.first().second?.smb },
            save = { user, pass ->
                settingsStore.update { s ->
                    s.copy(second = s.second?.let { it.copy(smb = it.smb.copy(username = user, password = pass)) })
                }
            },
            onReauthSaved = { onSecondReauthSaved() },
        )
    }

    @Volatile private var secondReauthSaves = 0

    /** A new second-location login was saved: clear its notification and run a backup so copying resumes. */
    private suspend fun onSecondReauthSaved() {
        secondReauthSaves++
        BackupNotifications.clearSecondError(app)
        backup.runNow(settings().chargingOnly)
    }

    /** The second location was removed or replaced: its stored status line and notification no longer apply. */
    private suspend fun clearSecondStatus() {
        statusRepository.update { it.copy(secondStatus = null) }
        BackupNotifications.clearSecondError(app)
    }

    /** Opens the "Re-enter second location password" dialog and checks the new login against the second drive. */
    suspend fun reenterSecondPassword() {
        secondAuthPrompt.prompt { cfg -> storeFactory(cfg).use { it.connect() } }
    }

    /** Tapped the second location's "Re-enter password" notification: try the saved login, ask only if refused. */
    fun reauthSecondFromNotification() {
        appScope.launch {
            try {
                val before = secondReauthSaves
                secondAuthPrompt.withAuthRetry { cfg -> storeFactory(cfg).use { it.connect() } }
                if (secondReauthSaves == before) onSecondReauthSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // cancelled, still failing or removed meanwhile: the status sheet shows the state
            }
        }
    }

    private suspend fun settings(): AppSettings = settingsStore.settings.first()

    /** A store for the saved drive; every call re-prompts on a refused login. Caller closes it. */
    fun driveStore(): RemoteStore = AuthRetryingStore(authPrompt, storeFactory)

    /** Hands the Gallery/Browse list to the viewer (`viewer/{source}/{index}`). */
    val viewerLists = ViewerListHolder()

    /** The viewer's full-screen drive photos: their own connection (app lifetime), apart from thumbnails. */
    val viewerPhotoStore: RemoteStore by lazy { driveStore() }

    /** Closes a store off the main thread: smbj's logoff can wait for a hung drive. */
    fun closeInBackground(store: java.io.Closeable) {
        appScope.launch(Dispatchers.IO) { runCatching { store.close() } }
    }

    /** Player speed for this launch only (resets to 1× next launch). */
    val playerSession = PlayerSession()

    /** Saves the viewer's loop toggle. */
    fun setLoopVideos(loop: Boolean) {
        appScope.launch {
            try {
                settingsStore.update { it.copy(loopVideos = loop) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e // unreadable settings: the toggle is just not saved
            }
        }
    }

    /**
     * The one store all drive thumbnails go through (SmbCoilFetcher serialises its calls), kept open for the app's
     * life. Screens use their own [driveStore], so a reconnect there never kills a thumbnail stream or vice versa.
     */
    val thumbnailStore: RemoteStore by lazy { driveStore() }

    /** Copy to phone streams the whole file, so it gets its own connection, closed afterwards. */
    suspend fun copyToPhone(remotePath: String, isVideo: Boolean) {
        driveStore().use { CopyToPhone.copy(app, it, remotePath, isVideo) }
    }

    suspend fun driveRoot(): SmbRoot? = driveRootOf { settings() }

    /** Connects and logs in to the saved drive (via the auth prompt); throws on failure. */
    suspend fun checkDrive() {
        val drive = authPrompt.withAuthRetry { cfg -> storeFactory(cfg).use { it.connect() } }
        // For the status sheet's "X free on the drive". Cosmetic: a failed write must not fail the check.
        try {
            statusRepository.recordDriveFree(drive.freeBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** A new login was saved (from any screen or the notification): clear the stale error and resume backing up. */
    private suspend fun onReauthSaved() {
        reauthSaves++
        BackupNotifications.clearError(app)
        backup.runNow(settings().chargingOnly)
    }

    @Volatile private var reauthSaves = 0

    /** Opens the "Re-enter drive password" dialog and checks the new login against the drive. */
    suspend fun reenterPassword() {
        authPrompt.prompt { cfg -> storeFactory(cfg).use { it.connect() } }
    }

    /** Tapped the "Wrong username or password" notification: ask, then retry the backup that failed. */
    fun reauthFromNotification() {
        appScope.launch {
            try {
                // The saved login first; the dialog only appears if the drive refuses it (or it is unreadable).
                val before = reauthSaves
                checkDrive()
                // The prompt already cleared/resumed if it saved a new login; otherwise the saved login works now.
                if (reauthSaves == before) onReauthSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // cancelled or still failing: Home shows the state
            }
        }
    }

    /** Bytes the "Free up" rule would offer now; 0 without photo access. */
    suspend fun freeUpBytes(s: AppSettings): Long {
        val phone = try {
            media.query(s.folders)
        } catch (_: SecurityException) {
            return 0
        }
        val verified = ledger.byStatus(BackupStatus.VERIFIED)
        return withContext(Dispatchers.Default) {
            SuggestionRule.suggest(
                verified,
                phone.associateBy { it.mediaId },
                System.currentTimeMillis(),
                s.freeUpAgeMonths,
                ZoneId.systemDefault(),
            ).sumOf { it.sizeBytes }
        }
    }

    val setupFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            SetupViewModel(
                settingsStore = settingsStore,
                discover = { discovery.discover() },
                listShares = { host, user, pass -> shareLister.shares(host, user, pass) },
                openStore = storeFactory,
                backup = backup,
            )
        }
    }

    /** Setup in "second" mode, from Settings: picks the second backup location and saves it to `second` only. */
    val secondSetupFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            SetupViewModel(
                settingsStore = settingsStore,
                discover = { discovery.discover() },
                listShares = { host, user, pass -> shareLister.shares(host, user, pass) },
                openStore = storeFactory,
                backup = backup,
                mode = SetupMode.SECOND,
                clearSecondStatus = { clearSecondStatus() },
            )
        }
    }

    val homeFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            HomeViewModel(
                settings = settingsStore.settings,
                status = statusRepository.status,
                counts = ledger.observeCounts(),
                checkDrive = { checkDrive() },
                reenterPassword = { reenterPassword() },
                freeUpBytes = { freeUpBytes(it) },
                backup = backup,
                reenterSecondPassword = { reenterSecondPassword() },
            )
        }
    }

    val settingsFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            SettingsViewModel(
                settingsStore, storeFactory, backup, media::folderCounts,
                ledger = ledger, assignments = LedgerDatabase.get(app).folderAssignmentDao(),
                clearSecondCopies = { LedgerDatabase.get(app).secondCopyDao().clear() },
                clearSecondStatus = { clearSecondStatus() },
            )
        }
    }

    val galleryFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            GalleryViewModel(
                queryPhone = { media.query(settings().folders) },
                ledger = ledger.observeAll(),
                checkDrive = { checkDrive() },
                driveRoot = { driveRoot() },
                needsPassword = { passwordUnreadable(settings()) },
                assignments = LedgerDatabase.get(app).folderAssignmentDao(),
                rules = { settings().folderRules },
                runNow = { backup.runNow(settings().chargingOnly) },
                root = { settings().let { it.destination to it.deviceName } },
                second = secondCopies(),
            )
        }
    }

    /** The Gallery badge's second copies ([secondCopiesFlow]: never fails, null while no second location is set). */
    private fun secondCopies(): Flow<Map<Long, SecondCopy>?> =
        secondCopiesFlow(settingsStore.settings) { LedgerDatabase.get(app).secondCopyDao().observeAll() }

    /** Browse one folder; each `browse?path=` back-stack entry gets its own ViewModel and connection. */
    fun browseFactory(path: String): ViewModelProvider.Factory = viewModelFactory {
        initializer { BrowseViewModel(path, driveStore()) { remote, video -> copyToPhone(remote, video) } }
    }

    val freeUpFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val repo = FreeUpRepository(ledger)
            FreeUpViewModel(
                repo = repo,
                queryPhone = { media.query(settings().folders) },
                openStore = { if (settings().smb == null) null else driveStore() },
                ageMonths = { settings().freeUpAgeMonths },
                opener = media.opener(),
                makeDeleteRequest = { items -> repo.deleteRequest(app, items) },
                openSecondGate = { secondGate().open() },
            )
        }
    }

    /** The Free up / Finish "verified on both drives" gate; Off (no connection) unless the settings turn it on. */
    private fun secondGate() = SecondGateOpener(
        settings = { settings() },
        openStore = storeFactory,
        copies = LedgerDatabase.get(app).secondCopyDao(),
    )

    /** Opens a store for the saved drive, or null when none is set up. */
    private suspend fun driveStoreOrNull(): RemoteStore? = if (settings().smb == null) null else driveStore()

    val reviewFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val db = LedgerDatabase.get(app)
            val repo = FreeUpRepository(ledger)
            val finisher = RealReviewFinisher(
                ledger = ledger,
                decisions = db.reviewDecisionDao(),
                keeps = db.reviewKeepDao(),
                repo = repo,
                openStore = { driveStoreOrNull() },
                queryPhone = { media.query(settings().folders).associateBy { it.mediaId } },
                phoneExists = { ids -> media.existingIds(ids) },
                openSecondGate = { secondGate().open() },
                opener = media.opener(),
                makeDeleteRequest = { items -> repo.deleteRequest(app, items) },
                runNow = {
                    appScope.launch {
                        try {
                            backup.runNow(settings().chargingOnly)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // The next scheduled run does the drive-side work instead.
                        }
                    }
                },
            )
            val g = gestures
            ReviewViewModel(
                ledger = ledger,
                queryPhone = { media.query(settings().folders) },
                decisions = db.reviewDecisionDao(),
                keeps = db.reviewKeepDao(),
                assignments = db.folderAssignmentDao(),
                folderRules = { settings().folderRules },
                gestures = { g.value ?: emptyMap() }, // not loaded yet: every swipe is ignored
                finisher = finisher,
            )
        }
    }

    val binFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val repo = BinRepository(
                ledger = ledger,
                assignments = LedgerDatabase.get(app).folderAssignmentDao(),
                openStore = { driveStoreOrNull() },
                root = { settings().let { it.destination to it.deviceName } },
            )
            BinViewModel(
                list = repo.list(),
                restore = { repo.restore(it) },
                empty = { repo.empty(it) },
                emptyAll = { repo.emptyAll() },
            )
        }
    }
}
