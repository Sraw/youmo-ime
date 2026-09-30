/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * The user's words as a log of records, each appended as it happens: a header, then records of
 * a length, a type and its fields, and a CRC32 of type and fields. A record cut short (the app
 * killed while writing) or garbled fails its length or CRC; reading stops there, and what came
 * before stands.
 *
 * Words are written as text and spelt syllables, not ids: the log outlives the dictionary it
 * was written against. A record whose syllables this build does not know is skipped.
 */
object UserLog {
    private val MAGIC = byteArrayOf('F'.code.toByte(), 'X'.code.toByte(), 'U'.code.toByte(), 'L'.code.toByte())
    const val VERSION = 1
    const val HEADER_SIZE = 8

    private const val SENTENCE: Byte = 1
    private const val WORD: Byte = 2
    private const val PAIR: Byte = 3

    /** A record is a few words; past this a length is garbage, so none longer is written. */
    const val MAX_RECORD = 1 shl 16

    fun header(): ByteArray = ByteBuffer.allocate(HEADER_SIZE).put(MAGIC).putInt(VERSION).array()

    /** Whether [bytes] start with this format's header. */
    fun hasHeader(bytes: ByteArray) = bytes.size >= HEADER_SIZE && ByteBuffer.wrap(bytes).let { b ->
        MAGIC.all { it == b.get() } && b.getInt() == VERSION
    }

    fun sentence(prev: Entry?, sentence: List<Entry>) = record(SENTENCE) {
        writeBoolean(prev != null)
        if (prev != null) entry(prev)
        writeShort(sentence.size)
        sentence.forEach { entry(it) }
    }

    fun word(entry: Entry, count: Float) = record(WORD) {
        entry(entry)
        writeFloat(count)
    }

    fun pair(first: Entry, second: Entry, count: Float) = record(PAIR) {
        entry(first)
        entry(second)
        writeFloat(count)
    }

    /**
     * Replays the records of [bytes], which start with the header, into [model].
     *
     * @return the length of the records read whole: past it the log was cut short
     */
    fun read(bytes: ByteArray, model: UserModel): Int {
        require(hasHeader(bytes)) { "not a user log of version $VERSION" }
        var at = HEADER_SIZE
        var length = wholeRecord(bytes, at)
        while (length > 0) {
            replay(DataInputStream(ByteArrayInputStream(bytes, at + 4, length)), model)
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

    private fun replay(input: DataInputStream, model: UserModel) {
        try {
            when (input.readByte()) {
                SENTENCE -> {
                    val prev = if (input.readBoolean()) input.entry() else null
                    val sentence = List(input.readShort().toInt()) { input.entry() }
                    if (sentence.isNotEmpty()) model.learn(prev, sentence)
                }
                WORD -> model.restore(input.entry(), input.readFloat())
                PAIR -> model.restore(input.entry(), input.entry(), input.readFloat())
                // a type from a later version: what it held is lost, the rest still reads
                else -> Unit
            }
        } catch (_: Unreadable) {
            // written by a build with other syllables: this word is no longer typeable
        } catch (_: EOFException) {
            // the CRC held, so this is a writer's bug rather than damage; skip the record
        }
    }

    private class Unreadable : Exception()

    private fun DataOutputStream.entry(entry: Entry) {
        writeUTF(entry.text)
        writeUTF(entry.syllables.joinToString(" ") { Syllables.spelling(it) })
    }

    private fun DataInputStream.entry(): Entry {
        val text = readUTF()
        val syllables = readUTF().split(' ').map { Syllables.id(it) }.toIntArray()
        if (text.isEmpty() || syllables.any { it < 0 }) throw Unreadable()
        return Entry(text, syllables)
    }

    private inline fun record(type: Byte, fields: DataOutputStream.() -> Unit): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).apply {
            writeByte(type.toInt())
            fields()
        }
        val bytes = body.toByteArray()
        val crc = CRC32().apply { update(bytes) }.value.toInt()
        return ByteBuffer.allocate(4 + bytes.size + 4).putInt(bytes.size).put(bytes).putInt(crc).array()
    }
}
