package com.offlinefiles.explorer.ui.player

import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinefiles.explorer.R
import com.offlinefiles.explorer.core.player.AspectMode
import com.offlinefiles.explorer.core.player.PlaybackSpeed
import com.offlinefiles.explorer.core.player.PlayerTime
import com.offlinefiles.explorer.core.player.RepeatMode
import com.offlinefiles.explorer.core.player.RotationMode
import com.offlinefiles.explorer.core.player.VideoLayout
import com.offlinefiles.explorer.ui.MainViewModel
import com.offlinefiles.explorer.ui.theme.PlayerTheme
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MAX_ZOOM = 4f
private const val HIDE_AFTER_MS = 3500L

/** Transient feedback in the middle of the screen while a gesture or button changes something. */
private sealed interface Feedback {
    data class Seek(val target: Long, val delta: Long) : Feedback
    data class Level(val volume: Boolean, val fraction: Float) : Feedback
    data class Jump(val forward: Boolean, val ms: Long) : Feedback
    data class Zoom(val percent: Int) : Feedback
    data class Note(val text: String) : Feedback
}

private enum class Drag { NONE, IGNORE, SEEK, BRIGHTNESS, VOLUME, PINCH }

/** Full-screen player drawn over whichever screen opened it (file list, gallery, viewer). */
@Composable
fun PlayerHost(vm: MainViewModel, snackbar: SnackbarHostState) {
    val ui by vm.player.ui.collectAsStateWithLifecycle()
    val token = ui?.token ?: return
    val settings by vm.settings.collectAsStateWithLifecycle()
    PlayerTheme(settings.dynamicColor) {
        key(token) { PlayerScreen(vm, snackbar) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val p = vm.player
    val s = p.ui.collectAsStateWithLifecycle().value ?: return
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    val volume = remember(context) { Volume(context) }

    var chrome by rememberSaveable { mutableStateOf(true) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var lockHint by remember { mutableStateOf(false) }
    var panel by rememberSaveable { mutableStateOf<Panel?>(null) }
    var interaction by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf<Feedback?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var showRemaining by rememberSaveable { mutableStateOf(false) }
    val inPip = Pip.rememberMode(p, s)

    PlayerSystemEffects(p, s, barsVisible = chrome && !locked && panel == null, inPip = inPip)

    BackHandler {
        when {
            panel != null -> panel = null
            locked -> lockHint = true
            else -> p.close()
        }
    }

    // Controls fade out while a video plays; music keeps them.
    LaunchedEffect(chrome, s.playing, s.isVideo, panel, interaction, locked) {
        if (chrome && s.playing && s.isVideo && panel == null && !locked) {
            delay(HIDE_AFTER_MS)
            chrome = false
        }
    }
    LaunchedEffect(s.ended, s.error) { if (s.ended || s.error) chrome = true }
    LaunchedEffect(lockHint) {
        if (lockHint) {
            delay(2500)
            lockHint = false
        }
    }
    LaunchedEffect(feedback) {
        val f = feedback
        if (f is Feedback.Jump || f is Feedback.Note) {
            delay(800)
            if (feedback === f) feedback = null
        }
    }
    LaunchedEffect(s.aspect) {
        zoom = 1f
        pan = Offset.Zero
    }

    val note = { text: String -> feedback = Feedback.Note(text) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (s.isVideo) VideoLayer(p, s, zoom, pan) else AudioLayer(s)
        if (inPip) {
            SubtitleLayer(p, s, raised = false, small = true)
            return@Box
        }

        // ------------------------------------------------------------ gestures
        val current by rememberUpdatedState(s)
        var boosting by remember { mutableStateOf(false) }
        var streakSide by remember { mutableIntStateOf(0) }
        var streakUntil by remember { mutableLongStateOf(0L) }
        var streakMs by remember { mutableLongStateOf(0L) }
        val density = LocalDensity.current
        val edgePx = with(density) { 36.dp.toPx() }

        fun jump(side: Int) {
            val now = SystemClock.uptimeMillis()
            if (side != streakSide || now > streakUntil) streakMs = 0
            streakSide = side
            streakUntil = now + 900
            streakMs += PlayerModel.SEEK_STEP_MS
            p.seekBy(side * PlayerModel.SEEK_STEP_MS)
            feedback = Feedback.Jump(side > 0, streakMs)
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(locked) {
                    if (locked) {
                        detectTapGestures(onTap = { lockHint = true })
                        return@pointerInput
                    }
                    fun side(x: Float) = when {
                        x < size.width * 0.35f -> -1
                        x > size.width * 0.65f -> 1
                        else -> 0
                    }
                    detectTapGestures(
                        onTap = { pos ->
                            val side = side(pos.x)
                            if (side != 0 && side == streakSide && SystemClock.uptimeMillis() < streakUntil && current.prepared) {
                                jump(side)
                            } else if (current.isVideo) {
                                chrome = !chrome
                                interaction++
                            }
                        },
                        onDoubleTap = { pos ->
                            val side = side(pos.x)
                            if (!current.prepared) return@detectTapGestures
                            if (side == 0) {
                                p.togglePlay()
                            } else {
                                jump(side)
                            }
                        },
                        onLongPress = {
                            if (current.playing && current.isVideo) {
                                boosting = true
                                p.setBoost(true)
                            }
                        },
                        onPress = {
                            tryAwaitRelease()
                            if (boosting) {
                                boosting = false
                                p.setBoost(false)
                            }
                        },
                    )
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        // The top and bottom edges belong to the status bar and gesture navigation.
                        val fromEdge = down.position.y < edgePx || down.position.y > h - edgePx
                        var mode = Drag.NONE
                        var total = Offset.Zero
                        var startPos = 0L
                        var startLevel = 0f
                        var target = 0L
                        var lastSeek = 0L
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2 && current.isVideo) {
                                if (mode == Drag.SEEK) p.seekTo(target)
                                mode = Drag.PINCH
                                zoom = (zoom * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                                val maxX = w * (zoom - 1f) / 2f
                                val maxY = h * (zoom - 1f) / 2f
                                val moved = pan + event.calculatePan()
                                pan = Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY))
                                feedback = Feedback.Zoom((zoom * 100).roundToInt())
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            val change = pressed.first()
                            when (mode) {
                                Drag.PINCH, Drag.IGNORE -> {
                                    if (mode == Drag.PINCH) change.consume()
                                    continue
                                }
                                Drag.NONE -> {
                                    if (fromEdge || boosting) continue
                                    total += change.positionChange()
                                    if (total.getDistance() < viewConfiguration.touchSlop) continue
                                    val horizontal = abs(total.x) > abs(total.y)
                                    mode = when {
                                        horizontal && current.prepared && current.durationMs > 0 -> Drag.SEEK
                                        horizontal || !current.isVideo -> Drag.IGNORE
                                        down.position.x < w / 2 -> Drag.BRIGHTNESS
                                        else -> Drag.VOLUME
                                    }
                                    total = Offset.Zero
                                    startPos = p.position.value
                                    target = startPos
                                    startLevel = when (mode) {
                                        Drag.BRIGHTNESS -> activity?.let { Brightness.get(it) } ?: 0.5f
                                        Drag.VOLUME -> volume.get()
                                        else -> 0f
                                    }
                                    if (mode != Drag.IGNORE) change.consume()
                                }
                                Drag.SEEK -> {
                                    total += change.positionChange()
                                    target = (startPos + PlayerTime.swipeSeek(total.x / w, current.durationMs)).coerceIn(0, current.durationMs)
                                    feedback = Feedback.Seek(target, target - startPos)
                                    val now = SystemClock.uptimeMillis()
                                    if (now - lastSeek > 120) {
                                        lastSeek = now
                                        p.seekTo(target, exact = false)
                                    }
                                    change.consume()
                                }
                                Drag.BRIGHTNESS, Drag.VOLUME -> {
                                    total += change.positionChange()
                                    val f = (startLevel - total.y / (h * 0.75f)).coerceIn(0f, 1f)
                                    if (mode == Drag.VOLUME) volume.set(f) else activity?.let { Brightness.set(it, f) }
                                    feedback = Feedback.Level(mode == Drag.VOLUME, f)
                                    change.consume()
                                }
                            }
                        }
                        when (mode) {
                            Drag.SEEK -> {
                                p.seekTo(target)
                                feedback = null
                            }
                            Drag.BRIGHTNESS, Drag.VOLUME, Drag.PINCH -> feedback = null
                            else -> Unit
                        }
                    }
                },
        )

        SubtitleLayer(p, s, raised = chrome && !locked, small = false)
        FeedbackLayer(feedback, s.durationMs, boosting = s.boost)

        if (s.error) ErrorLayer(vm, s)

        // ------------------------------------------------------------ controls
        if (!locked) {
            AnimatedVisibility(
                visible = chrome || !s.isVideo,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                TopBar(
                    s,
                    onBack = p::close,
                    onPanel = { panel = it },
                    onRotate = {
                        val next = s.rotation.next()
                        p.setRotation(next)
                        note(rotationLabelText(context, next))
                    },
                )
            }
            AnimatedVisibility(
                visible = chrome || !s.isVideo,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                BottomBar(
                    p = p,
                    s = s,
                    showRemaining = showRemaining,
                    onToggleRemaining = { showRemaining = !showRemaining },
                    onLock = {
                        locked = true
                        chrome = false
                        lockHint = true
                    },
                    onAspect = {
                        val next = s.aspect.next()
                        p.setAspect(next)
                        note(aspectLabelText(context, next))
                    },
                    onScrub = { target, start ->
                        interaction++
                        feedback = if (target == null) null else Feedback.Seek(target, target - start)
                    },
                    onPanel = { panel = it },
                )
            }
        } else {
            LockLayer(visible = lockHint) {
                locked = false
                lockHint = false
                chrome = true
            }
        }

        ResumeBanner(p, s, raised = chrome && !locked)

        val open = panel
        if (open != null) {
            PlayerPanels(vm, s, open, onPanel = { panel = it }, onNote = note)
        }

        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(bottom = 140.dp),
        )
    }
}

