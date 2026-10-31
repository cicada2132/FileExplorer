package com.offlinefiles.explorer.ui.gallery

import android.content.Context
import android.os.Build
import com.offlinefiles.explorer.core.gallery.GalleryIndex
import com.offlinefiles.explorer.core.gallery.MediaItem
import com.offlinefiles.explorer.data.MediaLibrary
import com.offlinefiles.explorer.data.Stores
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class GalleryTab { PHOTOS, ALBUMS }

/**
 * What the full-screen viewer shows: an album from the gallery ([albumKey]), or a fixed list
 * ([items], e.g. the photos of a folder opened from the file list). [token] makes every
 * opening a fresh viewer.
 */
data class ViewerRequest(
    val albumKey: String?,
    val items: List<MediaItem>?,
    val startPath: String,
    val token: Long,
)

/**
 * Gallery state shared by the gallery screens, the home card and the viewer. Lives inside
 * MainViewModel (same scope), so it survives rotation and keeps grids where they were.
 */
class GalleryModel(private val context: Context, private val scope: CoroutineScope) {
    data class Ui(
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val index: GalleryIndex = GalleryIndex.EMPTY,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    val tab = MutableStateFlow(GalleryTab.PHOTOS)
    val selection = MutableStateFlow<Set<String>>(emptySet())
    val viewer = MutableStateFlow<ViewerRequest?>(null)

    /** Item to bring into view in the grid after the viewer closes. */
    val reveal = MutableStateFlow<String?>(null)

    /** Grid scroll positions per screen (timeline or album key). */
    val scrollMemory = HashMap<String, Pair<Int, Int>>()

    /** Where the items come from. Tests swap in the folder scan; devices use MediaStore. */
    internal var source: (Context) -> List<MediaItem> = MediaLibrary::load

    private var loadJob: Job? = null
    private var lastStamp: String? = null
    private var version = 0L
    private var nextToken = 1L

    /** Paths removed by this app recently. Android 10 and older may still list them for a moment. */
    private val removed = HashMap<String, Long>()

    // ------------------------------------------------------------------ loading

    fun ensureLoaded() {
        if (!_ui.value.loaded && loadJob?.isActive != true) reload()
    }

    fun reload(delayMs: Long = 0) {
        loadJob?.cancel()
        loadJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            _ui.update { it.copy(loading = true) }
            try {
                val stamp = withContext(Dispatchers.IO) { MediaLibrary.stamp(context) }
                val found = withContext(Dispatchers.IO) { source(context) }
                val now = System.currentTimeMillis()
                removed.entries.removeAll { now - it.value > 60_000L }
                val recentlyRemoved = HashSet(removed.keys)
                val index = withContext(Dispatchers.Default) {
                    val kept = if (recentlyRemoved.isEmpty()) found
                    else found.filterNot { it.path in recentlyRemoved && !File(it.path).exists() }
                    GalleryIndex.build(kept)
                }
                lastStamp = stamp
                publish(index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, loaded = true) }
            }
        }
    }

    private fun publish(index: GalleryIndex) {
        version++
        _ui.value = Ui(loading = false, loaded = true, index = index)
        val all = index.items.mapTo(HashSet()) { it.path }
        selection.update { sel -> if (sel.all { it in all }) sel else sel.filterTo(HashSet()) { it in all } }
    }

    /** Back in the app: re-read MediaStore unless it says nothing changed (Android 11+). */
    fun onResume() {
        if (!_ui.value.loaded || loadJob?.isActive == true) return
        scope.launch {
            val stamp = withContext(Dispatchers.IO) { MediaLibrary.stamp(context) }
            if (stamp == null || stamp != lastStamp) reload()
        }
    }

    /** Something on disk changed (copy, extract, rename…): re-read MediaStore shortly. */
    fun onChanged() {
        if (!_ui.value.loaded && loadJob?.isActive != true) return
        // Android 10 and older index changes in the background (see FileIntents.notifyMediaStore).
        reload(delayMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) 300L else 1500L)
    }

    /** Files (or whole folders) were deleted or moved away: drop them at once, then re-read. */
    fun onRemoved(paths: Collection<String>) {
        if (paths.isEmpty()) return
        scope.launch {
            val gone = withContext(Dispatchers.IO) { paths.filterNot { Stores.exists(it) } }
            if (gone.isNotEmpty()) {
                val now = System.currentTimeMillis()
                gone.forEach { removed[it] = now }
                val exact = gone.toHashSet()
                val prefixes = gone.map { "$it/" }
                val isGone = { p: String -> p in exact || prefixes.any { p.startsWith(it) } }
                selection.update { sel -> sel.filterNotTo(HashSet()) { isGone(it) } }
                viewer.update { v ->
                    val list = v?.items
                    if (v == null || list == null) v else v.copy(items = list.filterNot { isGone(it.path) })
                }
                val before = version
                val current = _ui.value.index
                val rebuilt = withContext(Dispatchers.Default) {
                    val kept = current.items.filterNot { isGone(it.path) }
                    if (kept.size == current.items.size) null else GalleryIndex.build(kept)
                }
                if (rebuilt != null && before == version && _ui.value.loaded) publish(rebuilt)
            }
            onChanged()
        }
    }

    /** A file was renamed: show the new name right away, MediaStore catches up on reload. */
    fun onRenamed(from: String, to: String) {
        val name = File(to).name
        val swap = { m: MediaItem -> if (m.path == from) m.copy(path = to, name = name) else m }
        selection.update { sel -> if (from in sel) sel - from + to else sel }
        viewer.update { v ->
            val list = v?.items
            if (v == null || list == null) v else v.copy(items = list.map(swap))
        }
        val current = _ui.value.index
        if (current.items.any { it.path == from }) {
            val before = version
            scope.launch {
                val rebuilt = withContext(Dispatchers.Default) { GalleryIndex.build(current.items.map(swap)) }
                if (before == version) publish(rebuilt)
            }
        }
        onChanged()
    }

    // ------------------------------------------------------------------ selection

    fun toggle(path: String) = selection.update { if (path in it) it - path else it + path }

    fun setSelected(paths: Collection<String>, selected: Boolean) =
        selection.update { if (selected) it + paths else it - paths.toSet() }

    fun clearSelection() {
        selection.value = emptySet()
    }

    // ------------------------------------------------------------------ viewer

    fun openViewer(albumKey: String, path: String) {
        viewer.value = ViewerRequest(albumKey, null, path, nextToken++)
    }

    fun openViewer(items: List<MediaItem>, path: String) {
        viewer.value = ViewerRequest(null, items, path, nextToken++)
    }

    fun closeViewer(lastPath: String?) {
        viewer.value = null
        if (lastPath != null) reveal.value = lastPath
    }

    fun find(path: String): MediaItem? = _ui.value.index.items.firstOrNull { it.path == path }
}
