package com.offlinefiles.explorer.ui.player

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offlinefiles.explorer.R
import com.offlinefiles.explorer.core.player.PlaybackSpeed
import com.offlinefiles.explorer.core.player.PlayerTime
import com.offlinefiles.explorer.core.player.RepeatMode
import com.offlinefiles.explorer.data.MimeTypes
import com.offlinefiles.explorer.data.PlayerStore
import com.offlinefiles.explorer.data.Stores
import com.offlinefiles.explorer.ui.MainViewModel
import com.offlinefiles.explorer.ui.components.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** Side sheets of the player (bottom sheets in portrait). */
enum class Panel { MORE, SPEED, SUBTITLES, AUDIO, SLEEP, QUEUE, INFO }

private val PanelColor = Color(0xFF181A1F)

@Composable
fun BoxScope.PlayerPanels(vm: MainViewModel, s: PlayerUi, panel: Panel, onPanel: (Panel?) -> Unit, onNote: (String) -> Unit) {
    val p = vm.player
    val context = LocalContext.current
    val view = LocalView.current
    val close = { onPanel(null) }
    when (panel) {
        Panel.MORE -> Sheet(stringResource(R.string.action_more), close) {
            ActionRow(Icons.Filled.Speed, stringResource(R.string.player_speed), PlaybackSpeed.label(s.speed)) { onPanel(Panel.SPEED) }
            ActionRow(
                if (s.repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                stringResource(R.string.player_repeat),
                repeatLabel(s.repeat),
            ) { p.cycleRepeat() }
            if (s.queue.size > 1) {
                ActionRow(Icons.Filled.Shuffle, stringResource(R.string.player_shuffle), stringResource(if (s.shuffle) R.string.player_on else R.string.player_off)) {
                    p.toggleShuffle()
                }
            }
            ActionRow(
                Icons.Filled.Repeat,
                stringResource(R.string.player_ab),
                stringResource(
                    when {
                        s.abA == null -> R.string.player_ab_set_a
                        s.abB == null -> R.string.player_ab_set_b
                        else -> R.string.player_ab_clear
                    },
                ),
                enabled = s.prepared,
            ) {
                val note = when {
                    s.abA == null -> R.string.player_ab_a_marked
                    s.abB == null -> R.string.player_ab_b_marked
                    else -> R.string.player_ab_cleared
                }
                p.abRepeat()
                close()
                val after = p.ui.value
                if (after != null && (after.abA != s.abA || after.abB != s.abB)) onNote(context.getString(note))
            }
            ActionRow(Icons.Filled.Timer, stringResource(R.string.player_sleep), sleepLabel(s)) { onPanel(Panel.SLEEP) }
            if (s.isVideo && Pip.supported(context)) {
                ActionRow(Icons.Filled.PictureInPictureAlt, stringResource(R.string.player_pip), null, enabled = s.prepared) {
                    close()
                    view.context.findActivity()?.let { Pip.enter(it, s) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Color.White.copy(alpha = 0.12f))
            val item = s.current
            if (item != null) {
                ActionRow(Icons.Filled.Info, stringResource(R.string.action_details), null) { onPanel(Panel.INFO) }
                ActionRow(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.player_open_other), null) {
                    p.pause()
                    close()
                    vm.openWith(item.path)
                }
                ActionRow(Icons.Filled.Share, stringResource(R.string.action_share), null) {
                    p.pause()
                    close()
                    vm.share(listOf(item.path))
                }
                ActionRow(Icons.Filled.FolderOpen, stringResource(R.string.action_show_in_folder), null) {
                    close()
                    p.close()
                    vm.showInFolder(item.path)
                }
            }
        }

        Panel.SPEED -> Sheet(stringResource(R.string.player_speed), close) {
            Text(
                PlaybackSpeed.label(s.speed),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 8.dp),
            )
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { p.setSpeed(s.speed - 0.05f) }) { Icon(Icons.Filled.Remove, "−0.05") }
                Slider(
                    value = s.speed,
                    onValueChange = { p.setSpeed(it) },
                    valueRange = PlaybackSpeed.MIN..PlaybackSpeed.MAX,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { p.setSpeed(s.speed + 0.05f) }) { Icon(Icons.Filled.Add, "+0.05") }
            }
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (preset in PlaybackSpeed.PRESETS) {
                    FilterChip(
                        selected = s.speed == preset,
                        onClick = { p.setSpeed(preset) },
                        label = { Text(PlaybackSpeed.label(preset)) },
                        colors = FilterChipDefaults.filterChipColors(labelColor = Color.White),
                    )
                }
            }
            Hint(stringResource(R.string.player_speed_hint))
        }

        Panel.SUBTITLES -> Sheet(stringResource(R.string.player_subtitles), close) {
            Choice(stringResource(R.string.player_off), selected = s.subtitle == null) { p.selectSubtitle(null) }
            for (option in s.subtitles) {
                val label = when (option) {
                    is SubtitleSource.Embedded -> stringResource(R.string.player_subtitle_embedded, option.label)
                    is SubtitleSource.External -> option.label
                }
                Choice(label, selected = s.subtitle?.id == option.id) { p.selectSubtitle(option) }
            }
            if (s.subtitles.isEmpty()) Hint(stringResource(R.string.player_subtitle_none))
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.12f))
            Label(stringResource(R.string.player_subtitle_size))
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = s.subtitleScale,
                    onValueChange = { p.setSubtitleScale((it * 10).roundToInt() / 10f) },
                    valueRange = PlayerStore.MIN_SUBTITLE_SCALE..PlayerStore.MAX_SUBTITLE_SCALE,
                    modifier = Modifier.weight(1f),
                )
                Text("${(s.subtitleScale * 100).roundToInt()}%", modifier = Modifier.widthIn(min = 52.dp).padding(start = 8.dp))
            }
            if (s.subtitle is SubtitleSource.External) {
                Label(stringResource(R.string.player_subtitle_sync))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = { p.setSubtitleDelay(s.subtitleDelayMs - 1000) }) { Text("−1") }
                    TextButton(onClick = { p.setSubtitleDelay(s.subtitleDelayMs - 100) }) { Text("−0.1") }
                    Text(
                        stringResource(R.string.player_delay_value, String.format(Locale.ROOT, "%+.1f", s.subtitleDelayMs / 1000f)),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.clickable { p.setSubtitleDelay(0) },
                    )
                    TextButton(onClick = { p.setSubtitleDelay(s.subtitleDelayMs + 100) }) { Text("+0.1") }
                    TextButton(onClick = { p.setSubtitleDelay(s.subtitleDelayMs + 1000) }) { Text("+1") }
                }
                Hint(stringResource(R.string.player_subtitle_sync_hint))
            }
        }

        Panel.AUDIO -> Sheet(stringResource(R.string.player_audio), close) {
            for (t in s.audioTracks) {
                Choice(t.label, selected = s.audioTrack == t.index) { p.selectAudioTrack(t.index) }
            }
            if (s.audioTracks.size <= 1) Hint(stringResource(R.string.player_single_audio))
        }

        Panel.SLEEP -> Sheet(stringResource(R.string.player_sleep), close) {
            Choice(stringResource(R.string.player_off), selected = s.sleepAt == null && !s.sleepAfterItem) {
                p.setSleep(0)
                close()
            }
            for (minutes in intArrayOf(15, 30, 45, 60, 90)) {
                Choice(pluralStringResource(R.plurals.player_minutes, minutes, minutes), selected = false) {
                    p.setSleep(minutes)
                    close()
                    onNote(context.resources.getQuantityString(R.plurals.player_minutes, minutes, minutes))
                }
            }
            Choice(stringResource(R.string.player_sleep_end_of_file), selected = s.sleepAfterItem) {
                p.sleepAfterCurrent()
                close()
            }
            if (s.sleepAt != null) Hint(stringResource(R.string.player_sleep_remaining, sleepLabel(s)))
        }

        Panel.QUEUE -> Sheet(stringResource(R.string.player_queue) + " · ${s.queue.size}", close, scroll = false) {
            val list = rememberLazyListState(initialFirstVisibleItemIndex = (s.index - 2).coerceAtLeast(0))
            LazyColumn(state = list) {
                itemsIndexed(s.queue, key = { _, it -> it.path }) { i, item ->
                    val now = i == s.index
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { p.jumpTo(i) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(36.dp)) {
                            if (now) Icon(Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                            else Text("${i + 1}", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelLarge)
                        }
                        Text(
                            item.name,
                            color = if (now) MaterialTheme.colorScheme.primary else Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    }
                }
            }
        }

        Panel.INFO -> Sheet(stringResource(R.string.action_details), close) {
            val item = s.current ?: return@Sheet
            val size by produceState<Long?>(null, item.path) { value = withContext(Dispatchers.IO) { Stores.tree(item.path)?.stat(item.path)?.size ?: File(item.path).length() } }
            Info(stringResource(R.string.props_name), item.name)
            Info(stringResource(R.string.props_location), File(item.path).parent.orEmpty())
            size?.let { Info(stringResource(R.string.props_size), Format.size(context, it)) }
            Info(stringResource(R.string.player_info_format), MimeTypes.of(item.name))
            if (s.durationMs > 0) Info(stringResource(R.string.info_duration), PlayerTime.format(s.durationMs))
            if (s.videoW > 0 && s.videoH > 0) Info(stringResource(R.string.info_resolution), "${s.videoW} × ${s.videoH}")
            if (s.audioTracks.isNotEmpty()) Info(stringResource(R.string.player_audio), s.audioTracks.joinToString("\n") { it.label })
            s.meta?.artist?.let { Info(stringResource(R.string.player_info_artist), it) }
            s.meta?.album?.let { Info(stringResource(R.string.player_info_album), it) }
        }
    }
}

