package dev.photobackup.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import dev.photobackup.gallery.GalleryItem
import dev.photobackup.gallery.SmbRoot
import dev.photobackup.smb.RemoteStore
import dev.photobackup.smb.SmbUri
import java.io.Closeable

/**
 * The drive store for one player: opened on the first drive read, closed with the page. Its own connection, so a
 * long stream is never killed by a reconnect elsewhere (Browse, thumbnails).
 */
private class PageStore(
    private val open: () -> RemoteStore,
    private val closeLater: (Closeable) -> Unit,
) : Closeable {
    private var store: RemoteStore? = null
    private var closed = false

    @Synchronized
    fun get(): RemoteStore {
        check(!closed) { "Player page closed" }
        return store ?: open().also { store = it }
    }

    /** Never blocks: the store (smbj logoff, which can wait on a hung drive) is closed by [closeLater]. */
    override fun close() {
        val s = synchronized(this) {
            closed = true
            store.also { store = null }
        }
        s?.let(closeLater)
    }
}

@OptIn(UnstableApi::class)
private fun mediaSource(context: Context, item: GalleryItem, root: SmbRoot?, store: PageStore): MediaSource? {
    val phone = item.phoneUri
    if (phone != null) return DefaultMediaSourceFactory(context).createMediaSource(MediaItem.fromUri(phone))
    val remote = item.remotePath ?: return null
    root ?: return null
    return ProgressiveMediaSource.Factory(SmbDataSource.Factory { store.get() })
        .setLoadErrorHandlingPolicy(SmbDataSource.loadErrorPolicy())
        .createMediaSource(MediaItem.fromUri(SmbUri.build(root.host, root.share, remote)))
}

/**
 * One video. Only the [active] (settled, visible) page holds an ExoPlayer; it is released when the page stops being
 * active or leaves composition. Plays the phone copy when there is one, else streams the drive copy through
 * [SmbDataSource]. Pauses while the app is in the background.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPage(
    item: GalleryItem,
    root: SmbRoot?,
    active: Boolean,
    loop: Boolean,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    openStore: () -> RemoteStore,
    closeStore: (Closeable) -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (active) ActivePlayer(item, root, loop, speed, onSpeedChange, openStore, closeStore)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ActivePlayer(
    item: GalleryItem,
    root: SmbRoot?,
    loop: Boolean,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    openStore: () -> RemoteStore,
    closeStore: (Closeable) -> Unit,
) {
    val context = LocalContext.current
    val fromDrive = item.phoneUri == null
    val missing = unavailableMessage(item.phoneUri != null, item.remotePath != null, root != null, isVideo = true)
    if (missing != null) {
        PageMessage(missing)
        return
    }
    val store = remember(item.key) { PageStore(openStore, closeStore) }
    val source = remember(item.key, root) { mediaSource(context, item, root, store) }
    if (source == null) {
        DisposableEffect(store) { onDispose { store.close() } }
        PageMessage(MSG_CANT_PLAY)
        return
    }
    // Survives a rotation: the position and play/pause state are captured when the activity pauses (before it is
    // saved and recreated) and restored when the player is rebuilt.
    var snapshot by rememberSaveable(item.key, stateSaver = PlaybackSnapshot.Saver) { mutableStateOf(PlaybackSnapshot()) }
    var error by remember(item.key) { mutableStateOf<String?>(null) }
    val currentOnSpeed by rememberUpdatedState(onSpeedChange)

    val player = remember(item.key) {
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(source)
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            playbackParameters = PlaybackParameters(speed, 1f)
            if (snapshot.positionMs > 0) seekTo(snapshot.positionMs)
            playWhenReady = snapshot.playWhenReady
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = viewerErrorMessage(e, fromDrive = fromDrive, isVideo = true)
            }

            // PlayerView's own settings menu can change the speed too: keep the session speed in step.
            override fun onPlaybackParametersChanged(p: PlaybackParameters) = currentOnSpeed(p.speed)
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            store.close() // off the main thread; also ends any drive read still in flight
        }
    }
    LaunchedEffect(player, loop) { player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    LaunchedEffect(player, speed) {
        if (player.playbackParameters.speed != speed) player.playbackParameters = PlaybackParameters(speed, 1f)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        var resume = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    resume = player.playWhenReady
                    snapshot = PlaybackSnapshot.capture(player.currentPosition, player.playWhenReady)
                    player.pause()
                }
                Lifecycle.Event.ON_RESUME -> if (resume) {
                    resume = false
                    player.play()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                setShowNextButton(false)
                setShowPreviousButton(false)
                setShowSubtitleButton(false)
                setShowShuffleButton(false)
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
    )
    error?.let { msg ->
        PageMessage(msg) {
            error = null
            player.prepare()
            player.play()
        }
    }
}
