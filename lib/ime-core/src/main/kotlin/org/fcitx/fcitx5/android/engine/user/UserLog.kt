/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.lattice.LayerPrior
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.store.RecordFormat
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * The user's words as a [RecordFormat] log: sentences learned and words forgotten as they are,
 * and, once compacted, the counts of words and of pairs.
 *
 * Words are written as text and spelt syllables, not ids: the log outlives the dictionary it
 * was written against. A record whose syllables this build does not know is skipped.
 */
object UserLog {
    val FORMAT = RecordFormat("FXUL", 1)
    const val MAX_RECORD = RecordFormat.MAX_RECORD

    private const val SENTENCE: Byte = 1
    private const val WORD: Byte = 2
    private const val PAIR: Byte = 3
    private const val FORGOT: Byte = 4
    private const val PRIOR: Byte = 5

    fun header(): ByteArray = FORMAT.header()

    /** Whether [bytes] start with this format's header. */
    fun hasHeader(bytes: ByteArray) = FORMAT.hasHeader(bytes)

    fun sentence(prev: Entry?, sentence: List<Entry>) = FORMAT.record(SENTENCE) {
        writeBoolean(prev != null)
        if (prev != null) entry(prev)
        writeShort(sentence.size)
        sentence.forEach { entry(it) }
    }

    fun word(entry: Entry, count: Float) = FORMAT.record(WORD) {
        entry(entry)
        writeFloat(count)
    }

    fun pair(first: Entry, second: Entry, count: Float) = FORMAT.record(PAIR) {
        entry(first)
        entry(second)
        writeFloat(count)
    }

    /** A layer's prior ([LayerPrior]) as it is now. */
    fun prior(name: String, value: Float) = FORMAT.record(PRIOR) {
        writeUTF(name)
        writeFloat(value)
    }

    fun forgot(words: List<Entry>) = FORMAT.record(FORGOT) {
        writeShort(words.size)
        words.forEach { entry(it) }
    }

    /**
     * Replays the records of [bytes], which start with the header, into [model].
     *
     * @return the length of the records read whole: past it the log was cut short
     */
    fun read(bytes: ByteArray, model: UserModel, prior: LayerPrior? = null): Int =
        FORMAT.read(bytes) { type, input -> replay(type, input, model, prior) }

    internal fun replay(type: Byte, input: DataInputStream, model: UserModel, prior: LayerPrior?) {
        try {
            when (type) {
                SENTENCE -> {
                    val prev = if (input.readBoolean()) input.entry() else null
                    val sentence = List(input.readShort().toInt()) { input.entry() }
                    if (sentence.isNotEmpty()) model.learn(prev, sentence)
                }
                WORD -> model.restore(input.entry(), input.readFloat())
                PAIR -> model.restore(input.entry(), input.entry(), input.readFloat())
                FORGOT -> model.forget(List(input.readShort().toInt()) { input.entry() })
                PRIOR -> {
                    val name = input.readUTF()
                    val value = input.readFloat()
                    // a layer this build does not have is left behind
                    prior?.restore(name, value)
                }
                // a type from a later version: what it held is lost, the rest still reads
                else -> Unit
            }
        } catch (_: Unreadable) {
            // written by a build with other syllables: this word is no longer typeable
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
}
