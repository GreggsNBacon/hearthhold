package dev.photobackup.player

import android.net.Uri
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale
import dev.photobackup.gallery.GalleryItem
import dev.photobackup.gallery.SmbCoilFetcher
import dev.photobackup.gallery.SmbRoot
import dev.photobackup.smb.SmbUri
import kotlin.math.max
import kotlin.math.min

/** Pinch / pan / double-tap zoom for one photo. Offsets are from the centre, in px. */
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    private var size = IntSize.Zero

    val zoomed: Boolean get() = scale > 1.01f

    fun setSize(s: IntSize) {
        size = s
    }

    /** Applies one gesture step: zoom about [centroid] (a pointer position), then [pan]. */
    fun transform(zoom: Float, pan: Offset, centroid: Offset) {
        val c = centroid - Offset(size.width / 2f, size.height / 2f)
        val newScale = (scale * zoom).coerceIn(MIN, MAX)
        val o = c - (c - offset) * (newScale / scale) + pan
        scale = newScale
        offset = clamp(o, newScale)
    }

    /** Double tap: zoom in on [at], or back out when zoomed. */
    fun toggle(at: Offset) {
        if (zoomed) {
            reset()
        } else {
            val c = at - Offset(size.width / 2f, size.height / 2f)
            scale = DOUBLE_TAP
            offset = clamp(c * (1f - DOUBLE_TAP), DOUBLE_TAP)
        }
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    private fun clamp(o: Offset, s: Float): Offset {
        val mx = size.width * (s - 1f) / 2f
        val my = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    private companion object {
        const val MIN = 1f
        const val MAX = 5f
        const val DOUBLE_TAP = 2.5f
    }
}

/**
 * One full-screen photo: the phone copy when there is one, else the drive copy through `SmbCoilFetcher` (via the
 * shared thumbnail store and its saved login), decoded near screen size rather than as a thumbnail.
 * Reports [onZoomChange] so the pager stops swiping while zoomed.
 */
@Composable
fun PhotoPage(item: GalleryItem, root: SmbRoot?, active: Boolean, onZoomChange: (Boolean) -> Unit) {
    val zoom = remember { ZoomState() }
    LaunchedEffect(active) { if (!active) zoom.reset() }
    LaunchedEffect(zoom.zoomed, active) { if (active) onZoomChange(zoom.zoomed) }

    val context = LocalContext.current
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val shortPx = with(density) { min(config.screenWidthDp, config.screenHeightDp) * this.density }.toInt()
    val longPx = with(density) { max(config.screenWidthDp, config.screenHeightDp) * this.density }.toInt()

    val phone = item.phoneUri
    val remote = item.remotePath
    val missing = unavailableMessage(phone != null, remote != null, root != null, isVideo = false)
    var attempt by remember(item.key) { mutableIntStateOf(0) }
    val request = remember(item.key, root, shortPx, longPx, attempt) {
        when {
            missing != null -> null
            // Coil's own decoder: fits the photo inside a long-side square, so either orientation stays sharp.
            phone != null -> ImageRequest.Builder(context).data(Uri.parse(phone)).size(longPx, longPx).scale(Scale.FIT)
            // SmbCoilFetcher, full-size path: its own store and lock, apart from thumbnails. It keeps both sides at
            // least this big (inSampleSize), so ask for the short side. Not disk-cached, so viewing many photos
            // never evicts grid thumbnails (the memory cache still covers swiping back).
            remote != null && root != null ->
                ImageRequest.Builder(context).data(SmbUri.build(root.host, root.share, remote)).size(shortPx, shortPx)
                    .setParameter(SmbCoilFetcher.FULL_SIZE, true, memoryCacheKey = "full")
                    .diskCachePolicy(CachePolicy.DISABLED)
            else -> null
        }?.setParameter("attempt", attempt, memoryCacheKey = null)?.build() // a new request on Retry
    }
    var state by remember(request) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { zoom.setSize(it) }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoom.toggle(it) }) }
            .pointerInput(Unit) {
                // Two fingers always zoom; one finger pans only while zoomed, otherwise the pager gets the swipe.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed > 1 || zoom.zoomed) {
                            zoom.transform(event.calculateZoom(), event.calculatePan(), event.calculateCentroid())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (request == null) {
            PageMessage(missing ?: MSG_CANT_SHOW)
            return@Box
        }
        AsyncImage(
            model = request,
            contentDescription = item.displayName,
            contentScale = ContentScale.Fit,
            onState = { state = it },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offset.x
                    translationY = zoom.offset.y
                },
        )
        when (val s = state) {
            is AsyncImagePainter.State.Loading -> CircularProgressIndicator()
            is AsyncImagePainter.State.Error ->
                PageMessage(viewerErrorMessage(s.result.throwable, fromDrive = phone == null, isVideo = false)) { attempt++ }
            else -> Unit
        }
    }
}
