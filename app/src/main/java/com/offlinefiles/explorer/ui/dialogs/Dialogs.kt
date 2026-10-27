package com.offlinefiles.explorer.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinefiles.explorer.R
import com.offlinefiles.explorer.core.DirStats
import com.offlinefiles.explorer.core.FileOps
import com.offlinefiles.explorer.core.NameError
import com.offlinefiles.explorer.core.SortField
import com.offlinefiles.explorer.core.SortSpec
import com.offlinefiles.explorer.core.archive.ArchiveFormats
import com.offlinefiles.explorer.core.archive.ArchiveType
import com.offlinefiles.explorer.core.archive.CompressionLevel
import com.offlinefiles.explorer.data.MimeTypes
import com.offlinefiles.explorer.ui.DialogState
import com.offlinefiles.explorer.ui.MainViewModel
import com.offlinefiles.explorer.ui.components.Format
import com.offlinefiles.explorer.ui.gallery.MediaInfoSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DialogHost(vm: MainViewModel) {
    val state by vm.dialog.collectAsStateWithLifecycle()
    val close = { vm.showDialog(null) }
    val context = LocalContext.current
    when (val d = state) {
        null -> Unit
        is DialogState.NewFolder -> {
            val initial = remember(d) { FileOps.uniqueChild(File(d.parent), context.getString(R.string.default_folder_name), true).name }
            NameDialog(
                title = stringResource(R.string.action_new_folder),
                initial = initial,
                parent = File(d.parent),
                originalPath = null,
                isDirectory = true,
                confirmLabel = stringResource(R.string.action_create),
                onDismiss = close,
                onConfirm = { vm.createFolder(d.parent, it) },
            )
        }
        is DialogState.NewFile -> {
            val initial = remember(d) { FileOps.uniqueChild(File(d.parent), context.getString(R.string.default_file_name), false).name }
            NameDialog(
                title = stringResource(R.string.action_new_file),
                initial = initial,
                parent = File(d.parent),
                originalPath = null,
                isDirectory = false,
                confirmLabel = stringResource(R.string.action_create),
                onDismiss = close,
                onConfirm = { vm.createFile(d.parent, it) },
            )
        }
        is DialogState.Rename -> {
            val f = File(d.path)
            NameDialog(
                title = stringResource(R.string.action_rename),
                initial = f.name,
                parent = f.parentFile ?: File("/"),
                originalPath = d.path,
                isDirectory = f.isDirectory,
                confirmLabel = stringResource(R.string.action_rename),
                onDismiss = close,
                onConfirm = { vm.rename(d.path, it) },
            )
        }
        is DialogState.ConfirmDelete -> DeleteDialog(d.paths, onDismiss = close, onConfirm = { vm.delete(d.paths) })
        is DialogState.Properties -> PropertiesDialog(d.paths, onDismiss = close)
        DialogState.Sort -> {
            val settings by vm.settings.collectAsStateWithLifecycle()
            SortDialog(settings.sort, onChange = vm::setSort, onDismiss = close)
        }
        is DialogState.Compress -> CompressDialog(d.paths, onDismiss = close) { name, type, level, pw ->
            vm.compress(d.paths, name, type, level, pw)
        }
        is DialogState.Password -> PasswordDialog(File(d.archive).name, d.wrong, onDismiss = close, onSubmit = d.onSubmit)
        is DialogState.OpenAs -> OpenAsDialog(onDismiss = close) { mime ->
            close()
            vm.openAs(d.path, mime)
        }
        is DialogState.ItemActions -> ItemActionsSheet(vm, d.item, onDismiss = close)
        is DialogState.ArchiveActions -> ArchiveActionsSheet(vm, d.item, onDismiss = close)
        is DialogState.ArchiveExtract -> ArchiveExtractSheet(vm, d.selection, d.stripPrefix, onDismiss = close)
        is DialogState.MediaInfo -> MediaInfoSheet(d.item, onDismiss = close)
    }
}

