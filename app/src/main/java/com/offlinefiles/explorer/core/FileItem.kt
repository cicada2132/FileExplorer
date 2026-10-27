package com.offlinefiles.explorer.core

import com.offlinefiles.explorer.core.archive.ArchiveFormats
import java.io.File

/** What a file is, as far as icons, thumbnails and "open" behaviour are concerned. */
enum class FileKind { FOLDER, IMAGE, VIDEO, AUDIO, PDF, DOCUMENT, TEXT, ARCHIVE, APK, CODE, OTHER }

/** One row in a directory listing. Immutable snapshot taken when the folder was read. */
data class FileItem(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val isHidden: Boolean,
) {
    val file: File get() = File(path)
    val extension: String = if (isDirectory) "" else FileTypes.extensionOf(name)
    val kind: FileKind = if (isDirectory) FileKind.FOLDER else FileTypes.kindOf(name)

    companion object {
        fun from(file: File): FileItem = FileItem(
            path = file.path,
            name = file.name,
            isDirectory = file.isDirectory,
            size = if (file.isDirectory) 0L else file.length(),
            lastModified = file.lastModified(),
            isHidden = file.name.startsWith("."),
        )
    }
}

object FileTypes {
    private val image = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "dng", "svg", "ico", "tif", "tiff")
    private val video = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "3g2", "ts", "m2ts", "mts", "mpg", "mpeg", "vob", "rmvb", "ogv", "f4v")
    private val audio = setOf("mp3", "wav", "flac", "aac", "m4a", "ogg", "oga", "opus", "wma", "amr", "mid", "midi", "ape", "alac", "aiff", "m4b")
    private val document = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "hwp", "hwpx", "rtf", "csv", "epub", "pages", "numbers", "key", "cell", "show")
    private val text = setOf("txt", "md", "log", "ini", "cfg", "conf", "srt", "smi", "ass", "vtt", "nfo")
    private val code = setOf("html", "htm", "xml", "json", "js", "ts", "css", "kt", "java", "py", "c", "cpp", "h", "sh", "yml", "yaml", "gradle", "sql", "php", "go", "rs", "swift")
    private val archive = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "alz", "egg")

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
    }

    fun kindOf(name: String): FileKind {
        val ext = extensionOf(name)
        return when {
            ext.isEmpty() -> FileKind.OTHER
            ext == "apk" || ext == "apks" || ext == "xapk" -> FileKind.APK
            ext == "pdf" -> FileKind.PDF
            ext in image -> FileKind.IMAGE
            ext in video -> FileKind.VIDEO
            ext in audio -> FileKind.AUDIO
            ext in document -> FileKind.DOCUMENT
            ext in text -> FileKind.TEXT
            ext in code -> FileKind.CODE
            ext in archive -> FileKind.ARCHIVE
            ArchiveFormats.typeOf(name) != null -> FileKind.ARCHIVE
            else -> FileKind.OTHER
        }
    }

    /** MIME types that Android's MimeTypeMap is known to miss or get wrong. */
    private val extraMime = mapOf(
        "7z" to "application/x-7z-compressed",
        "zip" to "application/zip",
        "rar" to "application/vnd.rar",
        "apk" to "application/vnd.android.package-archive",
        "hwp" to "application/x-hwp",
        "hwpx" to "application/haansofthwpx",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "ts" to "video/mp2t",
        "m2ts" to "video/mp2t",
        "flac" to "audio/flac",
        "opus" to "audio/ogg",
        "m4a" to "audio/mp4",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
        "webp" to "image/webp",
        "srt" to "text/plain",
        "smi" to "text/plain",
        "log" to "text/plain",
        "md" to "text/markdown",
        "csv" to "text/csv",
        "json" to "application/json",
        "epub" to "application/epub+zip",
    )

    fun fallbackMime(name: String): String? = extraMime[extensionOf(name)]

    /** Generic MIME for a kind, used for "open as" and when nothing better is known. */
    fun genericMime(kind: FileKind): String = when (kind) {
        FileKind.IMAGE -> "image/*"
        FileKind.VIDEO -> "video/*"
        FileKind.AUDIO -> "audio/*"
        FileKind.TEXT, FileKind.CODE -> "text/plain"
        FileKind.PDF -> "application/pdf"
        FileKind.APK -> "application/vnd.android.package-archive"
        else -> "*/*"
    }
}
