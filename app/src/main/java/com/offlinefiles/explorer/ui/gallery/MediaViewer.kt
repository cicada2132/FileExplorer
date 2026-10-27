package com.offlinefiles.explorer.ui.gallery

import android.app.Activity
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinefiles.explorer.R
import com.offlinefiles.explorer.core.gallery.GalleryIndex
import com.offlinefiles.explorer.core.gallery.MediaDates
import com.offlinefiles.explorer.core.gallery.MediaItem
import com.offlinefiles.explorer.ui.DialogState
import com.offlinefiles.explorer.ui.MainViewModel
import com.offlinefiles.explorer.ui.browser.MenuItem
import com.offlinefiles.explorer.ui.components.Format
import com.offlinefiles.explorer.ui.components.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * Full-screen photo/video viewer. Drawn above the screen that opened it (gallery, album or
 * file list), so that screen stays exactly where it was.
 */
@Composable
fun MediaViewerHost(vm: MainViewModel, snackbar: SnackbarHostState) {
    val request by vm.gallery.viewer.collectAsStateWithLifecycle()
    val r = request ?: return
    key(r.token) { MediaViewer(vm, r, snackbar) }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MediaViewer(vm: MainViewModel, request: ViewerRequest, snackbar: SnackbarHostState) {
    val g = vm.gallery
    val ui by g.ui.collectAsStateWithLifecycle()
    val items = request.items ?: ui.index.itemsOf(request.albumKey ?: GalleryIndex.ALL)
    var chrome by rememberSaveable { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    val start = remember { items.indexOfFirst { it.path == request.startPath }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = start) { items.size }
    val current = items.getOrNull(pager.currentPage)

    // The last item was deleted (or moved away): nothing left to show.
    val empty = items.isEmpty()
    LaunchedEffect(empty) { if (empty) g.closeViewer(null) }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    BackHandler { g.closeViewer(current?.path) }
    ViewerSystemBars(visible = chrome)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            pageSpacing = 12.dp,
            userScrollEnabled = !zoomed,
            key = { i -> items.getOrNull(i)?.path ?: i },
        ) { page ->
            val item = items.getOrNull(page)
            if (item != null) {
                if (item.isVideo) {
                    VideoPage(item, onTap = { chrome = !chrome }, onPlay = { vm.openMedia(item) })
                } else {
                    ImagePage(
                        item = item,
                        isCurrent = page == pager.currentPage,
                        onTap = { chrome = !chrome },
                        onZoomedChange = { z -> if (page == pager.currentPage) zoomed = z },
                        onOpenWith = { vm.openWith(item.path) },
                    )
                }
            }
        }
        if (current != null) {
            ViewerTopBar(vm, current, chrome) { g.closeViewer(current.path) }
            ViewerBottomBar(vm, current, chrome)
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(bottom = 88.dp),
        )
    }
}

// ------------------------------------------------------------------ pages

