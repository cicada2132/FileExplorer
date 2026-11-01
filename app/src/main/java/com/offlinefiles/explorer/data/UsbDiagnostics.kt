package com.offlinefiles.explorer.data

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.storage.StorageManager
import com.offlinefiles.explorer.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What happened the last few times a USB drive was opened to the app, plus how Android currently
 * describes the drives. Shown in Settings so a user can copy it when a drive won't open.
 * Kept on the device only (the app has no network access).
 */
object UsbDiagnostics {
    private const val PREFS = "usb_diagnostics"
    private const val KEY = "log"
    private const val MAX_LINES = 60

    fun log(context: Context, message: String) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val lines = sp.getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() } + "$time $message"
        sp.edit().putString(KEY, lines.takeLast(MAX_LINES).joinToString("\n")).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun report(context: Context): String = buildString {
        appendLine("App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("All files access: ${StoragePermission.isGranted(context)}")
        appendLine()
        appendLine("[System volumes]")
        val sm = context.getSystemService(StorageManager::class.java)
        runCatching {
            for (v in sm?.storageVolumes.orEmpty()) {
                val dir: File? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) v.directory else null
                val listed = runCatching { dir?.list()?.size }.getOrNull()
                appendLine(
                    "- ${runCatching { v.getDescription(context) }.getOrNull()} uuid=${v.uuid} state=${v.state} " +
                        "primary=${v.isPrimary} removable=${v.isRemovable} dir=${dir?.path} list=${listed ?: "null"}",
                )
            }
        }.onFailure { appendLine("! ${it.javaClass.simpleName}: ${it.message}") }
        appendLine()
        appendLine("[App volumes]")
        runCatching {
            for (v in StorageVolumes.list(context)) {
                appendLine("- ${v.label} kind=${v.kind} access=${v.access} uuid=${v.uuid} path=${v.path} readOnly=${v.readOnly} tree=${v.treeUri}")
            }
        }.onFailure { appendLine("! ${it.javaClass.simpleName}: ${it.message}") }
        appendLine()
        appendLine("[Granted folders]")
        runCatching {
            val perms = context.contentResolver.persistedUriPermissions
            if (perms.isEmpty()) appendLine("(none)")
            for (p in perms) appendLine("- ${p.uri} read=${p.isReadPermission} write=${p.isWritePermission}")
        }.onFailure { appendLine("! ${it.javaClass.simpleName}: ${it.message}") }
        appendLine()
        appendLine("[Recent steps]")
        val log = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        append(log.ifBlank { "(none)" })
    }

    /** One line describing an intent sent to or returned by the system file picker. */
    fun describe(intent: Intent?): String {
        if (intent == null) return "null"
        val extras = runCatching { intent.extras?.keySet()?.joinToString { k -> "$k=${@Suppress("DEPRECATION") intent.extras?.get(k)}" } }.getOrNull()
        return "action=${intent.action} data=${intent.data} flags=0x${Integer.toHexString(intent.flags)} extras={${extras.orEmpty()}}"
    }
}
