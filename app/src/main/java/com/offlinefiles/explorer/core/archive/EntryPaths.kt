package com.offlinefiles.explorer.core.archive

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32

internal object EntryPaths {

    /**
     * Turns an archive entry name into a safe relative path ("a/b/c.txt"), or null when the
     * name tries to escape the destination ("../", absolute paths) or is empty.
     */
    fun normalize(raw: String): String? {
        var s = raw.replace('\\', '/')
        if (s.length >= 2 && s[1] == ':' && s[0].isLetter()) s = s.substring(2)
        val parts = s.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/") { sanitizeSegment(it) }
    }

    /** Replaces characters that SD cards / USB drives (FAT, exFAT) can't store. */
    fun sanitizeSegment(seg: String): String {
        val sb = StringBuilder(seg.length)
        for (c in seg) sb.append(if (c.code < 0x20 || c in "\"*:<>?|") '_' else c)
        val trimmed = sb.toString().trimEnd(' ', '.')
        return if (trimmed.isEmpty()) "_" else trimmed
    }

    fun parentOf(path: String): String = path.substringBeforeLast('/', "")

    fun nameOf(path: String): String = path.substringAfterLast('/')
}

/**
 * Decodes zip entry names. Zips made by Korean Windows tools usually store names in CP949
 * without the UTF-8 flag; macOS and Linux tools often store UTF-8 without the flag.
 */
internal object ZipNames {

    val koreanCharset: Charset by lazy {
        listOf("x-windows-949", "MS949", "windows-949", "CP949", "EUC-KR")
            .firstNotNullOfOrNull { name -> runCatching { Charset.forName(name) }.getOrNull() }
            ?: Charsets.UTF_8
    }

    private fun strictDecoder(cs: Charset): CharsetDecoder = cs.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    fun isValidUtf8(bytes: ByteArray): Boolean = try {
        strictDecoder(Charsets.UTF_8).decode(ByteBuffer.wrap(bytes)); true
    } catch (e: Exception) {
        false
    }

    private fun isAscii(bytes: ByteArray) = bytes.all { it >= 0 }

    /** Picks one charset for all names in the archive that lack the UTF-8 flag. */
    fun detectCharset(unflaggedNames: List<ByteArray>): Charset {
        val nonAscii = unflaggedNames.filterNot { isAscii(it) }
        if (nonAscii.isEmpty()) return Charsets.UTF_8
        if (nonAscii.all { isValidUtf8(it) }) return Charsets.UTF_8
        return koreanCharset
    }

    /**
     * Info-ZIP "Unicode Path" extra field (0x7075): version(1) + CRC32 of the raw name(4) + UTF-8 name.
     * Only trusted when the CRC matches the stored name.
     */
    fun unicodePathFromExtra(rawName: ByteArray, data: ByteArray?): String? {
        if (data == null || data.size < 6 || data[0].toInt() != 1) return null
        val crc = (data[1].toLong() and 0xFF) or ((data[2].toLong() and 0xFF) shl 8) or
            ((data[3].toLong() and 0xFF) shl 16) or ((data[4].toLong() and 0xFF) shl 24)
        val actual = CRC32().apply { update(rawName) }.value
        if (crc != actual) return null
        val nameBytes = data.copyOfRange(5, data.size)
        return if (isValidUtf8(nameBytes)) String(nameBytes, Charsets.UTF_8) else null
    }

    fun decode(raw: ByteArray, utf8Flag: Boolean, unicodeExtra: String?, fallback: Charset): String = when {
        utf8Flag -> String(raw, Charsets.UTF_8)
        unicodeExtra != null -> unicodeExtra
        else -> String(raw, fallback)
    }
}
