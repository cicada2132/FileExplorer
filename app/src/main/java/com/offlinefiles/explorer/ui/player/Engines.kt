package com.offlinefiles.explorer.ui.player

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.offlinefiles.explorer.data.Stores
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.libvlc.MediaPlayer as VlcPlayer

/** What the player model hears back from an engine. Always called on the main thread. */
interface EngineListener {
    fun onPrepared()
    fun onCompletion()
    fun onError(detail: String)
    fun onFirstFrame()
    fun onVideoSize(width: Int, height: Int)
    fun onSeekComplete()
    fun onTimedText(text: String?)
    fun onTracksChanged()
}

/** A track as the engine numbers it; [mime] only when known. */
data class EngineTrack(val index: Int, val language: String?, val mime: String?, val name: String?)

data class EngineTracks(
    val audio: List<EngineTrack>,
    val selectedAudio: Int,
    val subtitles: List<EngineTrack>,
    /** False when the engine opened the file but found no picture it can show. */
    val hasVideo: Boolean,
)

/**
 * One way of playing a file: the system's MediaPlayer, or libVLC for formats the system can't
 * decode (AVI, WMV, FLV, DivX/Xvid, AC3/DTS...). The player model drives either the same way.
 */
interface Engine {
    val name: String
    fun open(item: PlayItem, attributes: AudioAttributes, surface: Surface?, surfaceW: Int, surfaceH: Int)
    fun setSurface(surface: Surface?, width: Int, height: Int)
    fun setSurfaceSize(width: Int, height: Int)
    fun start()
    fun pause()
    val isPlaying: Boolean
    fun seekTo(ms: Long, exact: Boolean)
    val position: Long
    val duration: Long
    val videoWidth: Int
    val videoHeight: Int
    fun setSpeed(speed: Float): Boolean
    fun setVolume(volume: Float)
    fun tracks(): EngineTracks
    fun selectAudio(index: Int): Boolean
    fun selectSubtitle(index: Int): Boolean
    fun deselectSubtitle(index: Int)
    fun release()
}

/** The framework MediaPlayer: hardware decoders, MP4/MKV/WebM/TS with H.264/H.265/VP9/AV1. */
class SystemEngine(private val context: Context, private val listener: EngineListener) : Engine {
    override val name = "system"
    private val mp = MediaPlayer()

    override fun open(item: PlayItem, attributes: AudioAttributes, surface: Surface?, surfaceW: Int, surfaceH: Int) {
        mp.setAudioAttributes(attributes)
        mp.setOnPreparedListener { listener.onPrepared() }
        mp.setOnCompletionListener { listener.onCompletion() }
        mp.setOnErrorListener { _, what, extra ->
            listener.onError("system what=$what extra=$extra")
            true
        }
        mp.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) listener.onFirstFrame()
            false
        }
        mp.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) listener.onVideoSize(w, h) }
        mp.setOnSeekCompleteListener { listener.onSeekComplete() }
        mp.setOnTimedTextListener { _, text -> listener.onTimedText(text?.text) }
        // USB drives opened through the system picker have no file paths: play through the provider.
        val tree = Stores.tree(item.path)
        if (tree != null) mp.setDataSource(context, tree.documentUri(item.path)) else mp.setDataSource(item.path)
        if (item.isVideo && surface != null) mp.setSurface(surface)
        mp.prepareAsync()
    }

    override fun setSurface(surface: Surface?, width: Int, height: Int) = mp.setSurface(surface)
    override fun setSurfaceSize(width: Int, height: Int) = Unit
    override fun start() = mp.start()
    override fun pause() = mp.pause()
    override val isPlaying: Boolean get() = mp.isPlaying

    override fun seekTo(ms: Long, exact: Boolean) =
        mp.seekTo(ms, if (exact) MediaPlayer.SEEK_CLOSEST else MediaPlayer.SEEK_PREVIOUS_SYNC)

    override val position: Long get() = mp.currentPosition.toLong()
    override val duration: Long get() = mp.duration.toLong()
    override val videoWidth: Int get() = mp.videoWidth
    override val videoHeight: Int get() = mp.videoHeight

    /** Only while playing: on a paused MediaPlayer a non-zero speed also starts playback. */
    override fun setSpeed(speed: Float): Boolean = runCatching { mp.playbackParams = PlaybackParams().setSpeed(speed) }.isSuccess
    override fun setVolume(volume: Float) = mp.setVolume(volume, volume)

    override fun tracks(): EngineTracks {
        val infos = runCatching { mp.trackInfo }.getOrNull() ?: return EngineTracks(emptyList(), -1, emptyList(), hasVideo = true)
        val audio = ArrayList<EngineTrack>()
        val subs = ArrayList<EngineTrack>()
        var video = false
        infos.forEachIndexed { i, t ->
            val mime = runCatching { t?.format?.getString(MediaFormat.KEY_MIME) }.getOrNull()
            when (t?.trackType) {
                MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO -> video = true
                MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO -> audio += EngineTrack(i, t.language, mime, null)
                MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT -> subs += EngineTrack(i, t.language, mime, null)
            }
        }
        val selected = runCatching { mp.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) }.getOrDefault(-1)
        return EngineTracks(audio, selected, subs, hasVideo = video || infos.isEmpty())
    }

    override fun selectAudio(index: Int) = runCatching { mp.selectTrack(index) }.isSuccess
    override fun selectSubtitle(index: Int) = runCatching { mp.selectTrack(index) }.isSuccess
    override fun deselectSubtitle(index: Int) {
        runCatching { mp.deselectTrack(index) }
    }

    override fun release() = mp.release()
}

