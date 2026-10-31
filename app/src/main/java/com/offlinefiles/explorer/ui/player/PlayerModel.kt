package com.offlinefiles.explorer.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaFormat
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import androidx.core.content.ContextCompat
import com.offlinefiles.explorer.R
import com.offlinefiles.explorer.core.player.AspectMode
import com.offlinefiles.explorer.core.player.PlayQueue
import com.offlinefiles.explorer.core.player.PlaybackSpeed
import com.offlinefiles.explorer.core.player.RepeatMode
import com.offlinefiles.explorer.core.player.Resume
import com.offlinefiles.explorer.core.player.RotationMode
import com.offlinefiles.explorer.core.player.SubtitleTrack
import com.offlinefiles.explorer.core.player.Subtitles
import com.offlinefiles.explorer.data.PlayerStore
import com.offlinefiles.explorer.data.Stores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max

/** One file in the play queue. */
data class PlayItem(val path: String, val name: String, val isVideo: Boolean)

/** An audio track of the file; [index] is MediaPlayer's track number. */
data class TrackOption(val index: Int, val label: String)

/** An entry of the subtitle menu: a track inside the video, or a subtitle file (one language of it). */
sealed interface SubtitleSource {
    val id: String
    val label: String

    data class Embedded(val index: Int, override val label: String) : SubtitleSource {
        override val id: String get() = "e:$index"
    }

    data class External(val path: String, val track: Int, override val label: String) : SubtitleSource {
        override val id: String get() = "x:$path#$track"
    }
}

/** Tags and artwork: album art for music, a still frame for video (shown until the first frame arrives). */
data class MediaMeta(val path: String, val title: String?, val artist: String?, val album: String?, val picture: Bitmap?)

data class PlayerUi(
    val queue: List<PlayItem>,
    val index: Int,
    /** New for every [PlayerModel.open], so the screen starts fresh. */
    val token: Long,
    /** New for every file loaded. */
    val loadId: Long = 0,
    val prepared: Boolean = false,
    val playing: Boolean = false,
    val ended: Boolean = false,
    val error: Boolean = false,
    /** The video has drawn its first frame (until then the still frame stands in). */
    val firstFrame: Boolean = false,
    val durationMs: Long = 0,
    val videoW: Int = 0,
    val videoH: Int = 0,
    val speed: Float = 1f,
    /** Held-down fast forward (2×) is active. */
    val boost: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val shuffle: Boolean = false,
    val canNext: Boolean = false,
    val canPrevious: Boolean = false,
    val aspect: AspectMode = AspectMode.FIT,
    val rotation: RotationMode = RotationMode.AUTO,
    val abA: Long? = null,
    val abB: Long? = null,
    val audioTracks: List<TrackOption> = emptyList(),
    val audioTrack: Int = -1,
    val subtitles: List<SubtitleSource> = emptyList(),
    val subtitle: SubtitleSource? = null,
    val subtitleDelayMs: Long = 0,
    val subtitleScale: Float = 1f,
    /** SystemClock.elapsedRealtime() when the sleep timer pauses playback. */
    val sleepAt: Long? = null,
    val sleepAfterItem: Boolean = false,
    /** Set when the file started where it was left off last time. */
    val resumedFrom: Long? = null,
    val meta: MediaMeta? = null,
) {
    val current: PlayItem? get() = queue.getOrNull(index)
    val isVideo: Boolean get() = current?.isVideo == true
}

/**
 * The built-in player: one framework MediaPlayer (no extra libraries), a play queue, subtitles,
 * audio focus and headset buttons. Lives in MainViewModel, so playback carries on through
 * rotation; the screen only lends it a Surface to draw on.
 */
