package com.offlinefiles.explorer.ui.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.offlinefiles.explorer.R
import com.offlinefiles.explorer.core.FileItem
import com.offlinefiles.explorer.ui.components.FileThumb
import com.offlinefiles.explorer.ui.components.FolderCounts
import com.offlinefiles.explorer.ui.components.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
private fun rowBackground(selected: Boolean, highlighted: Boolean): Color {
    val target = when {
        selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        highlighted -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
        else -> Color.Transparent
    }
    return animateColorAsState(target, label = "row").value
}

@Composable
fun folderCountText(item: FileItem, enabled: Boolean, includeHidden: Boolean): String? {
    if (!enabled) return null
    val count by produceState(FolderCounts.peek(item, includeHidden), item.path, item.lastModified, includeHidden) {
        if (value == null) value = FolderCounts.load(item, includeHidden)
    }
    return count?.let { pluralStringResource(R.plurals.item_count, it, it) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    item: FileItem,
    selected: Boolean,
    selecting: Boolean,
    highlighted: Boolean,
    showThumbnail: Boolean,
    showFolderCount: Boolean,
    includeHidden: Boolean,
    location: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
) {
    val context = LocalContext.current
    val detail = if (item.isDirectory) {
        listOfNotNull(folderCountText(item, showFolderCount, includeHidden), Format.date(context, item.lastModified).ifEmpty { null })
    } else {
        listOf(Format.size(context, item.size), Format.date(context, item.lastModified))
    }.joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .background(rowBackground(selected, highlighted))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumb(item, size = 46.dp, showThumbnail = showThumbnail, selected = selected, modifier = Modifier.alpha(if (item.isHidden) 0.6f else 1f))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                color = if (item.isHidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (location != null) "$location  ·  $detail" else detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        } else {
            IconButton(onClick = onMore) {
                Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileCell(
    item: FileItem,
    selected: Boolean,
    selecting: Boolean,
    highlighted: Boolean,
    showThumbnail: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(rowBackground(selected, highlighted))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
            FileThumb(item, size = maxWidth, showThumbnail = showThumbnail, corner = 14.dp)
            if (selecting) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.height(34.dp),
        )
    }
}

// ------------------------------------------------------------------ fast scroller

@Composable
fun FastScroller(state: LazyListState, total: Int, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    FastScrollerImpl(
        total = total,
        first = { state.firstVisibleItemIndex },
        visible = { state.layoutInfo.visibleItemsInfo.size },
        scrolling = state.isScrollInProgress,
        scrollTo = { scope.launch { state.scrollToItem(it) } },
        modifier = modifier,
    )
}

@Composable
fun FastScroller(state: LazyGridState, total: Int, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    FastScrollerImpl(
        total = total,
        first = { state.firstVisibleItemIndex },
        visible = { state.layoutInfo.visibleItemsInfo.size },
        scrolling = state.isScrollInProgress,
        scrollTo = { scope.launch { state.scrollToItem(it) } },
        modifier = modifier,
    )
}

/** A draggable thumb on the right edge, shown while scrolling long folders. */
@Composable
private fun FastScrollerImpl(
    total: Int,
    first: () -> Int,
    visible: () -> Int,
    scrolling: Boolean,
    scrollTo: (Int) -> Unit,
    modifier: Modifier,
) {
    if (total < 50) return
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) shown = true
        else {
            delay(1200); shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "scroller")
    val fraction by remember(total) {
        derivedStateOf {
            val range = (total - visible()).coerceAtLeast(1)
            (first().toFloat() / range).coerceIn(0f, 1f)
        }
    }
    val density = LocalDensity.current
    val thumbHeight = 52.dp
    BoxWithConstraints(modifier.fillMaxHeight().width(28.dp)) {
        val trackPx = with(density) { (maxHeight - thumbHeight).toPx() }.coerceAtLeast(1f)
        val shownFraction = if (dragging) dragFraction else fraction
        Box(
            Modifier
                .offset { IntOffset(0, (shownFraction * trackPx).roundToInt()) }
                .align(Alignment.TopEnd)
                .width(28.dp)
                .height(thumbHeight)
                .alpha(alpha)
                .then(
                    if (alpha > 0.2f) Modifier.pointerInput(total, trackPx) {
                        detectVerticalDragGestures(
                            onDragStart = { dragging = true; dragFraction = fraction },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, dy ->
                            change.consume()
                            dragFraction = (dragFraction + dy / trackPx).coerceIn(0f, 1f)
                            val target = (dragFraction * (total - visible()).coerceAtLeast(0)).roundToInt()
                            scrollTo(target.coerceIn(0, (total - 1).coerceAtLeast(0)))
                        }
                    } else Modifier,
                ),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .width(if (dragging) 10.dp else 7.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
            )
        }
    }
}
