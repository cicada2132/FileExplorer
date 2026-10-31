package com.offlinefiles.explorer.core.player

import com.offlinefiles.explorer.core.FileTypes
import com.offlinefiles.explorer.core.archive.ZipNames
import java.io.File
import java.util.Locale

/** One subtitle line (or block of lines) and when it is on screen, in milliseconds. */
data class Cue(val start: Long, val end: Long, val text: String)

/**
 * Cues of one language, sorted by start time. Lookups are a binary search, so asking several
 * times a second during playback costs nothing even for files with thousands of lines.
 */
class SubtitleTrack(val label: String, cues: List<Cue>) {
    val cues: List<Cue> = cues.filter { it.end > it.start && it.text.isNotBlank() }.sortedBy { it.start }
    private val starts = LongArray(this.cues.size) { this.cues[it].start }
    private val longest = this.cues.maxOfOrNull { it.end - it.start } ?: 0L

    /** Text on screen at [positionMs]; overlapping cues are stacked in start order. Null when nothing shows. */
    fun textAt(positionMs: Long): String? {
        if (cues.isEmpty()) return null
        // Last cue that has started by now.
        var lo = 0
        var hi = starts.size - 1
        var last = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= positionMs) {
                last = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (last < 0) return null
        var lines: ArrayList<String>? = null
        var i = last
        while (i >= 0 && positionMs - starts[i] < longest) {
            val c = cues[i]
            if (positionMs < c.end) (lines ?: ArrayList<String>().also { lines = it }).add(0, c.text)
            i--
        }
        return lines?.joinToString("\n")
    }
}

/**
 * Reads the subtitle formats people actually have next to their videos: SubRip (.srt),
 * SAMI (.smi, common in Korea, often with several languages in one file), WebVTT (.vtt)
 * and SubStation Alpha (.ass/.ssa). Styling is dropped; line breaks are kept.
 */
object Subtitles {
    val EXTENSIONS = setOf("srt", "smi", "sami", "vtt", "ass", "ssa")

    /** Files bigger than this are not subtitles (or not worth holding in memory). */
    const val MAX_BYTES = 8L shl 20

    fun isSubtitle(name: String): Boolean = FileTypes.extensionOf(name) in EXTENSIONS

    /** Parses [file]; one track per language (only SAMI files can hold several). Empty when unreadable. */
    fun load(file: File): List<SubtitleTrack> {
        if (!file.isFile || file.length() > MAX_BYTES) return emptyList()
        val text = runCatching { decode(file.readBytes()) }.getOrNull() ?: return emptyList()
        return parse(text, FileTypes.extensionOf(file.name), file.name)
    }

    fun parse(text: String, extension: String, name: String): List<SubtitleTrack> {
        val tracks = when (extension.lowercase(Locale.ROOT)) {
            "smi", "sami" -> parseSami(text).map { (cls, cues) -> SubtitleTrack(if (cls.isEmpty()) name else "$name · $cls", cues) }
            "ass", "ssa" -> listOf(SubtitleTrack(name, parseAss(text)))
            else -> listOf(SubtitleTrack(name, parseSrt(text)))
        }
        return tracks.filter { it.cues.isNotEmpty() }
    }

    // ------------------------------------------------------------------ text encoding

    /** UTF-8/UTF-16 with or without BOM; anything else is taken as Korean Windows text (CP949). */
    fun decode(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        ZipNames.isValidUtf8(bytes) -> String(bytes, Charsets.UTF_8)
        else -> String(bytes, ZipNames.koreanCharset)
    }

    // ------------------------------------------------------------------ SubRip / WebVTT

    private val TIMING = Regex(
        """((?:\d+:)?\d{1,2}:\d{1,2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{1,2}[.,]\d{1,3})""",
    )