class PlayerModel(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: PlayerStore,
    private val resumeEnabled: () -> Boolean,
    private val message: (Int) -> Unit,
) {
    private val _ui = MutableStateFlow<PlayerUi?>(null)
    val ui: StateFlow<PlayerUi?> = _ui.asStateFlow()

    private val _position = MutableStateFlow(0L)
    /** Playback position, refreshed about ten times a second while playing. */
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _subtitleText = MutableStateFlow<String?>(null)
    val subtitleText: StateFlow<String?> = _subtitleText.asStateFlow()

    private var player: MediaPlayer? = null
    private var prepared = false
    private var surface: Surface? = null
    private var order: List<Int> = emptyList()
    private var nextToken = 1L
    private var loadCounter = 0L
    private var appliedSpeed = 1f
    private var ticker: Job? = null
    private var sidecarJob: Job? = null

    private var seekInFlightSince = 0L
    private var pendingSeek: Pair<Long, Boolean>? = null

    private val parsed = HashMap<String, List<SubtitleTrack>>()
    private var externalFiles: List<File> = emptyList()
    private var embeddedOptions: List<SubtitleSource.Embedded> = emptyList()
    private var activeTrack: SubtitleTrack? = null
    private var embeddedText: String? = null

    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var session: MediaSession? = null
    private var noisyRegistered = false

    val isOpen: Boolean get() = _ui.value != null

    private fun update(transform: (PlayerUi) -> PlayerUi) = _ui.update { it?.let(transform) }

    // ------------------------------------------------------------------ open / close

    /** Plays [queue] starting at [startPath]. */
    fun open(queue: List<PlayItem>, startPath: String) {
        if (queue.isEmpty()) return
        val start = queue.indexOfFirst { it.path == startPath }.coerceAtLeast(0)
        if (_ui.value != null) stopCurrent()
        val shuffle = store.shuffle
        order = if (shuffle) PlayQueue.shuffled(queue.size, start) else queue.indices.toList()
        parsed.clear()
        _ui.value = PlayerUi(
            queue = queue,
            index = start,
            token = nextToken++,
            repeat = store.repeat,
            shuffle = shuffle,
            aspect = store.aspect,
            rotation = store.rotation,
            subtitleScale = store.subtitleScale,
        )
        startSession()
        registerNoisy()
        startTicker()
        load(start)
    }

    fun close() {
        if (_ui.value == null) return
        stopCurrent()
        ticker?.cancel()
        ticker = null
        abandonFocus()
        unregisterNoisy()
        releaseSession()
        _ui.value = null
        _position.value = 0
        _subtitleText.value = null
        parsed.clear()
        activeTrack = null
        embeddedText = null
    }

    /** The file on screen (or the folder it is in) went away. */
    fun onRemoved(paths: Collection<String>) {
        val path = _ui.value?.current?.path ?: return
        if (paths.any { path == it || path.startsWith("$it/") }) close()
    }

    private fun load(index: Int, autoplay: Boolean = true) {
        val s = _ui.value ?: return
        val item = s.queue.getOrNull(index) ?: return
        stopCurrent()
        val id = ++loadCounter
        activeTrack = null
        embeddedText = null
        embeddedOptions = emptyList()
        externalFiles = emptyList()
        _subtitleText.value = null
        _position.value = 0
        _ui.value = s.copy(
            index = index, loadId = id, prepared = false, playing = false, ended = false, error = false,
            firstFrame = false, durationMs = 0, videoW = 0, videoH = 0, boost = false, abA = null, abB = null,
            audioTracks = emptyList(), audioTrack = -1, subtitles = emptyList(), subtitle = null, subtitleDelayMs = 0,
            resumedFrom = null, meta = null,
            canNext = PlayQueue.step(order, index, 1, s.repeat) != null,
            canPrevious = PlayQueue.step(order, index, -1, s.repeat) != null,
        )
        val resumeAt = if (resumeEnabled()) store.position(item.path) else null
        val mp = MediaPlayer()
        player = mp
        prepared = false
        appliedSpeed = 1f
        mp.setAudioAttributes(attributes(item.isVideo))
        mp.setOnPreparedListener { if (player === it) onPrepared(it, autoplay, resumeAt) }
        mp.setOnCompletionListener { if (player === it && prepared) onCompletion() }
        mp.setOnErrorListener { p, _, _ ->
            if (player === p) onError()
            true
        }
        mp.setOnInfoListener { p, what, _ ->
            if (player === p && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) update { it.copy(firstFrame = true) }
            false
        }
        mp.setOnVideoSizeChangedListener { p, w, h ->
            if (player === p && w > 0 && h > 0) update { it.copy(videoW = w, videoH = h) }
        }
        mp.setOnSeekCompleteListener { p -> if (player === p) onSeekComplete() }
        mp.setOnTimedTextListener { p, text ->
            if (player === p) {
                embeddedText = text?.text?.let(Subtitles::cleanMarkup)?.ifBlank { null }
                refreshSubtitle()
            }
        }
        try {
            // USB drives opened through the system picker have no file paths: play through the provider.
            val tree = Stores.tree(item.path)
            if (tree != null) mp.setDataSource(context, tree.documentUri(item.path)) else mp.setDataSource(item.path)
            if (item.isVideo) surface?.let { mp.setSurface(it) }
            mp.prepareAsync()
        } catch (e: Exception) {
            onError()
        }
        loadSidecars(item, id, resumeAt)
        updateSession()
    }

    private fun attributes(video: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(if (video) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** Saves where the current file was left off and frees its MediaPlayer. */
    private fun stopCurrent() {
        saveResume()
        sidecarJob?.cancel()
        val mp = player ?: return
        player = null
        prepared = false
        seekInFlightSince = 0L
        pendingSeek = null
        runCatching { mp.release() }
    }

    private fun onPrepared(mp: MediaPlayer, autoplay: Boolean, resumeAt: Long?) {
        prepared = true
        val duration = runCatching { mp.duration.toLong() }.getOrDefault(0L).coerceAtLeast(0L)
        val item = _ui.value?.current
        val resume = resumeAt?.takeIf {
            item != null && Resume.applies(duration, item.isVideo) && Resume.shouldRemember(it, duration)
        }
        if (resume != null) {
            _position.value = resume
            doSeek(mp, resume, exact = false)
        }
        val tracks = readTracks(mp)
        embeddedOptions = tracks.subtitles
        val w = runCatching { mp.videoWidth }.getOrDefault(0)
        val h = runCatching { mp.videoHeight }.getOrDefault(0)
        update {
            it.copy(
                prepared = true,
                durationMs = duration,
                videoW = if (w > 0 && h > 0) w else it.videoW,
                videoH = if (w > 0 && h > 0) h else it.videoH,
                audioTracks = tracks.audio,
                audioTrack = tracks.selectedAudio,
                resumedFrom = resume,
            )
        }
        rebuildSubtitleOptions()
        refreshSubtitle()
        if (autoplay) play() else updateSession()
    }

    private fun onCompletion() {
        val s = _ui.value ?: return
        val item = s.current ?: return
        store.forget(item.path)
        if (s.sleepAfterItem) {
            finish(s)
            update { it.copy(sleepAfterItem = false) }
            message(R.string.player_sleep_done)
            return
        }
        val next = PlayQueue.afterCompletion(order, s.index, s.repeat)
        when (next) {
            null -> finish(s)
            s.index -> {
                seekTo(0)
                play()
            }
            else -> load(next)
        }
    }

    private fun finish(s: PlayerUi) {
        update { it.copy(playing = false, ended = true, boost = false) }
        _position.value = s.durationMs
        abandonFocus()
        updateSession()
    }

    private fun onError() {
        prepared = false
        update { it.copy(error = true, playing = false, prepared = false, boost = false) }
        abandonFocus()
        updateSession()
    }

    // ------------------------------------------------------------------ transport

    fun play() {
        val mp = player ?: return
        val s = _ui.value ?: return
        if (!prepared || s.error) return
        if (s.ended) {
            _position.value = 0
            doSeek(mp, 0, exact = true)
        }
        if (!requestFocus()) return
        try {
            mp.start()
        } catch (e: IllegalStateException) {
            return
        }
        applySpeed(mp, if (s.boost) BOOST_SPEED else s.speed)
        update { it.copy(playing = true, ended = false) }
        updateSession()
    }

    fun pause() {
        val mp = player
        if (mp != null && prepared) runCatching { if (mp.isPlaying) mp.pause() }
        resumeOnFocusGain = false
        update { it.copy(playing = false, boost = false) }
        saveResume()
        updateSession()
    }

    fun togglePlay() {
        if (_ui.value?.playing == true) pause() else play()
    }

    /** Seeks to [ms]. [exact] lands on that very frame; otherwise the nearest earlier key frame (fast, for scrubbing). */
    fun seekTo(ms: Long, exact: Boolean = true) {
        val mp = player ?: return
        val s = _ui.value ?: return
        if (!prepared) return
        val target = if (s.durationMs > 0) ms.coerceIn(0, s.durationMs) else ms.coerceAtLeast(0)
        _position.value = target
        if (s.ended && target < s.durationMs) update { it.copy(ended = false) }
        refreshSubtitle()
        val busy = seekInFlightSince != 0L && SystemClock.uptimeMillis() - seekInFlightSince < SEEK_TIMEOUT_MS
        if (busy) pendingSeek = target to exact else doSeek(mp, target, exact)
    }

    fun seekBy(deltaMs: Long) = seekTo(_position.value + deltaMs)

    private fun doSeek(mp: MediaPlayer, target: Long, exact: Boolean) {
        seekInFlightSince = SystemClock.uptimeMillis().coerceAtLeast(1L)
        try {
            mp.seekTo(target, if (exact) MediaPlayer.SEEK_CLOSEST else MediaPlayer.SEEK_PREVIOUS_SYNC)
        } catch (e: Exception) {
            seekInFlightSince = 0L
        }
        updateSession()
    }

    private fun onSeekComplete() {
        seekInFlightSince = 0L
        val mp = player ?: return
        val p = pendingSeek
        pendingSeek = null
        if (p != null) {
            doSeek(mp, p.first, p.second)
        } else {
            runCatching { mp.currentPosition.toLong() }.getOrNull()?.let { _position.value = it }
            refreshSubtitle()
        }
    }

    fun next() {
        val s = _ui.value ?: return
        PlayQueue.step(order, s.index, 1, s.repeat)?.let { load(it) }
    }

    /** Music restarts the song when it is past the first seconds; otherwise the previous file. */
    fun previous() {
        val s = _ui.value ?: return
        if (!s.isVideo && s.prepared && _position.value > 5_000) {
            seekTo(0)
            return
        }
        val prev = PlayQueue.step(order, s.index, -1, s.repeat)
        if (prev != null) load(prev) else seekTo(0)
    }

    fun jumpTo(index: Int) {
        val s = _ui.value ?: return
        if (index in s.queue.indices && index != s.index) load(index)
    }

    /** Retry after an error, e.g. once the storage is back. */
    fun reload() {
        val s = _ui.value ?: return
        load(s.index)
    }

    // ------------------------------------------------------------------ playback options

    fun setSpeed(speed: Float) {
        val v = PlaybackSpeed.clamp(speed)
        update { it.copy(speed = v) }
        val s = _ui.value ?: return
        val mp = player ?: return
        if (prepared && s.playing && !s.boost) applySpeed(mp, v)
    }

    /** Fast forward at 2× while a finger is held on the video. */
    fun setBoost(on: Boolean) {
        val s = _ui.value ?: return
        if (on == s.boost || (on && !s.playing)) return
        update { it.copy(boost = on) }
        val mp = player ?: return
        if (prepared && s.playing) applySpeed(mp, if (on) BOOST_SPEED else s.speed)
    }

    /** Only while playing: on a paused MediaPlayer a non-zero speed also starts playback. */
    private fun applySpeed(mp: MediaPlayer, speed: Float) {
        if (speed == appliedSpeed) return
        runCatching {
            mp.playbackParams = PlaybackParams().setSpeed(speed)
            appliedSpeed = speed
        }
    }

    fun cycleRepeat() {
        val r = (_ui.value ?: return).repeat.next()
        store.repeat = r
        update { it.copy(repeat = r) }
        refreshNav()
    }

    fun toggleShuffle() {
        val s = _ui.value ?: return
        val on = !s.shuffle
        store.shuffle = on
        order = if (on) PlayQueue.shuffled(s.queue.size, s.index) else s.queue.indices.toList()
        update { it.copy(shuffle = on) }
        refreshNav()
    }

    private fun refreshNav() = update {
        it.copy(
            canNext = PlayQueue.step(order, it.index, 1, it.repeat) != null,
            canPrevious = PlayQueue.step(order, it.index, -1, it.repeat) != null,
        )
    }

    fun setAspect(mode: AspectMode) {
        store.aspect = mode
        update { it.copy(aspect = mode) }
    }

    fun setRotation(mode: RotationMode) {
        store.rotation = mode
        update { it.copy(rotation = mode) }
    }

    /** First tap marks A, the second marks B and loops between them, the third clears both. */
    fun abRepeat() {
        val s = _ui.value ?: return
        if (!s.prepared) return
        val pos = _position.value
        val a = s.abA
        when {
            a == null -> update { it.copy(abA = pos) }
            s.abB == null -> if (pos > a + AB_MIN_MS) {
                update { it.copy(abB = pos) }
                seekTo(a)
            } else {
                message(R.string.player_ab_too_short)
            }
            else -> clearAb()
        }
    }

    fun clearAb() = update { it.copy(abA = null, abB = null) }

    fun setSleep(minutes: Int) = update {
        it.copy(sleepAt = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else null, sleepAfterItem = false)
    }

    fun sleepAfterCurrent() = update { it.copy(sleepAt = null, sleepAfterItem = true) }

    fun selectAudioTrack(index: Int) {
        val mp = player ?: return
        if (!prepared) return
        runCatching { mp.selectTrack(index) }
            .onSuccess { update { it.copy(audioTrack = index) } }
            .onFailure { message(R.string.player_track_failed) }
    }

    // ------------------------------------------------------------------ subtitles

    fun selectSubtitle(choice: SubtitleSource?) {
        val mp = player
        val current = _ui.value?.subtitle
        if (current is SubtitleSource.Embedded && current.id != choice?.id) runCatching { mp?.deselectTrack(current.index) }
        embeddedText = null
        when (choice) {
            null -> {
                activeTrack = null
                update { it.copy(subtitle = null) }
            }
            is SubtitleSource.Embedded -> {
                if (mp == null || !prepared || runCatching { mp.selectTrack(choice.index) }.isFailure) {
                    message(R.string.player_track_failed)
                    return
                }
                activeTrack = null
                update { it.copy(subtitle = choice) }
            }
            is SubtitleSource.External -> {
                val tracks = parsed[choice.path]
                if (tracks == null) {
                    val loadId = _ui.value?.loadId
                    scope.launch {
                        val loaded = withContext(Dispatchers.IO) { Subtitles.load(File(choice.path)) }
                        if (_ui.value?.loadId != loadId) return@launch
                        parsed[choice.path] = loaded
                        rebuildSubtitleOptions()
                        if (loaded.isEmpty()) message(R.string.player_subtitle_unreadable)
                        else selectSubtitle(choice.copy(label = loaded.getOrNull(choice.track)?.label ?: choice.label))
                    }
                    return
                }
                val track = tracks.getOrNull(choice.track)
                if (track == null) {
                    message(R.string.player_subtitle_unreadable)
                    return
                }
                activeTrack = track
                update { it.copy(subtitle = choice) }
            }
        }
        refreshSubtitle()
    }

    fun setSubtitleDelay(ms: Long) {
        update { it.copy(subtitleDelayMs = ms.coerceIn(-MAX_SUBTITLE_DELAY, MAX_SUBTITLE_DELAY)) }
        refreshSubtitle()
    }

    fun setSubtitleScale(scale: Float) {
        store.subtitleScale = scale
        update { it.copy(subtitleScale = store.subtitleScale) }
    }

    private fun rebuildSubtitleOptions() {
        val external = externalFiles.flatMap { f ->
            val tracks = parsed[f.path]
            if (tracks == null || tracks.size <= 1) listOf(SubtitleSource.External(f.path, 0, f.name))
            else tracks.mapIndexed { i, t -> SubtitleSource.External(f.path, i, t.label) }
        }
        update { it.copy(subtitles = embeddedOptions + external) }
    }

    private fun refreshSubtitle() {
        val s = _ui.value
        val text = when (s?.subtitle) {
            is SubtitleSource.External -> activeTrack?.textAt(_position.value - s.subtitleDelayMs)
            is SubtitleSource.Embedded -> embeddedText
            null -> null
        }
        if (_subtitleText.value != text) _subtitleText.value = text
    }

    /** Tags, artwork and the subtitle files next to the video, read off the main thread. */
    private fun loadSidecars(item: PlayItem, id: Long, resumeAt: Long?) {
        sidecarJob = scope.launch {
            if (item.isVideo && !Stores.isDocument(item.path)) {
                val file = File(item.path)
                val found = withContext(Dispatchers.IO) {
                    val all = Subtitles.findFor(file, file.parentFile?.listFiles()?.toList().orEmpty())
                    val matching = all.filter { Subtitles.matches(file, it) }.take(MAX_EAGER_SUBTITLES)
                    all to matching.associate { it.path to Subtitles.load(it) }
                }
                if (loadCounter != id) return@launch
                parsed.putAll(found.second)
                externalFiles = found.first
                rebuildSubtitleOptions()
                val preferred = Subtitles.preferred(file, found.first, Locale.getDefault().language)
                if (preferred != null && _ui.value?.subtitle == null && !parsed[preferred.path].isNullOrEmpty()) {
                    val label = parsed[preferred.path]?.firstOrNull()?.label ?: preferred.name
                    selectSubtitle(SubtitleSource.External(preferred.path, 0, label))
                }
            }
            val meta = withContext(Dispatchers.IO) { readMeta(item, resumeAt) }
            if (loadCounter != id) return@launch
            update { it.copy(meta = meta) }
            updateSession()
        }
    }

    private fun readMeta(item: PlayItem, resumeAt: Long?): MediaMeta? {
        val r = MediaMetadataRetriever()
        val pfd = Stores.tree(item.path)?.let { it.openFile(item.path) ?: return null }
        return try {
            if (pfd != null) r.setDataSource(pfd.fileDescriptor) else r.setDataSource(item.path)
            fun tag(key: Int) = r.extractMetadata(key)?.trim()?.ifBlank { null }
            val picture = if (item.isVideo) {
                val us = (resumeAt ?: 0L) * 1000
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, POSTER_PX, POSTER_PX)
                } else {
                    r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            } else {
                r.embeddedPicture?.let { decodeArt(it) }
            }
            MediaMeta(
                path = item.path,
                // Video titles are often encoder junk; the file name is what people recognise.
                title = if (item.isVideo) null else tag(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                picture = picture,
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
            runCatching { pfd?.close() }
        }
    }

    private fun decodeArt(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ART_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private data class Tracks(val audio: List<TrackOption>, val selectedAudio: Int, val subtitles: List<SubtitleSource.Embedded>)

    private fun readTracks(mp: MediaPlayer): Tracks {
        val infos = runCatching { mp.trackInfo }.getOrNull() ?: return Tracks(emptyList(), -1, emptyList())
        val audioTracks = ArrayList<TrackOption>()
        val subs = ArrayList<SubtitleSource.Embedded>()
        infos.forEachIndexed { i, t ->
            when (t?.trackType) {
                MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO -> audioTracks += TrackOption(i, trackLabel(t, audioTracks.size + 1))
                MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT -> subs += SubtitleSource.Embedded(i, trackLabel(t, subs.size + 1))
            }
        }
        val selected = runCatching { mp.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) }.getOrDefault(-1)
        return Tracks(audioTracks, selected, subs)
    }

    /** "Track 2 · Korean · AC3". */
    private fun trackLabel(t: MediaPlayer.TrackInfo, n: Int): String {
        val lang = Languages.name(t.language)
        val codec = runCatching { t.format?.getString(MediaFormat.KEY_MIME) }.getOrNull()?.let(::codecName)
        return listOfNotNull(context.getString(R.string.player_track_n, n), lang, codec).joinToString(" · ")
    }

    private fun codecName(mime: String): String? = when (val sub = mime.substringAfter('/').lowercase(Locale.ROOT)) {
        "mp4a-latm", "aac" -> "AAC"
        "ac3" -> "AC3"
        "eac3" -> "E-AC3"
        "mpeg" -> "MP3"
        "raw" -> "PCM"
        "opus", "vorbis", "flac" -> sub.replaceFirstChar { it.uppercase(Locale.ROOT) }
        "dts", "vnd.dts" -> "DTS"
        "3gpp", "x-subrip", "x-quicktime-tx3g" -> null
        else -> sub.uppercase(Locale.ROOT).takeIf { it.length <= 8 }
    }

    // ------------------------------------------------------------------ position ticker

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                tick()
                delay(if (_ui.value?.playing == true) 100L else 300L)
            }
        }
    }

    private fun tick() {
        val mp = player ?: return
        val s = _ui.value ?: return
        if (!prepared || s.error) return
        if (seekInFlightSince == 0L && !s.ended) {
            runCatching { mp.currentPosition.toLong() }.getOrNull()?.let { _position.value = it }
        }
        val pos = _position.value
        val a = s.abA
        val b = s.abB
        if (a != null && b != null && s.playing && pos >= b) seekTo(a)
        val sleepAt = s.sleepAt
        if (sleepAt != null && SystemClock.elapsedRealtime() >= sleepAt) {
            update { it.copy(sleepAt = null) }
            if (s.playing) pause()
            message(R.string.player_sleep_done)
        }
        refreshSubtitle()
    }

    private fun saveResume() {
        val s = _ui.value ?: return
        val item = s.current ?: return
        val mp = player ?: return
        if (!prepared || s.error || !resumeEnabled()) return
        if (s.ended || !Resume.applies(s.durationMs, item.isVideo)) {
            store.forget(item.path)
            return
        }
        val pos = runCatching { mp.currentPosition.toLong() }.getOrNull() ?: return
        if (Resume.shouldRemember(pos, s.durationMs)) store.remember(item.path, pos) else store.forget(item.path)
    }

    /** The Surface the video is drawn on; null while the screen has none (rotation, closing). */
    fun attachSurface(s: Surface?) {
        surface = s
        val mp = player ?: return
        if (_ui.value?.isVideo != true) return
        runCatching { mp.setSurface(s) }
        // A paused video shows nothing on a fresh surface until the next frame: redraw it.
        if (s != null && prepared && _ui.value?.playing == false) seekTo(_position.value)
    }

    fun detachSurface(s: Surface) {
        if (surface === s) attachSurface(null)
    }

    // ------------------------------------------------------------------ audio focus, headphones, media buttons

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val wasPlaying = _ui.value?.playing == true
                pause()
                resumeOnFocusGain = wasPlaying
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> runCatching { player?.setVolume(DUCK_VOLUME, DUCK_VOLUME) }
            AudioManager.AUDIOFOCUS_GAIN -> {
                runCatching { player?.setVolume(1f, 1f) }
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    play()
                }
            }
        }
    }

    private fun requestFocus(): Boolean {
        val am = audio ?: return true
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes(_ui.value?.isVideo == true))
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { focusRequest = it }
        val result = runCatching { am.requestAudioFocus(req) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return result != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        runCatching { audio?.abandonAudioFocusRequest(req) }
        focusRequest = null
        resumeOnFocusGain = false
    }

    /** Headphones unplugged or Bluetooth disconnected: pause instead of blasting the speaker. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        noisyRegistered = runCatching {
            ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
    }

    private fun unregisterNoisy() {
        if (noisyRegistered) runCatching { context.unregisterReceiver(noisy) }
        noisyRegistered = false
    }

    /** Headset and Bluetooth buttons (play/pause, next, previous) while the player is open. */
    private fun startSession() {
        if (session != null) return
        session = runCatching {
            MediaSession(context, "player").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() = play()
                    override fun onPause() = pause()
                    override fun onStop() = pause()
                    override fun onSkipToNext() = next()
                    override fun onSkipToPrevious() = previous()
                    override fun onSeekTo(pos: Long) = seekTo(pos)
                    override fun onFastForward() = seekBy(SEEK_STEP_MS)
                    override fun onRewind() = seekBy(-SEEK_STEP_MS)
                })
                isActive = true
            }
        }.getOrNull()
    }

    private fun updateSession() {
        val ms = session ?: return
        val s = _ui.value ?: return
        runCatching {
            ms.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or
                            PlaybackState.ACTION_REWIND or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                    )
                    .setState(
                        if (s.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        _position.value,
                        if (s.playing) s.speed else 0f,
                    )
                    .build(),
            )
            ms.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, s.meta?.title ?: s.current?.name)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, s.meta?.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, s.meta?.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, s.durationMs)
                    .build(),
            )
        }
    }

    /**
     * Headset buttons only reach the player while the app is on screen (or in picture-in-picture):
     * there is no background playback, so a button press must not start sound behind the user's back.
     */
    fun setForeground(on: Boolean) {
        val ms = session ?: return
        runCatching { ms.isActive = on }
    }

    private fun releaseSession() {
        val ms = session ?: return
        session = null
        runCatching {
            ms.isActive = false
            ms.release()
        }
    }

    companion object {
        const val BOOST_SPEED = 2f
        const val SEEK_STEP_MS = 10_000L
        const val MAX_SUBTITLE_DELAY = 60_000L
        private const val AB_MIN_MS = 500L
        private const val SEEK_TIMEOUT_MS = 1_500L
        private const val DUCK_VOLUME = 0.3f
        private const val POSTER_PX = 1280
        private const val ART_PX = 1024
        private const val MAX_EAGER_SUBTITLES = 6
    }
}

/** ISO 639-2 codes from media files ("kor", "eng") to names in the user's language. */
internal object Languages {
    private val byIso3: Map<String, Locale> by lazy {
        Locale.getISOLanguages().mapNotNull { code ->
            val l = Locale(code)
            runCatching { l.isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { it to l }
        }.toMap()
    }

    fun name(code: String?): String? {
        val c = code?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
        val locale = if (c.length == 3) byIso3[c] ?: alias[c]?.let { Locale(it) } else Locale(c)
        return locale?.displayLanguage?.takeIf { it.isNotBlank() && !it.equals(c, ignoreCase = true) } ?: c
    }

    /** Bibliographic codes that differ from the terminology codes ISO3 lookups use. */
    private val alias = mapOf("chi" to "zh", "fre" to "fr", "ger" to "de", "dut" to "nl", "gre" to "el", "per" to "fa", "cze" to "cs", "rum" to "ro", "slo" to "sk")
}
