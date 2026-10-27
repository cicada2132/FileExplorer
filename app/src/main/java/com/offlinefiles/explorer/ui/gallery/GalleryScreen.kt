package com.offlinefiles.explorer.ui.gallery

import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinefiles.explorer.R
import com.offlinefiles.explorer.core.gallery.Album
import com.offlinefiles.explorer.core.gallery.AlbumType
import com.offlinefiles.explorer.core.gallery.DaySection
import com.offlinefiles.explorer.core.gallery.GalleryIndex
import com.offlinefiles.explorer.core.gallery.MediaDates
import com.offlinefiles.explorer.core.gallery.MediaItem
import com.offlinefiles.explorer.data.StorageVolumes
import com.offlinefiles.explorer.data.VolumeInfo
import com.offlinefiles.explorer.data.VolumeKind
import com.offlinefiles.explorer.ui.DialogState
import com.offlinefiles.explorer.ui.MainViewModel
import com.offlinefiles.explorer.ui.browser.BarAction
import com.offlinefiles.explorer.ui.browser.EmptyState
import com.offlinefiles.explorer.ui.browser.FastScroller
import com.offlinefiles.explorer.ui.browser.MenuItem
import com.offlinefiles.explorer.ui.browser.SelectionTopBar
import com.offlinefiles.explorer.ui.components.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId

/** Smallest cell width per zoom level (Settings.galleryZoom: 0 small, 1 medium, 2 large). */
private val zoomCell = listOf(72.dp, 96.dp, 132.dp)

/** Photos tab (everything, by day) and Albums tab (one album per folder). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val g = vm.gallery
    val ui by g.ui.collectAsStateWithLifecycle()
    val tab by g.tab.collectAsStateWithLifecycle()
    val selection by g.selection.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val items = ui.index.items
    val selecting = selection.isNotEmpty()

    LaunchedEffect(Unit) { g.ensureLoaded() }
    BackHandler {
        if (selecting) g.clearSelection() else vm.back()
    }

    Scaffold(
        topBar = {
            Column {
                if (selecting) {
                    val all = selection.size >= items.size
                    SelectionTopBar(
                        count = selection.size,
                        allSelected = all,
                        onClose = g::clearSelection,
                        onSelectAll = { if (all) g.clearSelection() else g.setSelected(items.map { it.path }, true) },
                    )
                } else {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { vm.back() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_up))
                            }
                        },
                        title = { Text(stringResource(R.string.gallery_title)) },
                        actions = {
                            GalleryMenu(
                                zoom = settings.galleryZoom,
                                showZoom = tab == GalleryTab.PHOTOS,
                                onZoom = { z -> vm.updateSettings { it.copy(galleryZoom = z.coerceIn(0, 2)) } },
                                onRefresh = { g.reload() },
                            )
                        },
                    )
                }
                PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                    Tab(
                        selected = tab == GalleryTab.PHOTOS,
                        onClick = { g.tab.value = GalleryTab.PHOTOS },
                        enabled = !selecting,
                        text = { Text(stringResource(R.string.gallery_tab_photos)) },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Tab(
                        selected = tab == GalleryTab.ALBUMS,
                        onClick = { g.tab.value = GalleryTab.ALBUMS },
                        enabled = !selecting,
                        text = { Text(stringResource(R.string.gallery_tab_albums)) },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (ui.loading && ui.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            if (selecting) GallerySelectionBar(vm, items.filter { it.path in selection })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when {
                !ui.loaded -> Loading()
                items.isEmpty() -> EmptyState(
                    Icons.Filled.PhotoLibrary,
                    stringResource(R.string.gallery_empty_title),
                    stringResource(R.string.gallery_empty_body),
                )
                tab == GalleryTab.PHOTOS -> MediaGrid(
                    vm = vm,
                    key = GalleryIndex.ALL,
                    items = items,
                    selection = selection,
                    zoom = settings.galleryZoom,
                    bottomPadding = padding.calculateBottomPadding(),
                )
                else -> AlbumGrid(ui.index.albums, volumes, g, padding.calculateBottomPadding()) { vm.openAlbum(it.key) }
            }
        }
    }
}

/** One album: a folder, or all videos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(vm: MainViewModel, key: String, snackbar: SnackbarHostState) {
    val g = vm.gallery
    val ui by g.ui.collectAsStateWithLifecycle()
    val selection by g.selection.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val album = ui.index.album(key)
    val items = ui.index.itemsOf(key)
    val selecting = selection.isNotEmpty()

    LaunchedEffect(Unit) { g.ensureLoaded() }
    BackHandler {
        if (selecting) g.clearSelection() else vm.back()
    }

    Scaffold(
        topBar = {
            Column {
                if (selecting) {
                    val all = selection.size >= items.size
                    SelectionTopBar(
                        count = selection.size,
                        allSelected = all,
                        onClose = g::clearSelection,
                        onSelectAll = { if (all) g.clearSelection() else g.setSelected(items.map { it.path }, true) },
                    )
                } else {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { vm.back() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_up))
                            }
                        },
                        title = {
                            Column {
                                val title = if (album != null) albumLabel(album, volumes) else key.substringAfterLast('/').takeUnless { key.startsWith("*") }.orEmpty()
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                                if (items.isNotEmpty()) {
                                    Text(
                                        countText(items.count { !it.isVideo }, items.count { it.isVideo }),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        actions = {
                            val folder = album?.path
                            if (folder != null) {
                                IconButton(onClick = { vm.openDir(folder) }) {
                                    Icon(Icons.Filled.FolderOpen, stringResource(R.string.action_open_folder))
                                }
                            }
                            GalleryMenu(
                                zoom = settings.galleryZoom,
                                showZoom = true,
                                onZoom = { z -> vm.updateSettings { it.copy(galleryZoom = z.coerceIn(0, 2)) } },
                                onRefresh = { g.reload() },
                            )
                        },
                    )
                }
                if (ui.loading && ui.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            if (selecting) GallerySelectionBar(vm, items.filter { it.path in selection })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when {
                !ui.loaded -> Loading()
                items.isEmpty() -> EmptyState(Icons.Filled.PhotoLibrary, stringResource(R.string.gallery_album_empty), null)
                else -> MediaGrid(
                    vm = vm,
                    key = key,
                    items = items,
                    selection = selection,
                    zoom = settings.galleryZoom,
                    bottomPadding = padding.calculateBottomPadding(),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ photo grid

/** A row of the photo grid: a day header spanning the full width, or one photo/video. */
sealed interface GridEntry {
    val key: String

