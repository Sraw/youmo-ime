/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.store

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.CRC32

/** An append-only log: a [magic]/[version] header, then records of length, type, fields and a CRC32; reading stops at a record cut short or garbled. */
class RecordFormat(magic: String, val version: Int) {
    private val magic = magic.toByteArray(Charsets.US_ASCII).also { require(it.size == MAGIC_SIZE) { "magic of four letters" } }

    fun header(): ByteArray = ByteBuffer.allocate(HEADER_SIZE).put(magic).putInt(version).array()

    fun hasHeader(bytes: ByteArray) = bytes.size >= HEADER_SIZE && ByteBuffer.wrap(bytes).let { b ->
        magic.all { it == b.get() } && b.getInt() == version
    }

    fun record(type: Byte, fields: DataOutputStream.() -> Unit): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).apply {
            writeByte(type.toInt())
            fields()
        }
        val bytes = body.toByteArray()
        val crc = CRC32().apply { update(bytes) }.value.toInt()
        return ByteBuffer.allocate(4 + bytes.size + 4).putInt(bytes.size).put(bytes).putInt(crc).array()
    }

    /**
     * Hands each record to [replay]. A record whose CRC holds but whose fields do not read (a writer's bug) is
     * skipped and told to [skipped]. Given [garbled], a run of CRC-failing records followed by a whole one is read
     * past, so one damaged byte does not lose the rest; without it (a backup) reading stops there.
     *
     * @return the length of the records read whole: past it the log was cut short
     */
    fun read(
        bytes: ByteArray,
        skipped: (Exception) -> Unit = {},
        garbled: ((start: Int, end: Int) -> Unit)? = null,
        replay: (Byte, DataInputStream) -> Unit,
    ): Int {
        require(hasHeader(bytes)) { "not a log of ${String(magic, Charsets.US_ASCII)} $version" }
        var at = HEADER_SIZE
        var length = wholeRecord(bytes, at)
        while (length < 0 && garbled != null) {
            at = pastGarbled(bytes, at, garbled) ?: break
            length = wholeRecord(bytes, at)
        }
        while (length > 0) {
            val input = DataInputStream(ByteArrayInputStream(bytes, at + 4, length))
            try {
                replay(input.readByte(), input)
            } catch (e: IOException) {
                // the bytes are in memory, so this is a record read wrong, not the disk
                skipped(e)
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                skipped(e)
            }
            at += 4 + length + 4
            length = wholeRecord(bytes, at)
            while (length < 0 && garbled != null) {
                at = pastGarbled(bytes, at, garbled) ?: break
                length = wholeRecord(bytes, at)
            }
        }
        return at
    }

    /** Where the whole record after the garbled ones from [at] starts; null if none follows. */
    private fun pastGarbled(bytes: ByteArray, at: Int, garbled: (Int, Int) -> Unit): Int? {
        val ends = ArrayList<Int>()
        var next = at
        do {
            if (bytes.size - next < 4) return null
            val length = ByteBuffer.wrap(bytes).getInt(next)
            if (length !in 1..MAX_RECORD || bytes.size - next - 4 < length + 4) return null
            next += 4 + length + 4
            ends += next
        } while (wholeRecord(bytes, next) < 0)
        var start = at
        for (end in ends) {
            garbled(start, end)
            start = end
        }
        return next
    }

    /** Hands [visit] each record's span, as [read] would read them, for a log rewritten in part; returns the length read whole. */
    fun spans(bytes: ByteArray, visit: (start: Int, end: Int) -> Unit): Int {
        require(hasHeader(bytes)) { "not a log of ${String(magic, Charsets.US_ASCII)} $version" }
        var at = HEADER_SIZE
        var length = wholeRecord(bytes, at)
        while (length > 0) {
            visit(at, at + 4 + length + 4)
            at += 4 + length + 4
            length = wholeRecord(bytes, at)
        }
        return at
    }

    private fun wholeRecord(bytes: ByteArray, at: Int): Int {
        if (bytes.size - at < 4) return -1
        val buffer = ByteBuffer.wrap(bytes)
        val length = buffer.getInt(at)
        if (length !in 1..MAX_RECORD || bytes.size - at - 4 < length + 4) return -1
        val crc = CRC32().apply { update(bytes, at + 4, length) }.value.toInt()
        return if (crc == buffer.getInt(at + 4 + length)) length else -1
    }

    companion object {
        private const val MAGIC_SIZE = 4
        const val HEADER_SIZE = 8

        /** A record is a few words; past this a length is garbage. */
        const val MAX_RECORD = 1 shl 16
    }
}
