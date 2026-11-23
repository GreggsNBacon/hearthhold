package dev.photobackup.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import dev.photobackup.smb.SmbUri
import dev.photobackup.ui.home.HomeViewModel
import dev.photobackup.ui.home.Reachability
import dev.photobackup.ui.home.StatusChip
import dev.photobackup.ui.home.StatusSheetContent
import dev.photobackup.ui.home.statusChip
import java.time.LocalTime

/** Thumbnails are always requested at this size, so the drive thumbnail cache key is stable across layouts. */
const val THUMB_PX = 384

/**
 * The main screen: the combined Gallery with backup badges, a status chip (tap → status sheet with the old Home
 * content) and an overflow menu (Browse drive, Settings). It is the start destination, so Back exits the app.
 * The drive check is the Gallery's own (re-run on resume); the status sheet reuses its result (no second login).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    status: HomeViewModel,
    onOpen: (items: List<GalleryItem>, index: Int) -> Unit,
    onFreeUp: () -> Unit,
    onBrowse: () -> Unit,
    onSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val home by status.state.collectAsState()
    // First show loads everything; later resumes (back from Settings, app switch) re-check the drive.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.resume()
        status.refreshFreeUp()
    }

    val reach = when (state.drive) {
        DriveState.CHECKING -> Reachability.CHECKING
        DriveState.REACHABLE -> Reachability.YES
        DriveState.UNREACHABLE -> Reachability.NO
        DriveState.NOT_CONFIGURED, DriveState.NEEDS_PASSWORD -> Reachability.UNKNOWN
    }
    val chip = statusChip(
        status = home.status,
        reach = reach,
        waiting = home.waiting,
        failed = home.failed,
        needsPassword = home.needsPassword,
        window = home.settings?.overnightWindow(),
        now = LocalTime.now(),
    )
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var errorItem by remember { mutableStateOf<GalleryItem?>(null) }
    val refreshAll = {
        viewModel.refresh()
        status.refreshFreeUp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hearthhold") },
                actions = {
                    StatusChip(chip, onClick = { showSheet = true })
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Refresh") },
                                onClick = { menuOpen = false; refreshAll() },
                            )
                            DropdownMenuItem(
                                text = { Text("Browse drive") },
                                onClick = { menuOpen = false; onBrowse() },
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = { menuOpen = false; onSettings() },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (state.drive) {
                DriveState.UNREACHABLE -> Banner("Drive not reachable — items only on the drive are greyed out")
                DriveState.CHECKING -> Banner("Checking the drive…")
                DriveState.NEEDS_PASSWORD ->
                    Banner("The saved drive password can't be read — tap to re-enter it") { showSheet = true }
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
                            onShowError = { errorItem = item },
                        )
                    }
                }
            }
        }
    }

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = sheetState) {
            StatusSheetContent(
                state = home,
                reach = reach,
                reachMessage = state.driveMessage,
                onBackUpNow = { status.backUpNow() },
                onFreeUp = {
                    showSheet = false
                    onFreeUp()
                },
                onReenter = { status.reenter(onDone = refreshAll) },
            )
        }
    }

    errorItem?.let { item ->
        AlertDialog(
            onDismissRequest = { errorItem = null },
            confirmButton = { TextButton(onClick = { errorItem = null }) { Text("OK") } },
            title = { Text("Backup failed") },
            text = { Text("${item.displayName}\n\n${item.lastError ?: "No reason was recorded."}") },
        )
    }
}

@Composable
private fun Banner(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Cell(
    item: GalleryItem,
    root: SmbRoot?,
    available: Boolean,
    driveReachable: Boolean,
    onClick: () -> Unit,
    onShowError: () -> Unit,
) {
    val context = LocalContext.current
    val badge = badgeFor(item)
    val model: Any? = item.phoneUri ?: run {
        val path = item.remotePath
        if (root == null || path == null) null else SmbUri.build(root.host, root.share, path)
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                enabled = available,
                onClick = onClick,
                onLongClick = if (badge == Badge.FAILED) onShowError else null,
            ),
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
        BackupBadge(
            badge,
            onClick = if (badge == Badge.FAILED) onShowError else null,
            modifier = Modifier.align(Alignment.BottomEnd),
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

/** Small solid circle with a white rim, so it reads on light and dark photos. Nothing for [Badge.NONE]. */
@Composable
private fun BackupBadge(badge: Badge, onClick: (() -> Unit)?, modifier: Modifier) {
    val (fill, label) = when (badge) {
        Badge.NONE -> return
        Badge.BACKED_UP -> Color(0xFF2E7D32) to "Backed up"
        Badge.DRIVE_ONLY -> Color(0xFF1565C0) to "On drive only"
        Badge.FAILED -> Color(0xFFC62828) to "Backup failed — show why"
    }
    // The outer box is the touch target (bigger than the dot) when the badge is tappable.
    Box(
        modifier
            .size(30.dp)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Show why", onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .background(fill, CircleShape)
                .border(1.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when (badge) {
                Badge.BACKED_UP -> Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Badge.DRIVE_ONLY -> Icon(CloudDone, null, tint = Color.White, modifier = Modifier.size(13.dp))
                else -> Text(
                    "!",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}

/** Material "cloud_done" icon path (Apache 2.0); material-icons-extended is not a dependency. */
private val CloudDone: ImageVector by lazy {
    ImageVector.Builder("CloudDone", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(
            "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 " +
                "2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96zM10,17l-3.5,-3.5 " +
                "1.41,-1.41L10,14.17 15.18,9l1.41,1.41L10,17z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
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