@Composable
fun NameDialog(
    title: String,
    initial: String,
    parent: File,
    originalPath: String?,
    isDirectory: Boolean,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val baseEnd = remember(initial) { FileOps.splitName(initial, isDirectory).first.length }
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, baseEnd))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val name = value.text.trim()
    val error: String? = when (FileOps.validateName(name)) {
        NameError.EMPTY -> null
        NameError.INVALID_CHARS -> stringResource(R.string.name_invalid_chars)
        NameError.RESERVED -> stringResource(R.string.name_reserved)
        NameError.TOO_LONG -> stringResource(R.string.name_too_long)
        null -> {
            val target = File(parent, name)
            val clash = target.exists() && (originalPath == null || !FileOps.isSameFile(File(originalPath), target))
            if (clash) stringResource(R.string.name_exists) else null
        }
    }
    val canConfirm = name.isNotEmpty() && error == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                isError = error != null,
                supportingText = { if (error != null) Text(error) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canConfirm) onConfirm(name) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(enabled = canConfirm, onClick = { onConfirm(name) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun DeleteDialog(paths: List<String>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Delete, null) },
        title = { Text(stringResource(R.string.delete_title)) },
        text = {
            Text(
                if (paths.size == 1) stringResource(R.string.delete_body_single, File(paths[0]).name)
                else pluralStringResource(R.plurals.delete_body_many, paths.size, paths.size),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun SortDialog(sort: SortSpec, onChange: (SortSpec) -> Unit, onDismiss: () -> Unit) {
    val labels = listOf(
        SortField.NAME to R.string.sort_name,
        SortField.DATE to R.string.sort_date,
        SortField.SIZE to R.string.sort_size,
        SortField.TYPE to R.string.sort_type,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_sort)) },
        text = {
            Column {
                labels.forEach { (field, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = sort.field == field, role = Role.RadioButton) {
                                // Newest / largest first is what people usually want for date and size.
                                val asc = if (field == sort.field) sort.ascending else field == SortField.NAME || field == SortField.TYPE
                                onChange(SortSpec(field, asc))
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = sort.field == field, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(16.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = sort.ascending,
                        onClick = { onChange(sort.copy(ascending = true)) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text(stringResource(R.string.sort_ascending)) }
                    SegmentedButton(
                        selected = !sort.ascending,
                        onClick = { onChange(sort.copy(ascending = false)) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text(stringResource(R.string.sort_descending)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    )
}

@Composable
fun PasswordDialog(archiveName: String, wrong: Boolean, onDismiss: () -> Unit, onSubmit: (CharArray) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Lock, null) },
        title = { Text(stringResource(R.string.password_title)) },
        text = {
            Column {
                Text(
                    if (wrong) stringResource(R.string.password_wrong) else stringResource(R.string.password_body, archiveName),
                    color = if (wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.password_label)) },
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) {
                            Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, stringResource(R.string.password_toggle))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (pw.isNotEmpty()) onSubmit(pw.toCharArray()) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(enabled = pw.isNotEmpty(), onClick = { onSubmit(pw.toCharArray()) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun OpenAsDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val options = listOf(
        R.string.open_as_text to "text/plain",
        R.string.open_as_image to "image/*",
        R.string.open_as_video to "video/*",
        R.string.open_as_audio to "audio/*",
        R.string.open_as_other to "*/*",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.open_as_title)) },
        text = {
            Column {
                Text(stringResource(R.string.open_as_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                options.forEach { (label, mime) ->
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(mime) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun CompressDialog(
    paths: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String, ArchiveType, CompressionLevel, CharArray?) -> Unit,
) {
    val files = remember(paths) { paths.map(::File) }
    val parent = files.first().parentFile ?: File("/")
    val defaultBase = remember(paths) {
        val f = files.first()
        val base = if (files.size == 1) FileOps.splitName(f.name, f.isDirectory).first else parent.name.ifEmpty { "archive" }
        base.ifBlank { "archive" }
    }
    var name by remember { mutableStateOf(TextFieldValue(defaultBase, TextRange(0, defaultBase.length))) }
    var type by remember { mutableStateOf(ArchiveType.ZIP) }
    var level by remember { mutableStateOf(CompressionLevel.NORMAL) }
    var usePassword by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }

    val base = name.text.trim()
    val fullName = "$base.${type.extension}"
    val nameError: String? = when (FileOps.validateName(fullName)) {
        NameError.INVALID_CHARS -> stringResource(R.string.name_invalid_chars)
        NameError.TOO_LONG -> stringResource(R.string.name_too_long)
        NameError.RESERVED -> stringResource(R.string.name_reserved)
        else -> if (base.isNotEmpty() && File(parent, fullName).exists()) stringResource(R.string.name_exists) else null
    }
    val pwError = usePassword && pw2.isNotEmpty() && pw != pw2
    val canConfirm = base.isNotEmpty() && nameError == null && (!usePassword || (pw.isNotEmpty() && pw == pw2))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compress_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.compress_name)) },
                    suffix = { Text(".${type.extension}") },
                    isError = nameError != null,
                    supportingText = { if (nameError != null) Text(nameError) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.compress_format), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ArchiveType.entries.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = type == t,
                            onClick = { type = t },
                            shape = SegmentedButtonDefaults.itemShape(i, ArchiveType.entries.size),
                        ) { Text(t.extension.uppercase()) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.compress_level), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                val levels = listOf(
                    CompressionLevel.STORE to R.string.level_store,
                    CompressionLevel.FAST to R.string.level_fast,
                    CompressionLevel.NORMAL to R.string.level_normal,
                    CompressionLevel.MAX to R.string.level_max,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    levels.forEachIndexed { i, (l, label) ->
                        SegmentedButton(
                            selected = level == l,
                            onClick = { level = l },
                            shape = SegmentedButtonDefaults.itemShape(i, levels.size),
                            icon = {},
                        ) { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Clip) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { usePassword = !usePassword }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = usePassword, onCheckedChange = { usePassword = it })
                    Text(stringResource(R.string.compress_use_password))
                }
                if (usePassword) {
                    val visual = if (show) VisualTransformation.None else PasswordVisualTransformation()
                    OutlinedTextField(
                        value = pw,
                        onValueChange = { pw = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.password_label)) },
                        visualTransformation = visual,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { show = !show }) {
                                Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, stringResource(R.string.password_toggle))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = pw2,
                        onValueChange = { pw2 = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.password_confirm)) },
                        visualTransformation = visual,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = pwError,
                        supportingText = {
                            Text(
                                if (pwError) stringResource(R.string.password_mismatch) else stringResource(R.string.compress_password_note),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = { onConfirm(base, type, level, if (usePassword) pw.toCharArray() else null) },
            ) { Text(stringResource(R.string.action_compress)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun PropertiesDialog(paths: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val files = remember(paths) { paths.map(::File) }
    val single = files.singleOrNull()
    var stats by remember { mutableStateOf<DirStats?>(null) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(paths) {
        withContext(Dispatchers.IO) {
            var acc = DirStats()
            for (f in files) {
                val base = acc
                val s = FileOps.measureTree(f) { partial ->
                    stats = DirStats(base.files + partial.files, base.folders + partial.folders, base.bytes + partial.bytes)
                }
                acc = DirStats(acc.files + s.files, acc.folders + s.folders, acc.bytes + s.bytes)
                stats = acc
            }
            done = true
        }
    }
    val calculating = stringResource(R.string.props_calculating)
    val sizeText = stats?.let { s ->
        val bytes = context.getString(R.string.props_bytes, "%,d".format(s.bytes))
        "${Format.size(context, s.bytes)} ($bytes)" + if (!done) " …" else ""
    } ?: calculating

    val rows = buildList {
        if (single != null) {
            add(R.string.props_name to single.name)
            add(R.string.props_location to (single.parent ?: "/"))
            if (single.isDirectory) {
                add(R.string.props_type to context.getString(R.string.props_type_folder))
                add(R.string.props_size to sizeText)
                stats?.let { s -> add(R.string.props_contents to context.getString(R.string.props_contents_value, s.files, s.folders)) }
            } else {
                val ext = ArchiveFormats.typeOf(single.name)?.let { " (${it.extension.uppercase()})" } ?: ""
                add(R.string.props_type to MimeTypes.of(single.name) + ext)
                add(R.string.props_size to "${Format.size(context, single.length())} (${context.getString(R.string.props_bytes, "%,d".format(single.length()))})")
            }
            add(R.string.props_modified to Format.dateTime(context, single.lastModified()))
            val access = listOfNotNull(
                if (single.canRead()) context.getString(R.string.props_readable) else null,
                if (single.canWrite()) context.getString(R.string.props_writable) else null,
            ).joinToString(", ").ifEmpty { "-" }
            add(R.string.props_access to access)
            if (single.name.startsWith(".")) add(R.string.props_hidden to context.getString(R.string.props_yes))
        } else {
            add(R.string.props_selected to context.getString(R.string.props_selected_value, files.size))
            val parents = files.mapNotNull { it.parent }.distinct()
            if (parents.size == 1) add(R.string.props_location to parents[0])
            add(R.string.props_size to sizeText)
            stats?.let { s -> add(R.string.props_contents to context.getString(R.string.props_contents_value, s.files, s.folders)) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_properties)) },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    rows.forEach { (label, value) ->
                        Column {
                            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Normal)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
