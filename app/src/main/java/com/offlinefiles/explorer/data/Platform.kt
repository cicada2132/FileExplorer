package com.offlinefiles.explorer.data

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.offlinefiles.explorer.R
import com.offlinefiles.explorer.core.FileTypes
import java.io.File

object StoragePermission {
    /** Android 11+ uses "All files access"; older versions use the classic runtime permissions. */
    val usesAllFilesAccess: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    val legacyPermissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    fun isGranted(context: Context): Boolean =
        if (usesAllFilesAccess) {
            runCatching { Environment.isExternalStorageManager() }
                .getOrElse { Environment.isExternalStorageManager(Environment.getExternalStorageDirectory()) }
        } else {
            legacyPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        }

    fun allFilesAccessIntents(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    )

    fun appDetailsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}

object MimeTypes {
    fun of(name: String): String {
        val ext = FileTypes.extensionOf(name)
        if (ext.isEmpty()) return "*/*"
        return FileTypes.fallbackMime(name)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "*/*"
    }
}

/**
 * Hands files to other apps through content:// URIs (never raw file paths). Files on USB drives
 * opened through the system file picker go out with their document URIs.
 */
object FileIntents {
    private fun uriFor(context: Context, path: String): Uri =
        Stores.tree(path)?.documentUri(path)
            ?: FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))

    /** Returns false when no installed app can open this type, so the caller can offer "Open as". */
    fun open(context: Context, path: String, mimeOverride: String? = null, chooser: Boolean = false): Boolean {
        val mime = mimeOverride ?: MimeTypes.of(File(path).name)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, path), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return try {
            context.startActivity(if (chooser) Intent.createChooser(intent, context.getString(R.string.action_open_with)) else intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /** Folders are left out; paths on USB document trees were checked by the caller. */
    fun share(context: Context, paths: List<String>): Boolean {
        val shareable = paths.filter { Stores.isDocument(it) || File(it).isFile }
        if (shareable.isEmpty()) return false
        val uris = shareable.map { uriFor(context, it) }
        val types = shareable.map { MimeTypes.of(File(it).name) }.distinct()
        val mime = when {
            types.size == 1 -> types[0]
            types.map { it.substringBefore('/') }.distinct().size == 1 -> types[0].substringBefore('/') + "/*"
            else -> "*/*"
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = mime
        intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** "Use as" (wallpaper, contact photo…), offered by whichever apps handle it. */
    fun setAs(context: Context, path: String, mimeOverride: String? = null): Boolean {
        val mime = mimeOverride ?: MimeTypes.of(File(path).name)
        val intent = Intent(Intent.ACTION_ATTACH_DATA)
            .setDataAndType(uriFor(context, path), mime)
            .putExtra("mimeType", mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_set_as)))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /**
     * Android 10 and older don't notice file changes by themselves; newer versions do.
     * Drives opened through the file picker aren't in the media library, so they're skipped.
     */
    fun notifyMediaStore(context: Context, paths: Collection<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || paths.isEmpty()) return
        val expanded = paths.filterNot(Stores::isDocument).flatMap { p ->
            val f = File(p)
            if (f.isDirectory) f.walkTopDown().take(2000).map { it.path }.toList() else listOf(p)
        }
        if (expanded.isEmpty()) return
        MediaScannerConnection.scanFile(context.applicationContext, expanded.toTypedArray(), null, null)
    }
}
