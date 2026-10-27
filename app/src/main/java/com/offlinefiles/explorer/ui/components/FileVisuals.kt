package com.offlinefiles.explorer.ui.components

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.offlinefiles.explorer.core.FileItem
import com.offlinefiles.explorer.core.FileKind
import com.offlinefiles.explorer.ui.theme.LocalKindColors

object Format {
    fun size(context: Context, bytes: Long): String = Formatter.formatFileSize(context, bytes)

    fun shortSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun dateTime(context: Context, millis: Long): String =
        if (millis <= 0) "" else DateUtils.formatDateTime(
            context, millis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_NUMERIC_DATE or DateUtils.FORMAT_SHOW_TIME,
        )

    fun date(context: Context, millis: Long): String =
        if (millis <= 0) "" else DateUtils.formatDateTime(
            context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_NUMERIC_DATE,
        )

    /** "Tue, Oct 27, 2026" / "2026년 10월 27일 (화)". */
    fun longDate(context: Context, millis: Long): String =
        if (millis <= 0) "" else DateUtils.formatDateTime(
            context, millis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY,
        )

    fun time(context: Context, millis: Long): String =
        if (millis <= 0) "" else DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)
}

fun FileKind.icon(): ImageVector = when (this) {
    FileKind.FOLDER -> Icons.Filled.Folder
    FileKind.IMAGE -> Icons.Filled.Image
    FileKind.VIDEO -> Icons.Filled.VideoFile
    FileKind.AUDIO -> Icons.Filled.AudioFile
    FileKind.PDF -> Icons.Filled.PictureAsPdf
    FileKind.DOCUMENT -> Icons.Filled.Description
    FileKind.TEXT -> Icons.AutoMirrored.Filled.TextSnippet
    FileKind.ARCHIVE -> Icons.Filled.FolderZip
    FileKind.APK -> Icons.Filled.Android
    FileKind.CODE -> Icons.Filled.Code
    FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
fun rememberThumbnail(item: FileItem, size: Dp, enabled: Boolean): ImageBitmap? {
    if (!enabled || !Thumbnails.supports(item)) return null
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceIn(48, 512)
    val state by produceState(Thumbnails.peek(item, px)?.asImageBitmap(), item.path, item.lastModified, px) {
        if (value == null) value = Thumbnails.load(context, item, px)?.asImageBitmap()
    }
    return state
}

/** Rounded tile with a thumbnail when there is one, otherwise a tinted type icon. */
@Composable
fun FileThumb(
    item: FileItem,
    size: Dp,
    showThumbnail: Boolean,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    corner: Dp = 12.dp,
    locked: Boolean = false,
) {
    val color = LocalKindColors.current.of(item.kind)
    val thumb = rememberThumbnail(item, size, showThumbnail)
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(if (thumb != null) MaterialTheme.colorScheme.surfaceContainerHighest else color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb,
                contentDescription = null,
                contentScale = if (item.kind == FileKind.APK) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.matchParentSize().then(if (item.kind == FileKind.APK) Modifier.padding(size / 8) else Modifier),
            )
            if (item.kind == FileKind.VIDEO) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(3.dp)
                        .size(size * 0.36f)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(size * 0.26f))
                }
            }
        } else {
            Icon(item.kind.icon(), contentDescription = null, tint = color, modifier = Modifier.size(size * 0.56f))
        }
        if (locked) {
            Icon(
                Icons.Filled.Lock, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).size(size * 0.3f),
            )
        }
        if (selected) {
            Box(
                Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(size * 0.5f))
            }
        }
    }
}