// ------------------------------------------------------------------ video & audio

@Composable
private fun VideoLayer(p: PlayerModel, s: PlayerUi, zoom: Float, pan: Offset) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val (w, h) = VideoLayout.size(s.aspect, s.videoW, s.videoH, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val sized = if (w > 0f && h > 0f) Modifier.requiredSize(with(density) { w.toDp() }, with(density) { h.toDp() }) else Modifier.fillMaxSize()
        val transformed = sized.graphicsLayer {
            scaleX = zoom
            scaleY = zoom
            translationX = pan.x
            translationY = pan.y
        }
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        private var surface: Surface? = null

                        override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
                            val sf = Surface(st)
                            surface = sf
                            p.attachSurface(sf)
                        }

                        override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, width: Int, height: Int) = Unit

                        override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                            surface?.let {
                                p.detachSurface(it)
                                it.release()
                            }
                            surface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
                    }
                }
            },
            modifier = transformed,
        )
        // The still frame stands in until the video draws (also the resume point's frame).
        val poster = s.meta?.picture
        if (!s.firstFrame && poster != null) {
            val bitmap = remember(poster) { poster.asImageBitmap() }
            Image(
                bitmap,
                contentDescription = null,
                contentScale = if (w > 0f && s.videoW > 0) ContentScale.FillBounds else ContentScale.Fit,
                modifier = transformed,
            )
        }
        if (!s.prepared && !s.error && poster == null) {
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White.copy(alpha = 0.15f), modifier = Modifier.size(96.dp))
        }
    }
}

