/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The container every compiled engine data file uses: a header, then numbered sections, each a
 * self-contained array. Everything is little-endian and 8-byte aligned so that a memory-mapped
 * file can be read in place, without copying anything onto the Java heap.
 *
 * ```
 * "FCITXIME"  u32 containerVersion  u32 kind  u32 sectionCount  u32 kindVersion
 * sectionCount x (u32 id, u32 0, u64 offset, u64 length)
 * sections, each starting on an 8-byte boundary
 * ```
 *
 * [VERSION] covers only this container; what the sections hold is versioned per kind, so that
 * changing the pinyin layout does not invalidate code tables a user compiled.
 *
 * A file whose versions or kind are not what the reader expects is rejected outright: an engine
 * quietly reading a file laid out differently would produce plausible-looking garbage.
 */
class DataFile private constructor(buffer: ByteBuffer, expectedKind: Int, expectedKindVersion: Int) {

    // slice: offsets count from the caller's position, whatever buffer the file sits in
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
        // capped, not just bounded by the file size: each entry costs heap, and a crafted table
        // file could otherwise declare millions of empty sections
        if (count !in 0..MAX_SECTIONS || HEADER_BYTES + count.toLong() * ENTRY_BYTES > b.capacity()) {
            throw DataFormatException("bad section count $count")
        }
        for (i in 0 until count) {
            val entry = HEADER_BYTES + i * ENTRY_BYTES
            val id = b.getInt(entry)
            val offset = b.getLong(entry + 8)
            val length = b.getLong(entry + 16)
            if (!fits(offset, length, b.capacity())) throw DataFormatException("section $id out of bounds")
            if (sections.put(id, offset until offset + length) != null) throw DataFormatException("section $id listed twice")
        }
    }

    // compared this way round so that garbage lengths cannot overflow
    private fun fits(offset: Long, length: Long, capacity: Int) =
        offset in 0..capacity && length in 0..capacity - offset

    fun has(id: Int): Boolean = id in sections

    /** A little-endian view of section [id], indexed from 0. */
    fun section(id: Int): ByteBuffer {
        val range = sections[id] ?: throw DataFormatException("missing section $id")
        val view = buffer.duplicate()
        // through Buffer: ByteBuffer's covariant overrides of these only exist from Java 9, and
        // Android before API 34 does not have them
        (view as Buffer).limit(range.last.toInt() + 1)
        (view as Buffer).position(range.first.toInt())
        return view.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    fun bitPacked(id: Int): BitPacked = try {
        BitPacked(section(id))
    } catch (e: IllegalArgumentException) {
        throw DataFormatException("section $id: ${e.message}", e)
    }

    /** `key=value` lines, UTF-8. */
    fun meta(id: Int): Map<String, String> {
        val s = section(id)
        // copied onto the heap three times over (bytes, string, lines), so keep it small
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

        fun addMeta(id: Int, meta: Map<String, String>) = apply {
            meta.forEach { (k, v) -> require('=' !in k && '\n' !in k && '\n' !in v && '\r' !in k && '\r' !in v) { "bad meta entry $k=$v" } }
            add(id, meta.entries.joinToString("") { "${it.key}=${it.value}\n" }.toByteArray(Charsets.UTF_8))
        }

        fun writeTo(out: OutputStream) {
            val header = LittleEndianOutput(HEADER_BYTES + sections.size * ENTRY_BYTES)
            header.write(MAGIC)
            header.writeInt(VERSION)
            header.writeInt(kind)
            header.writeInt(sections.size)
            header.writeInt(kindVersion)
            var offset = align(HEADER_BYTES.toLong() + sections.size * ENTRY_BYTES)
            val offsets = sections.map { (id, bytes) ->
                header.writeInt(id)
                header.writeInt(0)
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
        const val VERSION = 1
        const val KIND_PINYIN = 1
        const val KIND_TABLE = 2

        private val MAGIC = "FCITXIME".toByteArray(Charsets.US_ASCII)
        private const val HEADER_BYTES = 24
        private const val ENTRY_BYTES = 24
        private const val MAX_SECTIONS = 256
        private const val MAX_META_BYTES = 64 * 1024

        /**
         * The file starts at [buffer]'s position and ends at its limit.
         * @throws DataFormatException if it is not a well-formed file of [kind] at [kindVersion]
         */
        fun open(buffer: ByteBuffer, kind: Int, kindVersion: Int): DataFile = DataFile(buffer, kind, kindVersion)
    }
}

class DataFormatException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** [require] for file contents: a bad file is a [DataFormatException], not a caller's bug. */
internal inline fun ensureFormat(ok: Boolean, message: () -> String) {
    if (!ok) throw DataFormatException(message())
}

/** Grows a little-endian byte array; the write side of the formats here. */
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
