package dev.photobackup.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import dev.photobackup.smb.SmbUri
import dev.photobackup.ui.Screen

/** Thumbnails are always requested at this size, so the drive thumbnail cache key is stable across layouts. */
const val THUMB_PX = 384

@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    onOpen: (items: List<GalleryItem>, index: Int) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.start() }

    Screen(
        "Gallery",
        onBack = onBack,
        actions = { TextButton(onClick = { viewModel.refresh() }) { Text("Refresh") } },
    ) {
        Column(Modifier.fillMaxSize()) {
            when (state.drive) {
                DriveState.UNREACHABLE -> Banner("Drive not reachable — items only on the drive are greyed out")
                DriveState.CHECKING -> Banner("Checking the drive…")
                else -> {}
            }
            if (state.phoneDenied) Banner("Allow photo access to see what's on the phone")
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No photos or videos yet")
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.items, key = { it.key }) { item ->
                        val available = state.isAvailable(item)
                        Cell(
                            item = item,
                            root = state.root,
                            available = available,
                            driveReachable = state.drive == DriveState.REACHABLE,
                            onClick = {
                                val list = state.items
                                onOpen(list, list.indexOf(item))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Cell(item: GalleryItem, root: SmbRoot?, available: Boolean, driveReachable: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val model: Any? = item.phoneUri ?: run {
        val path = item.remotePath
        if (root == null || path == null) null else SmbUri.build(root.host, root.share, path)
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = available, onClick = onClick),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(model)
                .size(THUMB_PX)
                // Drive thumbnails: disk cache only unless the drive answered just now (no connects, no prompts).
                .networkCachePolicy(
                    if (item.phoneUri != null || driveReachable) CachePolicy.ENABLED else CachePolicy.DISABLED,
                )
                .build(),
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(if (available) 1f else 0.4f),
        )
        if (item.isVideo) Tag("▶", Modifier.align(Alignment.TopStart))
        Tag(
            when (item.location) {
                Location.PHONE -> "Phone"
                Location.DRIVE -> "Drive"
                Location.BOTH -> "Both"
            },
            Modifier.align(Alignment.BottomEnd),
        )
        if (!available) {
            Text(
                "Drive not reachable",
                color = Color.White,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(4.dp),
            )
        }
    }
}

@Composable
private fun Tag(text: String, modifier: Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 10.sp,
        modifier = modifier
            .padding(4.dp)
            .background(Color(0x99000000), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}
