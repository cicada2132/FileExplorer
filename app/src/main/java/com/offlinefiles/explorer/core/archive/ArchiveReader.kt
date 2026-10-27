package com.offlinefiles.explorer.core.archive

import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import org.apache.commons.compress.MemoryLimitException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipShort
import org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel
import org.tukaani.xz.CorruptedInputException
import org.tukaani.xz.UnsupportedOptionsException
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile
import net.lingala.zip4j.ZipFile as Zip4jFile

/** One entry inside an archive. [path] is normalized and safe to join under a destination. */
data class ArchiveEntryInfo(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val compressedSize: Long,
    val lastModified: Long,
    val encrypted: Boolean,
    internal val index: Int,
) {
    val name: String get() = EntryPaths.nameOf(path)
}

interface ArchiveReader : Closeable {
    val type: ArchiveType
    val entries: List<ArchiveEntryInfo>
    /** Entries skipped because their names would escape the destination folder. */
    val unsafeEntries: Int
    val hasEncryptedEntries: Boolean get() = entries.any { it.encrypted }

    /**
     * Visits wanted file entries in archive order (fastest for solid 7z archives).
     * Read errors from the stream surface as [SourceReadException] so callers can tell them
     * apart from errors writing the destination.
     */
    suspend fun readFiles(wanted: (ArchiveEntryInfo) -> Boolean, consumer: suspend (ArchiveEntryInfo, InputStream) -> Unit)

    /** Random access to a single entry. Slower than [readFiles] for solid 7z archives. */
    fun openEntry(entry: ArchiveEntryInfo): InputStream
}

object Archives {

    /** Opens [file] (or the split set it belongs to). Throws [ArchiveException] with a precise reason. */
    fun open(file: File, password: CharArray?): ArchiveReader {
        val volumes = ArchiveFormats.resolve(file)
        return try {
            when (volumes.type) {
                ArchiveType.ZIP -> openZip(volumes.main, password)
                ArchiveType.SEVEN_Z -> SevenZReader(volumes.parts, password)
            }
        } catch (e: Throwable) {
            throw translate(e, password)
        }
    }

    private fun openZip(file: File, password: CharArray?): ArchiveReader = try {
        Zip4jReader(file, password)
    } catch (e: ZipException) {
        // zip4j only knows Store/Deflate. Windows' built-in zipper uses Deflate64 for big files,
        // and some tools use BZip2 – Commons Compress reads those (but not encryption).
        if (e.type == ZipException.Type.UNKNOWN_COMPRESSION_METHOD) CommonsZipReader(file) else throw e
    }

    /** Maps library exceptions to [ArchiveException] so the UI can explain what happened. */
    fun translate(e: Throwable, password: CharArray?): Throwable {
        if (e is SourceReadException && e.cause != null) return translate(e.cause!!, password)
        if (e is ArchiveException) return e
        if (e is kotlinx.coroutines.CancellationException) return e
        if (e is OutOfMemoryError || e is MemoryLimitException) return ArchiveException(ArchiveError.OUT_OF_MEMORY, cause = e)
        if (e is PasswordRequiredException) {
            return ArchiveException(if (password == null) ArchiveError.PASSWORD_REQUIRED else ArchiveError.WRONG_PASSWORD, cause = e)
        }
        if (e is ZipException) {
            return when (e.type) {
                ZipException.Type.WRONG_PASSWORD ->
                    ArchiveException(if (password == null) ArchiveError.PASSWORD_REQUIRED else ArchiveError.WRONG_PASSWORD, cause = e)
                ZipException.Type.UNKNOWN_COMPRESSION_METHOD,
                ZipException.Type.UNSUPPORTED_ENCRYPTION -> ArchiveException(ArchiveError.UNSUPPORTED_METHOD, cause = e)
                ZipException.Type.FILE_NOT_FOUND -> ArchiveException(ArchiveError.MISSING_VOLUME, e.message, e)
                else -> ArchiveException(ArchiveError.CORRUPT, e.message, e)
            }
        }
        if (e is UnsupportedOptionsException) return ArchiveException(ArchiveError.UNSUPPORTED_METHOD, e.message, e)
        val msg = e.message.orEmpty()
        if (msg.contains("Unsupported compression method", ignoreCase = true)) {
            return ArchiveException(ArchiveError.UNSUPPORTED_METHOD, msg, e)
        }
        if (e is CorruptedInputException || msg.contains("Checksum verification failed", true) ||
            msg.contains("CRC", ignoreCase = false)
        ) {
            return ArchiveException(if (password != null) ArchiveError.WRONG_PASSWORD else ArchiveError.CORRUPT, msg, e)
        }
        if (msg.contains("signature", true) || msg.contains("Not a 7z", true) || msg.contains("Bad 7z", true)) {
            return ArchiveException(ArchiveError.CORRUPT, msg, e)
        }
        if (e is java.io.EOFException || msg.contains("truncated", true) || msg.contains("Premature end", true)) {
            return ArchiveException(ArchiveError.CORRUPT, msg, e)
        }
        return if (e is IOException) e else ArchiveException(ArchiveError.CORRUPT, msg, e)
    }
}

