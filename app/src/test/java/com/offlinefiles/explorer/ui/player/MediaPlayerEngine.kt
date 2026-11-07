package com.offlinefiles.explorer.ui.player

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.view.Surface
import com.offlinefiles.explorer.data.Stores

/**
 * Test stand-in for VLC: Android's MediaPlayer, which Robolectric replaces with ShadowMediaPlayer
 * (prepares, seeks and reports lengths, decodes nothing). The app itself only uses VLC.
 */
class MediaPlayerEngine(private val context: Context, private val listener: EngineListener) : Engine {
    override val name = "test"
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
