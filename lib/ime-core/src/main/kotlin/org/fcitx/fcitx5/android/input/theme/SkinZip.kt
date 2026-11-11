/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.scene.skin.SkinError
import org.fcitx.fcitx5.android.scene.skin.SkinError.Reason
import org.fcitx.fcitx5.android.scene.skin.SkinLimits
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * A skin's ZIP (`.bds`, `.bdi`, `.ssf`, `.flex`, `.zip`) unpacked in memory, never to a disk: a name in it is only ever a key
 * ([org.fcitx.fcitx5.android.scene.skin.SkinFiles]), and one that would lead out of a folder (`..`, `/x`, `C:`) is
 * refused, not cleaned. Read by its central directory, or, where that is broken (skin makers' tools write odd
 * ZIPs), entry by entry from the start. Held to [SkinLimits]: the file, the files' count, each one unpacked and all,
 * and a file unpacked past [SkinLimits.MAX_RATIO] times its packed size (a bomb), each counted as it inflates, not
 * by what the ZIP says. Encrypted entries and methods other than stored and deflated are refused.
 */
object SkinZip {
    /** [input] read to its end within [SkinLimits.MAX_ARCHIVE_BYTES], then [read]. */
    fun read(input: InputStream): Map<String, ByteArray> = read(bytes(input))

    /**
     * A skin file [fileName]'s files: a lone YAML theme (Trime's) as itself, held to [SkinLimits.MAX_TEXT_BYTES]; a
     * ZIP holding one FlorisBoard `.flex` and no extension of its own (a download) as that one's; any other ZIP as [read].
     */
    fun readSkin(input: InputStream, fileName: String): Map<String, ByteArray> {
        val b = bytes(input)
        val name = fileName.substringAfterLast('/')
        if (ThemeFileName.isYaml(name)) {
            if (b.size > SkinLimits.MAX_TEXT_BYTES) fail(Reason.TOO_BIG)
            return mapOf(name to b)
        }
        val entries = read(b)
        val flex = entries.keys.filter { it.endsWith(".flex", ignoreCase = true) }
        val own = entries.keys.any { it.substringAfterLast('/') == "extension.json" }
        return if (flex.size == 1 && !own) read(entries.getValue(flex[0])) else entries
    }