    /** SubRip and WebVTT share the "start --> end" line; numbering, headers and cue settings are ignored. */
    fun parseSrt(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            val m = TIMING.find(lines[i])
            if (m == null) {
                i++
                continue
            }
            val start = clock(m.groupValues[1])
            val end = clock(m.groupValues[2])
            val body = StringBuilder()
            i++
            while (i < lines.size && lines[i].isNotBlank() && TIMING.find(lines[i]) == null) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i].trim())
                i++
            }
            val clean = cleanMarkup(body.toString())
            if (start != null && end != null && clean.isNotEmpty()) out.add(Cue(start, end, clean))
        }
        return out
    }

    /** "01:02:03,456", "02:03.456", "1:02:03.4" → milliseconds. */
    internal fun clock(s: String): Long? {
        val parts = s.trim().replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val secParts = parts.last().split('.')
        val sec = secParts[0].toLongOrNull() ?: return null
        val frac = secParts.getOrNull(1)?.let { f -> (f + "00").take(3).toLongOrNull() } ?: 0L
        val min = parts[parts.size - 2].toLongOrNull() ?: return null
        val hour = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        return ((hour * 60 + min) * 60 + sec) * 1000 + frac
    }

    // ------------------------------------------------------------------ SAMI

    private val SYNC = Regex("""<sync\b[^>]*?\bstart\s*=\s*["']?(-?\d+)[^>]*>""", RegexOption.IGNORE_CASE)
    private val P_TAG = Regex("""<p\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val CLASS_ATTR = Regex("""class\s*=\s*["']?([\w-]+)""", RegexOption.IGNORE_CASE)
    private val STYLE_CLASS = Regex("""\.([\w-]+)\s*\{([^}]*)}""")
    private val STYLE_NAME = Regex("""name\s*:\s*([^;]+)""", RegexOption.IGNORE_CASE)
    private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val SOURCE_BREAK = Regex("""\s*[\r\n]+\s*""")

    /** SAMI is HTML: line breaks in the file are just spaces, only <br> starts a new line. */
    private fun samiText(raw: String): String = cleanMarkup(raw.replace(SOURCE_BREAK, " ").replace(BR, "\n"))

    /**
     * Cues per language class, in file order. A line lasts until the next SYNC of the same
     * class (an "&nbsp;" SYNC clears the screen); the very last one stays for five seconds.
     */
    fun parseSami(text: String): List<Pair<String, List<Cue>>> {
        val bodyAt = text.indexOf("<body", ignoreCase = true)
        val head = if (bodyAt >= 0) text.substring(0, bodyAt) else ""
        val body = if (bodyAt >= 0) text.substring(bodyAt) else text
        val names = HashMap<String, String>()
        for (m in STYLE_CLASS.findAll(head)) {
            STYLE_NAME.find(m.groupValues[2])?.let { names[m.groupValues[1].lowercase(Locale.ROOT)] = it.groupValues[1].trim() }
        }
        // (class, start, text) in file order
        val entries = ArrayList<Triple<String, Long, String>>()
        val syncs = SYNC.findAll(body).toList()
        for ((n, m) in syncs.withIndex()) {
            val start = m.groupValues[1].toLongOrNull() ?: continue
            val chunkEnd = syncs.getOrNull(n + 1)?.range?.first ?: body.length
            var chunk = body.substring(m.range.last + 1, chunkEnd)
            chunk.indexOf("</body", ignoreCase = true).let { if (it >= 0) chunk = chunk.substring(0, it) }
            val ps = P_TAG.findAll(chunk).toList()
            if (ps.isEmpty()) {
                entries.add(Triple("", start, samiText(chunk)))
                continue
            }
            for ((k, p) in ps.withIndex()) {
                val cls = CLASS_ATTR.find(p.groupValues[1])?.groupValues?.get(1).orEmpty()
                val pEnd = ps.getOrNull(k + 1)?.range?.first ?: chunk.length
                entries.add(Triple(cls, start, samiText(chunk.substring(p.range.last + 1, pEnd))))
            }
        }
        val byClass = LinkedHashMap<String, MutableList<Pair<Long, String>>>()
        for ((cls, start, t) in entries) byClass.getOrPut(cls.lowercase(Locale.ROOT)) { ArrayList() }.add(start to t)
        val labels = HashMap<String, String>()
        for ((cls, _, _) in entries) labels.putIfAbsent(cls.lowercase(Locale.ROOT), cls)
        return byClass.mapNotNull { (cls, list) ->
            val sorted = list.sortedBy { it.first }
            val cues = ArrayList<Cue>()
            for ((i, e) in sorted.withIndex()) {
                if (e.second.isBlank()) continue
                val end = sorted.getOrNull(i + 1)?.first ?: (e.first + 5000)
                cues.add(Cue(e.first, end, e.second))
            }
            if (cues.isEmpty()) null else (names[cls] ?: labels[cls].orEmpty()) to cues
        }.let { tracks -> if (tracks.size == 1) listOf("" to tracks[0].second) else tracks }
    }

    // ------------------------------------------------------------------ SubStation Alpha

    /** Dialogue lines of the [Events] section, read with the section's own Format order. */
    fun parseAss(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        var inEvents = false
        var fields = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            when {
                line.startsWith("Format:", ignoreCase = true) ->
                    fields = line.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
                line.startsWith("Dialogue:", ignoreCase = true) -> {
                    val values = line.substringAfter(':').split(',', limit = fields.size)
                    if (values.size < fields.size) continue
                    val start = clock(values[fields.indexOf("start")]) ?: continue
                    val end = clock(values[fields.indexOf("end")]) ?: continue
                    val body = values[fields.indexOf("text").takeIf { it >= 0 } ?: (fields.size - 1)]
                        .replace(Regex("""\{[^}]*}"""), "")
                        .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                        .split('\n').joinToString("\n") { it.trim() }.trim()
                    if (body.isNotEmpty()) out.add(Cue(start, end, body))
                }
            }
        }
        return out.sortedBy { it.start }
    }

    // ------------------------------------------------------------------ markup

    private val TAG = Regex("""<[^>]*>""")
    private val ASS_OVERRIDE = Regex("""\{\\[^}]*}""")
    private val NUMERIC_ENTITY = Regex("""&#(x?[0-9a-fA-F]+);""")

    /** Drops HTML-ish tags and {\an8} overrides, decodes the common entities, trims every line. */
    internal fun cleanMarkup(s: String): String {
        val noTags = s.replace(TAG, "").replace(ASS_OVERRIDE, "")
        val decoded = NUMERIC_ENTITY.replace(noTags) { m ->
            val v = m.groupValues[1]
            val code = if (v.startsWith("x") || v.startsWith("X")) v.drop(1).toIntOrNull(16) else v.toIntOrNull()
            code?.let { String(Character.toChars(it)) } ?: m.value
        }
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
        return decoded.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    // ------------------------------------------------------------------ finding files

    /**
     * Subtitle files in the video's folder. Ones named after the video ("movie.srt",
     * "movie.ko.smi") come first, the rest of the folder's subtitles after them.
     */
    fun findFor(video: File, siblings: List<File>): List<File> {
        val subs = siblings.filter { it.isFile && isSubtitle(it.name) }
        val (matching, others) = subs.partition { matches(video, it) }
        val byName = compareBy<File> { it.name.lowercase(Locale.ROOT) }
        return matching.sortedWith(compareBy<File> { it.name.length }.then(byName)) + others.sortedWith(byName)
    }

    /** "movie.srt", "movie.ko.srt", "movie_eng.smi" belong to "movie.mp4". */
    fun matches(video: File, subtitle: File): Boolean {
        val base = video.name.substringBeforeLast('.').lowercase(Locale.ROOT)
        val n = subtitle.name.substringBeforeLast('.').lowercase(Locale.ROOT)
        return n == base || n.startsWith("$base.") || n.startsWith("${base}_") || n.startsWith("$base ")
    }

    /** Which of the matching subtitle files to show at start: one in the user's language if there is one. */
    fun preferred(video: File, candidates: List<File>, language: String): File? {
        val matching = candidates.filter { matches(video, it) }
        if (matching.isEmpty()) return null
        val tags = when (language) {
            "ko" -> listOf(".ko", ".kor", ".korean", "_ko", "_kor", " ko", "한글", "한국어")
            "en" -> listOf(".en", ".eng", ".english", "_en", "_eng", " en")
            else -> listOf(".$language", "_$language")
        }
        return matching.firstOrNull { f -> val n = f.name.lowercase(Locale.ROOT); tags.any { n.contains(it) } } ?: matching.first()
    }
}
