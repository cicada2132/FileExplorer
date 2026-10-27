package com.offlinefiles.explorer.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offlinefiles.explorer.R
import com.offlinefiles.explorer.core.FileItem
import com.offlinefiles.explorer.core.archive.ArchiveFormats
import com.offlinefiles.explorer.ui.DialogState
import com.offlinefiles.explorer.ui.ExtractChoice
import com.offlinefiles.explorer.ui.MainViewModel
import com.offlinefiles.explorer.ui.components.FileThumb
import com.offlinefiles.explorer.ui.components.Format
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 8.dp)) { content() }
    }
}

@Composable
private fun SheetHeader(item: FileItem, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        FileThumb(item, size = 48.dp, showThumbnail = true)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    HorizontalDivider()
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The "⋮" menu of a single row. */
@Composable
fun ItemActionsSheet(vm: MainViewModel, item: FileItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isArchive = !item.isDirectory && ArchiveFormats.typeOf(item.name) != null
    val subtitle = if (item.isDirectory) Format.dateTime(context, item.lastModified)
    else "${Format.size(context, item.size)} · ${Format.dateTime(context, item.lastModified)}"
    fun act(block: () -> Unit) {
        onDismiss(); block()
    }
    Sheet(onDismiss) {
        SheetHeader(item, subtitle)
        if (item.isDirectory) {
            SheetAction(Icons.Filled.FolderOpen, stringResource(R.string.action_open)) { act { vm.openDir(item.path) } }
        } else if (isArchive) {
            SheetAction(Icons.Filled.Unarchive, stringResource(R.string.action_extract)) { vm.showDialog(DialogState.ArchiveActions(item)) }
            SheetAction(Icons.Filled.FolderOpen, stringResource(R.string.archive_view_contents)) { act { vm.openArchiveViewer(item.path) } }
        } else {
            SheetAction(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.action_open)) { act { vm.onItemClick(item) } }
        }
        if (!item.isDirectory) {
            SheetAction(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.action_open_with)) { act { vm.openWith(item.path) } }
        }
        SheetAction(Icons.Filled.ContentCopy, stringResource(R.string.action_copy)) { act { vm.startCopy(listOf(item.path), move = false) } }
        SheetAction(Icons.AutoMirrored.Filled.DriveFileMove, stringResource(R.string.action_move)) { act { vm.startCopy(listOf(item.path), move = true) } }
        SheetAction(Icons.Filled.DriveFileRenameOutline, stringResource(R.string.action_rename)) { vm.showDialog(DialogState.Rename(item.path)) }
        if (!item.isDirectory) {
            SheetAction(Icons.Filled.Share, stringResource(R.string.action_share)) { act { vm.share(listOf(item.path)) } }
        }
        SheetAction(Icons.Filled.Archive, stringResource(R.string.action_compress)) { vm.showDialog(DialogState.Compress(listOf(item.path))) }
        SheetAction(Icons.Filled.Info, stringResource(R.string.action_properties)) { vm.showDialog(DialogState.Properties(listOf(item.path))) }
        SheetAction(Icons.Filled.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error) {
            vm.showDialog(DialogState.ConfirmDelete(listOf(item.path)))
        }
    }
}

/** Shown when an archive is tapped. */
@Composable
fun ArchiveActionsSheet(vm: MainViewModel, item: FileItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val base = ArchiveFormats.baseName(item.name)
    val parts = if (ArchiveFormats.isSplit(item.name)) runCatching { ArchiveFormats.resolve(File(item.path)).parts.size }.getOrNull() else null
    val subtitle = listOfNotNull(
        Format.size(context, item.size),
        parts?.let { context.getString(R.string.archive_split_parts, it) },
    ).joinToString(" · ")
    Sheet(onDismiss) {
        SheetHeader(item, subtitle)
        SheetAction(Icons.Filled.FolderOpen, stringResource(R.string.archive_view_contents)) { vm.openArchiveViewer(item.path) }
        SheetAction(Icons.Filled.CreateNewFolder, stringResource(R.string.extract_to_folder, base)) { vm.extractArchive(item.path, ExtractChoice.OWN_FOLDER) }
        SheetAction(Icons.Filled.Unarchive, stringResource(R.string.extract_here)) { vm.extractArchive(item.path, ExtractChoice.HERE) }
        SheetAction(Icons.AutoMirrored.Filled.DriveFileMove, stringResource(R.string.extract_choose)) { vm.extractArchive(item.path, ExtractChoice.CHOOSE) }
        SheetAction(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.action_open_with)) {
            onDismiss(); vm.openWith(item.path)
        }
    }
}

/** Extract choices inside the archive viewer. */
@Composable
fun ArchiveExtractSheet(vm: MainViewModel, selection: Set<String>?, strip: String, onDismiss: () -> Unit) {
    val a = vm.archive.value ?: return
    val base = ArchiveFormats.baseName(a.name)
    val folder = File(a.path).parentFile?.name.orEmpty()
    Sheet(onDismiss) {
        Text(
            if (selection == null) stringResource(R.string.action_extract_all) else stringResource(R.string.action_extract_selected),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        SheetAction(Icons.Filled.CreateNewFolder, stringResource(R.string.extract_to_folder, base)) {
            vm.archiveExtract(ExtractChoice.OWN_FOLDER, selection, strip)
        }
        SheetAction(Icons.Outlined.Folder, stringResource(R.string.extract_next_to_archive, folder)) {
            vm.archiveExtract(ExtractChoice.HERE, selection, strip)
        }
        SheetAction(Icons.AutoMirrored.Filled.DriveFileMove, stringResource(R.string.extract_choose)) {
            vm.archiveExtract(ExtractChoice.CHOOSE, selection, strip)
        }
    }
}