    private fun bytes(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > SkinLimits.MAX_ARCHIVE_BYTES) fail(Reason.TOO_BIG)
        }
        return out.toByteArray()
    }

    /** [b]'s files by name (`/`-separated, no directories), the first of a name twice; at least one. */
    fun read(b: ByteArray): Map<String, ByteArray> {
        if (b.size > SkinLimits.MAX_ARCHIVE_BYTES) fail(Reason.TOO_BIG)
        if (b.size >= 4 && String(b, 0, 4, Charsets.ISO_8859_1) == "Skin") fail(Reason.SOGOU_ENCRYPTED)
        if (b.size < 4 || u32(b, 0) != LOCAL && u32(b, 0) != END) fail(Reason.NOT_ZIP)
        val entries = central(b) ?: local(b)
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        for (e in entries) {
            if (e.name.isEmpty() || e.name.endsWith("/")) continue
            val bytes = unpack(b, e, SkinLimits.MAX_TOTAL_BYTES - total)
            total += bytes.size
            out.getOrPut(e.name) { bytes }
        }
        // no file in it: nothing a skin is
        if (out.isEmpty()) fail(Reason.NOT_ZIP)
        return out
    }

    private class Entry(val name: String, val flags: Int, val method: Int, val data: Int, val packed: Int)

    // the central directory's entries, or null where there is none to read (a local scan's turn)
    private fun central(b: ByteArray): List<Entry>? {
        val end = (b.size - END_SIZE downTo maxOf(0, b.size - END_SIZE - MAX_COMMENT)).firstOrNull { u32(b, it) == END } ?: return null
        val count = u16(b, end + END_COUNT)
        val at = u32(b, end + END_OFFSET)
        if (count > SkinLimits.MAX_ENTRIES) fail(Reason.TOO_MANY_FILES)
        if (at < 0 || at >= end) return null
        val out = ArrayList<Entry>()
        var p = at.toInt()
        repeat(count) {
            if (p + CEN_SIZE > b.size || u32(b, p) != CENTRAL) return null
            val flags = u16(b, p + CEN_FLAGS)
            val method = u16(b, p + CEN_METHOD)
            val packed = u32(b, p + CEN_PACKED)
            val nameLen = u16(b, p + CEN_NAME)
            val extra = u16(b, p + CEN_EXTRA)
            val comment = u16(b, p + CEN_COMMENT)
            val local = u32(b, p + CEN_LOCAL)
            if (p + CEN_SIZE + nameLen > b.size) return null
            val name = name(b, p + CEN_SIZE, nameLen, flags)
            if (local < 0 || local + LOC_SIZE > b.size || u32(b, local.toInt()) != LOCAL) return null
            val l = local.toInt()
            val data = l + LOC_SIZE + u16(b, l + LOC_NAME) + u16(b, l + LOC_EXTRA)
            if (packed < 0 || data + packed > b.size) return null
            out += Entry(name, flags, method, data, packed.toInt())
            p += CEN_SIZE + nameLen + extra + comment
        }
        return out
    }

    // entries read one after another from the start: a ZIP whose directory is broken or missing
    private fun local(b: ByteArray): List<Entry> {
        val out = ArrayList<Entry>()
        var p = 0
        while (p + LOC_SIZE <= b.size && u32(b, p) == LOCAL) {
            if (out.size >= SkinLimits.MAX_ENTRIES) fail(Reason.TOO_MANY_FILES)
            val flags = u16(b, p + LOC_FLAGS)
            val method = u16(b, p + LOC_METHOD)
            val nameLen = u16(b, p + LOC_NAME)
            val data = p + LOC_SIZE + nameLen + u16(b, p + LOC_EXTRA)
            if (data > b.size) fail(Reason.NOT_ZIP)
            val name = name(b, p + LOC_SIZE, nameLen, flags)
            var packed = u32(b, p + LOC_PACKED)
            if (flags and DESCRIPTOR != 0) {
                // sizes after the data: a deflated stream ends itself, a stored one cannot be found
                if (method != DEFLATED) fail(Reason.NOT_ZIP)
                packed = deflatedLength(b, data)
            }
            if (packed < 0 || data + packed > b.size) fail(Reason.NOT_ZIP)
            out += Entry(name, flags, method, data, packed.toInt())
            p = data + packed.toInt()
            if (flags and DESCRIPTOR != 0) p += if (p + 4 <= b.size && u32(b, p) == DESCRIPTOR_SIG) DESCRIPTOR_SIZE + 4 else DESCRIPTOR_SIZE
        }
        if (out.isEmpty()) fail(Reason.NOT_ZIP)
        return out
    }

    private fun deflatedLength(b: ByteArray, at: Int): Long {
        val inf = Inflater(true)
        try {
            inf.setInput(b, at, b.size - at)
            val sink = ByteArray(BUFFER)
            var out = 0L
            while (!inf.finished()) {
                val n = inf.inflate(sink)
                out += n
                if (out > SkinLimits.MAX_ENTRY_BYTES) fail(Reason.UNPACKED_TOO_BIG)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) fail(Reason.NOT_ZIP)
            }
            return inf.bytesRead
        } catch (e: DataFormatException) {
            fail(Reason.NOT_ZIP, e.message.orEmpty(), e)
        } finally {
            inf.end()
        }
    }

    private fun unpack(b: ByteArray, e: Entry, left: Long): ByteArray {
        if (e.flags and ENCRYPTED != 0) fail(Reason.ENCRYPTED, e.name)
        val cap = minOf(SkinLimits.MAX_ENTRY_BYTES, left)
        return when (e.method) {
            STORED -> {
                if (e.packed > SkinLimits.MAX_ENTRY_BYTES) fail(Reason.UNPACKED_TOO_BIG, e.name)
                if (e.packed > left) fail(Reason.UNPACKED_TOO_BIG, e.name)
                b.copyOfRange(e.data, e.data + e.packed)
            }
            DEFLATED -> inflate(b, e, cap)
            else -> fail(Reason.ENCRYPTED, e.name)
        }
    }

    private fun inflate(b: ByteArray, e: Entry, cap: Long): ByteArray {
        val inf = Inflater(true)
        try {
            inf.setInput(b, e.data, e.packed)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(BUFFER)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
                val size = out.size().toLong()
                if (size > cap) fail(Reason.UNPACKED_TOO_BIG, e.name)
                if (size > SkinLimits.RATIO_FROM && size > e.packed.toLong() * SkinLimits.MAX_RATIO) fail(Reason.UNPACKED_TOO_BIG, e.name)
            }
            return out.toByteArray()
        } catch (x: DataFormatException) {
            fail(Reason.NOT_ZIP, e.name, x)
        } finally {
            inf.end()
        }
    }

    // a name as UTF-8 (flagged so, or reading as such), else GB18030 (Chinese tools' ZIPs); one leading out refused
    private fun name(b: ByteArray, at: Int, len: Int, flags: Int): String {
        val raw = b.copyOfRange(at, at + len)
        val text = if (flags and UTF8 != 0) String(raw, Charsets.UTF_8) else strictUtf8(raw) ?: String(raw, GB18030)
        val name = text.replace('\\', '/')
        val parts = name.split('/')
        val out = name.startsWith("/") || DRIVE.containsMatchIn(name)
        if (out || parts.any { it == ".." } || name.any { it < ' ' }) fail(Reason.UNSAFE_NAME, name)
        return parts.filter { it.isNotEmpty() && it != "." }.joinToString("/") + if (name.endsWith("/")) "/" else ""
    }

    // null where it is not UTF-8: the failure is the answer
    @Suppress("SwallowedException")
    private fun strictUtf8(raw: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(raw)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun fail(reason: Reason, detail: String = "", cause: Throwable? = null): Nothing = throw SkinError(reason, detail, cause)

    /** GB18030, what Chinese skins' texts and names are where they are not UTF-8. */
    val GB18030: Charset = Charset.forName("GB18030")

    private fun u16(b: ByteArray, at: Int) = if (at + 2 > b.size) 0 else (b[at].toInt() and BYTE) or (b[at + 1].toInt() and BYTE shl BITS)
    private fun u32(b: ByteArray, at: Int): Long = if (at < 0 || at + 4 > b.size) -1 else (u16(b, at).toLong() or (u16(b, at + 2).toLong() shl BITS * 2))

    private const val BUFFER = 64 * 1024
    private const val LOCAL = 0x04034b50L
    private const val CENTRAL = 0x02014b50L
    private const val END = 0x06054b50L
    private const val DESCRIPTOR_SIG = 0x08074b50L
    private const val DESCRIPTOR_SIZE = 12
    private const val END_SIZE = 22
    private const val END_COUNT = 10
    private const val END_OFFSET = 16
    private const val MAX_COMMENT = 0xFFFF
    private const val CEN_SIZE = 46
    private const val CEN_FLAGS = 8
    private const val CEN_METHOD = 10
    private const val CEN_PACKED = 20
    private const val CEN_NAME = 28
    private const val CEN_EXTRA = 30
    private const val CEN_COMMENT = 32
    private const val CEN_LOCAL = 42
    private const val LOC_SIZE = 30
    private const val LOC_FLAGS = 6
    private const val LOC_METHOD = 8
    private const val LOC_PACKED = 18
    private const val LOC_NAME = 26
    private const val LOC_EXTRA = 28
    private const val ENCRYPTED = 1
    private const val DESCRIPTOR = 8
    private const val UTF8 = 0x800
    private const val STORED = 0
    private const val DEFLATED = 8
    private const val BYTE = 0xFF
    private const val BITS = 8
    private val DRIVE = Regex("^[A-Za-z]:")
}
