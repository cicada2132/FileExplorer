package com.offlinefiles.explorer.ui.player

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.offlinefiles.explorer.core.player.DecoderChoice
import com.offlinefiles.explorer.data.Stores
import com.offlinefiles.explorer.data.UsbDiagnostics
import org.videolan.libvlc.Dialog
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
 * What plays a file. The app plays everything with libVLC ([VlcEngine]); Android's own
 * MediaPlayer is not used. The interface exists so unit tests can stand in for VLC, whose
 * native code can't run on a PC (see [Engines.factory]).
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

    /** A transparent surface over the video for subtitles the engine draws itself (VLC). */
    fun setSubtitleSurface(surface: Surface?) = Unit

    /** Positive: sound later. False where the engine can't. */
    fun setAudioDelay(ms: Long): Boolean = false

    /** For subtitle tracks inside the file that the engine draws itself. */
    fun setSubtitleDelay(ms: Long): Boolean = false

    /** Decoding with the phone's hardware (MediaCodec) rather than in software. */
    val hardware: Boolean get() = false

    /** The video codec as a four-character code ("h264", "mp4v"...), once known. */
    fun videoCodec(): String? = null
}

/**
 * libVLC (VLC's engine with its own FFmpeg decoders). It has no "prepare" step: the file starts
 * muted and is paused at its first frame, which stands in for MediaPlayer's onPrepared.
 * Subtitle tracks inside the file are drawn by VLC on [setSubtitleSurface] (off until chosen);
 * subtitle files next to the video are drawn by the app. [hardware]: MediaCodec where it can,
 * otherwise VLC's own (software) decoders for everything.
 */
