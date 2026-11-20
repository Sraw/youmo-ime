/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Every compiled engine data file: little-endian, 8-byte aligned, so a mapped file is read in place.
 *
 * ```
 * "FCITXIME"  u32 containerVersion  u32 kind  u32 sectionCount  u32 kindVersion
 * sectionCount x (u32 id, u32 crc32, u64 offset, u64 length)
 * ```
 *
 * [VERSION] covers only the container; kinds version their sections. `verify` checks CRCs (flipped bits pass
 * bounds checks). Readers ask for every section they need: [has] cannot tell lost from never written.
 */
class DataFile private constructor(buffer: ByteBuffer, expectedKind: Int, expectedKindVersion: Int, verify: Boolean) {

    private val buffer: ByteBuffer = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
    private val sections = HashMap<Int, LongRange>()

    init {
        val b = this.buffer
        if (b.capacity() < HEADER_BYTES) throw DataFormatException("file too short")
        val magic = ByteArray(MAGIC.size) { b.get(it) }
        if (!magic.contentEquals(MAGIC)) throw DataFormatException("not an engine data file")
        val version = b.getInt(8)
        if (version != VERSION) throw DataFormatException("container version $version, this build reads $VERSION")
        val kind = b.getInt(12)
        if (kind != expectedKind) throw DataFormatException("file kind $kind, expected $expectedKind")
        val kindVersion = b.getInt(20)
        if (kindVersion != expectedKindVersion) {
            throw DataFormatException("kind $kind format version $kindVersion, this build reads $expectedKindVersion")
        }
        val count = b.getInt(16)
        // capped, not just bounded by file size: each entry costs heap, and a crafted file could declare millions
        if (count !in 0..MAX_SECTIONS || HEADER_BYTES + count.toLong() * ENTRY_BYTES > b.capacity()) {
            throw DataFormatException("bad section count $count")
        }
        val checksums = HashMap<Int, Int>()
        for (i in 0 until count) {
            val entry = HEADER_BYTES + i * ENTRY_BYTES
            val id = b.getInt(entry)
            checksums[id] = b.getInt(entry + 4)
            val offset = b.getLong(entry + 8)
            val length = b.getLong(entry + 16)
            if (!fits(offset, length, b.capacity())) throw DataFormatException("section $id out of bounds")
            if (sections.put(id, offset until offset + length) != null) throw DataFormatException("section $id listed twice")
        }
        if (verify) {
            for (id in sections.keys) {
                if (crc32(section(id)) != checksums[id]) throw DataFormatException("section $id is damaged (checksum mismatch)")
            }
        }
    }

    // this way round so garbage lengths cannot overflow
    private fun fits(offset: Long, length: Long, capacity: Int) =
        offset in 0..capacity && length in 0..capacity - offset

    fun has(id: Int): Boolean = id in sections

    internal val ids: Set<Int> get() = sections.keys

