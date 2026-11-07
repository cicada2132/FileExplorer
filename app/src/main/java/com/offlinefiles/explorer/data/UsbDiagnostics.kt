package com.offlinefiles.explorer.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.storage.StorageManager
import androidx.annotation.RequiresApi
import com.offlinefiles.explorer.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last crash, what happened the last few times a USB drive was opened to the app, and how
 * Android currently describes the drives. Shown in Settings so a user can copy it when a drive won't open.
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
        appendLine("[Last crash]")
        appendLine(CrashLog.last(context) ?: "(none)")
        appendLine()
        appendLine("[How the app last ended (Android)]")
        exitReasons(context).ifEmpty { listOf("(none)") }.forEach { appendLine("- $it") }
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

    /** The most recent ways this app's process ended (Android 11+), newest first. Shows native crashes too. */
    private fun exitReasons(context: Context, max: Int = 5): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, max) }
            .getOrDefault(emptyList())
            .map { describe(it) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describe(e: ApplicationExitInfo): String {
        val reason = when (e.reason) {
            ApplicationExitInfo.REASON_CRASH -> "crash (Java)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash (native)"
            ApplicationExitInfo.REASON_EXIT_SELF -> "exit by itself"
            ApplicationExitInfo.REASON_ANR -> "not responding"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
            ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by the user"
            ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by the user"
            else -> "reason ${e.reason}"
        }
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(e.timestamp))
        return "$time $reason status=${e.status} ${e.description.orEmpty()}".trim()
    }
}