class VlcEngine private constructor(
    private val context: Context,
    private val listener: EngineListener,
    libVlc: LibVLC,
    override val hardware: Boolean,
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
    private var subtitleSurface: Surface? = null
    private var winW = 0
    private var winH = 0
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
        // Hardware where it helps (MediaCodec, then VLC's own decoders if it can't), or software only.
        media.setHWDecoderEnabled(hardware, false)
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
                    // Subtitle tracks stay off until chosen, as with Android's player.
                    mp.setSpuTrack(-1)
                    listener.onPrepared()
                }
            }
            VlcPlayer.Event.Vout -> if (e.voutCount > 0) listener.onFirstFrame()
            VlcPlayer.Event.ESAdded, VlcPlayer.Event.ESDeleted -> if (prepared) listener.onTracksChanged()
            VlcPlayer.Event.EndReached -> {
                ended = true
                listener.onCompletion()
            }
            VlcPlayer.Event.EncounteredError -> listener.onError("vlc error" + (takeLastMessage()?.let { ": $it" } ?: ""))
        }
    }

    override fun setSurface(surface: Surface?, width: Int, height: Int) {
        this.surface = surface
        if (width > 0 && height > 0) {
            winW = width
            winH = height
        }
        attach()
    }

    override fun setSubtitleSurface(surface: Surface?) {
        if (surface === subtitleSurface) return
        subtitleSurface = surface
        // Re-attaching while VLC sets up or draws its video can crash it: a new subtitle surface
        // is taken at the next attach (the screen gives it before VLC starts, so this is rare).
        if (this.surface != null && !mp.vlcVout.areViewsAttached()) attach()
    }

    /** VLC takes its surfaces only while detached. */
    private fun attach() {
        val vout = mp.vlcVout
        if (vout.areViewsAttached()) vout.detachViews()
        val video = surface ?: return
        vout.setVideoSurface(video, null)
        subtitleSurface?.let { vout.setSubtitlesSurface(it, null) }
        if (winW > 0 && winH > 0) vout.setWindowSize(winW, winH)
        vout.attachViews(layout)
        mp.setVideoTrackEnabled(true)
    }

    override fun setSurfaceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        winW = width
        winH = height
        if (surface != null) mp.vlcVout.setWindowSize(width, height)
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
        val subs = mp.spuTracks.orEmpty().filter { it.id >= 0 }.map { EngineTrack(it.id, null, null, it.name) }
        return EngineTracks(audio, mp.audioTrack, subs, hasVideo = true)
    }

    override fun selectAudio(index: Int) = mp.setAudioTrack(index)
    override fun selectSubtitle(index: Int) = mp.setSpuTrack(index)
    override fun deselectSubtitle(index: Int) {
        mp.setSpuTrack(-1)
    }

    override fun videoCodec(): String? =
        runCatching { mp.currentVideoTrack }.getOrNull()?.let { DecoderChoice.fourccName(it.fourcc) }

    override fun setAudioDelay(ms: Long) = mp.setAudioDelay(ms * 1000)
    override fun setSubtitleDelay(ms: Long) = mp.setSpuDelay(ms * 1000)

    override fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        runCatching { mp.setEventListener(null) }
        // Stop first, then take the surfaces away: VLC's video thread must not draw on a surface that's gone.
        runCatching { mp.stop() }
        runCatching { if (mp.vlcVout.areViewsAttached()) mp.vlcVout.detachViews() }
        runCatching { mp.release() }
        runCatching { fd?.close() }
        fd = null
    }

    companion object {
        @Volatile
        private var shared: LibVLC? = null

        @Volatile
        private var broken = false

        /** False only where the native library can't load (an unsupported CPU, or unit tests on a PC). */
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
                    val app = context.applicationContext
                    LibVLC(app, options()).also {
                        answerDialogs(app, it)
                        shared = it
                    }
                } catch (e: Throwable) {
                    broken = true
                    null
                }
            }
        }

        /**
         * VLC for Android's own defaults (the setup most phones run VLC with), plus answers to the
         * questions VLC would otherwise ask in a dialog.
         */
        private fun options(): ArrayList<String> = arrayListOf(
            // Keeps the pitch when playing faster or slower.
            "--audio-time-stretch",
            // Software decoding: skip the deblocking filter only on non-reference frames on multi-core
            // phones (on all but key frames on slow ones), as VLC for Android does.
            "--avcodec-skiploopfilter", if (Runtime.getRuntime().availableProcessors() > 2) "1" else "3",
            "--avcodec-skip-frame", "0",
            "--avcodec-skip-idct", "0",
            // Software-decoded video is drawn as 32-bit RGB (16-bit shows banding).
            "--android-display-chroma", "RV32",
            // AVI files with a broken or missing index (common in downloads): build one in memory
            // instead of asking "Do not play / Build index / Play as is" in a dialog.
            "--avi-index=3",
            // The app draws subtitle files itself; VLC must not load them a second time.
            "--no-sub-autodetect-file",
            "--no-stats",
        )

        @Volatile
        private var lastMessage: String? = null

        /** The last error VLC reported in a dialog (e.g. "VLC could not decode the format..."), once. */
        fun takeLastMessage(): String? = lastMessage.also { lastMessage = null }

        /**
         * There is nobody to answer VLC's dialogs: questions take their first action (the one that
         * keeps playing), log-ins are declined, and errors go to the diagnostics log.
         */
        private fun answerDialogs(context: Context, lib: LibVLC) {
            Dialog.setCallbacks(lib, object : Dialog.Callbacks {
                override fun onDisplay(dialog: Dialog.ErrorMessage) {
                    val text = listOfNotNull(dialog.title, dialog.text).joinToString(": ")
                    lastMessage = text
                    UsbDiagnostics.log(context, "vlc error dialog: $text")
                }

                override fun onDisplay(dialog: Dialog.LoginDialog) {
                    runCatching { dialog.dismiss() }
                }

                override fun onDisplay(dialog: Dialog.QuestionDialog) {
                    UsbDiagnostics.log(context, "vlc question: ${dialog.title}: ${dialog.text} → ${dialog.action1Text}")
                    runCatching { dialog.postAction(1) }
                }

                override fun onDisplay(dialog: Dialog.ProgressDialog) = Unit
                override fun onCanceled(dialog: Dialog?) = Unit
                override fun onProgressUpdate(dialog: Dialog.ProgressDialog) = Unit
            })
        }

        fun create(context: Context, listener: EngineListener, hardware: Boolean): VlcEngine? {
            val lib = libVlc(context) ?: return null
            return try {
                VlcEngine(context, listener, lib, hardware)
            } catch (e: Throwable) {
                null
            }
        }
    }
}

/** Where the player and the photo viewer get their engine from. */
object Engines {
    /** Always VLC in the app. Unit tests replace it with a stand-in (VLC's native code can't load on a PC). */
    @Volatile
    var factory: (context: Context, listener: EngineListener, hardware: Boolean) -> Engine? =
        { context, listener, hardware -> VlcEngine.create(context, listener, hardware) }

    /** Null when VLC can't run on this device. */
    fun create(context: Context, listener: EngineListener, hardware: Boolean): Engine? = factory(context, listener, hardware)
}
