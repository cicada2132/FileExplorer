package com.offlinefiles.explorer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlinefiles.explorer.App
import com.offlinefiles.explorer.R
import com.offlinefiles.explorer.core.DirectoryLister
import com.offlinefiles.explorer.core.FailureReason
import com.offlinefiles.explorer.core.FileItem
import com.offlinefiles.explorer.core.FileKind
import com.offlinefiles.explorer.core.FileOpException
import com.offlinefiles.explorer.core.FileOps
import com.offlinefiles.explorer.core.FileTypes
import com.offlinefiles.explorer.core.ListResult
import com.offlinefiles.explorer.core.OperationListener
import com.offlinefiles.explorer.core.OperationResult
import com.offlinefiles.explorer.core.SortSpec
import com.offlinefiles.explorer.core.Sorting
import com.offlinefiles.explorer.core.StoreOps
import com.offlinefiles.explorer.core.archive.ArchiveEntryInfo
import com.offlinefiles.explorer.core.archive.ArchiveError
import com.offlinefiles.explorer.core.archive.ArchiveException
import com.offlinefiles.explorer.core.archive.ArchiveFormats
import com.offlinefiles.explorer.core.archive.ArchiveOps
import com.offlinefiles.explorer.core.archive.ArchiveReader
import com.offlinefiles.explorer.core.archive.ArchiveType
import com.offlinefiles.explorer.core.archive.Archives
import com.offlinefiles.explorer.core.archive.CompressionLevel
import com.offlinefiles.explorer.core.gallery.MediaItem
import com.offlinefiles.explorer.core.gallery.toMediaItem
import com.offlinefiles.explorer.data.FileIntents
import com.offlinefiles.explorer.data.GrantResult
import com.offlinefiles.explorer.data.Settings
import com.offlinefiles.explorer.data.StorageVolumes
import com.offlinefiles.explorer.data.Stores
import com.offlinefiles.explorer.data.ViewMode
import com.offlinefiles.explorer.data.VolumeAccess
import com.offlinefiles.explorer.data.VolumeInfo
import com.offlinefiles.explorer.data.VolumeKind
import com.offlinefiles.explorer.ops.DocumentStaging
import com.offlinefiles.explorer.ops.OpEvent
import com.offlinefiles.explorer.ops.OpOutcome
import com.offlinefiles.explorer.ops.OpRequest
import com.offlinefiles.explorer.ui.components.FolderCounts
import com.offlinefiles.explorer.ui.gallery.GalleryModel
import com.offlinefiles.explorer.ui.player.PlayItem
import com.offlinefiles.explorer.ui.player.PlayerModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Route {
    data object Home : Route
    data class Browse(val path: String) : Route
    data class Archive(val path: String) : Route
    data object Gallery : Route
    /** An album in the gallery: a folder path, or a virtual album key (see GalleryIndex). */
    data class Album(val key: String) : Route
    data object Settings : Route
    data object Licenses : Route
}

/** Something waiting for the user to pick a destination folder. */
sealed interface Pending {
    data class Paste(val paths: List<String>, val move: Boolean) : Pending
    data class ExtractTo(
        val archive: String,
        val password: CharArray?,
        val selection: Set<String>?,
        val stripPrefix: String,
        val count: Int,
    ) : Pending
}

sealed interface DialogState {
    data class NewFolder(val parent: String) : DialogState
    data class NewFile(val parent: String) : DialogState
    data class Rename(val path: String) : DialogState
    data class ConfirmDelete(val paths: List<String>) : DialogState
    data class Properties(val paths: List<String>) : DialogState
    data object Sort : DialogState
    data class Compress(val paths: List<String>) : DialogState
    data class Password(val archive: String, val wrong: Boolean, val onSubmit: (CharArray) -> Unit) : DialogState
    data class OpenAs(val path: String) : DialogState
    data class ItemActions(val item: FileItem) : DialogState
    data class ArchiveActions(val item: FileItem) : DialogState
    /** Extract choices inside the archive viewer; [selection] null means everything. */
    data class ArchiveExtract(val selection: Set<String>?, val stripPrefix: String) : DialogState
    /** Details of a photo or video (date, resolution, camera…). */
    data class MediaInfo(val item: MediaItem) : DialogState
    /** Explains the system file picker before it opens for a USB drive. */
    data class UsbAccess(val volume: VolumeInfo) : DialogState
}

sealed interface UiEvent {
    data class Open(val path: String, val mime: String? = null, val chooser: Boolean = false) : UiEvent
    data class Share(val paths: List<String>) : UiEvent
    data class ScrollTo(val path: String) : UiEvent
    data class SetAs(val path: String, val mime: String?) : UiEvent
    /** Open the system file picker so the user can give access to a drive. */
    data class RequestAccess(val intent: Intent) : UiEvent
}

data class BrowserUi(
    val path: String = "",
    val loading: Boolean = true,
    val all: List<FileItem> = emptyList(),
    val status: Status = Status.OK,
) {
    enum class Status { OK, DENIED, NOT_FOUND }
}

data class SearchUi(
    val query: String = "",
    val recursive: Boolean = false,
    val running: Boolean = false,
    val results: List<FileItem> = emptyList(),
    val truncated: Boolean = false,
)

/** A row in the archive viewer: a real entry or a folder implied by entry paths. */
data class ArchiveNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val encrypted: Boolean,
    val childCount: Int,
)

