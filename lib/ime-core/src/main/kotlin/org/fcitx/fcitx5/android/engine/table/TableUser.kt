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
 * The phrases a table saved as the user's: those typed character by character that an older build
 * made into words (自动造词, gone since: U-28 C46), kept as the user's words. It outlives the
 * sessions made over it (a setting changed makes them anew); [journal] hears of each change, for a
 * [Store] to keep. How often a candidate was picked is the learning record's now (`tb:<im>`), as
 * the order a table learns is; an older build's picks were converted into it once, and are kept
 * in the log as read till then (a heavy user's conversion runs after the log was opened).
 *
 * Kept by code and text, not by where an entry is in [table]: the log outlives the table it was
 * written against. A pick of an entry no longer in the table, in an older build's log, is kept as
 * a phrase: picked, it was the user's.
 */
class TableUser(private val table: TableDictionary) {

    // in code order, so that the codes a prefix starts are a range of them
    private val saved = TreeMap<String, MutableList<String>>()

    // an older build's picks (PICKED), as summed: kept through a compaction for the one conversion
    // that reads them, which may run off the thread well after this log was opened (and compacted).
    // Past it they are dead weight, read by nothing (LegacyConversion reads after its marker only);
    // [forget] drops one, [forgetOlderPicks] all, as 全部清空 should (review F3140)
    private val olderPicks = LinkedHashMap<Pair<String, String>, Int>()

    /** Hears each record of what was learned, as it is learned. */
    var journal: ((ByteArray) -> Unit)? = null

    /** The phrases saved, each once. */
    fun savedTexts(): Set<String> = saved.values.flatMapTo(LinkedHashSet()) { it }

    fun isSaved(code: String, text: String): Boolean = saved[code]?.contains(text) == true

    /** The saved phrases whose code starts with [prefix], as code and text, in code order. */
    fun saved(prefix: String): List<Pair<String, String>> =
        savedUnder(prefix).flatMap { (code, texts) -> texts.map { code to it } }

    /** Whether some saved phrase has a code starting with [prefix]. */
    fun leadsAnywhere(prefix: String) = savedUnder(prefix).isNotEmpty()

    private fun savedUnder(prefix: String): Map<String, List<String>> =
        if (prefix.isEmpty()) saved else saved.subMap(prefix, true, prefix + Char.MAX_VALUE, false)

    /** Forgets the phrase [text] under [code], as the user asked. */
    fun forget(code: String, text: String) {
        olderPicks.remove(code to text)
        if (!drop(code, text)) return
        forgot++
        journal?.invoke(TableLog.forgot(code, text))
    }

    /** How many phrases [forget] forgot: kept apart from the learning record, none comes back with an undo. */
    var forgot = 0
        private set

    /** [forget] of [text] under whatever code it was saved. */
    fun forgetText(text: String) = forgetTexts(setOf(text))

    /** [forgetText] of each of [texts], going over what was saved once however many they are: whether any was. */
    fun forgetTexts(texts: Set<String>): Boolean {
        if (texts.isEmpty()) return false
        val learned = ArrayList<Pair<String, String>>()
        for ((code, list) in saved) for (text in list) if (text in texts) learned += code to text
        for ((code, text) in learned) forget(code, text)
        return learned.isNotEmpty()
    }

    /** The older build's picks forgotten, every one: none is converted after (全部清空). */
    fun forgetOlderPicks() {
        for ((code, text) in olderPicks.keys.toList()) {
            olderPicks.remove(code to text)
            // FORGOT on replay drops the phrase too: one saved under it is kept
            if (isSaved(code, text)) continue
            journal?.invoke(TableLog.forgot(code, text))
        }
    }

    private fun drop(code: String, text: String): Boolean {
        val texts = saved[code] ?: return false
        val dropped = texts.remove(text)
        if (texts.isEmpty()) saved.remove(code)
        return dropped
    }

    private fun keep(code: String, text: String) {
        val texts = saved.getOrPut(code) { ArrayList() }
        if (text !in texts) texts += text
    }

    /** Writes everything saved as records, as [TableLog] reads them back. */
    fun forEachRecord(write: (ByteArray) -> Unit) {
        for ((code, texts) in saved) for (text in texts) write(TableLog.saved(code, text))
        for ((key, n) in olderPicks) write(TableLog.picked(key.first, key.second, n))
    }

    /** What the user taught a table as a [RecordFormat] log. */
    internal object TableLog {
        val FORMAT = RecordFormat("FXUT", 1)
        internal const val PICKED: Byte = 1
        internal const val SAVED: Byte = 2
        internal const val SEEN: Byte = 3
        internal const val FORGOT: Byte = 4

        fun picked(code: String, text: String, n: Int) = FORMAT.record(PICKED) { writeUTF(code); writeUTF(text); writeInt(n) }
        fun saved(code: String, text: String) = FORMAT.record(SAVED) { writeUTF(code); writeUTF(text) }
        fun seen(code: String, text: String, n: Int) = FORMAT.record(SEEN) { writeUTF(code); writeUTF(text); writeInt(n) }
        fun forgot(code: String, text: String) = FORMAT.record(FORGOT) { writeUTF(code); writeUTF(text) }

        fun replay(type: Byte, input: DataInputStream, user: TableUser) {
            // a type from a later version: what it held is lost, the rest still reads; what an
            // older build saw typed (SEEN) made a phrase no more
            if (type != PICKED && type != SAVED && type != FORGOT) return
            val code = input.readUTF()
            val text = input.readUTF()
            if (code.isEmpty() || text.isEmpty()) return
            if (type == PICKED) {
                val n = input.readInt()
                user.olderPicks[code to text] = (user.olderPicks[code to text] ?: 0) + n
            }
            when {
                type == FORGOT -> {
                    user.olderPicks.remove(code to text)
                    user.drop(code, text)
                }
                // a phrase a later table has as its own entry: the entry stands for it; an entry a
                // later table left out as rare, picked: the user's, kept as a phrase
                user.table.indexOf(code, text) < 0 -> user.keep(code, text)
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
}