// ------------------------------------------------------------------ building blocks

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.Sheet(title: String, onDismiss: () -> Unit, scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    )
    BoxWithConstraints(Modifier.matchParentSize()) {
        val landscape = maxWidth > maxHeight
        val shape = if (landscape) RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp) else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        val placement = if (landscape) {
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(380.dp)
        } else {
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.75f)
        }
        Surface(
            modifier = placement.pointerInput(Unit) { detectTapGestures { } },
            shape = shape,
            color = PanelColor,
            contentColor = Color.White,
        ) {
            val insets = if (landscape) {
                WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.navigationBarsIgnoringVisibility).union(WindowInsets.displayCutout)
            } else {
                WindowInsets.navigationBarsIgnoringVisibility
            }
            Column(
                Modifier
                    .windowInsetsPadding(insets)
                    .padding(top = 8.dp, bottom = 12.dp)
                    .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                content()
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, value: String?, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.4f)
        Icon(icon, null, tint = tint.copy(alpha = if (enabled) 0.85f else 0.4f))
        Spacer(Modifier.width(20.dp))
        Text(label, color = tint, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Color.White.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
    )
}

@Composable
private fun Info(label: String, value: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun repeatLabel(mode: RepeatMode): String = stringResource(
    when (mode) {
        RepeatMode.OFF -> R.string.player_off
        RepeatMode.ONE -> R.string.repeat_one
        RepeatMode.ALL -> R.string.repeat_all
    },
)

@Composable
private fun sleepLabel(s: PlayerUi): String {
    if (s.sleepAfterItem) return stringResource(R.string.player_sleep_end_short)
    val at = s.sleepAt ?: return stringResource(R.string.player_off)
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(at) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    return PlayerTime.format((at - now).coerceAtLeast(0))
}