    class Header(val section: DaySection) : GridEntry {
        override val key: String get() = "day:${section.epochDay}"
    }

    class Cell(val item: MediaItem) : GridEntry {
        override val key: String get() = item.path
    }
}

fun gridEntries(items: List<MediaItem>, zone: ZoneId = ZoneId.systemDefault()): List<GridEntry> {
    val sections = GalleryIndex.sections(items, zone.rules)
    val out = ArrayList<GridEntry>(items.size + sections.size)
    for (s in sections) {
        out.add(GridEntry.Header(s))
        for (m in s.items) out.add(GridEntry.Cell(m))
    }
    return out
}

@Composable
private fun MediaGrid(
    vm: MainViewModel,
    key: String,
    items: List<MediaItem>,
    selection: Set<String>,
    zoom: Int,
    bottomPadding: Dp,
) {
    val g = vm.gallery
    val selecting = selection.isNotEmpty()
    val entries by produceState<List<GridEntry>?>(null, items) {
        value = withContext(Dispatchers.Default) { gridEntries(items) }
    }
    val state = remember(key) {
        val m = g.scrollMemory[key]
        LazyGridState(m?.first ?: 0, m?.second ?: 0)
    }
    DisposableEffect(key) {
        onDispose { g.scrollMemory[key] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
    }
    // After the viewer closes, make sure the last photo shown is on screen.
    val reveal by g.reveal.collectAsStateWithLifecycle()
    val list = entries
    LaunchedEffect(reveal, list) {
        val path = reveal ?: return@LaunchedEffect
        val l = list ?: return@LaunchedEffect
        val index = l.indexOfFirst { it is GridEntry.Cell && it.item.path == path }
        if (index >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == index }) state.scrollToItem(index)
        g.reveal.value = null
    }
    if (list == null) {
        Loading()
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = (maxWidth / zoomCell[zoom.coerceIn(0, 2)]).toInt().coerceAtLeast(2)
        val px = Thumbnails.mediaSize(with(LocalDensity.current) { (maxWidth / columns).roundToPx() })
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            contentPadding = PaddingValues(bottom = bottomPadding + 16.dp),
            modifier = Modifier
                .fillMaxSize()
                .pinchToZoom(zoom) { z -> vm.updateSettings { it.copy(galleryZoom = z.coerceIn(0, 2)) } },
        ) {
            items(
                list,
                key = { it.key },
                span = { GridItemSpan(if (it is GridEntry.Header) maxLineSpan else 1) },
                contentType = { if (it is GridEntry.Header) 0 else 1 },
            ) { e ->
                when (e) {
                    is GridEntry.Header -> {
                        val dayPaths = e.section.items
                        val allSelected = selecting && dayPaths.all { it.path in selection }
                        DayHeader(e.section, selecting, allSelected) {
                            g.setSelected(dayPaths.map { it.path }, !allSelected)
                        }
                    }
                    is GridEntry.Cell -> MediaCell(
                        item = e.item,
                        px = px,
                        selecting = selecting,
                        selected = e.item.path in selection,
                        onClick = { if (selecting) g.toggle(e.item.path) else g.openViewer(key, e.item.path) },
                        onLongClick = { g.toggle(e.item.path) },
                    )
                }
            }
        }
        FastScroller(state, list.size, Modifier.align(Alignment.CenterEnd).padding(bottom = bottomPadding))
    }
}

