/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.store.RecordFormat
import org.fcitx.fcitx5.android.engine.store.RecordStore
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.TreeMap
import java.util.concurrent.Executor

/**
 * What the user taught a table input method: how often each candidate was picked, the phrases
 * saved as the user's, and those typed character by character but not yet often enough. It
 * outlives the sessions made over it (a setting changed makes them anew); [journal] hears of
 * each change, for a [Store] to keep.
 *
 * Kept by code and text, not by where an entry is in [table]: the log outlives the table it was
 * written against. A pick of an entry no longer in the table counts as one of a saved phrase's.
 */
class TableUser(private val table: TableDictionary) {

    /** A phrase learned: its code and its text. */
    private data class Phrase(val code: String, val text: String)

    // picks of table entries by index, of saved phrases by code and text
    private val picks = IndexCounts()
    private val savedPicks = HashMap<Phrase, Int>()
    // in code order, so that the codes a prefix starts are a range of them
    private val saved = TreeMap<String, MutableList<String>>()
    // the most recently seen last
    private val autoPhrases = object : LinkedHashMap<Phrase, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Phrase, Int>?) = size > MAX_AUTO_PHRASES
    }

    /** Hears each record of what was learned, as it is learned. */
    var journal: ((ByteArray) -> Unit)? = null

    /** Hears of each phrase saved as it is, not as a log is read back: a word for the other input methods too. */
    var onSaved: ((String) -> Unit)? = null

    /** How often the table's entry at [index] was picked. */
    fun picks(index: Int): Int = picks[index]

    /** The phrases saved, each once. */
    fun savedTexts(): Set<String> = saved.values.flatMapTo(LinkedHashSet()) { it }

    /** How often the saved phrase [text] under [code] was picked. */
    fun picks(code: String, text: String): Int = savedPicks[Phrase(code, text)] ?: 0

    fun isSaved(code: String, text: String): Boolean = saved[code]?.contains(text) == true

    /** Whether [forget] has anything of [text] under [code] to forget: a pick, or the phrase saved or seen. */
    fun hasLearned(code: String, text: String): Boolean {
        val phrase = Phrase(code, text)
        val index = table.indexOf(code, text)
        return (index >= 0 && picks[index] > 0) || phrase in savedPicks || phrase in autoPhrases || isSaved(code, text)
    }

    /** The saved phrases whose code starts with [prefix], as code and text, in code order. */
    fun saved(prefix: String): List<Pair<String, String>> =
        savedUnder(prefix).flatMap { (code, texts) -> texts.map { code to it } }

    /** The phrases seen but not saved whose code starts with [prefix], the most recently seen first. */
    fun seen(prefix: String): List<Pair<String, String>> =
        autoPhrases.keys.filter { it.code.startsWith(prefix) }.asReversed().map { it.code to it.text }

    /** Whether some saved or seen phrase has a code starting with [prefix]. */
    fun leadsAnywhere(prefix: String) = savedUnder(prefix).isNotEmpty() || autoPhrases.keys.any { it.code.startsWith(prefix) }

    // seen phrases are at most MAX_AUTO_PHRASES to go over; saved ones are as many as the user made
    private fun savedUnder(prefix: String): Map<String, List<String>> =
        if (prefix.isEmpty()) saved else saved.subMap(prefix, true, prefix + Char.MAX_VALUE, false)

    /**
     * [text] under [code] was picked. Counted for the table's first entry of that code and text,
     * as the log is read back, or else for the phrase.
     */
    fun picked(code: String, text: String) {
        count(table.indexOf(code, text), code, text, 1)
        journal?.invoke(TableLog.picked(code, text, 1))
    }

    /** [text] under [code] is the user's phrase from now on. */
    fun save(code: String, text: String) {
        if (!keep(code, text)) return
        journal?.invoke(TableLog.saved(code, text))
        onSaved?.invoke(text)
    }

    /**
     * The phrase [text] under [code] was typed character by character once more: saved once it
     * was [saveAfter] times (never if 0).
     */
    fun sighted(code: String, text: String, saveAfter: Int) {
        if (isSaved(code, text)) return
        val phrase = Phrase(code, text)
        val seen = (autoPhrases[phrase] ?: 0) + 1
        if (saveAfter in 1..seen) {
            save(code, text)
        } else {
            autoPhrases[phrase] = seen
            journal?.invoke(TableLog.seen(code, text, 1))
        }
    }

    /**
     * Forgets what was learned of [text] under [code], as the user asked: how often it was picked,
     * and the phrase, saved or seen. The table's own entry stays, in the table's order.
     */
    fun forget(code: String, text: String) {
        if (!drop(code, text)) return
        journal?.invoke(TableLog.forgot(code, text))
    }

    /**
     * [forget] of [text] under whatever code it was saved, seen or picked as a phrase: a word of
     * the other input methods picked here is kept as its pick alone, and read back it is saved.
     */
    fun forgetText(text: String) = forgetTexts(setOf(text))

    /** [forgetText] of each of [texts], going over what was learned once however many they are. */
    fun forgetTexts(texts: Set<String>) {
        if (texts.isEmpty()) return
        val learned = LinkedHashSet<Phrase>()
        for ((code, list) in saved) for (text in list) if (text in texts) learned += Phrase(code, text)
        autoPhrases.keys.filterTo(learned) { it.text in texts }
        savedPicks.keys.filterTo(learned) { it.text in texts }
        for (phrase in learned) forget(phrase.code, phrase.text)
    }

    private fun drop(code: String, text: String): Boolean {
        val phrase = Phrase(code, text)
        val index = table.indexOf(code, text)
        var dropped = (index >= 0 && picks.remove(index)) or (savedPicks.remove(phrase) != null) or (autoPhrases.remove(phrase) != null)
        saved[code]?.let { texts ->
            dropped = texts.remove(text) or dropped
            if (texts.isEmpty()) saved.remove(code)
        }
        return dropped
    }

    private fun count(index: Int, code: String, text: String, n: Int) {
        if (index >= 0) {
            picks.add(index, n)
        } else {
            val phrase = Phrase(code, text)
            savedPicks[phrase] = (savedPicks[phrase] ?: 0) + n
        }
    }

    private fun keep(code: String, text: String): Boolean {
        autoPhrases.remove(Phrase(code, text))
        val texts = saved.getOrPut(code) { ArrayList() }
        if (text in texts) return false
        texts += text
        return true
    }

    /** Writes everything learned as records, as [TableLog] reads them back. */
    fun forEachRecord(write: (ByteArray) -> Unit) {
        for ((code, texts) in saved) for (text in texts) write(TableLog.saved(code, text))
        // eldest first, to be seen in the same order again
        for ((phrase, n) in autoPhrases) write(TableLog.seen(phrase.code, phrase.text, n))
        picks.forEach { index, n -> write(TableLog.picked(table.code(index), table.text(index), n)) }
        for ((phrase, n) in savedPicks) write(TableLog.picked(phrase.code, phrase.text, n))
    }

    /** What the user taught a table as a [RecordFormat] log. */
    internal object TableLog {
        val FORMAT = RecordFormat("FXUT", 1)
        private const val PICKED: Byte = 1
        private const val SAVED: Byte = 2
        private const val SEEN: Byte = 3
        private const val FORGOT: Byte = 4

        fun picked(code: String, text: String, n: Int) = FORMAT.record(PICKED) { writeUTF(code); writeUTF(text); writeInt(n) }
        fun saved(code: String, text: String) = FORMAT.record(SAVED) { writeUTF(code); writeUTF(text) }
        fun seen(code: String, text: String, n: Int) = FORMAT.record(SEEN) { writeUTF(code); writeUTF(text); writeInt(n) }
        fun forgot(code: String, text: String) = FORMAT.record(FORGOT) { writeUTF(code); writeUTF(text) }

        fun replay(type: Byte, input: DataInputStream, user: TableUser) {
            // a type from a later version: what it held is lost, the rest still reads
            if (type !in PICKED..FORGOT) return
            val code = input.readUTF()
            val text = input.readUTF()
            if (code.isEmpty() || text.isEmpty()) return
            val index = user.table.indexOf(code, text)
            when (type) {
                PICKED -> {
                    user.count(index, code, text, input.readInt())
                    // an entry a later table left out as rare: picked, it is the user's, kept as a phrase
                    if (index < 0) user.keep(code, text)
                }
                // a phrase a later table has as its own entry: the entry stands for it
                SAVED -> if (index < 0) user.keep(code, text)
                FORGOT -> user.drop(code, text)
                else -> if (index < 0 && !user.isSaved(code, text)) {
                    val phrase = Phrase(code, text)
                    user.autoPhrases[phrase] = (user.autoPhrases[phrase] ?: 0) + input.readInt()
                }
            }
        }
    }

    /**
     * Keeps [user] in [file]: [open] replays the log into it, and what it learns from then on is
     * appended (see [RecordStore]).
     */
    class Store(
        file: File,
        private val user: TableUser,
        onError: (IOException) -> Unit = {},
        compactAt: Long = RecordStore.DEFAULT_COMPACT_AT,
        /** Writes a compaction an append made due, if given: see [RecordStore]. */
        background: Executor? = null,
    ) : Closeable {
        private val store = RecordStore(file, TableLog.FORMAT, compactAt, onError, background)

        fun open() {
            store.open(replay = { type, input -> TableLog.replay(type, input, user) }, counts = user::forEachRecord)
            user.journal = store::append
        }

        override fun close() {
            user.journal = null
            store.close()
        }
    }

    companion object {
        // how many phrases seen but not yet saved are remembered
        private const val MAX_AUTO_PHRASES = 1024
    }
}
