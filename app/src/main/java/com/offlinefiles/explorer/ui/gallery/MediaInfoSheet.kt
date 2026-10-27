package com.offlinefiles.explorer.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.offlinefiles.explorer.R
import com.offlinefiles.explorer.core.gallery.MediaDates
import com.offlinefiles.explorer.core.gallery.MediaItem
import com.offlinefiles.explorer.data.MimeTypes
import com.offlinefiles.explorer.ui.components.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Date, resolution, size, camera and exposure of one photo or video. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaInfoSheet(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val details by produceState<MediaDetails?>(null, item.path) {
        value = withContext(Dispatchers.IO) { runCatching { MediaDecode.details(item) }.getOrNull() }
    }
    val d = details
    val rows = buildList {
        add(R.string.props_name to item.name)
        add(R.string.info_date to Format.dateTime(context, item.date))
        val w = d?.width ?: item.width
        val h = d?.height ?: item.height
        if (w > 0 && h > 0) {
            val mp = String.format(Locale.ROOT, "%.1f", w.toLong() * h / 1_000_000.0)
            add(R.string.info_resolution to context.getString(R.string.info_resolution_value, w, h, mp))
        }
        val duration = d?.durationMs ?: item.durationMs
        if (item.isVideo && duration > 0) add(R.string.info_duration to MediaDates.duration(duration))
        val bytes = context.getString(R.string.props_bytes, "%,d".format(item.size))
        add(R.string.props_size to "${Format.size(context, item.size)} ($bytes)")
        add(R.string.props_type to item.mime.ifBlank { MimeTypes.of(item.name) })
        add(R.string.props_location to item.folder)
        d?.camera?.let { add(R.string.info_camera to it) }
        d?.exposure?.let { add(R.string.info_exposure to it) }
        val lat = d?.latitude
        val lon = d?.longitude
        if (lat != null && lon != null) {
            add(R.string.info_gps to String.format(Locale.ROOT, "%.5f, %.5f", lat, lon))
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.action_details), style = MaterialTheme.typography.titleLarge)
            if (d == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    rows.forEach { (label, value) ->
                        Column {
                            Text(
                                stringResource(label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
