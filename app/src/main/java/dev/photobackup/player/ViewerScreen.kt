package dev.photobackup.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.photobackup.gallery.GalleryItem
import dev.photobackup.gallery.SmbRoot
import dev.photobackup.smb.RemoteStore
import java.io.Closeable

/**
 * Full-screen viewer over the list it was opened from (Gallery or Browse), starting at [startIndex]; swipe for the
 * next or previous item. Photos zoom (pinch, double tap; no swiping while zoomed). Videos play on the settled page
 * only, with the loop toggle (saved via [onLoopChange]) and the speed chip ([session], this launch only).
 *
 * @param root host/share of the saved drive, for items only on the drive (null: no drive set up).
 * @param openStore a new drive store for one video page.
 * @param closeStore closes such a store off the main thread (a drive logoff can block).
 */
@Composable
fun ViewerScreen(
    items: List<GalleryItem>,
    startIndex: Int,
    root: SmbRoot?,
    loop: Boolean,
    onLoopChange: (Boolean) -> Unit,
    session: PlayerSession,
    openStore: () -> RemoteStore,
    closeStore: (Closeable) -> Unit,
    onBack: () -> Unit,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(session.speed) }
    val setSpeed: (Float) -> Unit = {
        speed = it
        session.speed = it
    }
    LaunchedEffect(pager.settledPage) { zoomed = false }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            key = { items[it].key },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items[page]
            val active = page == pager.settledPage
            if (item.isVideo) {
                VideoPage(item, root, active, loop, speed, setSpeed, openStore, closeStore)
            } else {
                PhotoPage(item, root, active) { if (active) zoomed = it }
            }
        }

        val current = items[pager.currentPage]
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .safeDrawingPadding()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack) { Text("Back", color = Color.White) }
            Text(
                current.displayName,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (current.isVideo) {
                LoopChip(loop, onLoopChange)
                SpeedChip(speed, setSpeed)
            }
        }
    }
}

/** A message in the middle of a page, with an optional Retry. */
@Composable
internal fun PageMessage(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier.padding(24.dp).background(Color.Black.copy(alpha = 0.6f)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = Color.White, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text("Retry", color = Color.White) }
        }
    }
}