@Composable
private fun ImagePage(
    item: MediaItem,
    isCurrent: Boolean,
    onTap: () -> Unit,
    onZoomedChange: (Boolean) -> Unit,
    onOpenWith: () -> Unit,
) {
    val maxSide = rememberDecodeSize()
    val decoded by produceState<Decoded?>(null, item.path, item.dateModified, maxSide) {
        value = withContext(Dispatchers.IO) { MediaDecode.full(item, maxSide) }
    }
    // The grid's thumbnail stands in while the full picture decodes.
    val placeholder = remember(item.path, item.dateModified) { Thumbnails.peekMediaAny(item)?.asImageBitmap() }
    val painter: Painter? = when (val d = decoded) {
        is Decoded.Still -> remember(d) { BitmapPainter(d.bitmap.asImageBitmap()) }
        is Decoded.Animated -> remember(d) { DrawablePainter(d.drawable) }
        else -> if (placeholder != null) remember(placeholder) { BitmapPainter(placeholder) } else null
    }
    when {
        painter != null -> ZoomableImage(painter, item.name, isCurrent, onTap, onZoomedChange)
        decoded == Decoded.Failed -> CannotShow(onTap, onOpenWith)
        else -> Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun VideoPage(item: MediaItem, onTap: () -> Unit, onPlay: () -> Unit) {
    val maxSide = rememberDecodeSize()
    val placeholder = remember(item.path, item.dateModified) { Thumbnails.peekMediaAny(item)?.asImageBitmap() }
    val frame by produceState(placeholder, item.path, item.dateModified) {
        val big = withContext(Dispatchers.IO) { MediaDecode.videoFrame(item, maxSide / 2) }
        if (big != null) value = big.asImageBitmap()
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center,
    ) {
        val f = frame
        if (f != null) {
            Image(f, contentDescription = item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.Movie, contentDescription = item.name, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(96.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(onClick = onPlay, shape = CircleShape, color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White) {
                Icon(Icons.Filled.PlayArrow, stringResource(R.string.action_play), modifier = Modifier.padding(14.dp).size(52.dp))
            }
            if (item.durationMs > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    MediaDates.duration(item.durationMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun CannotShow(onTap: () -> Unit, onOpenWith: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.BrokenImage, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.viewer_cannot_show), color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onOpenWith) { Text(stringResource(R.string.action_open_with)) }
    }
}

/** Twice the screen's long side, capped so a 200-megapixel photo can't exhaust memory. */
@Composable
private fun rememberDecodeSize(): Int {
    val config = LocalConfiguration.current
    val density = LocalDensity.current.density
    return remember(config.screenWidthDp, config.screenHeightDp, density) {
        val longSide = (max(config.screenWidthDp, config.screenHeightDp) * density).roundToInt()
        (longSide * 2).coerceIn(1024, 3072)
    }
}

// ------------------------------------------------------------------ zoom

/**
 * Pinch to zoom, drag to pan when zoomed, double-tap to zoom in or out. At 1× a one-finger
 * drag is left alone so the pager can swipe to the next photo.
 */
@Composable
private fun ZoomableImage(
    painter: Painter,
    description: String?,
    isCurrent: Boolean,
    onTap: () -> Unit,
    onZoomedChange: (Boolean) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val currentPainter by rememberUpdatedState(painter)
    val tap by rememberUpdatedState(onTap)

    // Size of the picture inside the box at 1× (ContentScale.Fit).
    fun fitted(): Size {
        val src = currentPainter.intrinsicSize
        val bw = box.width.toFloat()
        val bh = box.height.toFloat()
        if (bw <= 0f || bh <= 0f || src.isUnspecified || src.width <= 0f || src.height <= 0f) return Size(bw, bh)
        val k = min(bw / src.width, bh / src.height)
        return Size(src.width * k, src.height * k)
    }

    // Keeps the picture covering the screen edge to edge while panning.
    fun clamp(o: Offset, s: Float): Offset {
        val f = fitted()
        val maxX = ((f.width * s - box.width) / 2f).coerceAtLeast(0f)
        val maxY = ((f.height * s - box.height) / 2f).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    val zoomed = scale > 1.01f
    LaunchedEffect(zoomed, isCurrent) { if (isCurrent) onZoomedChange(zoomed) }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { at ->
                        val fromScale = scale
                        val fromOffset = offset
                        val toScale = if (fromScale > 1.01f) 1f else DOUBLE_TAP_SCALE
                        val center = Offset(box.width / 2f, box.height / 2f)
                        // Zoom in around the tapped point.
                        val toOffset = if (toScale == 1f) Offset.Zero else clamp((center - at) * (toScale - 1f), toScale)
                        scope.launch {
                            animate(0f, 1f, animationSpec = tween(220)) { t, _ ->
                                scale = fromScale + (toScale - fromScale) * t
                                offset = Offset(
                                    fromOffset.x + (toOffset.x - fromOffset.x) * t,
                                    fromOffset.y + (toOffset.y - fromOffset.y) * t,
                                )
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            if (centroid.isSpecified) {
                                val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                                val k = newScale / scale
                                // Keep the point between the fingers under the fingers.
                                val d = centroid - Offset(box.width / 2f, box.height / 2f)
                                offset = clamp(offset * k + d * (1f - k) + pan, newScale)
                                scale = newScale
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale < 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painter,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

/** Draws an Android Drawable (animated GIF/WebP) in Compose and keeps it animating. */
private class DrawablePainter(private val drawable: Drawable) : Painter(), RememberObserver {
    private var frame by mutableIntStateOf(0)
    private val handler = Handler(Looper.getMainLooper())
    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            frame++
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            handler.postAtTime(what, `when`)
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            handler.removeCallbacks(what)
        }
    }

    override val intrinsicSize: Size
        get() = Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())

    override fun DrawScope.onDraw() {
        if (frame < 0) return // reading the counter redraws on every new frame
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }

    override fun onRemembered() {
        drawable.callback = callback
        drawable.setVisible(true, true)
        (drawable as? Animatable)?.start()
    }

    override fun onForgotten() {
        (drawable as? Animatable)?.stop()
        drawable.setVisible(false, false)
        drawable.callback = null
    }

    override fun onAbandoned() = onForgotten()
}

// ------------------------------------------------------------------ chrome

/** Hides the status and navigation bars together with the viewer's own bars. */
@Composable
private fun ViewerSystemBars(visible: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    DisposableEffect(window) {
        val c = window?.let { WindowCompat.getInsetsController(it, view) }
        c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { c?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(window, visible) {
        val c = window?.let { WindowCompat.getInsetsController(it, view) } ?: return@LaunchedEffect
        if (visible) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.ViewerTopBar(vm: MainViewModel, item: MediaItem, visible: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_up), tint = Color.White)
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    Format.longDate(context, item.date),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(Format.time(context, item.date), item.name).filter { it.isNotEmpty() }.joinToString(" · "),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more), tint = Color.White)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.action_open_with)) {
                        menu = false
                        vm.openWith(item.path)
                    }
                    MenuItem(Icons.Filled.FolderOpen, stringResource(R.string.action_show_in_folder)) {
                        menu = false
                        vm.showInFolder(item.path)
                    }
                    MenuItem(Icons.Filled.DriveFileRenameOutline, stringResource(R.string.action_rename)) {
                        menu = false
                        vm.showDialog(DialogState.Rename(item.path))
                    }
                    if (!item.isVideo) {
                        MenuItem(Icons.Filled.Wallpaper, stringResource(R.string.action_set_as)) {
                            menu = false
                            vm.setAs(item)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.ViewerBottomBar(vm: MainViewModel, item: MediaItem, visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(top = 28.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewerAction(Icons.Filled.Share, stringResource(R.string.action_share)) { vm.share(listOf(item.path)) }
            if (item.isVideo) {
                ViewerAction(Icons.Filled.PlayArrow, stringResource(R.string.action_play)) { vm.openMedia(item) }
            }
            ViewerAction(Icons.Filled.Info, stringResource(R.string.action_details)) { vm.showDialog(DialogState.MediaInfo(item)) }
            ViewerAction(Icons.Filled.Delete, stringResource(R.string.action_delete)) {
                vm.showDialog(DialogState.ConfirmDelete(listOf(item.path)))
            }
        }
    }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
