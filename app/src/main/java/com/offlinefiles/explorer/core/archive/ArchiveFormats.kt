package com.offlinefiles.explorer.core.archive

import java.io.File
import java.io.FileInputStream
import java.io.IOException

enum class ArchiveType(val extension: String) { ZIP("zip"), SEVEN_Z("7z") }

/** The file(s) that make up one archive. Split archives have several [parts] in order. */
data class ArchiveVolumes(val type: ArchiveType, val main: File, val parts: List<File>)

enum class ArchiveError {
    PASSWORD_REQUIRED,
    WRONG_PASSWORD,
    UNSUPPORTED_METHOD,
    CORRUPT,
    MISSING_VOLUME,
    NOT_AN_ARCHIVE,
    OUT_OF_MEMORY,
}

class ArchiveException(val error: ArchiveError, message: String? = null, cause: Throwable? = null) :
    IOException(message ?: error.name, cause)

object ArchiveFormats {
    private val sevenZSplit = Regex("""^(.+\.7z)\.(\d{3})$""", RegexOption.IGNORE_CASE)
    private val zipSplitPart = Regex("""^(.+)\.z(\d{2})$""", RegexOption.IGNORE_CASE)

    /** Archive type by file name, or null if this app doesn't open it. */
    fun typeOf(name: String): ArchiveType? {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".zip") -> ArchiveType.ZIP
            lower.endsWith(".7z") -> ArchiveType.SEVEN_Z
            sevenZSplit.matches(name) -> ArchiveType.SEVEN_Z
            zipSplitPart.matches(name) -> ArchiveType.ZIP
            else -> null
        }
    }

    fun isSplit(name: String): Boolean = sevenZSplit.matches(name) || zipSplitPart.matches(name)

    /** Name without archive extensions: "photos.zip" → "photos", "movie.7z.001" → "movie". */
    fun baseName(name: String): String {
        sevenZSplit.find(name)?.let { return it.groupValues[1].dropLast(3) }
        zipSplitPart.find(name)?.let { return it.groupValues[1] }
        val lower = name.lowercase()
        return when {
            lower.endsWith(".zip") -> name.dropLast(4)
            lower.endsWith(".7z") -> name.dropLast(3)
            else -> name.substringBeforeLast('.', name)
        }.ifBlank { name }
    }

    /**
     * Works out which files to read for the archive the user tapped.
     * "x.7z.003" → parts x.7z.001…x.7z.NNN; "x.z02" → the zip's last part "x.zip".
     */
    fun resolve(file: File): ArchiveVolumes {
        val dir = file.absoluteFile.parentFile ?: throw ArchiveException(ArchiveError.MISSING_VOLUME)
        sevenZSplit.find(file.name)?.let { m ->
            val base = m.groupValues[1]
            val digits = m.groupValues[2].length
            val parts = mutableListOf<File>()
            var i = 1
            while (true) {
                val part = File(dir, base + "." + i.toString().padStart(digits, '0'))
                if (!part.isFile) break
                parts += part
                i++
            }
            if (parts.isEmpty()) throw ArchiveException(ArchiveError.MISSING_VOLUME, "$base.001")
            return ArchiveVolumes(ArchiveType.SEVEN_Z, parts.first(), parts)
        }
        zipSplitPart.find(file.name)?.let { m ->
            val main = sequenceOf("zip", "ZIP", "Zip").map { File(dir, m.groupValues[1] + "." + it) }.firstOrNull { it.isFile }
                ?: throw ArchiveException(ArchiveError.MISSING_VOLUME, m.groupValues[1] + ".zip")
            return ArchiveVolumes(ArchiveType.ZIP, main, listOf(main))
        }
        val type = sniff(file) ?: typeOf(file.name) ?: throw ArchiveException(ArchiveError.NOT_AN_ARCHIVE)
        return ArchiveVolumes(type, file, listOf(file))
    }

    /** Detects the format from the first bytes, so a mislabeled file still opens correctly. */
    fun sniff(file: File): ArchiveType? {
        val head = ByteArray(6)
        val n = try {
            FileInputStream(file).use { it.read(head) }
        } catch (e: IOException) {
            return null
        }
        if (n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            (head[2].toInt() == 3 || head[2].toInt() == 5 || head[2].toInt() == 7) &&
            (head[3].toInt() == 4 || head[3].toInt() == 6 || head[3].toInt() == 8)
        ) return ArchiveType.ZIP
        val sig = byteArrayOf('7'.code.toByte(), 'z'.code.toByte(), 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
        if (n == 6 && head.contentEquals(sig)) return ArchiveType.SEVEN_Z
        return null
    }
}
