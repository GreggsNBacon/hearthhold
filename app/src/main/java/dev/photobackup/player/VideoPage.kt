package dev.photobackup.player

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import dev.photobackup.R
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import dev.photobackup.gallery.CopyToPhone
import dev.photobackup.gallery.GalleryItem
import dev.photobackup.gallery.SmbRoot
import dev.photobackup.smb.RemoteStore
import dev.photobackup.smb.SmbUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // Cached on the phone as it streams: loops and re-opens don't download again.
    val cached = CacheDataSource.Factory()
        .setCache(VideoCache.get(context))
        .setUpstreamDataSourceFactory(SmbDataSource.Factory { store.get() })
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    return ProgressiveMediaSource.Factory(cached)
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
    // The phone can't play this format (or has no decoder for its picture): offer another app instead of Retry.
    var unsupported by remember(item.key) { mutableStateOf(false) }
    var soundUnsupported by remember(item.key) { mutableStateOf(false) }
    val currentOnSpeed by rememberUpdatedState(onSpeedChange)

    val player = remember(item.key) {
        ExoPlayer.Builder(context, VideoCache.renderersFactory(context))
            .setLoadControl(VideoCache.loadControl()).build().apply {
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
                if (PlaybackFallback.wantsFallback(e.errorCode)) {
                    unsupported = true
                } else {
                    error = viewerErrorMessage(e, fromDrive = fromDrive, isVideo = true)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (tracks.groups.isEmpty()) return // not known yet
                when (PlaybackFallback.verdict(tracks)) {
                    TrackVerdict.NO_VIDEO -> {
                        unsupported = true
                        player.pause() // no picture: don't play the sound alone behind the notice
                    }
                    TrackVerdict.NO_AUDIO -> soundUnsupported = true
                    TrackVerdict.OK -> Unit
                }
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
            (LayoutInflater.from(ctx).inflate(R.layout.video_player_view, null) as PlayerView).apply {
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
    val msg = error
    when {
        unsupported -> OpenElsewherePanel(item, store, MSG_FORMAT_UNSUPPORTED, compact = false)
        msg != null -> PageMessage(msg) {
            error = null
            player.prepare()
            player.play()
        }
        soundUnsupported -> Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            OpenElsewherePanel(item, store, MSG_SOUND_UNSUPPORTED, compact = true)
        }
    }
}

/** Where "Open in another app" is: idle, copying a drive file into the cache, or showing why it can't. */
private sealed interface OpenState {
    data object Idle : OpenState
    data class Copying(val copied: Long, val total: Long) : OpenState
    data class Failed(val message: String) : OpenState
}

/**
 * [message] + "Open in another app". A phone file opens straight away; a drive file is copied into the app's cache
 * first (progress and Cancel; refused over 2 GB or without room on the phone). [compact]: a small notice (the
 * picture plays, the sound can't) instead of the centred card.
 */
@Composable
private fun OpenElsewherePanel(item: GalleryItem, store: PageStore, message: String, compact: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(item.key) { mutableStateOf<OpenState>(OpenState.Idle) }
    var job by remember(item.key) { mutableStateOf<Job?>(null) }

    fun open() {
        val phone = item.phoneUri
        if (phone != null) {
            val uri = Uri.parse(phone)
            OpenElsewhere.launch(context, uri, OpenElsewhere.mimeFor(context, uri, item.displayName))
                ?.let { state = OpenState.Failed(it) }
            return
        }
        val remote = item.remotePath ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            state = OpenState.Copying(0, item.sizeBytes)
            state = try {
                val dir = OpenElsewhere.cacheDir(context)
                val drive = withContext(Dispatchers.IO) { store.get() }
                val size = item.sizeBytes.takeIf { it > 0 }
                    ?: withContext(Dispatchers.IO) { drive.stat(remote)?.sizeBytes ?: -1L }
                val refused = withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    OpenElsewhere.refusal(size, dir.usableSpace)
                }
                if (refused != null) {
                    OpenState.Failed(refused)
                } else {
                    val file = OpenElsewhere.copyToCache(drive, remote, size, dir) { n -> state = OpenState.Copying(n, size) }
                    val uri = OpenElsewhere.shareUri(context, file)
                    OpenElsewhere.launch(context, uri, CopyToPhone.mimeType(file.name, isVideo = true))
                        ?.let { OpenState.Failed(it) } ?: OpenState.Idle
                }
            } catch (e: CancellationException) {
                state = OpenState.Idle
                throw e
            } catch (_: TooBigException) {
                OpenState.Failed(MSG_TOO_BIG)
            } catch (_: NoSpaceException) {
                OpenState.Failed(MSG_NO_SPACE)
            } catch (_: LocalWriteException) {
                OpenState.Failed(MSG_SAVE_FAILED)
            } catch (e: Exception) {
                val why = viewerErrorMessage(e, fromDrive = true, isVideo = true) // never the exception text
                OpenState.Failed(if (why == MSG_CANT_PLAY) MSG_COPY_FAILED else why)
            }
        }
    }

    Column(
        Modifier.padding(if (compact) 8.dp else 24.dp)
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(if (compact) 8.dp else 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = Color.White, textAlign = TextAlign.Center)
        when (val s = state) {
            is OpenState.Copying -> {
                Spacer(Modifier.height(8.dp))
                Text("Copying from the drive…", color = Color.White)
                Spacer(Modifier.height(8.dp))
                if (s.total > 0) {
                    LinearProgressIndicator(
                        progress = { (s.copied.toFloat() / s.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(0.6f),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth(0.6f))
                }
                TextButton(onClick = { job?.cancel() }) { Text("Cancel", color = Color.White) }
            }
            is OpenState.Failed -> {
                Spacer(Modifier.height(8.dp))
                Text(s.message, color = Color.White, textAlign = TextAlign.Center)
                TextButton(onClick = { open() }) { Text(LABEL_OPEN_ELSEWHERE, color = Color.White) }
            }
            OpenState.Idle -> TextButton(onClick = { open() }) { Text(LABEL_OPEN_ELSEWHERE, color = Color.White) }
        }
    }
}