/** Wraps an error that happened while reading the archive (as opposed to writing the output). */
class SourceReadException(cause: Throwable) : IOException(cause.message, cause)

/** Opens the real stream on first use and tags its read errors as [SourceReadException]. */
private class SourceStream(private val opener: () -> InputStream) : InputStream() {
    private var inner: InputStream? = null

    private fun stream(): InputStream = inner ?: try {
        opener().also { inner = it }
    } catch (e: IOException) {
        throw SourceReadException(e)
    } catch (e: RuntimeException) {
        throw SourceReadException(e)
    }

    override fun read(): Int = try { stream().read() } catch (e: SourceReadException) { throw e } catch (e: IOException) { throw SourceReadException(e) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        try { stream().read(b, off, len) } catch (e: SourceReadException) { throw e } catch (e: IOException) { throw SourceReadException(e) }

    override fun close() {
        inner?.close()
    }
}

// ------------------------------------------------------------------ ZIP via zip4j (passwords, split zips)

private class Zip4jReader(file: File, password: CharArray?) : ArchiveReader {
    private val zip = Zip4jFile(file, password).apply {
        // Read names as raw bytes (ISO-8859-1 is 1:1) and decode them ourselves.
        charset = Charsets.ISO_8859_1
    }
    private val headers: List<FileHeader>
    override val type = ArchiveType.ZIP
    override val entries: List<ArchiveEntryInfo>
    override val unsafeEntries: Int

    init {
        try {
            // Not isValidZipFile(): it swallows the reason (e.g. an unknown compression method).
            headers = zip.fileHeaders
            if (zip.isSplitArchive && zip.splitZipFiles.any { !it.exists() }) {
                throw ArchiveException(ArchiveError.MISSING_VOLUME)
            }
        } catch (e: Throwable) {
            runCatching { zip.close() }
            throw e
        }
        val raw = headers.map { it.fileName.toByteArray(Charsets.ISO_8859_1) }
        val fallback = ZipNames.detectCharset(headers.indices.filter { !headers[it].isFileNameUTF8Encoded }.map { raw[it] })
        var unsafe = 0
        entries = headers.mapIndexedNotNull { i, h ->
            val extra = h.extraDataRecords?.firstOrNull { it.header == 0x7075L }?.data
            val name = ZipNames.decode(raw[i], h.isFileNameUTF8Encoded, ZipNames.unicodePathFromExtra(raw[i], extra), fallback)
            val path = EntryPaths.normalize(name)
            if (path == null) {
                if (name.replace('\\', '/').trim('/').isNotEmpty() && name.trim('/', '\\', '.').isNotEmpty()) unsafe++
                null
            } else {
                ArchiveEntryInfo(
                    path = path,
                    isDirectory = h.isDirectory || name.endsWith("/") || name.endsWith("\\"),
                    size = h.uncompressedSize,
                    compressedSize = h.compressedSize,
                    lastModified = h.lastModifiedTimeEpoch,
                    encrypted = h.isEncrypted,
                    index = i,
                )
            }
        }
        unsafeEntries = unsafe
    }

    override suspend fun readFiles(wanted: (ArchiveEntryInfo) -> Boolean, consumer: suspend (ArchiveEntryInfo, InputStream) -> Unit) {
        for (e in entries) {
            if (e.isDirectory || !wanted(e)) continue
            SourceStream { openEntry(e) }.use { consumer(e, it) }
        }
    }

    override fun openEntry(entry: ArchiveEntryInfo): InputStream = zip.getInputStream(headers[entry.index])

    override fun close() = zip.close()
}

// ------------------------------------------------------------------ ZIP via Commons Compress (Deflate64, BZip2…)

private class CommonsZipReader(file: File) : ArchiveReader {
    private val zip: CommonsZipFile = CommonsZipFile.builder()
        .setFile(file)
        .setCharset(Charsets.ISO_8859_1)
        .setUseUnicodeExtraFields(false)
        .get()
    private val raw: List<ZipArchiveEntry> = zip.entries.toList()
    override val type = ArchiveType.ZIP
    override val entries: List<ArchiveEntryInfo>
    override val unsafeEntries: Int

    init {
        val names = raw.map { it.rawName ?: it.name.toByteArray(Charsets.ISO_8859_1) }
        val fallback = ZipNames.detectCharset(raw.indices.filter { !raw[it].generalPurposeBit.usesUTF8ForNames() }.map { names[it] })
        var unsafe = 0
        entries = raw.mapIndexedNotNull { i, z ->
            val extra = z.getExtraField(ZipShort(0x7075))?.localFileDataData
            val name = ZipNames.decode(names[i], z.generalPurposeBit.usesUTF8ForNames(), ZipNames.unicodePathFromExtra(names[i], extra), fallback)
            val path = EntryPaths.normalize(name)
            if (path == null) {
                if (name.trim('/', '\\', '.').isNotEmpty()) unsafe++
                null
            } else {
                ArchiveEntryInfo(
                    path = path,
                    isDirectory = z.isDirectory || name.endsWith("\\"),
                    size = z.size.coerceAtLeast(0),
                    compressedSize = z.compressedSize.coerceAtLeast(0),
                    lastModified = z.time,
                    encrypted = z.generalPurposeBit.usesEncryption(),
                    index = i,
                )
            }
        }
        unsafeEntries = unsafe
    }

