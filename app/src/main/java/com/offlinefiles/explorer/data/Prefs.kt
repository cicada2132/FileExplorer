package com.offlinefiles.explorer.data

import android.content.Context
import com.offlinefiles.explorer.core.SortSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ViewMode { LIST, GRID }

data class Settings(
    val sort: SortSpec = SortSpec(),
    val viewMode: ViewMode = ViewMode.LIST,
    val showHidden: Boolean = false,
    val showThumbnails: Boolean = true,
    val showFolderCounts: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    /** Tapping a photo in the file list opens the built-in viewer instead of another app. */
    val openMediaInApp: Boolean = true,
    /** Gallery thumbnail size: 0 small, 1 medium, 2 large. */
    val galleryZoom: Int = 1,
)

/** Small, synchronous settings store. SharedPreferences is plenty for a handful of flags. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val settings: StateFlow<Settings> = state.asStateFlow()

    fun update(transform: (Settings) -> Settings) {
        val next = transform(state.value)
        if (next == state.value) return
        state.value = next
        sp.edit()
            .putString("sort_field", next.sort.field.name)
            .putBoolean("sort_asc", next.sort.ascending)
            .putString("view_mode", next.viewMode.name)
            .putBoolean("show_hidden", next.showHidden)
            .putBoolean("thumbnails", next.showThumbnails)
            .putBoolean("folder_counts", next.showFolderCounts)
            .putString("theme", next.theme.name)
            .putBoolean("dynamic_color", next.dynamicColor)
            .putBoolean("open_media_in_app", next.openMediaInApp)
            .putInt("gallery_zoom", next.galleryZoom)
            .apply()
    }

    private fun read(): Settings {
        val d = Settings()
        return Settings(
            sort = SortSpec(
                field = enumOr(sp.getString("sort_field", null), d.sort.field),
                ascending = sp.getBoolean("sort_asc", d.sort.ascending),
            ),
            viewMode = enumOr(sp.getString("view_mode", null), d.viewMode),
            showHidden = sp.getBoolean("show_hidden", d.showHidden),
            showThumbnails = sp.getBoolean("thumbnails", d.showThumbnails),
            showFolderCounts = sp.getBoolean("folder_counts", d.showFolderCounts),
            theme = enumOr(sp.getString("theme", null), d.theme),
            dynamicColor = sp.getBoolean("dynamic_color", d.dynamicColor),
            openMediaInApp = sp.getBoolean("open_media_in_app", d.openMediaInApp),
            galleryZoom = sp.getInt("gallery_zoom", d.galleryZoom).coerceIn(0, 2),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback
}
