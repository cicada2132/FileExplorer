package com.offlinefiles.explorer.ui.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.offlinefiles.explorer.R
import com.offlinefiles.explorer.core.player.RotationMode
import kotlin.math.roundToInt

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Everything the player changes outside its own drawing: screen kept on while playing, system
 * bars hidden with the controls, volume keys driving media volume, the orientation the video
 * wants, and pausing when the app leaves the screen. All of it is undone when the player closes.
 */
@Composable
fun PlayerSystemEffects(player: PlayerModel, ui: PlayerUi, barsVisible: Boolean, inPip: Boolean) {
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }

    DisposableEffect(view, ui.playing) {
        view.keepScreenOn = ui.playing
        onDispose { view.keepScreenOn = false }
    }

    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val oldStream = activity?.volumeControlStream
        activity?.volumeControlStream = AudioManager.STREAM_MUSIC
        val oldOrientation = activity?.requestedOrientation
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (activity != null) {
                oldStream?.let { activity.volumeControlStream = it }
                oldOrientation?.let { runCatching { activity.requestedOrientation = it } }
                Brightness.reset(activity)
            }
        }
    }

    val showBars = barsVisible || !ui.isVideo
    LaunchedEffect(activity, showBars, inPip) {
        val window = activity?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(window, view)
        if (showBars && !inPip) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
    }

    val wanted = desiredOrientation(ui)
    LaunchedEffect(activity, wanted) {
        if (activity != null && wanted != null && activity.requestedOrientation != wanted) {
            runCatching { activity.requestedOrientation = wanted }
        }
    }

    // Leaving the app pauses; closing the picture-in-picture window ends playback (rotation does neither).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pip by rememberUpdatedState(inPip)
    DisposableEffect(lifecycle, activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> player.setForeground(true)
                Lifecycle.Event.ON_STOP -> if (activity?.isChangingConfigurations != true) {
                    player.setForeground(false)
                    if (pip) player.close() else player.pause()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/** Null keeps whatever orientation is set (the size of the video isn't known yet, or it's music). */
private fun desiredOrientation(ui: PlayerUi): Int? {
    if (!ui.isVideo) return null
    return when (ui.rotation) {
        RotationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        RotationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        RotationMode.AUTO -> when {
            ui.videoW <= 0 || ui.videoH <= 0 -> null
            ui.videoW > ui.videoH -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

/** Window brightness for the left-edge swipe; the system setting itself is never touched. */
object Brightness {
    fun get(activity: Activity): Float {
        val own = activity.window.attributes.screenBrightness
        if (own >= 0f) return own
        val system = runCatching { Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
        return (system?.div(255f) ?: 0.5f).coerceIn(0f, 1f)
    }

    fun set(activity: Activity, value: Float) {
        val lp = activity.window.attributes
        lp.screenBrightness = value.coerceIn(0.01f, 1f)
        activity.window.attributes = lp
    }

    fun reset(activity: Activity) {
        val lp = activity.window.attributes
        if (lp.screenBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) return
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = lp
    }
}

/** Media volume for the right-edge swipe, as a 0–1 fraction. */
class Volume(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val max = runCatching { am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull()?.takeIf { it > 0 } ?: 15

    fun get(): Float = (runCatching { am?.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: 0) / max.toFloat()

    fun set(fraction: Float) {
        val index = (fraction.coerceIn(0f, 1f) * max).roundToInt()
        runCatching { am?.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
    }
}

// ------------------------------------------------------------------ picture-in-picture

object Pip {
    private const val ACTION = "com.offlinefiles.explorer.PLAYER_PIP"
    private const val EXTRA = "control"
    private const val PLAY_PAUSE = 1
    private const val NEXT = 2
    private const val PREVIOUS = 3

    fun supported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun enter(activity: Activity, ui: PlayerUi): Boolean =
        supported(activity) && runCatching { activity.enterPictureInPictureMode(params(activity, ui)) }.getOrDefault(false)

    fun params(context: Context, ui: PlayerUi): PictureInPictureParams {
        // Android only accepts ratios between 1:2.39 and 2.39:1.
        val ratio = if (ui.videoW > 0 && ui.videoH > 0) (ui.videoW.toFloat() / ui.videoH).coerceIn(0.42f, 2.38f) else 16f / 9f
        val actions = buildList {
            if (ui.queue.size > 1) add(action(context, PREVIOUS, R.drawable.ic_pip_previous, R.string.player_previous))
            if (ui.playing) add(action(context, PLAY_PAUSE, R.drawable.ic_pip_pause, R.string.action_pause))
            else add(action(context, PLAY_PAUSE, R.drawable.ic_pip_play, R.string.action_play))
            if (ui.queue.size > 1) add(action(context, NEXT, R.drawable.ic_pip_next, R.string.player_next))
        }
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).roundToInt(), 1000))
            .setActions(actions)
            .build()
    }

    private fun action(context: Context, code: Int, icon: Int, title: Int): RemoteAction {
        val intent = Intent(ACTION).setPackage(context.packageName).putExtra(EXTRA, code)
        val pending = PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val label = context.getString(title)
        return RemoteAction(Icon.createWithResource(context, icon), label, label, pending)
    }

    /** Whether the activity is in picture-in-picture mode, and the PiP window's buttons kept in sync. */
    @Composable
    fun rememberMode(player: PlayerModel, ui: PlayerUi): Boolean {
        val view = LocalView.current
        val activity = remember(view) { view.context.findActivity() as? ComponentActivity }
        var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
        DisposableEffect(activity) {
            val listener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
            activity?.addOnPictureInPictureModeChangedListener(listener)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.getIntExtra(EXTRA, 0)) {
                        PLAY_PAUSE -> player.togglePlay()
                        NEXT -> player.next()
                        PREVIOUS -> player.previous()
                    }
                }
            }
            val registered = activity != null && runCatching {
                ContextCompat.registerReceiver(activity, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
            }.isSuccess
            onDispose {
                activity?.removeOnPictureInPictureModeChangedListener(listener)
                if (registered) runCatching { activity?.unregisterReceiver(receiver) }
            }
        }
        LaunchedEffect(inPip, ui.playing, ui.videoW, ui.videoH, ui.queue.size) {
            if (inPip && activity != null) runCatching { activity.setPictureInPictureParams(params(activity, ui)) }
        }
        return inPip
    }
}
