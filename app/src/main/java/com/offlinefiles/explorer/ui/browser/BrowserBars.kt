package com.offlinefiles.explorer.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offlinefiles.explorer.R
import com.offlinefiles.explorer.core.FileItem
import com.offlinefiles.explorer.core.archive.ArchiveFormats
import com.offlinefiles.explorer.data.StorageVolumes
import com.offlinefiles.explorer.data.ViewMode
import com.offlinefiles.explorer.data.VolumeInfo
import com.offlinefiles.explorer.ui.DialogState
import com.offlinefiles.explorer.ui.MainViewModel
import com.offlinefiles.explorer.ui.Pending
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserTopBar(
    title: String,
    subtitle: String,
    viewMode: ViewMode,
    showHidden: Boolean,
    onUp: () -> Unit,
    onSearch: () -> Unit,
    onToggleView: () -> Unit,
    onSort: () -> Unit,
    onNewFolder: () -> Unit,
    onNewFile: () -> Unit,
    onSelectAll: () -> Unit,
    onToggleHidden: () -> Unit,
    onRefresh: () -> Unit,
    onHome: () -> Unit,
    onSettings: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_up)) }
        },
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        actions = {
            IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, stringResource(R.string.action_search)) }
            IconButton(onClick = onToggleView) {
                if (viewMode == ViewMode.LIST) Icon(Icons.Filled.GridView, stringResource(R.string.action_view_grid))
                else Icon(Icons.AutoMirrored.Filled.ViewList, stringResource(R.string.action_view_list))
            }
            IconButton(onClick = onSort) { Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.action_sort)) }
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuItem(Icons.Filled.CreateNewFolder, stringResource(R.string.action_new_folder)) { menu = false; onNewFolder() }
                MenuItem(Icons.AutoMirrored.Filled.NoteAdd, stringResource(R.string.action_new_file)) { menu = false; onNewFile() }
                MenuItem(Icons.Filled.SelectAll, stringResource(R.string.action_select_all)) { menu = false; onSelectAll() }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_show_hidden)) },
                    leadingIcon = { Icon(Icons.Filled.Visibility, null) },
                    trailingIcon = { if (showHidden) Icon(Icons.Filled.Check, null) },
                    onClick = { menu = false; onToggleHidden() },
                )
                MenuItem(Icons.Filled.Refresh, stringResource(R.string.action_refresh)) { menu = false; onRefresh() }
                HorizontalDivider()
                MenuItem(Icons.Filled.Home, stringResource(R.string.action_home)) { menu = false; onHome() }
                MenuItem(Icons.Filled.Settings, stringResource(R.string.settings_title)) { menu = false; onSettings() }
            }
        },
    )
}

@Composable
fun MenuItem(icon: ImageVector, text: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, null) },
        enabled = enabled,
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(count: Int, allSelected: Boolean, onClose: () -> Unit, onSelectAll: () -> Unit) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.action_cancel)) } },
        title = { Text(pluralStringResource(R.plurals.selected_count, count, count), fontWeight = FontWeight.SemiBold) },
        actions = {
            IconButton(onClick = onSelectAll) {
                if (allSelected) Icon(Icons.Filled.Deselect, stringResource(R.string.action_deselect_all))
                else Icon(Icons.Filled.SelectAll, stringResource(R.string.action_select_all))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(
    state: com.offlinefiles.explorer.ui.SearchUi,
    onQuery: (String) -> Unit,
    onRecursive: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close_search)) }
            },
            title = {
                BasicTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    decorationBox = { inner ->
                        if (state.query.isEmpty()) {
                            Text(
                                stringResource(R.string.search_hint),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    },
                )
            },
            actions = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, stringResource(R.string.action_clear)) }
                }
            },
        )
        Row(Modifier.padding(start = 16.dp, bottom = 8.dp)) {
            FilterChip(
                selected = state.recursive,
                onClick = { onRecursive(!state.recursive) },
                label = { Text(stringResource(R.string.search_subfolders)) },
                leadingIcon = if (state.recursive) ({ Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }) else null,
            )
        }
    }
}