@Composable
private fun AudioLayer(s: PlayerUi) {
    val meta = s.meta
    val picture = remember(meta?.picture) { meta?.picture?.asImageBitmap() }
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize()) {
        if (picture != null) {
            Image(
                picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(48.dp)
                    .graphicsLayer { alpha = 0.45f },
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), Color.Black))),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.2f), Color.Black.copy(alpha = 0.75f)))),
        )
        @OptIn(ExperimentalLayoutApi::class)
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.navigationBarsIgnoringVisibility))
                .padding(top = 64.dp, bottom = 168.dp, start = 24.dp, end = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val landscape = maxWidth > maxHeight
            val art = if (landscape) maxHeight * 0.9f else minOf(maxWidth * 0.8f, maxHeight * 0.62f)
            val cover: @Composable () -> Unit = {
                Box(
                    Modifier
                        .size(art)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.15f)))),
                    contentAlignment = Alignment.Center,
                ) {
                    if (picture != null) {
                        Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(Icons.Filled.MusicNote, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(art * 0.4f))
                    }
                }
            }
            val title = meta?.title ?: s.current?.name?.substringBeforeLast('.')?.ifEmpty { s.current?.name } ?: ""
            val subtitle = listOfNotNull(meta?.artist, meta?.album).joinToString(" · ")
            val texts: @Composable (Alignment.Horizontal, TextAlign) -> Unit = { align, textAlign ->
                Column(horizontalAlignment = align) {
                    Text(
                        title,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = textAlign,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            subtitle,
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = textAlign,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (landscape) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    cover()
                    Spacer(Modifier.width(32.dp))
                    Box(Modifier.weight(1f)) { texts(Alignment.Start, TextAlign.Start) }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    cover()
                    Spacer(Modifier.height(28.dp))
                    texts(Alignment.CenterHorizontally, TextAlign.Center)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ subtitles & feedback

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.SubtitleLayer(p: PlayerModel, s: PlayerUi, raised: Boolean, small: Boolean) {
    val text by p.subtitleText.collectAsStateWithLifecycle()
    val t = text ?: return
    val size = (if (small) 11f else 20f) * s.subtitleScale
    val style = TextStyle(fontSize = size.sp, lineHeight = (size * 1.25f).sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout))
            .padding(start = 24.dp, end = 24.dp, bottom = if (small) 6.dp else if (raised) 150.dp else 28.dp),
    ) {
        // Outline first, then the fill: readable on any picture without a box behind the text.
        Text(t, color = Color.Black, style = style.copy(drawStyle = Stroke(width = size / 4f, join = androidx.compose.ui.graphics.StrokeJoin.Round)))
        Text(t, color = Color.White, style = style)
    }
}

@Composable
private fun BoxScope.FeedbackLayer(feedback: Feedback?, duration: Long, boosting: Boolean) {
    if (boosting) {
        Pill(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp),
        ) {
            Icon(Icons.Filled.FastForward, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(PlaybackSpeed.label(PlayerModel.BOOST_SPEED), color = Color.White, style = MaterialTheme.typography.titleSmall)
        }
    }
    when (val f = feedback) {
        null -> Unit
        is Feedback.Seek -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                PlayerTime.signed(f.delta),
                color = Color.White,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                "${PlayerTime.format(f.target, duration >= 3_600_000)} / ${PlayerTime.format(duration)}",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        is Feedback.Level -> LevelBar(f.volume, f.fraction, Modifier.align(if (f.volume) Alignment.CenterEnd else Alignment.CenterStart))
        is Feedback.Jump -> JumpRipple(f)
        is Feedback.Zoom -> Pill(Modifier.align(Alignment.Center)) {
            Icon(Icons.Filled.ZoomIn, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.player_zoom, f.percent), color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        is Feedback.Note -> Pill(Modifier.align(Alignment.Center)) {
            Text(f.text, color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Pill(modifier: Modifier, content: @Composable () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** MX-style vertical meter at the side of the screen being swiped. */
@Composable
private fun LevelBar(volume: Boolean, fraction: Float, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .padding(horizontal = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${(fraction * 100).roundToInt()}", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .width(6.dp)
                .height(140.dp)
                .drawBehind {
                    drawRoundRect(Color.White.copy(alpha = 0.25f), cornerRadius = CornerRadius(size.width / 2))
                    val fill = size.height * fraction
                    drawRoundRect(
                        accent,
                        topLeft = Offset(0f, size.height - fill),
                        size = Size(size.width, fill),
                        cornerRadius = CornerRadius(size.width / 2),
                    )
                },
        )
        Spacer(Modifier.height(10.dp))
        Icon(
            if (volume) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.BrightnessMedium,
            contentDescription = stringResource(if (volume) R.string.player_volume else R.string.player_brightness),
            tint = Color.White,
        )
    }
}

/** Half-moon flash on the tapped side with the total jumped, like double-tap seek in MX Player. */
@Composable
private fun BoxScope.JumpRipple(f: Feedback.Jump) {
    val shape = if (f.forward) RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50) else RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
    Box(
        Modifier
            .align(if (f.forward) Alignment.CenterEnd else Alignment.CenterStart)
            .fillMaxHeight()
            .fillMaxWidth(0.38f)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (f.forward) Icons.Filled.FastForward else Icons.Filled.FastRewind, null, tint = Color.White, modifier = Modifier.size(40.dp))
            val seconds = (f.ms / 1000).toInt()
            Text(pluralStringResource(R.plurals.player_seconds, seconds, seconds), color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ------------------------------------------------------------------ bars

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopBar(s: PlayerUi, onBack: () -> Unit, onPanel: (Panel) -> Unit, onRotate: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.displayCutout.only(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal)))
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_up), tint = Color.White)
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        ) {
            Text(
                s.current?.name.orEmpty(),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            if (s.queue.size > 1) {
                Text(
                    "${s.index + 1} / ${s.queue.size}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (s.isVideo) {
            BarIcon(Icons.Filled.ClosedCaption, stringResource(R.string.player_subtitles), active = s.subtitle != null) { onPanel(Panel.SUBTITLES) }
        }
        if (s.audioTracks.size > 1) {
            BarIcon(Icons.Filled.Audiotrack, stringResource(R.string.player_audio)) { onPanel(Panel.AUDIO) }
        }
        if (s.isVideo) {
            BarIcon(Icons.Filled.ScreenRotation, stringResource(R.string.player_rotate), active = s.rotation != RotationMode.AUTO, onClick = onRotate)
        }
        if (s.queue.size > 1) {
            BarIcon(Icons.AutoMirrored.Filled.PlaylistPlay, stringResource(R.string.player_queue)) { onPanel(Panel.QUEUE) }
        }
        BarIcon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) { onPanel(Panel.MORE) }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.primary else Color.White)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BottomBar(
    p: PlayerModel,
    s: PlayerUi,
    showRemaining: Boolean,
    onToggleRemaining: () -> Unit,
    onLock: () -> Unit,
    onAspect: () -> Unit,
    onScrub: (Long?, Long) -> Unit,
    onPanel: (Panel) -> Unit,
) {
    val position by p.position.collectAsStateWithLifecycle()
    val hours = s.durationMs >= 3_600_000
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout.only(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal)))
            .padding(start = 12.dp, end = 12.dp, top = 32.dp, bottom = 6.dp),
    ) {
        StatusChips(s, onPanel)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                PlayerTime.format(position, hours),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.widthIn(min = 44.dp),
                textAlign = TextAlign.Center,
            )
            SeekBar(
                position = position,
                duration = s.durationMs,
                abA = s.abA,
                abB = s.abB,
                enabled = s.prepared,
                onScrub = { v, start, final ->
                    if (final) {
                        p.seekTo(v)
                        onScrub(null, start)
                    } else {
                        p.seekTo(v, exact = false)
                        onScrub(v, start)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text(
                if (s.durationMs <= 0) "--:--"
                else if (showRemaining) "−" + PlayerTime.format(s.durationMs - position, hours)
                else PlayerTime.format(s.durationMs),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = 44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleRemaining),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (s.isVideo) {
                BarIcon(Icons.Filled.Lock, stringResource(R.string.player_lock), onClick = onLock)
            } else {
                BarIcon(Icons.Filled.Shuffle, stringResource(R.string.player_shuffle), active = s.shuffle) { p.toggleShuffle() }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = p::previous, enabled = s.canPrevious || s.prepared) {
                Icon(Icons.Filled.SkipPrevious, stringResource(R.string.player_previous), tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(12.dp))
            Surface(
                onClick = p::togglePlay,
                enabled = s.prepared,
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.16f),
                contentColor = Color.White,
            ) {
                val (icon, label) = when {
                    s.ended -> Icons.Filled.Replay to R.string.player_replay
                    s.playing -> Icons.Filled.Pause to R.string.action_pause
                    else -> Icons.Filled.PlayArrow to R.string.action_play
                }
                Icon(icon, stringResource(label), modifier = Modifier.padding(12.dp).size(40.dp))
            }
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = p::next, enabled = s.canNext) {
                Icon(
                    Icons.Filled.SkipNext,
                    stringResource(R.string.player_next),
                    tint = if (s.canNext) Color.White else Color.White.copy(alpha = 0.35f),
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (s.isVideo) {
                BarIcon(Icons.Filled.Fullscreen, stringResource(R.string.player_aspect), onClick = onAspect)
            } else {
                BarIcon(
                    if (s.repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    stringResource(R.string.player_repeat),
                    active = s.repeat != RepeatMode.OFF,
                ) { p.cycleRepeat() }
            }
        }
    }
}

/** Small reminders above the seek bar: speed other than 1×, A-B loop, sleep timer. */
@Composable
private fun StatusChips(s: PlayerUi, onPanel: (Panel) -> Unit) {
    val chips = buildList<Triple<ImageVector?, String, Panel>> {
        if (s.speed != 1f) add(Triple(null, PlaybackSpeed.label(s.speed), Panel.SPEED))
        if (s.abA != null) add(Triple(Icons.Filled.Repeat, if (s.abB != null) "A-B" else "A-", Panel.MORE))
        if (s.sleepAt != null || s.sleepAfterItem) add(Triple(Icons.Filled.Timer, sleepChipText(s), Panel.SLEEP))
        if (s.isVideo && s.repeat != RepeatMode.OFF) {
            add(Triple(if (s.repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "", Panel.MORE))
        }
    }
    if (chips.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        for ((icon, label, target) in chips) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable { onPanel(target) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
                if (icon != null && label.isNotEmpty()) Spacer(Modifier.width(4.dp))
                if (label.isNotEmpty()) Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun sleepChipText(s: PlayerUi): String {
    val at = s.sleepAt ?: return stringResource(R.string.player_sleep_end_short)
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(at) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    return PlayerTime.format((at - now).coerceAtLeast(0))
}

/**
 * Thin MX-style seek bar: tap or drag anywhere on it. While dragging the picture follows on key
 * frames ([final] false); the exact frame is fetched when the finger lifts ([final] true).
 */
@Composable
private fun SeekBar(
    position: Long,
    duration: Long,
    abA: Long?,
    abB: Long?,
    enabled: Boolean,
    onScrub: (value: Long, start: Long, final: Boolean) -> Unit,
    modifier: Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    var scrub by remember { mutableStateOf<Long?>(null) }
    val shown = scrub ?: position
    val fraction = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val currentDuration by rememberUpdatedState(duration)
    val currentPosition by rememberUpdatedState(position)
    Box(
        modifier
            .height(36.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), 0f..duration.coerceAtLeast(1).toFloat())
                if (enabled && duration > 0) {
                    setProgress { v ->
                        onScrub(v.toLong(), currentPosition, true)
                        true
                    }
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val d = currentDuration
                    if (d <= 0) return@awaitEachGesture
                    val start = currentPosition
                    fun at(x: Float) = ((x / size.width).coerceIn(0f, 1f) * d).toLong()
                    var v = at(down.position.x)
                    scrub = v
                    onScrub(v, start, false)
                    down.consume()
                    var last = SystemClock.uptimeMillis()
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        v = at(c.position.x)
                        scrub = v
                        c.consume()
                        val now = SystemClock.uptimeMillis()
                        if (now - last > 100) {
                            last = now
                            onScrub(v, start, false)
                        }
                    }
                    onScrub(v, start, true)
                    scrub = null
                }
            }
            .drawBehind {
                val track = 3.dp.toPx()
                val y = size.height / 2
                val r = CornerRadius(track / 2)
                drawRoundRect(Color.White.copy(alpha = 0.3f), topLeft = Offset(0f, y - track / 2), size = Size(size.width, track), cornerRadius = r)
                if (abA != null && duration > 0) {
                    val ax = size.width * (abA.toFloat() / duration)
                    val bx = if (abB != null) size.width * (abB.toFloat() / duration) else ax
                    drawRect(accent.copy(alpha = 0.45f), topLeft = Offset(ax, y - track * 1.5f), size = Size((bx - ax).coerceAtLeast(2.dp.toPx()), track * 3))
                }
                drawRoundRect(accent, topLeft = Offset(0f, y - track / 2), size = Size(size.width * fraction, track), cornerRadius = r)
                val thumb = if (scrub != null) 9.dp.toPx() else 6.dp.toPx()
                drawCircle(accent, radius = thumb, center = Offset(size.width * fraction, y))
            },
    )
}

// ------------------------------------------------------------------ overlays

@Composable
private fun BoxScope.LockLayer(visible: Boolean, onUnlock: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.CenterStart)
            .padding(start = 32.dp),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(onClick = onUnlock, shape = CircleShape, color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White) {
                Icon(Icons.Filled.LockOpen, stringResource(R.string.player_unlock), modifier = Modifier.padding(16.dp).size(28.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.player_unlock),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.ResumeBanner(p: PlayerModel, s: PlayerUi, raised: Boolean) {
    val from = s.resumedFrom ?: return
    var visible by rememberSaveable(s.loadId) { mutableStateOf(true) }
    LaunchedEffect(s.loadId) {
        delay(6000)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
            .padding(bottom = if (raised) 160.dp else 32.dp),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(start = 18.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.player_resumed, PlayerTime.format(from)), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                p.seekTo(0)
                visible = false
            }) { Text(stringResource(R.string.player_start_over)) }
        }
    }
}

@Composable
private fun BoxScope.ErrorLayer(vm: MainViewModel, s: PlayerUi) {
    val item = s.current ?: return
    Column(
        Modifier
            .align(Alignment.Center)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.ErrorOutline, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.player_error_title), color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.player_error_body),
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.openWith(item.path) }) { Text(stringResource(R.string.player_open_other), color = Color.White) }
            if (s.canNext) OutlinedButton(onClick = vm.player::next) { Text(stringResource(R.string.player_next_file), color = Color.White) }
        }
    }
}

// ------------------------------------------------------------------ labels

fun aspectLabelText(context: android.content.Context, mode: AspectMode): String = context.getString(
    when (mode) {
        AspectMode.FIT -> R.string.aspect_fit
        AspectMode.FILL -> R.string.aspect_fill
        AspectMode.STRETCH -> R.string.aspect_stretch
        AspectMode.ORIGINAL -> R.string.aspect_original
        AspectMode.RATIO_16_9 -> R.string.aspect_16_9
        AspectMode.RATIO_4_3 -> R.string.aspect_4_3
    },
)

fun rotationLabelText(context: android.content.Context, mode: RotationMode): String = context.getString(
    when (mode) {
        RotationMode.AUTO -> R.string.rotation_auto
        RotationMode.LANDSCAPE -> R.string.rotation_landscape
        RotationMode.PORTRAIT -> R.string.rotation_portrait
    },
)