/**
 * libVLC (VLC's engine with its own FFmpeg decoders). It has no "prepare" step: the file starts
 * muted and is paused at its first frame, which stands in for MediaPlayer's onPrepared.
 * Subtitles inside the file are left off; subtitle files next to the video are drawn by the app.
 */
class VlcEngine private constructor(
    private val context: Context,
    private val listener: EngineListener,
    libVlc: LibVLC,
) : Engine {
    override val name = "vlc"
    private val mp = VlcPlayer(libVlc)
    private val vlc = libVlc
    private val main = Handler(Looper.getMainLooper())
    private var fd: ParcelFileDescriptor? = null
    private var prepared = false
    private var ended = false
    private var pendingSeek: Long? = null
    private var volume = 1f
    private var width = 0
    private var height = 0
    private var surface: Surface? = null
    private var released = false

    private val layout = IVLCVout.OnNewVideoLayoutListener { _, w, h, visibleW, visibleH, sarNum, sarDen ->
        val vw = if (visibleW > 0) visibleW else w
        val vh = if (visibleH > 0) visibleH else h
        val sar = if (sarNum > 0 && sarDen > 0) sarNum.toDouble() / sarDen else 1.0
        val shown = (vw * sar).toInt()
        if (shown > 0 && vh > 0 && (shown != width || vh != height)) {
            width = shown
            height = vh
            listener.onVideoSize(shown, vh)
        }
    }

    override fun open(item: PlayItem, attributes: AudioAttributes, surface: Surface?, surfaceW: Int, surfaceH: Int) {
        val tree = Stores.tree(item.path)
        val media = if (tree != null) {
            val pfd = tree.openFile(item.path) ?: throw java.io.FileNotFoundException(item.path)
            fd = pfd
            Media(vlc, pfd.fileDescriptor)
        } else {
            Media(vlc, item.path)
        }
        media.setHWDecoderEnabled(true, false)
        mp.setMedia(media)
        media.release()
        mp.setEventListener(VlcPlayer.EventListener { e -> if (!released) onEvent(e) })
        if (item.isVideo) setSurface(surface, surfaceW, surfaceH)
        mp.setVolume(0)
        mp.play()
    }

    private fun onEvent(e: VlcPlayer.Event) {
        when (e.type) {
            VlcPlayer.Event.Playing -> {
                ended = false
                if (!prepared) {
                    prepared = true
                    mp.pause()
                    listener.onPrepared()
                }
            }
            VlcPlayer.Event.Vout -> if (e.voutCount > 0) listener.onFirstFrame()
            VlcPlayer.Event.ESAdded, VlcPlayer.Event.ESDeleted -> if (prepared) listener.onTracksChanged()
            VlcPlayer.Event.EndReached -> {
                ended = true
                listener.onCompletion()
            }
            VlcPlayer.Event.EncounteredError -> listener.onError("vlc error")
        }
    }

    override fun setSurface(surface: Surface?, width: Int, height: Int) {
        val vout = mp.vlcVout
        if (vout.areViewsAttached()) vout.detachViews()
        this.surface = surface
        if (surface == null) return
        vout.setVideoSurface(surface, null)
        if (width > 0 && height > 0) vout.setWindowSize(width, height)
        vout.attachViews(layout)
        mp.setVideoTrackEnabled(true)
    }

    override fun setSurfaceSize(width: Int, height: Int) {
        if (surface != null && width > 0 && height > 0) mp.vlcVout.setWindowSize(width, height)
    }

    override fun start() {
        if (ended) {
            ended = false
            mp.stop()
            mp.play()
            mp.setTime(pendingSeek ?: 0L)
            pendingSeek = null
        } else {
            mp.play()
        }
        mp.setVolume((volume * 100).toInt())
    }

    override fun pause() {
        if (mp.isPlaying) mp.pause()
    }

    override val isPlaying: Boolean get() = mp.isPlaying

    override fun seekTo(ms: Long, exact: Boolean) {
        if (ended) pendingSeek = ms else mp.setTime(ms, !exact)
        // VLC seeks without telling when it's done; the model only needs to know it may seek again.
        main.post { if (!released) listener.onSeekComplete() }
    }

    override val position: Long get() = if (ended) duration else mp.time.coerceAtLeast(0L)
    override val duration: Long get() = mp.length.coerceAtLeast(0L)
    override val videoWidth: Int get() = width
    override val videoHeight: Int get() = height

    override fun setSpeed(speed: Float): Boolean {
        mp.setRate(speed)
        return true
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        if (prepared && mp.isPlaying) mp.setVolume((volume * 100).toInt())
    }

    override fun tracks(): EngineTracks {
        val audio = mp.audioTracks.orEmpty().filter { it.id >= 0 }.map { EngineTrack(it.id, null, null, it.name) }
        return EngineTracks(audio, mp.audioTrack, emptyList(), hasVideo = true)
    }

    override fun selectAudio(index: Int) = mp.setAudioTrack(index)
    override fun selectSubtitle(index: Int) = false
    override fun deselectSubtitle(index: Int) = Unit

    override fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        runCatching { mp.setEventListener(null) }
        runCatching { if (mp.vlcVout.areViewsAttached()) mp.vlcVout.detachViews() }
        runCatching { mp.stop() }
        runCatching { mp.release() }
        runCatching { fd?.close() }
        fd = null
    }

    companion object {
        @Volatile
        private var shared: LibVLC? = null

        @Volatile
        private var broken = false

        /** False where the native library can't load (an unsupported CPU, or unit tests on a PC). */
        fun available(context: Context): Boolean = libVlc(context) != null

        private fun libVlc(context: Context): LibVLC? {
            if (broken) return null
            shared?.let { return it }
            return synchronized(this) {
                shared ?: try {
                    // LibVLC exits the whole process when its native code won't load; load it here
                    // first, where a failure only means "no VLC".
                    System.loadLibrary("c++_shared")
                    System.loadLibrary("vlc")
                    System.loadLibrary("vlcjni")
                    LibVLC(context.applicationContext, OPTIONS).also { shared = it }
                } catch (e: Throwable) {
                    broken = true
                    null
                }
            }
        }

        fun create(context: Context, listener: EngineListener): VlcEngine? {
            val lib = libVlc(context) ?: return null
            return try {
                VlcEngine(context, listener, lib)
            } catch (e: Throwable) {
                null
            }
        }

        private val OPTIONS = arrayListOf(
            // Keeps the pitch when playing faster or slower.
            "--audio-time-stretch",
            // The app draws subtitle files itself; VLC must not add its own on top.
            "--no-sub-autodetect-file",
            "--no-spu",
            "--no-stats",
        )
    }
}