/** "Internal storage › DCIM › Camera" with each part tappable. */
@Composable
fun Breadcrumbs(path: String, volumes: List<VolumeInfo>, onNavigate: (String) -> Unit) {
    val crumbs = remember(path, volumes) {
        val vol = StorageVolumes.volumeFor(path, volumes)
        val list = ArrayList<Pair<String, String>>()
        if (vol != null) {
            list += vol.label to vol.path
            var acc = vol.path
            path.removePrefix(vol.path).split('/').filter { it.isNotEmpty() }.forEach {
                acc = "$acc/$it"
                list += it to acc
            }
        } else {
            var acc = ""
            list += "/" to "/"
            path.split('/').filter { it.isNotEmpty() }.forEach {
                acc = "$acc/$it"
                list += it to acc
            }
        }
        list
    }
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.animateScrollTo(scroll.maxValue) }
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { i, (label, target) ->
            val last = i == crumbs.lastIndex
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onNavigate(target) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
            if (!last) Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun SelectionBottomBar(vm: MainViewModel, selection: Set<String>, items: List<FileItem>) {
    val selected = items.filter { it.path in selection }
    val single = selected.singleOrNull()
    val paths = selected.map { it.path }.ifEmpty { selection.toList() }
    var more by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(72.dp)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarAction(Icons.Filled.ContentCopy, stringResource(R.string.action_copy)) { vm.startCopy(paths, move = false) }
            BarAction(Icons.AutoMirrored.Filled.DriveFileMove, stringResource(R.string.action_move)) { vm.startCopy(paths, move = true) }
            BarAction(Icons.Filled.DriveFileRenameOutline, stringResource(R.string.action_rename), enabled = single != null) {
                single?.let { vm.showDialog(DialogState.Rename(it.path)) }
            }
            BarAction(Icons.Filled.Delete, stringResource(R.string.action_delete)) { vm.showDialog(DialogState.ConfirmDelete(paths)) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BarAction(Icons.Filled.MoreHoriz, stringResource(R.string.action_more)) { more = true }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    MenuItem(Icons.Filled.Share, stringResource(R.string.action_share)) { more = false; vm.share(paths) }
                    MenuItem(Icons.Filled.Archive, stringResource(R.string.action_compress)) {
                        more = false; vm.showDialog(DialogState.Compress(paths))
                    }
                    if (single != null && !single.isDirectory && ArchiveFormats.typeOf(single.name) != null) {
                        MenuItem(Icons.Filled.Unarchive, stringResource(R.string.action_extract)) {
                            more = false; vm.showDialog(DialogState.ArchiveActions(single))
                        }
                    }
                    if (single != null && !single.isDirectory) {
                        MenuItem(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.action_open_with)) {
                            more = false; vm.clearSelection(); vm.openWith(single.path)
                        }
                    }
                    MenuItem(Icons.Filled.Info, stringResource(R.string.action_properties)) {
                        more = false; vm.showDialog(DialogState.Properties(paths))
                    }
                }
            }
        }
    }
}

/** Icon with a small label, used in the bottom bars of the file list and the gallery. */
@Composable
fun BarAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.38f
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .width(60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Shown while copied/cut items (or an extraction) wait for a destination folder. */
@Composable
fun PendingBar(pending: Pending, canAct: Boolean, onAct: () -> Unit, onCancel: () -> Unit) {
    val (text, action) = when (pending) {
        is Pending.Paste -> pluralStringResource(
            if (pending.move) R.plurals.pending_move else R.plurals.pending_copy,
            pending.paths.size, pending.paths.size,
        ) to stringResource(if (pending.move) R.string.action_move_here else R.string.action_paste_here)
        is Pending.ExtractTo -> stringResource(R.string.pending_extract, File(pending.archive).name) to
            stringResource(R.string.action_extract_here_short)
    }
    Surface(color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 3.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.ContentPaste, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                if (canAct) text else stringResource(R.string.pending_pick_folder, text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            if (canAct) FilledTonalButton(onClick = onAct) { Text(action) }
        }
    }
}
