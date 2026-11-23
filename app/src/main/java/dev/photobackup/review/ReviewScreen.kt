package dev.photobackup.review

import android.app.Activity
import android.content.IntentSender
import android.view.LayoutInflater
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import dev.photobackup.R
import dev.photobackup.ui.Screen
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/** A swipe counts once the card has moved this share of the card's width (sideways) or height (up/down). */
private const val THRESHOLD = 0.25f

private fun actionColor(a: ReviewAction): Color = when (a) {
    ReviewAction.KEEP -> Color(0xFF2E7D32)
    ReviewAction.DRIVE_ONLY -> Color(0xFF1565C0)
    ReviewAction.JUNK -> Color(0xFFC62828)
    ReviewAction.SKIP -> Color(0xFF616161)
}

/** Material "cloud" icon path (Apache 2.0); material-icons-extended is not a dependency. */
private val CloudIcon: ImageVector by lazy {
    ImageVector.Builder("Cloud", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(
            "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 " +
                "2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material "skip_next" icon path (Apache 2.0). */
private val SkipNextIcon: ImageVector by lazy {
    ImageVector.Builder("SkipNext", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z"),
        fill = SolidColor(Color.Black),
    ).build()
}

private fun actionIcon(a: ReviewAction): ImageVector = when (a) {
    ReviewAction.KEEP -> Icons.Filled.Check
    ReviewAction.DRIVE_ONLY -> CloudIcon
    ReviewAction.JUNK -> Icons.Filled.Delete
    ReviewAction.SKIP -> SkipNextIcon
}

/** The direction a drag is heading in (the dominant axis), and how far towards its threshold (0..1+). */
private fun heading(o: Offset, w: Float, h: Float): Pair<Swipe, Float>? {
    if (o == Offset.Zero || w <= 0f || h <= 0f) return null
    return if (abs(o.x) >= abs(o.y)) {
        (if (o.x > 0) Swipe.RIGHT else Swipe.LEFT) to abs(o.x) / (w * THRESHOLD)
    } else {
        (if (o.y > 0) Swipe.DOWN else Swipe.UP) to abs(o.y) / (h * THRESHOLD)
    }
}

/**
 * Swipe review: pick a folder (or All), then swipe each photo already safe on the drive. Nothing changes until
 * Finish, which re-checks the drive copies and asks Android once to delete the Drive only and Junk phone copies.
 */
@Composable
fun ReviewScreen(viewModel: ReviewViewModel, gestures: Map<Swipe, ReviewAction?>, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var started by rememberSaveable { mutableStateOf(false) }
    var askFinish by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refresh() }
    // The deck ran out with decisions waiting: open the Finish summary (offered once per session by the VM).
    LaunchedEffect(state.offerFinish, started) {
        if (state.offerFinish && started) {
            viewModel.finishOfferShown()
            if (state.finish == FinishState.Idle) askFinish = true
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) viewModel.onDeleteConfirmed() else viewModel.onDeleteCancelled()
    }
    // Saved so a rotation while the system dialog is open does not launch it a second time.
    var launched by rememberSaveable { mutableStateOf(false) }
    val finish = state.finish
    LaunchedEffect(finish) {
        if (finish !is FinishState.NeedsConfirm) {
            launched = false
            return@LaunchedEffect
        }
        if (launched) return@LaunchedEffect
        val sender = finish.request as? IntentSender
        if (sender == null) {
            viewModel.onDeleteCancelled()
            return@LaunchedEffect
        }
        launched = true
        try {
            launcher.launch(IntentSenderRequest.Builder(sender).build())
        } catch (_: Exception) {
            launched = false
            viewModel.onDeleteCancelled()
        }
    }

    Screen(
        "Swipe review",
        onBack = if (started && finish == FinishState.Idle) ({ started = false }) else onBack,
    ) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            !started -> FolderStart(state) { filter ->
                viewModel.setFolderFilter(filter)
                started = true
            }
            else -> Column(Modifier.fillMaxSize()) {
                Text(
                    "${state.folderFilter ?: "All folders"} · ${state.remaining} left",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                state.error?.let { ErrorLine(it) { viewModel.refresh() } }
                Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                    val current = state.current
                    if (current == null) {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("All done for now", style = MaterialTheme.typography.titleMedium)
                            if (state.summary.total > 0) Text("Tap Finish to apply your choices")
                        }
                    } else {
                        CardStack(
                            current = current,
                            next = state.next,
                            gestures = gestures,
                            enabled = finish == FinishState.Idle,
                            onSwipe = viewModel::swipe,
                        )
                    }
                }
                BottomBar(
                    state = state,
                    onUndo = viewModel::undo,
                    onFinish = { askFinish = true },
                )
            }
        }
    }

    if (askFinish) {
        AlertDialog(
            onDismissRequest = { askFinish = false },
            title = { Text("Finish review?") },
            text = {
                Text(
                    finishSummaryText(viewModel.finishSummary()) +
                        "\n\nThe drive copies are checked first, then Android asks once before deleting the phone copies."
                )
            },
            confirmButton = {
                TextButton(onClick = { askFinish = false; viewModel.startFinish() }) { Text("Check and finish") }
            },
            dismissButton = { TextButton(onClick = { askFinish = false }) { Text("Not yet") } },
        )
    }
    when (finish) {
        is FinishState.Checking -> Progress(checkingText(finish))
        FinishState.Applying -> Progress("Applying your choices…")
        is FinishState.Done -> ResultDialog("Review finished", doneLines(finish), state.leftAlone, viewModel::dismissFinish)
        is FinishState.Error -> ResultDialog("Couldn't finish", listOf(finish.message), state.leftAlone, viewModel::dismissFinish)
        else -> Unit
    }
}

