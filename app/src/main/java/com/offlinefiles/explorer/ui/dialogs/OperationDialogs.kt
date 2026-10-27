package com.offlinefiles.explorer.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinefiles.explorer.R
import com.offlinefiles.explorer.core.Conflict
import com.offlinefiles.explorer.core.ConflictAction
import com.offlinefiles.explorer.core.ConflictDecision
import com.offlinefiles.explorer.core.Phase
import com.offlinefiles.explorer.ops.OpRequest
import com.offlinefiles.explorer.ops.RunningOp
import com.offlinefiles.explorer.ui.MainViewModel
import com.offlinefiles.explorer.ui.components.Format
import kotlinx.coroutines.delay

@Composable
fun OperationHost(vm: MainViewModel) {
    val op by vm.operations.running.collectAsStateWithLifecycle()
    val conflict by vm.operations.conflict.collectAsStateWithLifecycle()
    val current = op
    if (current != null && !current.hidden && conflict == null) {
        // Don't flash a dialog for operations that finish instantly.
        var visible by remember(current.id) { mutableStateOf(false) }
        LaunchedEffect(current.id) {
            delay(350)
            visible = true
        }
        if (visible) {
            ProgressDialog(
                op = current,
                onHide = { vm.operations.setHidden(true) },
                onCancel = vm.operations::cancel,
            )
        }
    }
    conflict?.let { pending ->
        ConflictDialog(pending.conflict, onDecide = vm.operations::answerConflict, onCancel = vm.operations::cancel)
    }
}

@Composable
private fun opTitle(request: OpRequest): String = stringResource(
    when (request) {
        is OpRequest.Transfer -> if (request.move) R.string.op_moving else R.string.op_copying
        is OpRequest.Delete -> R.string.op_deleting
        is OpRequest.Compress -> R.string.op_compressing
        is OpRequest.Extract -> if (request.openAfter != null) R.string.op_opening else R.string.op_extracting
    },
)

@Composable
fun ProgressDialog(op: RunningOp, onHide: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val p = op.progress
    val fraction = p.fraction
    AlertDialog(
        onDismissRequest = onHide,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(opTitle(op.request)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (p.phase == Phase.PREPARING) stringResource(R.string.op_preparing) else p.currentName.orEmpty(),
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                if (p.phase == Phase.RUNNING) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            fraction?.let { "${(it * 100).toInt()}%" }.orEmpty(),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (p.totalItems > 0) {
                            Text("${p.doneItems.coerceAtMost(p.totalItems)} / ${p.totalItems}", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (p.totalBytes > 0) {
                        Text(
                            "${Format.size(context, p.doneBytes.coerceAtMost(p.totalBytes))} / ${Format.size(context, p.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onHide) { Text(stringResource(R.string.op_hide)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun ConflictDialog(conflict: Conflict, onDecide: (ConflictDecision) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var applyAll by remember(conflict) { mutableStateOf(false) }
    val folders = conflict.sourceIsDirectory && conflict.targetIsDirectory
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
        title = { Text(stringResource(if (folders) R.string.conflict_title_folder else R.string.conflict_title_file)) },
        text = {
            Column {
                Text(conflict.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                Spacer(Modifier.height(8.dp))
                if (folders) {
                    Text(stringResource(R.string.conflict_folder_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        stringResource(
                            R.string.conflict_new,
                            if (conflict.sourceIsDirectory) "-" else Format.size(context, conflict.sourceSize),
                            Format.dateTime(context, conflict.sourceModified),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(
                            R.string.conflict_existing,
                            if (conflict.targetIsDirectory) "-" else Format.size(context, conflict.targetSize),
                            Format.dateTime(context, conflict.targetModified),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceButton(stringResource(if (folders) R.string.conflict_merge else R.string.conflict_overwrite)) {
                        onDecide(ConflictDecision(ConflictAction.OVERWRITE, applyAll))
                    }
                    ChoiceButton(stringResource(R.string.conflict_keep_both)) {
                        onDecide(ConflictDecision(ConflictAction.KEEP_BOTH, applyAll))
                    }
                    ChoiceButton(stringResource(R.string.conflict_skip)) {
                        onDecide(ConflictDecision(ConflictAction.SKIP, applyAll))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { applyAll = !applyAll }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = applyAll, onCheckedChange = { applyAll = it })
                    Text(stringResource(R.string.conflict_apply_all), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.conflict_cancel_all)) } },
    )
}

@Composable
private fun ChoiceButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Text(text)
    }
}