data class ArchiveUi(
    val path: String,
    val loading: Boolean = true,
    val entries: List<ArchiveEntryInfo> = emptyList(),
    val inner: String = "",
    val password: CharArray? = null,
    val error: ArchiveError? = null,
) {
    val name: String get() = File(path).name
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val prefs = app.prefs
    val operations = app.operations
    val settings: StateFlow<Settings> = prefs.settings
    private val staging = app.staging

    private val stack = MutableStateFlow<List<Route>>(listOf(Route.Home))
    val route: StateFlow<Route> = stack.map { it.last() }.stateIn(viewModelScope, SharingStarted.Eagerly, Route.Home)

    private val _volumes = MutableStateFlow<List<VolumeInfo>>(emptyList())
    val volumes: StateFlow<List<VolumeInfo>> = _volumes.asStateFlow()

    private val _browser = MutableStateFlow(BrowserUi())
    val browser: StateFlow<BrowserUi> = _browser.asStateFlow()
    /** Latest raw listing; [token] makes every reload emit, even when nothing changed. */
    private data class Listing(val path: String, val items: List<FileItem>, val token: Long)
    private val rawListing = MutableStateFlow<Listing?>(null)
    private var listingToken = 0L

    val selection = MutableStateFlow<Set<String>>(emptySet())
    val search = MutableStateFlow<SearchUi?>(null)
    val pending = MutableStateFlow<Pending?>(null)
    val dialog = MutableStateFlow<DialogState?>(null)
    val highlight = MutableStateFlow<Set<String>>(emptySet())
    val archive = MutableStateFlow<ArchiveUi?>(null)

    /** Photos and videos (gallery screens, home card and the full-screen viewer). */
    val gallery = GalleryModel(app, viewModelScope)

    /** The built-in video and music player, drawn over whichever screen opened it. */
    val player = PlayerModel(
        context = app,
        scope = viewModelScope,
        store = app.playerStore,
        resumeEnabled = { settings.value.resumePlayback },
        message = { res -> message(res) },
    )

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()
    private val eventChannel = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = eventChannel.receiveAsFlow()

    /** A path the list should scroll to (newly created or pasted item). */
    val pendingScroll = MutableStateFlow<String?>(null)

    /** Scroll position per folder, so going back lands where you were. */
    val scrollMemory = HashMap<String, Pair<Int, Int>>()

    val visibleItems: StateFlow<List<FileItem>> = combine(_browser, search) { b, s ->
        when {
            s == null || s.query.isBlank() -> b.all
            s.recursive -> s.results
            else -> b.all.filter { it.name.contains(s.query.trim(), ignoreCase = true) }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val archiveNodes: StateFlow<List<ArchiveNode>> = archive.map { a ->
        if (a == null) emptyList() else childrenOf(a.entries, a.inner)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var highlightJob: Job? = null
    private var usbJob: Job? = null

    /** Cache copy of the archive shown in the viewer, when it lives on a USB document tree. */
    private var viewerArchive: Pair<String, DocumentStaging.Staged>? = null

    init {
        refreshVolumes()
        viewModelScope.launch {
            combine(rawListing, settings.map { it.sort to it.showHidden }.distinctUntilChanged()) { raw, opts ->
                raw?.let { it.path to sortAndFilter(it.items, opts.first, opts.second) }
            }.flowOn(Dispatchers.Default).collect { sorted ->
                if (sorted != null && sorted.first == _browser.value.path) {
                    _browser.update { it.copy(all = sorted.second, loading = false, status = BrowserUi.Status.OK) }
                }
            }
        }
        viewModelScope.launch { operations.events.collect { onOperationFinished(it) } }
    }

    private fun sortAndFilter(items: List<FileItem>, sort: SortSpec, showHidden: Boolean) =
        Sorting.sort(if (showHidden) items else items.filterNot { it.isHidden }, sort)

    // ------------------------------------------------------------------ navigation

    fun refreshVolumes(then: ((List<VolumeInfo>) -> Unit)? = null) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { StorageVolumes.list(app) }
            applyVolumes(list)
            then?.invoke(list)
        }
    }

    private fun applyVolumes(list: List<VolumeInfo>) {
        val before = _volumes.value
        Stores.update(app.contentResolver, list)
        _volumes.value = list
        if (before.isEmpty()) return
        // Drives reached through the file picker send no unmount broadcast, so notice them leaving here.
        val here = currentDir() ?: archive.value?.path
        val was = here?.let { StorageVolumes.volumeFor(it, before) }
        if (here != null && was != null && was.kind != VolumeKind.INTERNAL && StorageVolumes.volumeFor(here, list)?.path != was.path) {
            goHome()
            message(R.string.msg_volume_removed)
            return
        }
        list.firstOrNull { v -> v.kind != VolumeKind.INTERNAL && before.none { it.path == v.path } }
            ?.let { message(R.string.msg_volume_connected, it.label) }
    }

    /** A USB device was plugged in or out. Mounting takes a moment, so look again shortly after. */
    fun onUsbDeviceChanged() {
        usbJob?.cancel()
        usbJob = viewModelScope.launch {
            for (wait in longArrayOf(0L, 1500L, 3500L)) {
                delay(wait)
                applyVolumes(withContext(Dispatchers.IO) { StorageVolumes.list(app) })
            }
        }
    }

    /** Home's storage cards: drives Android keeps from apps ask for access first. */
    fun openVolume(v: VolumeInfo) {
        if (v.access == VolumeAccess.NEEDS_GRANT) dialog.value = DialogState.UsbAccess(v) else openDir(v.path)
    }

    /** Opens the system file picker at the root of [v]. */
    fun requestAccess(v: VolumeInfo) {
        dialog.value = null
        eventChannel.trySend(UiEvent.RequestAccess(StorageVolumes.accessIntent(app, v)))
    }

    /** What the user picked in the system file picker (null if they backed out). */
    fun onAccessResult(uri: Uri?) {
        if (uri == null) return
        when (val r = StorageVolumes.acceptGrant(app, uri, _volumes.value)) {
            is GrantResult.Granted -> refreshVolumes { list ->
                val v = list.firstOrNull { it.uuid == r.uuid } ?: return@refreshVolumes
                val here = currentDir()
                if (here != null && StorageVolumes.volumeFor(here, list)?.path == v.path) refresh() else openDir(v.path)
            }
            GrantResult.NotRoot -> message(R.string.msg_usb_pick_root)
            GrantResult.NotADrive -> message(R.string.msg_usb_not_drive)
            GrantResult.Failed -> message(R.string.msg_failed_generic)
        }
    }

    /** Settings: give back access to the drives opened through the file picker. */
    fun forgetDriveAccess() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { StorageVolumes.releaseGrants(app) }
            refreshVolumes()
            message(R.string.msg_usb_access_forgotten)
        }
    }

    /** An SD card or USB drive went away; leave it if we were inside. */
    fun onVolumeRemoved(path: String?) {
        refreshVolumes()
        gallery.onChanged()
        if (path != null) player.onRemoved(listOf(path))
        val here = currentDir() ?: archive.value?.path
        if (path != null && here != null && (here == path || here.startsWith("$path/"))) {
            goHome()
            message(R.string.msg_volume_removed)
        }
    }

    fun onResume() {
        refreshVolumes()
        if (route.value is Route.Browse) refresh()
        gallery.onResume()
    }

    fun openDir(path: String) {
        closeSearch()
        stack.update { it + Route.Browse(path) }
        load(path)
    }

    fun openSettings() = stack.update { it + Route.Settings }

    fun openLicenses() = stack.update { it + Route.Licenses }

    fun openGallery() {
        closeSearch()
        selection.value = emptySet()
        stack.update { it + Route.Gallery }
        gallery.ensureLoaded()
    }

    fun openAlbum(key: String) {
        gallery.clearSelection()
        stack.update { it + Route.Album(key) }
    }

    fun goHome() {
        stack.value = listOf(Route.Home)
        selection.value = emptySet()
        closeSearch()
        closeArchiveViewer()
        gallery.clearSelection()
        gallery.closeViewer(null)
        player.close()
        refreshVolumes()
    }

    /** System back. Returns false when there is nothing left to go back to. */
    fun back(): Boolean {
        val s = stack.value
        if (s.size <= 1) return false
        val leaving = s.last()
        val next = s.dropLast(1)
        stack.value = next
        if (leaving is Route.Archive) closeArchiveViewer()
        if (leaving is Route.Gallery || leaving is Route.Album) gallery.clearSelection()
        selection.value = emptySet()
        closeSearch()
        when (val r = next.last()) {
            is Route.Browse -> load(r.path)
            Route.Home -> refreshVolumes()
            else -> Unit
        }
        return true
    }

    /** Toolbar up-arrow: parent folder, or home at the top of a storage volume. */
    fun up() {
        val r = route.value as? Route.Browse ?: run { back(); return }
        val vol = StorageVolumes.volumeFor(r.path, _volumes.value)
        val parent = File(r.path).parent
        if (vol == null || r.path == vol.path || parent == null) {
            goHome(); return
        }
        val s = stack.value
        if (s.size >= 2 && (s[s.size - 2] as? Route.Browse)?.path == parent) {
            back()
        } else {
            stack.value = s.dropLast(1) + Route.Browse(parent)
            selection.value = emptySet()
            closeSearch()
            load(parent)
        }
    }

    fun currentDir(): String? = (route.value as? Route.Browse)?.path

    private fun load(path: String, keepSelection: Boolean = false) {
        loadJob?.cancel()
        val same = _browser.value.path == path
        _browser.value = if (same) _browser.value.copy(loading = true) else BrowserUi(path = path, loading = true)
        if (!same) rawListing.value = null
        if (!keepSelection) selection.value = emptySet()
        loadJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { Stores.tree(path)?.list(path) ?: DirectoryLister.list(File(path)) }
            when (result) {
                is ListResult.Ok -> {
                    rawListing.value = Listing(path, result.items, ++listingToken)
                    if (keepSelection) {
                        val present = result.items.mapTo(HashSet()) { it.path }
                        selection.update { sel -> sel.filterTo(HashSet()) { it in present } }
                    }
                }
                ListResult.Denied -> _browser.update { it.copy(loading = false, all = emptyList(), status = BrowserUi.Status.DENIED) }
                ListResult.NotFound -> _browser.update { it.copy(loading = false, all = emptyList(), status = BrowserUi.Status.NOT_FOUND) }
            }
        }
    }

    fun refresh() {
        currentDir()?.let { load(it, keepSelection = true) }
    }

    // ------------------------------------------------------------------ settings shortcuts

    fun updateSettings(transform: (Settings) -> Settings) = prefs.update(transform)

    fun toggleViewMode() = prefs.update { it.copy(viewMode = if (it.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST) }

    fun setSort(sort: SortSpec) = prefs.update { it.copy(sort = sort) }

    fun toggleHidden() {
        prefs.update { it.copy(showHidden = !it.showHidden) }
        FolderCounts.clear()
    }

    // ------------------------------------------------------------------ items & selection

    fun onItemClick(item: FileItem) {
        if (selection.value.isNotEmpty()) {
            toggleSelect(item.path); return
        }
        when {
            item.isDirectory -> openDir(item.path)
            ArchiveFormats.typeOf(item.name) != null -> dialog.value = DialogState.ArchiveActions(item)
            item.kind == FileKind.IMAGE && settings.value.openMediaInApp -> openFolderViewer(item)
            isPlayable(item.kind) && settings.value.playerInApp -> openFolderPlayer(item)
            else -> eventChannel.trySend(UiEvent.Open(item.path))
        }
    }

    private fun isPlayable(kind: FileKind) = kind == FileKind.VIDEO || kind == FileKind.AUDIO

    /** Built-in player over the videos (or the songs) of the list being shown, starting at [item]. */
    private fun openFolderPlayer(item: FileItem) {
        val queue = visibleItems.value.filter { it.kind == item.kind }.map { PlayItem(it.path, it.name, it.kind == FileKind.VIDEO) }
        player.open(queue.ifEmpty { listOf(PlayItem(item.path, item.name, item.kind == FileKind.VIDEO)) }, item.path)
    }

    /** Built-in viewer over the photos and videos of the list being shown, starting at [item]. */
    private fun openFolderViewer(item: FileItem) {
        val media = visibleItems.value.mapNotNull { it.toMediaItem() }
        if (media.none { it.path == item.path }) {
            eventChannel.trySend(UiEvent.Open(item.path)); return
        }
        gallery.openViewer(media, item.path)
    }

    fun onItemLongClick(item: FileItem) = toggleSelect(item.path)

    fun toggleSelect(path: String) = selection.update { if (path in it) it - path else it + path }

    fun selectAllToggle() {
        val all = visibleItems.value.mapTo(HashSet()) { it.path }
        selection.value = if (selection.value.containsAll(all)) emptySet() else all
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    fun selectedPaths(): List<String> {
        val sel = selection.value
        return visibleItems.value.filter { it.path in sel }.map { it.path }.ifEmpty { sel.toList() }
    }

    fun showDialog(d: DialogState?) {
        dialog.value = d
    }

    fun showItemActions(item: FileItem) {
        dialog.value = DialogState.ItemActions(item)
    }

    fun openWith(path: String) = eventChannel.trySend(UiEvent.Open(path, chooser = true))

    fun openAs(path: String, mime: String) = eventChannel.trySend(UiEvent.Open(path, mime = mime))

    fun share(paths: List<String>) {
        if (paths.any(Stores::isDocument)) {
            // Files on a USB document tree: ask the drive which of them are files.
            viewModelScope.launch {
                val files = withContext(Dispatchers.IO) {
                    paths.filter { p -> Stores.storeFor(p).stat(p)?.isDirectory == false }
                }
                if (files.isEmpty()) {
                    message(R.string.msg_share_folders)
                } else {
                    eventChannel.trySend(UiEvent.Share(files))
                    clearSelection()
                    gallery.clearSelection()
                }
            }
            return
        }
        if (paths.none { File(it).isFile }) {
            message(R.string.msg_share_folders); return
        }
        eventChannel.trySend(UiEvent.Share(paths))
        clearSelection()
        gallery.clearSelection()
    }

    // ------------------------------------------------------------------ gallery actions

    /**
     * Plays a video from the photo viewer: in the built-in player with the other videos of
     * [around] queued up, or in another app when the setting is off.
     */
    fun openMedia(item: MediaItem, around: List<MediaItem> = listOf(item)) {
        if (!settings.value.playerInApp) {
            eventChannel.trySend(UiEvent.Open(item.path, mime = item.mime.ifBlank { null }))
            return
        }
        val queue = around.filter { it.isVideo }.ifEmpty { listOf(item) }.map { PlayItem(it.path, it.name, it.isVideo) }
        player.open(queue, item.path)
    }

    fun setAs(item: MediaItem) {
        eventChannel.trySend(UiEvent.SetAs(item.path, item.mime.ifBlank { null }))
    }

    /** Opens the folder in the file list with the file highlighted. */
    fun showInFolder(path: String) {
        val parent = File(path).parent ?: return
        gallery.closeViewer(null)
        gallery.clearSelection()
        dialog.value = null
        openDir(parent)
        flash(listOf(path))
    }

    /** Copy or move from the gallery: the destination is picked in the file list, as usual. */
    fun galleryTransfer(paths: List<String>, move: Boolean) {
        if (paths.isEmpty()) return
        goHome()
        startCopy(paths, move)
        message(R.string.msg_pick_paste_folder)
    }

    // ------------------------------------------------------------------ search

    fun startSearch() {
        if (search.value == null) search.value = SearchUi()
        clearSelection()
    }

    fun setSearchQuery(q: String) {
        search.update { it?.copy(query = q) }
        if (search.value?.recursive == true) restartRecursiveSearch()
    }

    fun setRecursiveSearch(on: Boolean) {
        search.update { it?.copy(recursive = on, results = emptyList(), truncated = false) }
        if (on) restartRecursiveSearch() else searchJob?.cancel()
    }

    fun closeSearch() {
        searchJob?.cancel()
        search.value = null
    }

    private fun restartRecursiveSearch() {
        searchJob?.cancel()
        val s = search.value ?: return
        val root = currentDir() ?: return
        if (s.query.isBlank()) {
            search.update { it?.copy(results = emptyList(), running = false) }; return
        }
        val includeHidden = settings.value.showHidden
        searchJob = viewModelScope.launch {
            delay(250)
            search.update { it?.copy(running = true, results = emptyList(), truncated = false) }
            val found = ArrayList<FileItem>()
            var last = 0L
            val results = Stores.tree(root)?.let { StoreOps.search(it, root, s.query, includeHidden, LIMIT) }
                ?: DirectoryLister.search(File(root), s.query, includeHidden, LIMIT)
            results.flowOn(Dispatchers.IO).collect { item ->
                found += item
                val now = System.currentTimeMillis()
                if (now - last > 200) {
                    last = now
                    search.update { it?.copy(results = found.toList()) }
                }
            }
            search.update { it?.copy(results = found.toList(), running = false, truncated = found.size >= LIMIT) }
        }
    }

    // ------------------------------------------------------------------ simple file actions

    fun createFolder(parent: String, name: String) = quick {
        val path = Stores.tree(parent)?.let { StoreOps.createFolder(it, parent, name) }
            ?: FileOps.createFolder(File(parent), name).path
        afterQuickChange(path)
    }

    fun createFile(parent: String, name: String) = quick {
        val path = Stores.tree(parent)?.let { StoreOps.createFile(it, parent, name) }
            ?: FileOps.createFile(File(parent), name).path
        afterQuickChange(path)
    }

    fun rename(path: String, newName: String) = quick {
        val tree = Stores.tree(path)
        val newPath = if (tree != null) StoreOps.rename(tree, path, newName) else FileOps.rename(File(path), newName).path
        FileIntents.notifyMediaStore(app, listOf(path, newPath))
        withContext(Dispatchers.Main) {
            gallery.onRenamed(path, newPath)
            gallery.clearSelection()
        }
        afterQuickChange(newPath)
    }

    /** Whether [name] is taken in [parent] by something other than [originalPath] (name dialogs). */
    fun nameTaken(parent: String, name: String, originalPath: String? = null): Boolean {
        if (Stores.isDocument(parent)) {
            // Answer from the listing on screen; asking the drive on every keystroke is too slow.
            val b = _browser.value
            if (b.path != parent) return false
            return b.all.any { it.name.equals(name, ignoreCase = true) && !it.path.equals(originalPath, ignoreCase = true) }
        }
        val target = File(parent, name)
        return target.exists() && (originalPath == null || !FileOps.isSameFile(File(originalPath), target))
    }

    /** "New folder", or "New folder (1)" when that is taken, for the name dialogs. */
    fun freeName(parent: String, name: String, isDirectory: Boolean): String {
        if (!Stores.isDocument(parent)) return FileOps.uniqueChild(File(parent), name, isDirectory).name
        val taken = _browser.value.takeIf { it.path == parent }?.all.orEmpty().mapTo(HashSet()) { it.name.lowercase() }
        if (name.lowercase() !in taken) return name
        val (base, ext) = FileOps.splitName(name, isDirectory)
        var i = 1
        while ("$base ($i)$ext".lowercase() in taken) i++
        return "$base ($i)$ext"
    }

    /** Whether [path] is a folder; for USB document trees, as far as the listing on screen knows. */
    fun isFolder(path: String): Boolean =
        if (Stores.isDocument(path)) visibleItems.value.firstOrNull { it.path == path }?.isDirectory == true
        else File(path).isDirectory

    private fun quick(block: suspend () -> Unit) {
        dialog.value = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: FileOpException) {
                message(failureText(e.reason))
            } catch (e: Exception) {
                message(R.string.msg_failed_generic)
            }
        }
    }

    private suspend fun afterQuickChange(newPath: String) = withContext(Dispatchers.Main) {
        clearSelection()
        refresh()
        flash(listOf(newPath))
    }

    // ------------------------------------------------------------------ long operations

    fun startCopy(paths: List<String>, move: Boolean) {
        if (paths.isEmpty()) return
        pending.value = Pending.Paste(paths, move)
        clearSelection()
        dialog.value = null
    }

    fun cancelPending() {
        pending.value = null
    }

    fun pasteHere() {
        val dest = currentDir() ?: return
        when (val p = pending.value) {
            is Pending.Paste -> start(OpRequest.Transfer(p.paths, dest, p.move)) { l ->
                if (Stores.isDocument(dest) || p.paths.any(Stores::isDocument)) StoreOps.copyOrMove(p.paths, dest, p.move, Stores, l)
                else FileOps.copyOrMove(p.paths.map(::File), File(dest), p.move, l)
            }
            is Pending.ExtractTo -> runExtract(
                OpRequest.Extract(p.archive, dest, p.password, p.selection, p.stripPrefix),
            )
            null -> Unit
        }
    }

    fun delete(paths: List<String>) {
        dialog.value = null
        clearSelection()
        gallery.clearSelection()
        start(OpRequest.Delete(paths)) { l ->
            if (paths.any(Stores::isDocument)) StoreOps.delete(paths, Stores, l) else FileOps.delete(paths.map(::File), l)
        }
    }

    fun compress(paths: List<String>, name: String, type: ArchiveType, level: CompressionLevel, password: CharArray?) {
        dialog.value = null
        clearSelection()
        gallery.clearSelection()
        val parent = File(paths.first()).parentFile ?: return
        val output = File(parent, "$name.${type.extension}")
        start(OpRequest.Compress(paths, output.path, type, level, password)) { l ->
            if (Stores.isDocument(output.path) || paths.any(Stores::isDocument)) {
                staging.compress(paths, output.path, type, level, password, l)
            } else {
                ArchiveOps.compress(paths.map(::File), output, type, level, password, l)
            }
        }
    }

    /** Extract choices from the archive bottom sheet. */
    fun extractArchive(path: String, mode: ExtractChoice) {
        dialog.value = null
        clearSelection()
        val parent = File(path).parent ?: return
        when (mode) {
            ExtractChoice.HERE -> runExtract(OpRequest.Extract(path, parent, null))
            ExtractChoice.OWN_FOLDER -> runExtract(OpRequest.Extract(path, parent, null, smartFolder = true))
            ExtractChoice.CHOOSE -> choosePlaceForExtract(path, null, null, "", 0)
        }
    }

    private fun choosePlaceForExtract(archivePath: String, password: CharArray?, sel: Set<String>?, strip: String, count: Int) {
        pending.value = Pending.ExtractTo(archivePath, password, sel, strip, count)
        val parent = File(archivePath).parent ?: return
        if (route.value is Route.Archive) back()
        if (currentDir() != parent) openDir(parent)
        message(R.string.msg_pick_destination)
    }

    private fun runExtract(req: OpRequest.Extract) {
        start(req) { l -> doExtract(req, l) }
    }

    private suspend fun doExtract(req: OpRequest.Extract, l: OperationListener): OperationResult {
        if (!Stores.isDocument(req.archive) && !Stores.isDocument(req.destDir)) return extractLocal(File(req.archive), req, l)
        // A USB document tree on either side: archives are read from a cache copy, and files
        // for the drive are extracted into the cache and then moved over.
        val staged = if (Stores.isDocument(req.archive)) staging.acquireArchive(req.archive, l) else null
        try {
            val archiveFile = staged?.file ?: File(req.archive)
            if (!Stores.isDocument(req.destDir)) return extractLocal(archiveFile, req, l)
            return staging.extractInto(
                archiveFile, File(req.archive).name, req.password, req.destDir, l, req.selection, req.stripPrefix, req.smartFolder,
            )
        } finally {
            staged?.let(staging::release)
        }
    }

    private suspend fun extractLocal(archiveFile: File, req: OpRequest.Extract, l: OperationListener): OperationResult {
        if (req.openAfter != null) File(req.openAfter).delete()
        if (!req.smartFolder) {
            return ArchiveOps.extract(archiveFile, req.password, File(req.destDir), l, req.selection, req.stripPrefix)
        }
        val reader = Archives.open(archiveFile, req.password)
        return try {
            val dest = smartDestination(reader, File(req.destDir), ArchiveFormats.baseName(archiveFile.name))
            ArchiveOps.extractFrom(reader, req.password, dest, l, req.selection, req.stripPrefix)
        } finally {
            runCatching { reader.close() }
        }
    }

    /** "Extract to folder": skip the extra level when the archive already holds one folder of that name. */
    private fun smartDestination(reader: ArchiveReader, parent: File, base: String): File {
        val single = ArchiveOps.singleTopFolder(reader.entries)
        return if (single != null && single.equals(base, ignoreCase = true)) parent
        else FileOps.uniqueChild(parent, base, isDirectory = true)
    }

    private fun start(request: OpRequest, block: suspend (OperationListener) -> OperationResult) {
        if (!operations.start(request, block)) message(R.string.msg_busy)
    }

    private fun onOperationFinished(event: OpEvent) {
        val request = event.request
        when (val outcome = event.outcome) {
            is OpOutcome.Done -> {
                val r = outcome.result
                when (request) {
                    is OpRequest.Transfer -> {
                        if (pending.value is Pending.Paste) pending.value = null
                        if (r.succeeded > 0) message(if (request.move) R.string.msg_moved else R.string.msg_copied, r.succeeded)
                        FileIntents.notifyMediaStore(app, r.outputs.map { it.path } + if (request.move) request.sources else emptyList())
                        if (request.move) gallery.onRemoved(request.sources) else gallery.onChanged()
                    }
                    is OpRequest.Delete -> {
                        if (r.succeeded > 0) message(R.string.msg_deleted, r.succeeded)
                        FileIntents.notifyMediaStore(app, request.paths)
                        gallery.onRemoved(request.paths)
                        player.onRemoved(request.paths.filterNot { File(it).exists() })
                    }
                    is OpRequest.Compress -> {
                        message(R.string.msg_compressed, File(request.output).name)
                        FileIntents.notifyMediaStore(app, listOf(request.output))
                    }
                    is OpRequest.Extract -> {
                        if (pending.value is Pending.ExtractTo) pending.value = null
                        if (request.openAfter != null) {
                            if (File(request.openAfter).exists()) openExtracted(request.openAfter)
                        } else {
                            message(R.string.msg_extracted, r.succeeded)
                            FileIntents.notifyMediaStore(app, r.outputs.map { it.path })
                            gallery.onChanged()
                        }
                    }
                }
                if (r.failures.isNotEmpty()) reportFailures(r)
                if (r.skipped > 0 && r.succeeded == 0 && r.failures.isEmpty()) message(R.string.msg_nothing_done)
                refresh()
                if (request !is OpRequest.Extract || request.openAfter == null) flash(r.outputs.map { it.path })
            }
            is OpOutcome.Failed -> {
                val e = outcome.error
                if (e is ArchiveException && request is OpRequest.Extract &&
                    (e.error == ArchiveError.PASSWORD_REQUIRED || e.error == ArchiveError.WRONG_PASSWORD)
                ) {
                    dialog.value = DialogState.Password(request.archive, wrong = e.error == ArchiveError.WRONG_PASSWORD) { pw ->
                        dialog.value = null
                        archive.update { a -> if (a?.path == request.archive) a.copy(password = pw) else a }
                        runExtract(request.copy(password = pw))
                    }
                } else {
                    message(errorText(e))
                }
                refresh()
                gallery.onChanged()
            }
            OpOutcome.Cancelled -> {
                message(R.string.msg_cancelled)
                refresh()
                gallery.onChanged()
            }
        }
    }

    /** A single file unpacked from an archive to be opened: media plays here, the rest goes to other apps. */
    private fun openExtracted(path: String) {
        val f = File(path)
        val kind = FileTypes.kindOf(f.name)
        if (isPlayable(kind) && settings.value.playerInApp) {
            player.open(listOf(PlayItem(path, f.name, kind == FileKind.VIDEO)), path)
        } else {
            eventChannel.trySend(UiEvent.Open(path))
        }
    }

    private fun reportFailures(r: OperationResult) {
        val first = r.failures.first()
        val unsafe = r.failures.firstOrNull { it.reason == FailureReason.UNSAFE_PATH }
        if (unsafe != null && r.failures.size == 1) {
            message(R.string.msg_unsafe_entries, unsafe.detail?.toIntOrNull() ?: 1); return
        }
        message(app.getString(R.string.msg_some_failed, r.failures.size, failureText(first.reason)))
    }

    private fun flash(paths: List<String>) {
        if (paths.isEmpty()) return
        highlight.value = paths.toSet()
        eventChannel.trySend(UiEvent.ScrollTo(paths.first()))
        highlightJob?.cancel()
        highlightJob = viewModelScope.launch {
            delay(2500)
            highlight.value = emptySet()
        }
    }

    // ------------------------------------------------------------------ archive viewer

    fun openArchiveViewer(path: String) {
        dialog.value = null
        clearSelection()
        stack.update { it + Route.Archive(path) }
        releaseViewerArchive()
        archive.value = ArchiveUi(path)
        loadArchive(path, null)
    }

    private fun closeArchiveViewer() {
        archive.value = null
        releaseViewerArchive()
    }

    private fun releaseViewerArchive() {
        val held = viewerArchive ?: return
        viewerArchive = null
        app.appScope.launch(Dispatchers.IO) { staging.release(held.second) }
    }

    /** The archive as a local file: itself, or a cache copy when it lives on a USB document tree. Main thread. */
    private suspend fun viewerArchiveFile(path: String): File {
        if (!Stores.isDocument(path)) return File(path)
        viewerArchive?.takeIf { it.first == path }?.let { return it.second.file }
        val staged = withContext(Dispatchers.IO) { staging.acquireArchive(path, null) }
        if (archive.value?.path != path) {
            // The viewer was closed while the copy was made.
            withContext(Dispatchers.IO) { staging.release(staged) }
            throw ArchiveException(ArchiveError.CORRUPT)
        }
        releaseViewerArchive()
        viewerArchive = path to staged
        return staged.file
    }

    private fun loadArchive(path: String, password: CharArray?) {
        viewModelScope.launch {
            archive.update { it?.copy(loading = true, error = null) }
            val result = runCatching {
                val file = viewerArchiveFile(path)
                withContext(Dispatchers.IO) { Archives.open(file, password).use { it.entries } }
            }
            if (archive.value?.path != path) return@launch
            result.onSuccess { entries ->
                archive.update { it?.copy(loading = false, entries = entries, password = password) }
            }.onFailure { e ->
                val err = (e as? ArchiveException)?.error ?: ArchiveError.CORRUPT
                archive.update { it?.copy(loading = false, error = err) }
                if (err == ArchiveError.PASSWORD_REQUIRED || err == ArchiveError.WRONG_PASSWORD) {
                    dialog.value = DialogState.Password(path, wrong = err == ArchiveError.WRONG_PASSWORD) { pw ->
                        dialog.value = null
                        loadArchive(path, pw)
                    }
                }
            }
        }
    }

    fun retryArchivePassword() {
        val a = archive.value ?: return
        loadArchive(a.path, null)
    }

    fun archiveOpenFolder(inner: String) {
        archive.update { it?.copy(inner = inner) }
        clearSelection()
    }

    /** Back inside the archive viewer: one folder up. False at the archive root. */
    fun archiveUp(): Boolean {
        val a = archive.value ?: return false
        if (a.inner.isEmpty()) return false
        archive.update { it?.copy(inner = a.inner.substringBeforeLast('/', "")) }
        clearSelection()
        return true
    }

    fun archiveNodeClick(node: ArchiveNode) {
        if (selection.value.isNotEmpty()) {
            toggleSelect(node.path); return
        }
        if (node.isDirectory) {
            archiveOpenFolder(node.path); return
        }
        val a = archive.value ?: return
        val dest = File(app.openCacheDir, Integer.toHexString((a.path + "|" + node.path).hashCode()))
        val strip = node.path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        runExtract(
            OpRequest.Extract(
                archive = a.path,
                destDir = dest.path,
                password = a.password,
                selection = setOf(node.path),
                stripPrefix = strip,
                openAfter = File(dest, node.name).path,
            ),
        )
    }

    /** Extract the current selection (or everything) from the archive viewer. */
    fun archiveExtract(choice: ExtractChoice, selection: Set<String>?, strip: String) {
        val a = archive.value ?: return
        dialog.value = null
        clearSelection()
        val parent = File(a.path).parent ?: return
        val count = selection?.size ?: 0
        when (choice) {
            ExtractChoice.HERE -> runExtract(OpRequest.Extract(a.path, parent, a.password, selection, strip))
            ExtractChoice.OWN_FOLDER -> runExtract(OpRequest.Extract(a.path, parent, a.password, selection, strip, smartFolder = true))
            ExtractChoice.CHOOSE -> choosePlaceForExtract(a.path, a.password, selection, strip, count)
        }
    }

    override fun onCleared() {
        player.close()
        releaseViewerArchive()
        super.onCleared()
    }

    // ------------------------------------------------------------------ messages

    fun message(@StringRes res: Int, vararg args: Any) = message(app.getString(res, *args))

    fun message(text: String) {
        messageChannel.trySend(text)
    }

    fun failureText(reason: FailureReason): String = app.getString(
        when (reason) {
            FailureReason.NOT_FOUND -> R.string.fail_not_found
            FailureReason.ACCESS_DENIED -> R.string.fail_access_denied
            FailureReason.INTO_ITSELF -> R.string.fail_into_itself
            FailureReason.ALREADY_EXISTS -> R.string.fail_exists
            FailureReason.NO_SPACE -> R.string.fail_no_space
            FailureReason.UNSAFE_PATH -> R.string.fail_unsafe
            FailureReason.IO_ERROR -> R.string.fail_io
        },
    )

    fun errorText(e: Throwable): String = when (e) {
        is ArchiveException -> app.getString(archiveErrorText(e.error))
        is FileOpException -> failureText(e.reason)
        is OutOfMemoryError -> app.getString(R.string.err_archive_memory)
        else -> {
            val reason = FileOps.reasonOf(e, null)
            if (reason == FailureReason.IO_ERROR) app.getString(R.string.msg_failed_with, e.message ?: e.javaClass.simpleName)
            else failureText(reason)
        }
    }

    companion object {
        const val LIMIT = 2000

        @StringRes
        fun archiveErrorText(e: ArchiveError): Int = when (e) {
            ArchiveError.PASSWORD_REQUIRED -> R.string.err_archive_password
            ArchiveError.WRONG_PASSWORD -> R.string.err_archive_wrong_password
            ArchiveError.UNSUPPORTED_METHOD -> R.string.err_archive_unsupported
            ArchiveError.CORRUPT -> R.string.err_archive_corrupt
            ArchiveError.MISSING_VOLUME -> R.string.err_archive_missing_volume
            ArchiveError.NOT_AN_ARCHIVE -> R.string.err_archive_not_archive
            ArchiveError.OUT_OF_MEMORY -> R.string.err_archive_memory
        }

        /** Direct children of [inner] ("" = root), including folders implied by deeper paths. */
        fun childrenOf(entries: List<ArchiveEntryInfo>, inner: String): List<ArchiveNode> {
            val prefix = if (inner.isEmpty()) "" else "$inner/"
            val folders = LinkedHashMap<String, ArchiveNode>()
            val files = ArrayList<ArchiveNode>()
            val counts = HashMap<String, MutableSet<String>>()
            for (e in entries) {
                if (!e.path.startsWith(prefix) || e.path == inner) continue
                val rest = e.path.removePrefix(prefix)
                val slash = rest.indexOf('/')
                if (slash >= 0 || e.isDirectory) {
                    val name = if (slash >= 0) rest.substring(0, slash) else rest
                    val full = prefix + name
                    if (slash >= 0) counts.getOrPut(full) { HashSet() } += rest.substring(slash + 1).substringBefore('/')
                    val existing = folders[full]
                    val modified = if (e.isDirectory && slash < 0) e.lastModified else existing?.lastModified ?: 0L
                    folders[full] = ArchiveNode(name, full, true, 0, modified, false, 0)
                } else {
                    files += ArchiveNode(rest, e.path, false, e.size, e.lastModified, e.encrypted, 0)
                }
            }
            val dirNodes = folders.values.map { it.copy(childCount = counts[it.path]?.size ?: 0) }
                .sortedWith { a, b -> com.offlinefiles.explorer.core.NaturalOrder.compare(a.name, b.name) }
            val fileNodes = files.sortedWith { a, b -> com.offlinefiles.explorer.core.NaturalOrder.compare(a.name, b.name) }
            return dirNodes + fileNodes
        }
    }
}

enum class ExtractChoice { HERE, OWN_FOLDER, CHOOSE }