    override suspend fun readFiles(wanted: (ArchiveEntryInfo) -> Boolean, consumer: suspend (ArchiveEntryInfo, InputStream) -> Unit) {
        for (e in entries) {
            if (e.isDirectory || !wanted(e)) continue
            SourceStream { openEntry(e) }.use { consumer(e, it) }
        }
    }

    override fun openEntry(entry: ArchiveEntryInfo): InputStream {
        val z = raw[entry.index]
        if (z.generalPurposeBit.usesEncryption()) throw ArchiveException(ArchiveError.UNSUPPORTED_METHOD, "encrypted entry with unsupported method")
        if (!zip.canReadEntryData(z)) throw ArchiveException(ArchiveError.UNSUPPORTED_METHOD, "method ${z.method}")
        return zip.getInputStream(z)
    }

    override fun close() = zip.close()
}

// ------------------------------------------------------------------ 7z via Commons Compress (+ split .7z.001)

private class SevenZReader(parts: List<File>, password: CharArray?) : ArchiveReader {
    private val channel: SeekableByteChannel =
        if (parts.size == 1) Files.newByteChannel(parts[0].toPath(), StandardOpenOption.READ)
        else MultiReadOnlySeekableByteChannel.forFiles(*parts.toTypedArray())
    private val sz: SevenZFile
    private val raw: List<SevenZArchiveEntry>
    override val type = ArchiveType.SEVEN_Z
    override val entries: List<ArchiveEntryInfo>
    override val unsafeEntries: Int
    private val byRawIndex: Map<Int, ArchiveEntryInfo>

    init {
        try {
            val builder = SevenZFile.builder()
                .setSeekableByteChannel(channel)
                .setDefaultName(ArchiveFormats.baseName(parts[0].name))
                .setUseDefaultNameForUnnamedEntries(true)
                .setMaxMemoryLimitKiB(memoryLimitKiB())
            if (password != null) builder.setPassword(password)
            sz = builder.get()
        } catch (e: Throwable) {
            runCatching { channel.close() }
            throw e
        }
        raw = sz.entries.toList()
        var unsafe = 0
        val list = ArrayList<ArchiveEntryInfo>(raw.size)
        raw.forEachIndexed { i, e ->
            if (e.isAntiItem) return@forEachIndexed
            val name = e.name ?: return@forEachIndexed
            val path = EntryPaths.normalize(name)
            if (path == null) {
                if (name.trim('/', '\\', '.').isNotEmpty()) unsafe++
                return@forEachIndexed
            }
            val encrypted = runCatching { e.contentMethods?.any { it.method == SevenZMethod.AES256SHA256 } == true }.getOrDefault(false)
            list += ArchiveEntryInfo(
                path = path,
                isDirectory = e.isDirectory,
                size = if (e.isDirectory) 0 else e.size,
                compressedSize = 0,
                lastModified = if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0L,
                encrypted = encrypted,
                index = i,
            )
        }
        entries = list
        unsafeEntries = unsafe
        byRawIndex = list.associateBy { it.index }
    }

    override suspend fun readFiles(wanted: (ArchiveEntryInfo) -> Boolean, consumer: suspend (ArchiveEntryInfo, InputStream) -> Unit) {
        // Sequential access: each solid block is decompressed only once.
        var i = 0
        var entry = nextEntry()
        while (entry != null) {
            val info = byRawIndex[i]
            if (info != null && !info.isDirectory && wanted(info)) {
                val stream = if (entry.hasStream()) SourceStream { SevenZStream(sz) } else ByteArrayInputStream(ByteArray(0))
                consumer(info, stream)
            }
            i++
            entry = nextEntry()
        }
    }

    private fun nextEntry(): SevenZArchiveEntry? = try {
        sz.nextEntry
    } catch (e: IOException) {
        throw SourceReadException(e)
    }

    override fun openEntry(entry: ArchiveEntryInfo): InputStream {
        val e = raw[entry.index]
        if (!e.hasStream()) return ByteArrayInputStream(ByteArray(0))
        return sz.getInputStream(e)
    }

    override fun close() {
        sz.close()
        runCatching { channel.close() }
    }

    private class SevenZStream(private val sz: SevenZFile) : InputStream() {
        override fun read(): Int = sz.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = sz.read(b, off, len)
        override fun close() { /* the entry is finished by SevenZFile.nextEntry */ }
    }

    companion object {
        /** Refuse archives that need more memory than the app can safely give (e.g. huge dictionaries). */
        fun memoryLimitKiB(): Int {
            val max = Runtime.getRuntime().maxMemory()
            val limit = if (max == Long.MAX_VALUE) 1L shl 30 else max * 3 / 4
            return (limit / 1024).coerceIn(64L * 1024, Int.MAX_VALUE.toLong()).toInt()
        }
    }
}
