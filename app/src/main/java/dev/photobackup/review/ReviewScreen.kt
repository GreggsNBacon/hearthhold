package dev.photobackup.review

import android.app.Activity
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
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
    var askFinish by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refresh() }

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
        FinishState.Checking -> Progress("Checking the drive copies…")
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
    val currentGestures by rememberUpdatedState(gestures)
    var offset by remember { mutableStateOf(Offset.Zero) }
    var flying by remember { mutableStateOf(false) }

    /** Flies the card off in [dir], then applies it; the next card (or this one again, after Skip) starts centred. */
    fun fling(dir: Swipe) {
        if (flying) return
        flying = true
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
            flying = false
        }
    }

    fun settle() {
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
                            }
                        },
                    )
                },
            tint = headAction?.let { actionColor(it).copy(alpha = 0.4f * min(1f, head.second)) },
            stamp = headAction?.takeIf { head.second >= 1f }?.label,
        )
        // Direction hints: what each swipe does (tap one to apply it). Hidden for No action.
        Hint(gestures[Swipe.UP], enabled, Modifier.align(Alignment.TopCenter)) { fling(Swipe.UP) }
        Hint(gestures[Swipe.DOWN], enabled, Modifier.align(Alignment.BottomCenter)) { fling(Swipe.DOWN) }
        Hint(gestures[Swipe.LEFT], enabled, Modifier.align(Alignment.CenterStart)) { fling(Swipe.LEFT) }
        Hint(gestures[Swipe.RIGHT], enabled, Modifier.align(Alignment.CenterEnd)) { fling(Swipe.RIGHT) }
    }
}

@Composable
private fun Hint(action: ReviewAction?, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    action ?: return
    Text(
        action.label,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(actionColor(action).copy(alpha = 0.85f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun PhotoCard(card: ReviewCard, modifier: Modifier, tint: Color? = null, stamp: String? = null) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
        // Phone copy (still on the phone: the deck only holds phone-present items). Videos show their first frame.
        AsyncImage(
            model = card.phone.uri,
            contentDescription = card.phone.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (card.phone.isVideo) {
            Icon(
                Icons.Filled.PlayArrow, "Video", tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(64.dp).background(Color(0x66000000), CircleShape).padding(8.dp),
            )
        }
        if (tint != null) Box(Modifier.fillMaxSize().background(tint))
        if (stamp != null) {
            Text(
                stamp,
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
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
