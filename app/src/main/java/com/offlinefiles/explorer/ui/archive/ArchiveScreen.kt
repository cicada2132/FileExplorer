package com.offlinefiles.explorer.ui.archive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinefiles.explorer.R
import com.offlinefiles.explorer.core.FileItem
import com.offlinefiles.explorer.ui.ArchiveNode
import com.offlinefiles.explorer.ui.DialogState
import com.offlinefiles.explorer.ui.MainViewModel
import com.offlinefiles.explorer.ui.browser.EmptyState
import com.offlinefiles.explorer.ui.components.FileThumb
import com.offlinefiles.explorer.ui.components.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val state by vm.archive.collectAsStateWithLifecycle()
    val nodes by vm.archiveNodes.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val running by vm.operations.running.collectAsStateWithLifecycle()
    val a = state ?: return
    val selecting = selection.isNotEmpty()
    val strip = if (a.inner.isEmpty()) "" else "${a.inner}/"

    BackHandler {
        when {
            selecting -> vm.clearSelection()
            vm.archiveUp() -> Unit
            else -> vm.back()
        }
    }

    Scaffold(
        topBar = {
            Column {
                if (selecting) {
                    TopAppBar(
                        navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, stringResource(R.string.action_cancel)) } },
                        title = { Text(pluralStringResource(R.plurals.selected_count, selection.size, selection.size), fontWeight = FontWeight.SemiBold) },
                        actions = {
                            IconButton(onClick = {
                                val all = nodes.mapTo(HashSet()) { it.path }
                                vm.selection.value = if (selection.containsAll(all)) emptySet() else all
                            }) { Icon(Icons.Filled.SelectAll, stringResource(R.string.action_select_all)) }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    )
                } else {
                    TopAppBar(
                        navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_up)) } },
                        title = {
                            Column {
                                Text(a.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                                if (!a.loading && a.error == null) {
                                    Text(
                                        pluralStringResource(R.plurals.archive_entries, a.entries.size, a.entries.size),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                    )
                }
                ArchiveCrumbs(a.name, a.inner) { vm.archiveOpenFolder(it) }
                if (running != null && running?.hidden == true) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            if (selecting) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            pluralStringResource(R.plurals.selected_count, selection.size, selection.size),
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Button(onClick = { vm.showDialog(DialogState.ArchiveExtract(selection.toSet(), strip)) }) {
                            Icon(Icons.Filled.Unarchive, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_extract_selected))
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!selecting && !a.loading && a.error == null && a.entries.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { vm.showDialog(DialogState.ArchiveExtract(null, "")) },
                    icon = { Icon(Icons.Filled.Unarchive, null) },
                    text = { Text(stringResource(R.string.action_extract_all)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when {
                a.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                a.error != null -> EmptyState(
                    if (a.error == com.offlinefiles.explorer.core.archive.ArchiveError.PASSWORD_REQUIRED ||
                        a.error == com.offlinefiles.explorer.core.archive.ArchiveError.WRONG_PASSWORD
                    ) Icons.Filled.Lock else Icons.Filled.ErrorOutline,
                    stringResource(MainViewModel.archiveErrorText(a.error)),
                    null,
                    action = if (a.error == com.offlinefiles.explorer.core.archive.ArchiveError.PASSWORD_REQUIRED ||
                        a.error == com.offlinefiles.explorer.core.archive.ArchiveError.WRONG_PASSWORD
                    ) stringResource(R.string.action_enter_password) to vm::retryArchivePassword else null,
                )
                nodes.isEmpty() -> EmptyState(Icons.Outlined.FolderOpen, stringResource(R.string.browser_empty), null)
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = padding.calculateBottomPadding() + 96.dp),
                ) {
                    items(nodes, key = { it.path }) { node ->
                        ArchiveRow(
                            node = node,
                            selected = node.path in selection,
                            selecting = selecting,
                            onClick = { vm.archiveNodeClick(node) },
                            onLongClick = { vm.toggleSelect(node.path) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveCrumbs(archiveName: String, inner: String, onNavigate: (String) -> Unit) {
    val parts = remember(inner) { if (inner.isEmpty()) emptyList() else inner.split('/') }
    val scroll = rememberScrollState()
    LaunchedEffect(inner) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb(archiveName, parts.isEmpty(), icon = true) { onNavigate("") }
        var acc = ""
        parts.forEachIndexed { i, p ->
            acc = if (acc.isEmpty()) p else "$acc/$p"
            val target = acc
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
            Crumb(p, i == parts.lastIndex) { onNavigate(target) }
        }
    }
}

@Composable
private fun Crumb(label: String, last: Boolean, icon: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Icon(Icons.Filled.FolderZip, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchiveRow(node: ArchiveNode, selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val asItem = remember(node) { FileItem(node.path, node.name, node.isDirectory, node.size, node.lastModified, false) }
    val detail = if (node.isDirectory) pluralStringResource(R.plurals.item_count, node.childCount, node.childCount)
    else listOf(Format.size(context, node.size), Format.dateTime(context, node.lastModified)).filter { it.isNotEmpty() }.joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f) else MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumb(asItem, size = 46.dp, showThumbnail = false, selected = selected, locked = node.encrypted)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(node.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (selecting) Checkbox(checked = selected, onCheckedChange = { onClick() })
    }
}
