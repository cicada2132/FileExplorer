package com.offlinefiles.explorer.ui.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinefiles.explorer.BuildConfig
import com.offlinefiles.explorer.R
import com.offlinefiles.explorer.data.ThemeMode
import com.offlinefiles.explorer.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    BackHandler { vm.back() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_up)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Section(stringResource(R.string.settings_display))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Contrast, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    val modes = listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { i, (mode, label) ->
                            SegmentedButton(
                                selected = s.theme == mode,
                                onClick = { vm.updateSettings { it.copy(theme = mode) } },
                                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            ) { Text(stringResource(label)) }
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow(Icons.Filled.Palette, stringResource(R.string.settings_dynamic_color), stringResource(R.string.settings_dynamic_color_desc), s.dynamicColor) { on ->
                    vm.updateSettings { it.copy(dynamicColor = on) }
                }
            }
            Section(stringResource(R.string.settings_list))
            SwitchRow(Icons.Filled.Visibility, stringResource(R.string.action_show_hidden), stringResource(R.string.settings_hidden_desc), s.showHidden) {
                vm.toggleHidden()
            }
            SwitchRow(Icons.Filled.Image, stringResource(R.string.settings_thumbnails), stringResource(R.string.settings_thumbnails_desc), s.showThumbnails) { on ->
                vm.updateSettings { it.copy(showThumbnails = on) }
            }
            SwitchRow(Icons.Filled.FolderCopy, stringResource(R.string.settings_folder_counts), stringResource(R.string.settings_folder_counts_desc), s.showFolderCounts) { on ->
                vm.updateSettings { it.copy(showFolderCounts = on) }
            }
            SwitchRow(Icons.Filled.PhotoLibrary, stringResource(R.string.settings_open_media), stringResource(R.string.settings_open_media_desc), s.openMediaInApp) { on ->
                vm.updateSettings { it.copy(openMediaInApp = on) }
            }
            Section(stringResource(R.string.settings_about))
            InfoRow(Icons.Filled.WifiOff, stringResource(R.string.settings_offline), stringResource(R.string.settings_offline_desc))
            InfoRow(Icons.Filled.Info, stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
            InfoRow(Icons.Filled.Description, stringResource(R.string.licenses_title), stringResource(R.string.licenses_desc), onClick = vm::openLicenses)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, desc: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClick != null) Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

private data class Library(val name: String, val owner: String, val license: String)

private val libraries = listOf(
    Library("Jetpack Compose, AndroidX Core / Activity / Lifecycle", "The Android Open Source Project", "Apache License 2.0"),
    Library("Material Icons", "Google", "Apache License 2.0"),
    Library("Kotlin Standard Library", "JetBrains s.r.o.", "Apache License 2.0"),
    Library("kotlinx.coroutines", "JetBrains s.r.o.", "Apache License 2.0"),
    Library("Apache Commons Compress", "The Apache Software Foundation", "Apache License 2.0"),
    Library("Apache Commons IO", "The Apache Software Foundation", "Apache License 2.0"),
    Library("Apache Commons Lang", "The Apache Software Foundation", "Apache License 2.0"),
    Library("Apache Commons Codec", "The Apache Software Foundation", "Apache License 2.0"),
    Library("Zip4j", "Srikanth Reddy Lingala", "Apache License 2.0"),
    Library("XZ for Java", "Lasse Collin, Igor Pavlov", "0BSD"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(vm: MainViewModel) {
    BackHandler { vm.back() }
    val context = LocalContext.current
    var showFull by remember { mutableStateOf<String?>(null) }
    val apache = remember { context.resources.openRawResource(R.raw.apache_license_2).bufferedReader().use { it.readText() } }
    val notice = remember { context.resources.openRawResource(R.raw.notices).bufferedReader().use { it.readText() } }
    val zeroBsd = remember { context.resources.openRawResource(R.raw.license_0bsd).bufferedReader().use { it.readText() } }
    BackHandler(enabled = showFull != null) { showFull = null }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licenses_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (showFull != null) showFull = null else vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_up))
                    }
                },
            )
        },
    ) { padding ->
        val full = showFull
        if (full != null) {
            Text(
                full,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
                item {
                    Text(
                        stringResource(R.string.licenses_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                }
                items(libraries) { lib ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable { showFull = if (lib.license == "0BSD") zeroBsd else apache }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text(lib.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${lib.owner} · ${lib.license}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().padding(16.dp).clickable { showFull = notice },
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.licenses_notices), modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.ChevronRight, null)
                        }
                    }
                }
            }
        }
    }
}
