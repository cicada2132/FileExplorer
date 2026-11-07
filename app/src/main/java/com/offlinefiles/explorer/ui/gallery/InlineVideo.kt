package com.offlinefiles.explorer.ui.gallery

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Image
import com.offlinefiles.explorer.R
import com.offlinefiles.explorer.core.gallery.MediaDates
import com.offlinefiles.explorer.core.gallery.MediaItem
import com.offlinefiles.explorer.App
import com.offlinefiles.explorer.core.player.AspectMode
import com.offlinefiles.explorer.core.player.DecoderChoice
import com.offlinefiles.explorer.core.player.VideoLayout
import com.offlinefiles.explorer.data.UsbDiagnostics
import com.offlinefiles.explorer.ui.player.Engine
import com.offlinefiles.explorer.ui.player.EngineListener
import com.offlinefiles.explorer.ui.player.PlayItem
import com.offlinefiles.explorer.ui.player.Engines
import kotlinx.coroutines.delay

/**
 * A video playing in place in the photo viewer, so photos and videos can be swiped through
 * together. Deliberately simple (play/pause, seek, replay); the full player is one tap away.
 * Plays with VLC, like the full player.
 */
class InlinePlayer(private val context: Context) {
    var prepared by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var ended by mutableStateOf(false)
        private set
    var error by mutableStateOf(false)
        private set
    var firstFrame by mutableStateOf(false)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var videoW by mutableStateOf(0)
        private set
    var videoH by mutableStateOf(0)
        private set
    var position by mutableLongStateOf(0L)
        private set

    private var engine: Engine? = null
    private var item: MediaItem? = null
    private var surface: Surface? = null
    private var surfaceW = 0
    private var surfaceH = 0
    private val audio = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    /** Reopened with VLC's own decoders after hardware decoding failed. */
    private var softwareOnly = false

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()

    fun start(item: MediaItem) {
        if (this.item?.path == item.path && engine != null) return
        this.item = item
        load(software = false)
    }

    /** The same rule as the full player: hardware only for modern codecs, AVI/MPG/WMV... in software. */
    private fun load(software: Boolean) {
        val item = item ?: return
        engine?.let { e -> runCatching { e.release() } }
        engine = null
        prepared = false
        playing = false
        ended = false
        error = false
        firstFrame = false
        position = 0
        lateinit var e: Engine
        val listener = object : EngineListener {
            private fun mine() = engine === e

            override fun onPrepared() {
                if (!mine()) return
                if (e.hardware && !DecoderChoice.hardwareSuits(runCatching { e.videoCodec() }.getOrNull())) {
                    load(software = true)
                    return
                }
                prepared = true
                durationMs = runCatching { e.duration }.getOrDefault(0L).coerceAtLeast(0L)
                val w = runCatching { e.videoWidth }.getOrDefault(0)
                val h = runCatching { e.videoHeight }.getOrDefault(0)
                if (w > 0 && h > 0) {
                    videoW = w
                    videoH = h
                }
                play()
            }

            override fun onCompletion() {
                if (!mine()) return
                playing = false
                ended = true
                position = durationMs
                abandonFocus()
            }

            override fun onError(detail: String) {
                if (mine()) fail(e, detail)
            }

            override fun onFirstFrame() {
                if (mine()) firstFrame = true
            }

            override fun onVideoSize(width: Int, height: Int) {
                if (!mine()) return
                videoW = width
                videoH = height
            }

            override fun onSeekComplete() = Unit
            override fun onTimedText(text: String?) = Unit
            override fun onTracksChanged() = Unit
        }
        softwareOnly = software
        val hwPreferred = (context.applicationContext as? App)?.playerStore?.hwDecoding ?: true
        val hardware = hwPreferred && !software && DecoderChoice.hardwareFirst(item.name)
        val created = Engines.create(context, listener, hardware)
        if (created == null) {
            UsbDiagnostics.log(context, "viewer: VLC unavailable for ${item.name}")
            error = true
            return
        }
        e = created
        engine = e
        try {
            e.open(PlayItem(item.path, item.name, true), attributes, surface, surfaceW, surfaceH)
        } catch (ex: Exception) {
            fail(e, "open: ${ex.javaClass.simpleName}: ${ex.message}")
        }
    }

    private fun fail(e: Engine, detail: String) {
        UsbDiagnostics.log(context, "viewer ${e.name}${if (e.hardware) " (hardware)" else ""} ${item?.name}: $detail")
        if (e.hardware && !softwareOnly) {
            load(software = true)
            return
        }
        error = true
        playing = false
        abandonFocus()
    }

    fun play() {
        val e = engine ?: return
        if (!prepared || error) return
        if (ended) {
            ended = false
            position = 0
            runCatching { e.seekTo(0, exact = true) }
        }
        requestFocus()
        if (runCatching { e.start() }.isSuccess) playing = true
    }

    fun pause() {
        val e = engine
        if (e != null && prepared) runCatching { if (e.isPlaying) e.pause() }
        playing = false
    }

