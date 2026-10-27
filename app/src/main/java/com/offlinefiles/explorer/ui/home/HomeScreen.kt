package com.offlinefiles.explorer.ui.home

import android.os.Environment
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinefiles.explorer.R
import com.offlinefiles.explorer.core.gallery.MediaItem
import com.offlinefiles.explorer.data.VolumeInfo
import com.offlinefiles.explorer.data.VolumeKind
import com.offlinefiles.explorer.ui.MainViewModel
import com.offlinefiles.explorer.ui.browser.PendingBar
import com.offlinefiles.explorer.ui.components.Format
import com.offlinefiles.explorer.ui.components.Thumbnails
import com.offlinefiles.explorer.ui.gallery.GalleryModel
import com.offlinefiles.explorer.ui.gallery.countText
import com.offlinefiles.explorer.ui.gallery.rememberMediaThumb
import java.io.File

private data class Shortcut(val label: Int, val icon: ImageVector, val dir: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val gallery by vm.gallery.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.gallery.ensureLoaded() }
    val primary = volumes.firstOrNull { it.kind == VolumeKind.INTERNAL }
    val shortcuts = remember(primary?.path) {
        val root = primary?.path ?: Environment.getExternalStorageDirectory().path
        listOf(
            Shortcut(R.string.dir_download, Icons.Filled.Download, Environment.DIRECTORY_DOWNLOADS),
            Shortcut(R.string.dir_camera, Icons.Filled.CameraAlt, Environment.DIRECTORY_DCIM),
            Shortcut(R.string.dir_pictures, Icons.Filled.Image, Environment.DIRECTORY_PICTURES),
            Shortcut(R.string.dir_documents, Icons.Filled.Description, Environment.DIRECTORY_DOCUMENTS),
            Shortcut(R.string.dir_music, Icons.Filled.MusicNote, Environment.DIRECTORY_MUSIC),
            Shortcut(R.string.dir_movies, Icons.Filled.Movie, Environment.DIRECTORY_MOVIES),
        ).map { it.copy(dir = File(root, it.dir).path) }.filter { File(it.dir).isDirectory }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = vm::openSettings) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.settings_title))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = { pending?.let { PendingBar(it, canAct = false, onAct = {}, onCancel = vm::cancelPending) } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionTitle(stringResource(R.string.home_storage)) }
            items(volumes, key = { it.path }) { v -> VolumeCard(v) { vm.openDir(v.path) } }
            item { SectionTitle(stringResource(R.string.gallery_title)) }
            item { GalleryCard(gallery, onClick = vm::openGallery) }
            if (shortcuts.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.home_quick_access)) }
                items(shortcuts.chunked(3)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { s -> ShortcutTile(s, Modifier.weight(1f)) { vm.openDir(s.dir) } }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp),
    )
}

@Composable
private fun VolumeCard(v: VolumeInfo, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon = when (v.kind) {
        VolumeKind.INTERNAL -> Icons.Filled.PhoneAndroid
        VolumeKind.SD_CARD -> Icons.Filled.SdCard
        VolumeKind.USB -> Icons.Filled.Usb
    }
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(v.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                if (v.totalBytes > 0) {
                    Text(
                        stringResource(R.string.home_used_of, Format.shortSize(context, v.usedBytes), Format.shortSize(context, v.totalBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { v.usedFraction },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }
            if (v.totalBytes > 0) {
                Spacer(Modifier.width(14.dp))
                Text(
                    "${(v.usedFraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Photo and video counts plus the four newest; opens the gallery. */
@Composable
private fun GalleryCard(ui: GalleryModel.Ui, onClick: () -> Unit) {
    val index = ui.index
    val recent = index.items.take(4)
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PhotoLibrary, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            !ui.loaded -> stringResource(R.string.gallery_title)
                            index.items.isEmpty() -> stringResource(R.string.gallery_empty_title)
                            else -> countText(index.photoCount, index.videoCount)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when {
                            !ui.loaded -> stringResource(R.string.gallery_loading)
                            index.items.isEmpty() -> stringResource(R.string.gallery_empty_short)
                            else -> pluralStringResource(R.plurals.gallery_albums, index.albums.size, index.albums.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
            }
            if (recent.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { m -> RecentThumb(m, Modifier.weight(1f)) }
                    repeat(4 - recent.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun RecentThumb(item: MediaItem, modifier: Modifier) {
    val px = Thumbnails.mediaSize(with(LocalDensity.current) { 80.dp.roundToPx() })
    val thumb = rememberMediaThumb(item, px)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) {
            Image(thumb, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        if (item.isVideo) {
            Icon(Icons.Filled.PlayCircle, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ShortcutTile(s: Shortcut, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(s.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text(stringResource(s.label), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
