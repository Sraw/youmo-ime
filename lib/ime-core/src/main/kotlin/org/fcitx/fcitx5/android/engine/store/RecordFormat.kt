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

/**
 * A log of records, each appended as it happens: a header of [magic] and [version], then records
 * of a length, a type and its fields, and a CRC32 of type and fields. A record cut short (the app
 * killed while writing) or garbled fails its length or CRC; reading stops there, and what came
 * before stands.
 */
class RecordFormat(magic: String, val version: Int) {
    private val magic = magic.toByteArray(Charsets.US_ASCII).also { require(it.size == MAGIC_SIZE) { "magic of four letters" } }

    fun header(): ByteArray = ByteBuffer.allocate(HEADER_SIZE).put(magic).putInt(version).array()

    /** Whether [bytes] start with this format's header. */
    fun hasHeader(bytes: ByteArray) = bytes.size >= HEADER_SIZE && ByteBuffer.wrap(bytes).let { b ->
        magic.all { it == b.get() } && b.getInt() == version
    }

    /** A record of [type] with the [fields] written. */
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
     * Hands each record of [bytes], which start with the header, to [replay] with its type and
     * its fields to read. A record whose fields run short or do not read is skipped: its CRC held,
     * so that is a writer's bug rather than damage.
     *
     * @return the length of the records read whole: past it the log was cut short
     */
    fun read(bytes: ByteArray, replay: (Byte, DataInputStream) -> Unit): Int {
        require(hasHeader(bytes)) { "not a log of ${String(magic, Charsets.US_ASCII)} $version" }
        var at = HEADER_SIZE
        var length = wholeRecord(bytes, at)
        while (length > 0) {
            val input = DataInputStream(ByteArrayInputStream(bytes, at + 4, length))
            try {
                replay(input.readByte(), input)
            } catch (_: IOException) {
                // skipped: the bytes are in memory, so this is a record read wrong, not the disk
            }
            at += 4 + length + 4
            length = wholeRecord(bytes, at)
        }
        return at
    }

    /** The length of the fields of the record at [at], or -1 if it is cut short or garbled. */
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

        /** A record is a few words; past this a length is garbage, so none longer is written. */
        const val MAX_RECORD = 1 shl 16
    }
}
