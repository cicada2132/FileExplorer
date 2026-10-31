package com.offlinefiles.explorer.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinefiles.explorer.R
import com.offlinefiles.explorer.data.FileIntents
import com.offlinefiles.explorer.data.StoragePermission
import com.offlinefiles.explorer.data.ThemeMode
import com.offlinefiles.explorer.ui.archive.ArchiveScreen
import com.offlinefiles.explorer.ui.browser.BrowserScreen
import com.offlinefiles.explorer.ui.dialogs.DialogHost
import com.offlinefiles.explorer.ui.dialogs.OperationHost
import com.offlinefiles.explorer.ui.gallery.AlbumScreen
import com.offlinefiles.explorer.ui.gallery.GalleryScreen
import com.offlinefiles.explorer.ui.gallery.MediaViewerHost
import com.offlinefiles.explorer.ui.home.HomeScreen
import com.offlinefiles.explorer.ui.permission.PermissionScreen
import com.offlinefiles.explorer.ui.player.PlayerHost
import com.offlinefiles.explorer.ui.settings.LicensesScreen
import com.offlinefiles.explorer.ui.settings.SettingsScreen
import com.offlinefiles.explorer.ui.theme.FileExplorerTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

@Composable
fun AppRoot(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    FileExplorerTheme(settings.theme, settings.dynamicColor) {
        val dark = when (settings.theme) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        // The photo viewer and the player are always black, so they want light status bar icons.
        val playerOpen by remember(vm) { vm.player.ui.map { it != null }.distinctUntilChanged() }.collectAsStateWithLifecycle(false)
        val viewerOpen = vm.gallery.viewer.collectAsStateWithLifecycle().value != null || playerOpen
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as? Activity)?.window ?: return@SideEffect
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark && !viewerOpen
                    isAppearanceLightNavigationBars = !dark && !viewerOpen
                }
            }
        }

        val context = LocalContext.current
        var granted by remember { mutableStateOf(StoragePermission.isGranted(context)) }
        LifecycleResumeEffect(Unit) {
            val now = StoragePermission.isGranted(context)
            if (now && !granted) vm.refreshVolumes()
            granted = now
            if (now) vm.onResume()
            onPauseOrDispose { }
        }
        if (!granted) {
            PermissionScreen(onGranted = {
                granted = true
                vm.refreshVolumes()
            })
            return@FileExplorerTheme
        }

        // The system file picker, for USB drives Android only shares with apps the user allows there.
        val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            vm.onAccessResult(if (result.resultCode == Activity.RESULT_OK) result.data?.data else null)
        }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
            vm.messages.collect { text ->
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(text)
            }
        }
        LaunchedEffect(Unit) {
            vm.events.collect { e ->
                when (e) {
                    is UiEvent.Open -> {
                        val ok = FileIntents.open(context, e.path, e.mime, e.chooser)
                        if (!ok) {
                            if (e.mime == null) vm.showDialog(DialogState.OpenAs(e.path)) else vm.message(R.string.msg_no_app)
                        }
                    }
                    is UiEvent.Share -> FileIntents.share(context, e.paths)
                    is UiEvent.ScrollTo -> vm.pendingScroll.value = e.path
                    is UiEvent.SetAs -> if (!FileIntents.setAs(context, e.path, e.mime)) vm.message(R.string.msg_no_app)
                    is UiEvent.RequestAccess -> {
                        val started = runCatching { accessLauncher.launch(e.intent) }.isSuccess
                        if (!started) vm.message(R.string.msg_no_picker)
                    }
                }
            }
        }

        val route by vm.route.collectAsStateWithLifecycle()
        when (val r = route) {
            Route.Home -> HomeScreen(vm, snackbar)
            is Route.Browse -> BrowserScreen(vm, snackbar)
            is Route.Archive -> ArchiveScreen(vm, snackbar)
            Route.Gallery -> GalleryScreen(vm, snackbar)
            is Route.Album -> AlbumScreen(vm, r.key, snackbar)
            Route.Settings -> SettingsScreen(vm)
            Route.Licenses -> LicensesScreen(vm)
        }
        // Drawn over whichever screen opened it, so that screen keeps its scroll position.
        MediaViewerHost(vm, snackbar)
        PlayerHost(vm, snackbar)
        DialogHost(vm)
        OperationHost(vm)
    }
}
