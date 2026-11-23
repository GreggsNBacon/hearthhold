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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.photobackup.app.AppContainer
import dev.photobackup.bin.BinScreen
import dev.photobackup.freeup.FreeUpScreen
import dev.photobackup.freeup.FreeUpViewModel
import dev.photobackup.gallery.BrowseScreen
import dev.photobackup.gallery.GalleryScreen
import dev.photobackup.gallery.SmbRoot
import dev.photobackup.gallery.ViewerListHolder
import dev.photobackup.player.ViewerScreen
import dev.photobackup.review.ReviewScreen
import dev.photobackup.settings.AppSettings
import dev.photobackup.ui.settings.SettingsScreen
import dev.photobackup.ui.setup.SetupScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

object Routes {
    const val SETUP = "setup"
    const val GALLERY = "gallery"
    const val BROWSE = "browse?path={path}"
    const val VIEWER = "viewer/{source}/{index}"
    const val FREEUP = "freeup"
    const val SETTINGS = "settings"
    const val REVIEW = "review"
    const val BIN = "bin"

    fun browse(path: String = "") = "browse?path=${Uri.encode(path)}"
    fun viewer(source: String, index: Int) = "viewer/${Uri.encode(source)}/$index"

    /** Setup until a drive and a destination are saved; then the Gallery (the main screen). */
    fun start(s: AppSettings): String = if (s.smb == null || s.destination.isNullOrBlank()) SETUP else GALLERY
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
                onFinished = { nav.navigate(Routes.GALLERY) { popUpTo(Routes.SETUP) { inclusive = true } } },
            )
        }
        composable(Routes.FREEUP) {
            Screen("Free up space", onBack = { nav.popBackStack() }) {
                val vm: FreeUpViewModel = viewModel(factory = container.freeUpFactory)
                FreeUpScreen(vm)
            }
        }
        composable(Routes.REVIEW) {
            val gestures by container.gestures.collectAsState()
            ReviewScreen(
                viewModel = viewModel(factory = container.reviewFactory),
                gestures = gestures,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.BIN) {
            val root by produceState<SmbRoot?>(null) { value = container.driveRoot() }
            BinScreen(viewModel = viewModel(factory = container.binFactory), root = root, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel(factory = container.settingsFactory),
                driveStore = container::driveStore,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.GALLERY) {
            // Start destination: Back from here leaves the app.
            GalleryScreen(
                viewModel = viewModel(factory = container.galleryFactory),
                status = viewModel(factory = container.homeFactory),
                onOpen = { items, index ->
                    container.viewerLists.set(ViewerListHolder.GALLERY, items)
                    nav.navigate(Routes.viewer(ViewerListHolder.GALLERY, index))
                },
                onFreeUp = { nav.navigate(Routes.FREEUP) },
                onBrowse = { nav.navigate(Routes.browse()) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onReview = { nav.navigate(Routes.REVIEW) },
                onBin = { nav.navigate(Routes.BIN) },
            )
        }
        composable(
            Routes.BROWSE,
            arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            val path = entry.arguments?.getString("path").orEmpty()
            val root by produceState<SmbRoot?>(null) { value = container.driveRoot() }
            BrowseScreen(
                viewModel = viewModel(factory = container.browseFactory(path)),
                root = root,
                onBack = { nav.popBackStack() },
                onOpenFolder = { nav.navigate(Routes.browse(it)) },
                onOpen = { items, index ->
                    container.viewerLists.set(ViewerListHolder.BROWSE, items)
                    nav.navigate(Routes.viewer(ViewerListHolder.BROWSE, index))
                },
            )
        }
        composable(
            Routes.VIEWER,
            arguments = listOf(
                navArgument("source") { type = NavType.StringType },
                navArgument("index") { type = NavType.IntType },
            ),
        ) { entry ->
            val source = entry.arguments?.getString("source").orEmpty()
            val index = entry.arguments?.getInt("index") ?: 0
            // The list is gone after process death: go back to where it came from.
            val items = remember(source) { container.viewerLists.get(source).orEmpty() }
            val loop by remember { container.settingsStore.settings.map { it.loopVideos } }.collectAsState(initial = true)
            val root by produceState<Loaded<SmbRoot?>?>(null) { value = Loaded(container.driveRoot()) }
            val loaded = root
            if (loaded == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                ViewerScreen(
                    items = items,
                    startIndex = index,
                    root = loaded.value,
                    loop = loop,
                    onLoopChange = container::setLoopVideos,
                    session = container.playerSession,
                    openStore = container::driveStore,
                    closeStore = container::closeInBackground,
                    onBack = { nav.popBackStack() },
                )
            }
        }
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

/** A value that has finished loading (which may itself be null). */
private class Loaded<T>(val value: T)