    fun section(id: Int): ByteBuffer {
        val range = sections[id] ?: throw DataFormatException("missing section $id")
        val view = buffer.duplicate()
        // through Buffer: ByteBuffer's covariant overrides exist only from Java 9, missing on Android < API 34
        (view as Buffer).limit(range.last.toInt() + 1)
        (view as Buffer).position(range.first.toInt())
        return view.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    fun bitPacked(id: Int): BitPacked = try {
        BitPacked(section(id))
    } catch (e: IllegalArgumentException) {
        throw DataFormatException("section $id: ${e.message}", e)
    }

    fun eliasFano(id: Int): EliasFano = try {
        EliasFano(section(id))
    } catch (e: IllegalArgumentException) {
        throw DataFormatException("section $id: ${e.message}", e)
    }

    fun meta(id: Int): Map<String, String> {
        val s = section(id)
        // copied onto the heap three times (bytes, string, lines), so keep it small
        ensureFormat(s.capacity() <= MAX_META_BYTES) { "meta section of ${s.capacity()} bytes" }
        val bytes = ByteArray(s.capacity()) { s.get(it) }
        return String(bytes, Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }.associate {
            val eq = it.indexOf('=')
            if (eq < 0) throw DataFormatException("bad meta line \"$it\"")
            it.substring(0, eq) to it.substring(eq + 1)
        }
    }

    class Writer(private val kind: Int, private val kindVersion: Int) {
        private val sections = sortedMapOf<Int, ByteArray>()

        fun add(id: Int, bytes: ByteArray) = apply {
            require(sections.put(id, bytes) == null) { "section $id added twice" }
        }

        fun addMeta(id: Int, meta: Map<String, String>) = add(id, metaBytes(meta))

        fun writeTo(out: OutputStream) {
            val header = header(kind, kindVersion, sections.size)
            var offset = align(HEADER_BYTES.toLong() + sections.size * ENTRY_BYTES)
            val offsets = sections.map { (id, bytes) ->
                header.writeInt(id)
                header.writeInt(CRC32().apply { update(bytes) }.value.toInt())
                header.writeLong(offset)
                header.writeLong(bytes.size.toLong())
                offset.also { offset = align(offset + bytes.size) }
            }
            var written = header.toByteArray().also(out::write).size.toLong()
            sections.values.zip(offsets).forEach { (bytes, at) ->
                out.write(ByteArray((at - written).toInt()))
                out.write(bytes)
                written = at + bytes.size
            }
        }

        fun toByteArray(): ByteArray = ByteArrayOutputStream().also(::writeTo).toByteArray()

        private fun align(n: Long) = (n + 7) and 7L.inv()
    }

    companion object {
        const val VERSION = 2
        const val KIND_PINYIN = 1
        const val KIND_TABLE = 2
        const val KIND_ENGLISH = 3
        const val KIND_USER_DICTIONARY = 4
        const val KIND_CHAR_INFO = 5
        const val KIND_CHAR_LEVELS = 6

        private val MAGIC = "FCITXIME".toByteArray(Charsets.US_ASCII)
        private const val HEADER_BYTES = 24
        private const val VERSION_AT = 8
        private const val KIND_VERSION_AT = 20
        private const val ENTRY_BYTES = 24
        private const val MAX_SECTIONS = 256
        private const val MAX_META_BYTES = 64 * 1024
        private const val CRC_CHUNK = 64 * 1024

        /**
         * [verify] reads every byte for the CRCs; skip it for a file checked when copied, or inside the signed APK.
         * @throws DataFormatException if it is not a well-formed file of [kind] at [kindVersion]
         */
        fun open(buffer: ByteBuffer, kind: Int, kindVersion: Int, verify: Boolean = true): DataFile =
            DataFile(buffer, kind, kindVersion, verify)

        /** Read without mapping; null unless an engine data file of container [VERSION] at least a header long. */
        fun peekKindVersion(file: File): Int? = try {
            RandomAccessFile(file, "r").use { f ->
                val header = ByteArray(HEADER_BYTES)
                if (f.length() < HEADER_BYTES) {
                    null
                } else {
                    f.readFully(header)
                    val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    val ours = header.copyOf(MAGIC.size).contentEquals(MAGIC) && b.getInt(VERSION_AT) == VERSION
                    if (ours) b.getInt(KIND_VERSION_AT) else null
                }
            }
        } catch (_: IOException) {
            null
        }

        private fun header(kind: Int, kindVersion: Int, sections: Int) = LittleEndianOutput(HEADER_BYTES + sections * ENTRY_BYTES).apply {
            write(MAGIC)
            writeInt(VERSION)
            writeInt(kind)
            writeInt(sections)
            writeInt(kindVersion)
        }

        fun stream(file: RandomAccessFile, kind: Int, kindVersion: Int, ids: List<Int>, write: (Int, OutputStream) -> Unit) {
            require(ids.toSet().size == ids.size && ids.size <= MAX_SECTIONS) { "sections $ids" }
            val headerBytes = HEADER_BYTES + ids.size * ENTRY_BYTES
            val out = SectionOutput(file)
            out.pad((headerBytes + 7L) and 7L.inv())
            val header = header(kind, kindVersion, ids.size)
            for (id in ids) {
                out.pad((out.at + 7L) and 7L.inv())
                val from = out.at
                out.crc.reset()
                write(id, out)
                header.writeInt(id)
                header.writeInt(out.crc.value.toInt())
                header.writeLong(from)
                header.writeLong(out.at - from)
            }
            out.flush()
            file.setLength(out.at)
            file.seek(0)
            file.write(header.toByteArray())
        }

        /** [meta] as a meta section, checked against the reader's limits; throws IllegalArgumentException for a key with `=`, a line break, or too many bytes. */
        fun metaBytes(meta: Map<String, String>): ByteArray {
            meta.forEach { (k, v) -> require('=' !in k && '\n' !in k && '\n' !in v && '\r' !in k && '\r' !in v) { "bad meta entry $k=$v" } }
            val bytes = meta.entries.joinToString("") { "${it.key}=${it.value}\n" }.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_META_BYTES) { "meta section of ${bytes.size} bytes, over $MAX_META_BYTES" }
            return bytes
        }

        private class SectionOutput(private val file: RandomAccessFile) : OutputStream() {
            val crc = CRC32()
            var at = 0L
            private val buf = ByteArray(CRC_CHUNK)
            private var n = 0

            fun pad(to: Long) {
                while (at < to) {
                    if (n == buf.size) flush()
                    buf[n++] = 0
                    at++
                }
            }

            override fun write(b: Int) {
                if (n == buf.size) flush()
                buf[n++] = b.toByte()
                crc.update(b)
                at++
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                crc.update(b, off, len)
                var i = off
                while (i < off + len) {
                    if (n == buf.size) flush()
                    val k = minOf(buf.size - n, off + len - i)
                    System.arraycopy(b, i, buf, n, k)
                    n += k
                    i += k
                }
                at += len
            }

            override fun flush() {
                file.write(buf, 0, n)
                n = 0
            }
        }

        private fun crc32(buffer: ByteBuffer): Int = CRC32().apply { update(buffer) }.value.toInt()
    }
}

class DataFormatException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal inline fun ensureFormat(ok: Boolean, message: () -> String) {
    if (!ok) throw DataFormatException(message())
}

internal class LittleEndianOutput(initialCapacity: Int = 64) {
    private var bytes = ByteArray(maxOf(initialCapacity, 8))
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + extra))
    }

    fun write(b: ByteArray) {
        ensure(b.size)
        b.copyInto(bytes, size)
        size += b.size
    }

    fun writeByte(v: Int) {
        ensure(1)
        bytes[size++] = v.toByte()
    }

    fun writeShort(v: Int) {
        writeByte(v)
        writeByte(v ushr 8)
    }

    fun writeInt(v: Int) {
        ensure(4)
        for (i in 0 until 4) bytes[size++] = (v ushr (8 * i)).toByte()
    }

    fun writeLong(v: Long) {
        ensure(8)
        for (i in 0 until 8) bytes[size++] = (v ushr (8 * i)).toByte()
    }

    fun writeFloat(v: Float) = writeInt(java.lang.Float.floatToRawIntBits(v))

    fun toByteArray(): ByteArray = bytes.copyOf(size)
}
