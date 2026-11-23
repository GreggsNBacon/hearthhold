package dev.photobackup.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.photobackup.app.AppContainer
import dev.photobackup.freeup.FreeUpScreen
import dev.photobackup.freeup.FreeUpViewModel
import dev.photobackup.settings.AppSettings
import dev.photobackup.ui.home.HomeScreen
import dev.photobackup.ui.settings.SettingsScreen
import dev.photobackup.ui.setup.SetupScreen
import kotlinx.coroutines.flow.first

object Routes {
    const val SETUP = "setup"
    const val HOME = "home"
    const val GALLERY = "gallery"
    const val BROWSE = "browse?path={path}"
    const val VIEWER = "viewer/{source}/{index}"
    const val FREEUP = "freeup"
    const val SETTINGS = "settings"

    fun browse(path: String = "") = "browse?path=${Uri.encode(path)}"
    fun viewer(source: String, index: Int) = "viewer/${Uri.encode(source)}/$index"

    /** Setup until a drive and a destination are saved. */
    fun start(s: AppSettings): String = if (s.smb == null || s.destination.isNullOrBlank()) SETUP else HOME
}

@Composable
fun AppNav(container: AppContainer) {
    val start by produceState<String?>(null) { value = Routes.start(container.settingsStore.settings.first()) }
    val startRoute = start
    if (startRoute == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = startRoute) {
        composable(Routes.SETUP) {
            SetupScreen(
                viewModel = viewModel(factory = container.setupFactory),
                driveStore = container::driveStore,
                onFinished = { nav.navigate(Routes.HOME) { popUpTo(Routes.SETUP) { inclusive = true } } },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = viewModel(factory = container.homeFactory),
                onFreeUp = { nav.navigate(Routes.FREEUP) },
                onGallery = { nav.navigate(Routes.GALLERY) },
                onBrowse = { nav.navigate(Routes.browse()) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.FREEUP) {
            Screen("Free up space", onBack = { nav.popBackStack() }) {
                val vm: FreeUpViewModel = viewModel(factory = container.freeUpFactory)
                FreeUpScreen(vm)
            }
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel(factory = container.settingsFactory),
                driveStore = container::driveStore,
                onBack = { nav.popBackStack() },
            )
        }
        // Placeholders: Tasks 10/11 replace these.
        composable(Routes.GALLERY) { ComingSoon("Gallery") { nav.popBackStack() } }
        composable(
            Routes.BROWSE,
            arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
        ) { ComingSoon("Browse drive") { nav.popBackStack() } }
        composable(
            Routes.VIEWER,
            arguments = listOf(
                navArgument("source") { type = NavType.StringType },
                navArgument("index") { type = NavType.IntType },
            ),
        ) { ComingSoon("Viewer") { nav.popBackStack() } }
    }
}

/** Standard screen frame: title bar with an optional Back button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { if (onBack != null) TextButton(onClick = onBack) { Text("Back") } },
                actions = { actions() },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) { content() }
    }
}

@Composable
private fun ComingSoon(title: String, onBack: () -> Unit) {
    Screen(title, onBack = onBack) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Coming soon") }
    }
}