/** Two fingers apart: bigger thumbnails; together: smaller. One step per gesture. */
private fun Modifier.pinchToZoom(zoom: Int, onZoom: (Int) -> Unit): Modifier = pointerInput(zoom) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var total = 1f
        var done = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                total *= event.calculateZoom()
                event.changes.forEach { if (it.positionChanged()) it.consume() }
                if (!done && (total > 1.25f || total < 0.8f)) {
                    done = true
                    val next = if (total > 1f) zoom + 1 else zoom - 1
                    if (next in 0..2) onZoom(next)
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
private fun DayHeader(section: DaySection, selecting: Boolean, allSelected: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val label = remember(section.epochDay) { dayLabel(context, section.epochDay, section.items.first().date) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (selecting) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (allSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    stringResource(R.string.gallery_select_day),
                    tint = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** "Today", "Yesterday", "Mon, Oct 26" (this year) or "Fri, Dec 19, 2025". */
internal fun dayLabel(context: Context, epochDay: Long, millis: Long): String {
    val today = GalleryIndex.epochDay(System.currentTimeMillis(), ZoneId.systemDefault().rules)
    return when (epochDay) {
        today -> context.getString(R.string.gallery_today)
        today - 1 -> context.getString(R.string.gallery_yesterday)
        else -> {
            val sameYear = LocalDate.ofEpochDay(epochDay).year == LocalDate.ofEpochDay(today).year
            val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or
                (if (sameYear) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR)
            DateUtils.formatDateTime(context, millis, flags)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCell(
    item: MediaItem,
    px: Int,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val thumb = rememberMediaThumb(item, px)
    val inset by animateDpAsState(if (selected) 10.dp else 0.dp, label = "inset")
    val corner by animateDpAsState(if (selected) 14.dp else 2.dp, label = "corner")
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(1.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .padding(inset)
                .clip(RoundedCornerShape(corner))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            if (thumb != null) {
                Image(thumb, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            } else {
                Icon(
                    if (item.isVideo) Icons.Filled.Movie else Icons.Filled.Image,
                    contentDescription = item.name,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.Center).size(28.dp),
                )
            }
            if (item.isVideo) VideoBadge(item.durationMs, Modifier.align(Alignment.BottomEnd))
        }
        if (selecting) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.surface else Color.Black.copy(alpha = 0.25f)),
            )
        }
    }
}

@Composable
private fun VideoBadge(durationMs: Long, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        if (durationMs > 0) {
            Spacer(Modifier.width(2.dp))
            Text(MediaDates.duration(durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Cached thumbnail right away, otherwise loaded in the background. */
@Composable
fun rememberMediaThumb(item: MediaItem, px: Int): ImageBitmap? {
    val context = LocalContext.current
    val state by produceState(Thumbnails.peekMedia(item, px)?.asImageBitmap(), item.path, item.dateModified, px) {
        if (value == null) value = Thumbnails.loadMedia(context, item, px)?.asImageBitmap()
    }
    return state
}

// ------------------------------------------------------------------ albums

@Composable
private fun AlbumGrid(
    albums: List<Album>,
    volumes: List<VolumeInfo>,
    g: GalleryModel,
    bottomPadding: Dp,
    onOpen: (Album) -> Unit,
) {
    val state = remember {
        val m = g.scrollMemory[ALBUMS_KEY]
        LazyGridState(m?.first ?: 0, m?.second ?: 0)
    }
    DisposableEffect(Unit) {
        onDispose { g.scrollMemory[ALBUMS_KEY] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        state = state,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = bottomPadding + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.key }) { album -> AlbumCard(album, volumes) { onOpen(album) } }
    }
}

private const val ALBUMS_KEY = "*albums"

@Composable
private fun AlbumCard(album: Album, volumes: List<VolumeInfo>, onClick: () -> Unit) {
    val px = Thumbnails.mediaSize(with(LocalDensity.current) { 180.dp.roundToPx() })
    val thumb = rememberMediaThumb(album.cover, px)
    val volume = album.path?.let { StorageVolumes.volumeFor(it, volumes) }
    val typeBadge = when (album.type) {
        AlbumType.CAMERA -> Icons.Filled.PhotoCamera
        AlbumType.VIDEOS -> Icons.Filled.Videocam
        AlbumType.FOLDER -> null
    }
    val volumeBadge = when (volume?.kind) {
        VolumeKind.SD_CARD -> Icons.Filled.SdCard
        VolumeKind.USB -> Icons.Filled.Usb
        else -> null
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            if (thumb != null) {
                Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            } else {
                Icon(
                    Icons.Filled.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.Center).size(40.dp),
                )
            }
            Row(Modifier.align(Alignment.BottomStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (typeBadge != null) SmallBadge(typeBadge)
                if (volumeBadge != null) SmallBadge(volumeBadge)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            albumLabel(album, volumes),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            pluralStringResource(R.plurals.item_count, album.count, album.count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun SmallBadge(icon: ImageVector) {
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

/** Album title: localized for the virtual and camera albums, the volume name for a volume root. */
@Composable
internal fun albumLabel(album: Album, volumes: List<VolumeInfo>): String = when (album.type) {
    AlbumType.VIDEOS -> stringResource(R.string.album_videos)
    AlbumType.CAMERA -> stringResource(R.string.album_camera)
    AlbumType.FOLDER -> volumes.firstOrNull { it.path == album.path }?.label ?: album.name.ifEmpty { album.path.orEmpty() }
}

/** "120 photos · 3 videos". */
@Composable
internal fun countText(photos: Int, videos: Int): String {
    val nf = remember { NumberFormat.getIntegerInstance() }
    val parts = ArrayList<String>(2)
    if (photos > 0) parts.add(pluralStringResource(R.plurals.gallery_photos, photos, nf.format(photos.toLong())))
    if (videos > 0) parts.add(pluralStringResource(R.plurals.gallery_videos, videos, nf.format(videos.toLong())))
    return parts.joinToString(" · ")
}

// ------------------------------------------------------------------ shared bits

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun GalleryMenu(zoom: Int, showZoom: Boolean, onZoom: (Int) -> Unit, onRefresh: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (showZoom) {
                MenuItem(Icons.Filled.ZoomIn, stringResource(R.string.gallery_bigger), enabled = zoom < 2) {
                    open = false
                    onZoom(zoom + 1)
                }
                MenuItem(Icons.Filled.ZoomOut, stringResource(R.string.gallery_smaller), enabled = zoom > 0) {
                    open = false
                    onZoom(zoom - 1)
                }
            }
            MenuItem(Icons.Filled.Refresh, stringResource(R.string.action_refresh)) {
                open = false
                onRefresh()
            }
        }
    }
}

/** Bottom bar while photos are selected. Copy/move pick their destination in the file list. */
@Composable
private fun GallerySelectionBar(vm: MainViewModel, selected: List<MediaItem>) {
    val paths = selected.map { it.path }
    val single = selected.singleOrNull()
    var more by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(72.dp)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarAction(Icons.Filled.Share, stringResource(R.string.action_share)) { vm.share(paths) }
            BarAction(Icons.Filled.ContentCopy, stringResource(R.string.action_copy)) { vm.galleryTransfer(paths, move = false) }
            BarAction(Icons.AutoMirrored.Filled.DriveFileMove, stringResource(R.string.action_move)) { vm.galleryTransfer(paths, move = true) }
            BarAction(Icons.Filled.Delete, stringResource(R.string.action_delete)) { vm.showDialog(DialogState.ConfirmDelete(paths)) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BarAction(Icons.Filled.MoreHoriz, stringResource(R.string.action_more)) { more = true }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    if (single != null) {
                        MenuItem(Icons.Filled.Info, stringResource(R.string.action_details)) {
                            more = false
                            vm.showDialog(DialogState.MediaInfo(single))
                        }
                        MenuItem(Icons.Filled.DriveFileRenameOutline, stringResource(R.string.action_rename)) {
                            more = false
                            vm.showDialog(DialogState.Rename(single.path))
                        }
                        MenuItem(Icons.Filled.FolderOpen, stringResource(R.string.action_show_in_folder)) {
                            more = false
                            vm.showInFolder(single.path)
                        }
                    }
                    MenuItem(Icons.Filled.Archive, stringResource(R.string.action_compress)) {
                        more = false
                        vm.showDialog(DialogState.Compress(paths))
                    }
                    if (single == null) {
                        MenuItem(Icons.Filled.Info, stringResource(R.string.action_properties)) {
                            more = false
                            vm.showDialog(DialogState.Properties(paths))
                        }
                    }
                }
            }
        }
    }
}