    fun toggle() = if (playing) pause() else play()

    fun seekTo(ms: Long) {
        val e = engine ?: return
        if (!prepared) return
        val target = if (durationMs > 0) ms.coerceIn(0, durationMs) else ms.coerceAtLeast(0)
        position = target
        if (ended && target < durationMs) ended = false
        runCatching { e.seekTo(target, exact = false) }
    }

    /** Called about ten times a second while on screen. */
    fun tick() {
        val e = engine ?: return
        if (prepared && !ended && !error) runCatching { e.position }.getOrNull()?.let { position = it }
    }

    fun attachSurface(s: Surface?, width: Int, height: Int) {
        surface = s
        surfaceW = width
        surfaceH = height
        runCatching { engine?.setSurface(s, width, height) }
        // A paused video shows nothing on a fresh surface until the next frame: redraw it.
        if (s != null && prepared && !playing) seekTo(position)
    }

    fun surfaceSize(width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
        runCatching { engine?.setSurfaceSize(width, height) }
    }

    fun release() {
        engine?.let { runCatching { it.release() } }
        engine = null
        item = null
        prepared = false
        playing = false
        abandonFocus()
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
    }

    private fun requestFocus() {
        val am = audio ?: return
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { focus = it }
        runCatching { am.requestAudioFocus(req) }
    }

    private fun abandonFocus() {
        val req = focus ?: return
        runCatching { audio?.abandonAudioFocusRequest(req) }
        focus = null
    }
}

/**
 * The viewer page of a video. While [active] (the page on screen) it plays in place; swiping
 * away stops it and frees the decoder. [frame] stands in until the first picture arrives.
 */
@Composable
fun InlineVideoPage(
    item: MediaItem,
    active: Boolean,
    frame: ImageBitmap?,
    controls: Boolean,
    onTap: () -> Unit,
    onPlayingChange: (Boolean) -> Unit,
    playerRef: (InlinePlayer?) -> Unit,
) {
    val context = LocalContext.current
    val player = remember(item.path) { InlinePlayer(context.applicationContext) }
    DisposableEffect(player) {
        onDispose {
            player.release()
            playerRef(null)
        }
    }
    LaunchedEffect(active) {
        if (active) {
            player.start(item)
            playerRef(player)
        } else {
            player.release()
        }
    }
    LaunchedEffect(player.playing) { onPlayingChange(player.playing) }
    LaunchedEffect(active, player.playing) {
        while (active && player.playing) {
            player.tick()
            delay(100)
        }
        player.tick()
    }
    // Keep the screen on while the video plays.
    val view = LocalView.current
    DisposableEffect(player.playing) {
        view.keepScreenOn = player.playing
        onDispose { view.keepScreenOn = false }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { player.toggle() }) },
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val (w, h) = VideoLayout.size(AspectMode.FIT, player.videoW, player.videoH, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val sized = if (w > 0f && h > 0f) Modifier.requiredSize(with(density) { w.toDp() }, with(density) { h.toDp() }) else Modifier.fillMaxSize()
        if (active) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            private var surface: Surface? = null

                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                val sf = Surface(st)
                                surface = sf
                                player.attachSurface(sf, width, height)
                            }

                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) =
                                player.surfaceSize(width, height)

                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                surface?.let {
                                    player.attachSurface(null, 0, 0)
                                    it.release()
                                }
                                surface = null
                                return true
                            }

                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = sized,
            )
        }
        if (!player.firstFrame || !active) {
            if (frame != null) {
                Image(frame, contentDescription = item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else if (!active || player.error) {
                Icon(Icons.Filled.Movie, contentDescription = item.name, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(96.dp))
            }
        }
        when {
            player.error -> Text(
                stringResource(R.string.player_error_title),
                color = Color.White,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            active && !player.prepared -> CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
            controls || !player.playing -> Surface(
                onClick = { player.toggle() },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
            ) {
                val icon = when {
                    player.ended -> Icons.Filled.Replay
                    player.playing -> Icons.Filled.Pause
                    else -> Icons.Filled.PlayArrow
                }
                val label = stringResource(if (player.playing) R.string.action_pause else R.string.action_play)
                Icon(icon, label, modifier = Modifier.padding(14.dp).size(52.dp))
            }
        }
        if (active && player.prepared && controls) {
            SeekRow(player, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeekRow(player: InlinePlayer, modifier: Modifier) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = player.durationMs.coerceAtLeast(1L)
    val shown = if (dragging) (dragValue * duration).toLong() else player.position
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
            // Above the viewer's bottom bar (share, delete, ...).
            .padding(start = 16.dp, end = 16.dp, bottom = 76.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(MediaDates.duration(shown), color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 40.dp))
        Slider(
            value = (shown.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                player.seekTo((dragValue * duration).toLong())
                dragging = false
            },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
            modifier = Modifier.weight(1f),
        )
        Text(MediaDates.duration(player.durationMs), color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 40.dp))
    }
}
