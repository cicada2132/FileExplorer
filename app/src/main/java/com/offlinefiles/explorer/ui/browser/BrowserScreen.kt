package com.offlinefiles.explorer.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinefiles.explorer.R
import com.offlinefiles.explorer.core.FileItem
import com.offlinefiles.explorer.data.StorageVolumes
import com.offlinefiles.explorer.data.ViewMode
import com.offlinefiles.explorer.ui.BrowserUi
import com.offlinefiles.explorer.ui.DialogState
import com.offlinefiles.explorer.ui.MainViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val browser by vm.browser.collectAsStateWithLifecycle()
    val items by vm.visibleItems.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val highlight by vm.highlight.collectAsStateWithLifecycle()
    val running by vm.operations.running.collectAsStateWithLifecycle()
    val scrollTarget by vm.pendingScroll.collectAsStateWithLifecycle()
    val selecting = selection.isNotEmpty()
    val path = browser.path

    BackHandler {
        when {
            selecting -> vm.clearSelection()
            search != null -> vm.closeSearch()
            else -> vm.back()
        }
    }

    val memory = vm.scrollMemory[path]
    val listState = key(path) { remember { LazyListState(memory?.first ?: 0, memory?.second ?: 0) } }
    val gridState = key(path) { remember { LazyGridState(memory?.first ?: 0, memory?.second ?: 0) } }
    val grid = settings.viewMode == ViewMode.GRID
    DisposableEffect(path, grid) {
        onDispose {
            vm.scrollMemory[path] = if (grid) gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
            else listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
    }
    LaunchedEffect(scrollTarget, items) {
        val target = scrollTarget ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.path == target }
        if (index >= 0) {
            if (grid) gridState.animateScrollToItem(index) else listState.animateScrollToItem(index)
            vm.pendingScroll.value = null
        }
    }

    val volume = StorageVolumes.volumeFor(path, volumes)
    val title = if (volume != null && volume.path == path) volume.label else File(path).name.ifEmpty { path }
    val recursiveResults = search?.let { it.recursive && it.query.isNotBlank() } == true

    Scaffold(
        topBar = {
            Column {
                when {
                    selecting -> SelectionTopBar(
                        count = selection.size,
                        allSelected = items.isNotEmpty() && selection.size >= items.size,
                        onClose = vm::clearSelection,
                        onSelectAll = vm::selectAllToggle,
                    )
                    search != null -> SearchTopBar(
                        state = search!!,
                        onQuery = vm::setSearchQuery,
                        onRecursive = vm::setRecursiveSearch,
                        onClose = vm::closeSearch,
                    )
                    else -> BrowserTopBar(
                        title = title,
                        subtitle = if (browser.loading && items.isEmpty()) "" else pluralStringResource(R.plurals.item_count, items.size, items.size),
                        viewMode = settings.viewMode,
                        showHidden = settings.showHidden,
                        onUp = vm::up,
                        onSearch = vm::startSearch,
                        onToggleView = vm::toggleViewMode,
                        onSort = { vm.showDialog(DialogState.Sort) },
                        onNewFolder = { vm.showDialog(DialogState.NewFolder(path)) },
                        onNewFile = { vm.showDialog(DialogState.NewFile(path)) },
                        onSelectAll = vm::selectAllToggle,
                        onToggleHidden = vm::toggleHidden,
                        onRefresh = vm::refresh,
                        onHome = vm::goHome,
                        onSettings = vm::openSettings,
                    )
                }
                if (search == null) Breadcrumbs(path, volumes, onNavigate = { if (it != path) vm.openDir(it) })
                if (running?.hidden == true) {
                    val f = running?.progress?.fraction
                    if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (search?.running == true) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            when {
                selecting -> SelectionBottomBar(vm, selection, items)
                pending != null -> PendingBar(pending!!, canAct = browser.status == BrowserUi.Status.OK, onAct = vm::pasteHere, onCancel = vm::cancelPending)
            }
        },
        floatingActionButton = {
            if (!selecting && pending == null && search == null && browser.status == BrowserUi.Status.OK) {
                FloatingActionButton(onClick = { vm.showDialog(DialogState.NewFolder(path)) }) {
                    Icon(Icons.Filled.CreateNewFolder, stringResource(R.string.action_new_folder))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = browser.loading && browser.all.isNotEmpty(),
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) {
            val bottomPad = padding.calculateBottomPadding() + if (!selecting && pending == null) 88.dp else 8.dp
            when {
                browser.status == BrowserUi.Status.DENIED -> EmptyState(
                    Icons.Filled.Lock, stringResource(R.string.browser_denied_title), stringResource(R.string.browser_denied_body),
                )
                browser.status == BrowserUi.Status.NOT_FOUND -> EmptyState(
                    Icons.Filled.FolderOff, stringResource(R.string.browser_not_found), null,
                    action = stringResource(R.string.action_home) to vm::goHome,
                )
                browser.loading && browser.all.isEmpty() && search == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                items.isEmpty() && search != null && search!!.query.isNotBlank() && search?.running != true -> EmptyState(
                    Icons.Filled.SearchOff, stringResource(R.string.search_no_results), null,
                )
                items.isEmpty() && search == null -> EmptyState(Icons.Outlined.FolderOpen, stringResource(R.string.browser_empty), null)
                grid -> Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        state = gridState,
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = bottomPad),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items, key = { it.path }, contentType = { it.isDirectory }) { item ->
                            FileCell(
                                item = item,
                                selected = item.path in selection,
                                selecting = selecting,
                                highlighted = item.path in highlight,
                                showThumbnail = settings.showThumbnails,
                                onClick = { vm.onItemClick(item) },
                                onLongClick = { vm.onItemLongClick(item) },
                            )
                        }
                    }
                    FastScroller(gridState, items.size, Modifier.align(Alignment.CenterEnd).padding(bottom = bottomPad))
                }
                else -> Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(top = 4.dp, bottom = bottomPad),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items, key = { it.path }, contentType = { it.isDirectory }) { item ->
                            FileRow(
                                item = item,
                                selected = item.path in selection,
                                selecting = selecting,
                                highlighted = item.path in highlight,
                                showThumbnail = settings.showThumbnails,
                                showFolderCount = settings.showFolderCounts,
                                includeHidden = settings.showHidden,
                                location = if (recursiveResults) relativeParent(item, path) else null,
                                onClick = { vm.onItemClick(item) },
                                onLongClick = { vm.onItemLongClick(item) },
                                onMore = { vm.showItemActions(item) },
                            )
                        }
                        if (search?.truncated == true) {
                            item {
                                Text(
                                    stringResource(R.string.search_truncated, MainViewModel.LIMIT),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                    FastScroller(listState, items.size, Modifier.align(Alignment.CenterEnd).padding(bottom = bottomPad))
                }
            }
        }
    }
}

private fun relativeParent(item: FileItem, root: String): String {
    val parent = File(item.path).parent ?: return ""
    return if (parent == root) "./" else "./" + parent.removePrefix("$root/")
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String?, action: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = action.second) { Text(action.first) }
        }
    }
}