@Composable
private fun FolderStart(state: ReviewUiState, onPick: (String?) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Which photos?", style = MaterialTheme.typography.titleMedium)
            Text(
                "Only photos already safe on the drive are shown, oldest first. Nothing changes until you tap Finish.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item { Button(onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth()) { Text("All folders") } }
        items(state.folderOptions) { f ->
            OutlinedButton(onClick = { onPick(f) }, modifier = Modifier.fillMaxWidth()) { Text(f) }
        }
    }
}

@Composable
private fun ErrorLine(message: String, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun CardStack(
    current: ReviewCard,
    next: ReviewCard?,
    gestures: Map<Swipe, ReviewAction?>,
    enabled: Boolean,
    onSwipe: (Swipe) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        next?.let { n ->
            key(n.entry.mediaId) {
                PhotoCard(n, Modifier.fillMaxSize().graphicsLayer { scaleX = 0.95f; scaleY = 0.95f; alpha = 0.6f })
            }
        }
        key(current.entry.mediaId) {
            SwipeCard(current, w, h, gestures, enabled, onSwipe)
        }
    }
}

@Composable
private fun SwipeCard(
    card: ReviewCard,
    w: Float,
    h: Float,
    gestures: Map<Swipe, ReviewAction?>,
    enabled: Boolean,
    onSwipe: (Swipe) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentGestures by rememberUpdatedState(gestures)
    var offset by remember { mutableStateOf(Offset.Zero) }
    var flying by remember { mutableStateOf(false) }
    /** Past the threshold towards a direction with an action: one haptic tick each time this flips. */
    var armed by remember { mutableStateOf(false) }
    /** Video cards: tap plays muted, tap again pauses. [videoStarted] keeps the player once made, for resume. */
    var playing by remember { mutableStateOf(false) }
    var videoStarted by remember { mutableStateOf(false) }

    // Finish started (or the deck is otherwise disabled): stop the video and release its player.
    LaunchedEffect(enabled) {
        if (!enabled) {
            playing = false
            videoStarted = false
        }
    }

    fun updateArmed() {
        val hd = heading(offset, w, h)
        val now = hd != null && hd.second >= 1f && currentGestures[hd.first] != null
        if (now != armed) {
            armed = now
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    /** Flies the card off in [dir], then applies it; the next card (or this one again, after Skip) starts centred. */
    fun fling(dir: Swipe) {
        if (flying) return
        flying = true
        armed = false
        playing = false
        scope.launch {
            val target = when (dir) {
                Swipe.RIGHT -> Offset(w * 1.5f, offset.y)
                Swipe.LEFT -> Offset(-w * 1.5f, offset.y)
                Swipe.DOWN -> Offset(offset.x, h * 1.5f)
                Swipe.UP -> Offset(offset.x, -h * 1.5f)
            }
            animate(Offset.VectorConverter, offset, target, animationSpec = tween(180)) { v, _ -> offset = v }
            onSwipe(dir)
            offset = Offset.Zero
            videoStarted = false // Skip brings this card back: its player is released and starts over on tap
            flying = false
        }
    }

    fun settle() {
        armed = false
        scope.launch { animate(Offset.VectorConverter, offset, Offset.Zero, animationSpec = tween(200)) { v, _ -> offset = v } }
    }

    val head = heading(offset, w, h)
    val headAction = head?.let { gestures[it.first] }
    Box(Modifier.fillMaxSize()) {
        PhotoCard(
            card,
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offset.x
                    translationY = offset.y
                    rotationZ = if (w > 0f) offset.x / w * 15f else 0f
                }
                .pointerInput(enabled, card.phone.isVideo) {
                    if (!enabled || !card.phone.isVideo) return@pointerInput
                    // A drag consumes the pointer, so it never counts as a tap.
                    detectTapGestures(onTap = {
                        if (!flying) {
                            videoStarted = true
                            playing = !playing
                        }
                    })
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragEnd = {
                            val hd = heading(offset, w, h)
                            if (hd != null && hd.second >= 1f && currentGestures[hd.first] != null) fling(hd.first) else settle()
                        },
                        onDragCancel = { settle() },
                        onDrag = { change, amount ->
                            if (!flying) {
                                change.consume()
                                offset += amount
                                updateArmed()
                            }
                        },
                    )
                },
            tint = headAction?.let { actionColor(it).copy(alpha = 0.4f * min(1f, head.second)) },
            // Fades in from the start of the drag; full strength at the threshold.
            stamp = headAction,
            stampAlpha = head?.second?.coerceIn(0f, 1f) ?: 0f,
            video = if (enabled && card.phone.isVideo && videoStarted) VideoState(playing) { playing = false } else null,
        )
        // Direction hints: what each swipe does (tap one to apply it). Hidden for No action. The one being
        // approached grows and brightens with the drag.
        fun near(dir: Swipe) = if (head != null && head.first == dir) head.second.coerceIn(0f, 1f) else 0f
        Hint(gestures[Swipe.UP], enabled, near(Swipe.UP), Modifier.align(Alignment.TopCenter)) { fling(Swipe.UP) }
        Hint(gestures[Swipe.DOWN], enabled, near(Swipe.DOWN), Modifier.align(Alignment.BottomCenter)) { fling(Swipe.DOWN) }
        Hint(gestures[Swipe.LEFT], enabled, near(Swipe.LEFT), Modifier.align(Alignment.CenterStart)) { fling(Swipe.LEFT) }
        Hint(gestures[Swipe.RIGHT], enabled, near(Swipe.RIGHT), Modifier.align(Alignment.CenterEnd)) { fling(Swipe.RIGHT) }
    }
}

