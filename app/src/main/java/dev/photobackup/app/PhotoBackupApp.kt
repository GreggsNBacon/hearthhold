package dev.photobackup.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.photobackup.backup.BackupNotifications
import dev.photobackup.backup.BackupScheduler
import dev.photobackup.backup.BackupStatusRepository
import dev.photobackup.backup.LedgerDatabase
import dev.photobackup.discovery.HostDiscovery
import dev.photobackup.discovery.ShareLister
import dev.photobackup.freeup.FreeUpRepository
import dev.photobackup.freeup.FreeUpViewModel
import dev.photobackup.freeup.SuggestionRule
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerDao
import dev.photobackup.media.LocalMediaSource
import dev.photobackup.settings.AppSettings
import dev.photobackup.settings.KeystoreCredentialCipher
import dev.photobackup.settings.SettingsStore
import dev.photobackup.smb.RemoteStore
import dev.photobackup.smb.SmbConfig
import dev.photobackup.smb.SmbjRemoteStore
import dev.photobackup.ui.auth.AuthPrompt
import dev.photobackup.ui.auth.AuthRetryingStore
import dev.photobackup.ui.home.HomeViewModel
import dev.photobackup.ui.settings.SettingsViewModel
import dev.photobackup.ui.setup.SetupViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId

/** What the UI needs from the background scheduler; tests record the calls. */
interface BackupControl {
    fun scheduleAll(chargingOnly: Boolean)
    fun runNow(chargingOnly: Boolean)
}

class PhotoBackupApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/** Manual DI: the app's singletons and ViewModel factories. Everything is lazy so process start stays cheap. */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    /** Outlives screens; used for work started from a notification tap. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsStore: SettingsStore by lazy { SettingsStore(app, KeystoreCredentialCipher()) }
    val statusRepository: BackupStatusRepository get() = BackupStatusRepository.get(app)
    val ledger: LedgerDao get() = LedgerDatabase.get(app).ledgerDao()
    val media: LocalMediaSource by lazy { LocalMediaSource(app) }
    private val discovery: HostDiscovery by lazy { HostDiscovery(app) }
    private val shareLister: ShareLister by lazy { ShareLister() }
    val storeFactory: (SmbConfig) -> RemoteStore = { SmbjRemoteStore(it) }

    val backup: BackupControl = object : BackupControl {
        override fun scheduleAll(chargingOnly: Boolean) = BackupScheduler.scheduleAll(app, chargingOnly)
        override fun runNow(chargingOnly: Boolean) = BackupScheduler.runNow(app, chargingOnly)
    }

    val authPrompt: AuthPrompt by lazy {
        AuthPrompt(
            load = { settingsStore.settings.first().smb },
            save = { user, pass ->
                settingsStore.update { s -> s.copy(smb = s.smb?.copy(username = user, password = pass)) }
            },
        )
    }

    private suspend fun settings(): AppSettings = settingsStore.settings.first()

    /** A store for the saved drive; every call re-prompts on a refused login. Caller closes it. */
    fun driveStore(): RemoteStore = AuthRetryingStore(authPrompt, storeFactory)

    /** Connects and logs in to the saved drive (via the auth prompt); throws on failure. */
    suspend fun checkDrive() {
        authPrompt.withAuthRetry { cfg -> storeFactory(cfg).use { it.connect() } }
    }

    /** Opens the "Re-enter drive password" dialog and checks the new login against the drive. */
    suspend fun reenterPassword() {
        authPrompt.prompt { cfg -> storeFactory(cfg).use { it.connect() } }
        BackupNotifications.clearError(app)
    }

    /** Tapped the "Wrong username or password" notification: ask, then retry the backup that failed. */
    fun reauthFromNotification() {
        appScope.launch {
            try {
                reenterPassword()
                backup.runNow(settings().chargingOnly)
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
        return SuggestionRule.suggest(
            ledger.byStatus(BackupStatus.VERIFIED),
            phone.associateBy { it.mediaId },
            System.currentTimeMillis(),
            s.freeUpAgeMonths,
            ZoneId.systemDefault(),
        ).sumOf { it.sizeBytes }
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
            )
        }
    }

    val settingsFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { SettingsViewModel(settingsStore, storeFactory, backup) }
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
            )
        }
    }
}