/** In-card video: [playing] or paused; [onStopped] pauses it (playback error, app in the background). */
private class VideoState(val playing: Boolean, val onStopped: () -> Unit)

@Composable
private fun Hint(action: ReviewAction?, enabled: Boolean, near: Float, modifier: Modifier, onClick: () -> Unit) {
    action ?: return
    Row(
        modifier = modifier
            .padding(8.dp)
            .graphicsLayer {
                val s = 1f + 0.25f * near
                scaleX = s
                scaleY = s
            }
            .clip(RoundedCornerShape(12.dp))
            .background(actionColor(action).copy(alpha = 0.7f + 0.3f * near))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(actionIcon(action), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(action.label, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun PhotoCard(
    card: ReviewCard,
    modifier: Modifier,
    tint: Color? = null,
    stamp: ReviewAction? = null,
    stampAlpha: Float = 1f,
    video: VideoState? = null,
) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
        // Phone copy (still on the phone: the deck only holds phone-present items). Videos show their first frame.
        AsyncImage(
            model = card.phone.uri,
            contentDescription = card.phone.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (video != null) CardVideo(card, video)
        if (card.phone.isVideo && video?.playing != true) {
            Icon(
                Icons.Filled.PlayArrow, "Video", tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(64.dp).background(Color(0x66000000), CircleShape).padding(8.dp),
            )
        }
        if (tint != null) Box(Modifier.fillMaxSize().background(tint))
        if (stamp != null && stampAlpha > 0f) {
            Column(
                Modifier.align(Alignment.Center).graphicsLayer { alpha = stampAlpha },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(actionIcon(stamp), contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
                Text(
                    stamp.label,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            card.phone.displayName,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).background(Color(0x66000000)).padding(4.dp),
        )
    }
}

@Composable
private fun BottomBar(state: ReviewUiState, onUndo: () -> Unit, onFinish: () -> Unit) {
    val idle = state.finish == FinishState.Idle
    HorizontalDivider()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onUndo, enabled = idle && state.canUndo) { Text("Undo") }
        val s = state.summary
        Text(
            "Keep ${s.keep} · Drive only ${s.driveOnly} · Junk ${s.junk}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onFinish, enabled = idle && s.total > 0) { Text("Finish") }
    }
}

@Composable
private fun Progress(text: String) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(text)
            }
        },
    )
}

@Composable
private fun ResultDialog(title: String, lines: List<String>, leftAlone: List<LeftAlone>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lines.forEach { Text(it) }
                if (leftAlone.isNotEmpty()) {
                    Text("Left alone", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    leftAlone.forEach { Text("${it.name}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/**
 * Muted playback of the phone copy inside the current card. One player, made when the card is first tapped and
 * released when the card leaves (swiped away, or Skip) or the screen goes; paused when the app goes to the background.
 * Uses the TextureView player layout so it turns with the card and never shows black.
 */
@Composable
private fun CardVideo(card: ReviewCard, video: VideoState) {
    val context = LocalContext.current
    val currentVideo by rememberUpdatedState(video)
    // Made in an effect, not during composition, so an abandoned composition can't leak a player.
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    DisposableEffect(card.phone.uri) {
        val p = ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(card.phone.uri))
            prepare()
        }
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = currentVideo.onStopped() // never shown or logged
        }
        p.addListener(listener)
        player = p
        onDispose {
            player = null
            p.removeListener(listener)
            p.release()
        }
    }
    val p = player ?: return
    LaunchedEffect(p, video.playing) {
        // After an error the player is idle: prepare again so the next tap retries.
        if (video.playing && p.playbackState == Player.STATE_IDLE) p.prepare()
        p.playWhenReady = video.playing
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(p, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) currentVideo.onStopped()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    AndroidView(
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.video_player_view, null) as PlayerView).apply {
                useController = false
                this.player = p
            }
        },
        update = { it.player = p },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}
