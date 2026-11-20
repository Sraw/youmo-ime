/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.learn.LearnRecord.BatchRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.ImportBatch
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.ImportUndo
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Imported
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.ImportedRow
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import java.util.BitSet

/**
 * Words imported from other input methods' lists, as the learning record has them: batches (a file on a
 * day each) and their words by text and reading, each with its batch and place (the file's counts order
 * imported words among themselves, nothing more). A word is imported once: a later batch does not take it
 * over. Whether one is the user's word by now is the record's MINE: undoing a batch leaves those theirs.
 *
 * Packed, as 100 000-word lists are common: each word a place in arrays (chars, syllables, batch, order),
 * found by a hash of its text. A removed word keeps its place, marked gone, till the record is reread:
 * places are the user model's ids (`UserModel.attachImports`), and [restore] brings undone batches back by them.
 */
class ImportedWords {
    /** A batch: its source [file], the calendar day it came in, how many words it brought. */
    data class Batch(val id: Int, val file: String, val localDay: Int, val count: Int)

    private val batchMap = HashMap<Int, Batch>()
    // undone batches, for restore: their words keep their places, gone
    private val undone = HashMap<Int, Batch>()

    // words by place: text i is chars[textAt[i] until textAt[i + 1]], syllables likewise
    private var chars = CharArray(INITIAL * 3)
    private var textAt = IntArray(INITIAL + 1)
    private var syllables = ShortArray(INITIAL * 3)
    private var syllablesAt = IntArray(INITIAL + 1)
    private var batchOf = IntArray(INITIAL) // NO_BATCH once forgotten: not restored with its batch
    private var orderOf = IntArray(INITIAL)
    private val gone = BitSet()

    // by text: each slot a text's first place plus one (0 for none), other readings via nextSame
    private var table = IntArray(INITIAL * 2)
    private var texts = 0
    private var nextSame = IntArray(INITIAL)

    /** Every word put in since this record was read, gone ones too. */
    var places = 0
        private set

    /** Live words, every reading. */
    var size = 0
        private set

    /** Changes as words come and go, so derived data knows it is stale. */
    var version = 0
        private set

    /** Changes as words come (or a place goes live again), not as they go: a structure holding every live one and checking [has] is stale only then. */
    var grown = 0
        private set

    /** The highest batch id seen, undone too: a new batch never takes an id an undone one may come back as. */
    var lastId = 0
        private set

    val batches: List<Batch> get() = batchMap.values.sortedBy { it.id }

    fun isEmpty() = batchMap.isEmpty() && size == 0

    fun text(place: Int) = String(chars, textAt[place], textAt[place + 1] - textAt[place])

    fun syllables(place: Int) = IntArray(length(place)) { syllable(place, it) }

    fun length(place: Int) = syllablesAt[place + 1] - syllablesAt[place]

    fun syllable(place: Int, i: Int) = syllables[syllablesAt[place] + i].toInt()

    /** Syllables space-separated. */
    fun reading(place: Int) = Syllables.spelled(length(place)) { syllable(place, it) }

    fun batch(place: Int) = batchOf[place]

    /** Not undone nor forgotten. */
    fun has(place: Int) = place in 0 until places && !gone[place]

    /** -1 if none. */
    fun find(text: String, syllables: IntArray): Int = find(text, syllables, live = true)

    /** [reading]: syllables space-separated. */
    fun has(text: String, reading: String): Boolean = syllablesOf(reading)?.let { find(text, it) >= 0 } == true

    /** Readings still imported. */
    fun placesOf(text: String): List<Int> {
        val out = ArrayList<Int>(1)
        var p = first(text)
        while (p >= 0) {
            if (!gone[p]) out += p
            p = nextSame[p]
        }
        return out
    }

    /** Its batch, then its place there, over its earliest reading; -1 if none. */
    fun order(text: String): Long = placesOf(text).minOfOrNull(::order) ?: -1L

    fun order(place: Int): Long = (batchOf[place].toLong() shl Int.SIZE_BITS) or orderOf[place].toLong()

    /** An import record, or a snapshot row of one; whether the words changed. */
    fun apply(record: LearnRecord): Boolean = when (record) {
        is ImportBatch -> putBatch(Batch(record.id, record.file, record.localDay, record.count))
        is BatchRow -> putBatch(Batch(record.id, record.file, record.localDay, record.count))
        is Imported -> add(record.batch, record.order, record.text, record.reading)
        is ImportedRow -> add(record.batch, record.order, record.text, record.reading)
        is ImportUndo -> dropBatch(record.batch)
        else -> false
    }

    private fun putBatch(batch: Batch): Boolean {
        batchMap[batch.id] = batch
        undone.remove(batch.id)
        lastId = maxOf(lastId, batch.id)
        return false
    }

    private fun add(batch: Int, order: Int, text: String, reading: String): Boolean {
        // a word of a batch not (or no longer) there is not taken: its undo was written first
        if (batch !in batchMap || text.isEmpty()) return false
        val read = syllablesOf(reading) ?: return false
        val known = find(text, read, live = false)
        if (known >= 0 && !gone[known]) return false
        val place = if (known >= 0) known else append(text, read)
        gone.clear(place)
        batchOf[place] = batch
        orderOf[place] = order
        size++
        version++
        grown++
        return true
    }

    private fun dropBatch(id: Int): Boolean {
        lastId = maxOf(lastId, id)
        undone[id] = batchMap.remove(id) ?: return false
        var changed = false
        for (p in 0 until places) {
            if (batchOf[p] == id && !gone[p]) {
                take(p)
                changed = true
            }
        }
        return changed
    }

    /** In every reading; whether it was imported. */
    fun forget(text: String): Boolean {
        val out = placesOf(text)
        for (p in out) {
            take(p)
            batchOf[p] = NO_BATCH
        }
        return out.isNotEmpty()
    }

    private fun take(place: Int) {
        gone.set(place)
        size--
        version++
    }

    /**
     * Becomes [now] (the record reread after a rewrite), each word keeping its place here, new ones in new
     * places, missing ones gone: the user model's ids stay, so a session goes on as typed. A rewrite
     * (全部清空, 退回恢复前, an undo) ends every 撤销 as a fold or start does: no earlier undone batch can be
     * restored. With [endUndo] false (a rewrite the user did not make, older builds' learning landing) the
     * 撤销 of batches [now] has undone too is kept.
     */
    fun takeFrom(now: ImportedWords, endUndo: Boolean = true) {
        for (p in 0 until places) if (!gone[p]) take(p)
        batchMap.clear()
        for (b in now.batches) putBatch(b)
        for (p in now.places()) add(now.batchOf[p], now.orderOf[p], now.text(p), now.reading(p))
        lastId = maxOf(lastId, now.lastId)
        // cleared, not trimmed to [now]'s undone: a tail-keeping rewrite (an undo, 清掉最近) replays an earlier
        // ImportUndo into [now], whose 撤销 must end too
        if (endUndo) undone.clear() else undone.keys.retainAll(now.undone.keys)
    }

    /** Of [batch] alone if given, in place order. */
    fun places(batch: Int? = null): List<Int> = (0 until places).filter { !gone[it] && (batch == null || batchOf[it] == batch) }

    /** As snapshot rows: batches by id, then words by batch and place. */
    fun rows(): List<LearnRecord> = buildList {
        for (b in batches) add(BatchRow(b.id, b.file, b.localDay, b.count))
        val all = places().map { ImportedRow(batchOf[it], orderOf[it], text(it), reading(it)) }
        addAll(all.sortedWith(compareBy({ it.batch }, { it.order }, { it.text }, { it.reading })))
    }

    /**
     * The records that restore [batch] as [ImportUndo] removed it (撤销 of 撤掉这批), minus words forgotten or
     * re-imported since; empty if not undone or the record was reread or rewritten since. A word another
     * batch re-imported, that batch undone too, keeps that batch's place: restoring that batch brings it.
     */
    fun restore(batch: Int): List<LearnRecord> {
        val b = undone[batch] ?: return emptyList()
        val words = (0 until places).filter { gone[it] && batchOf[it] == batch && find(text(it), syllables(it), live = true) < 0 }
        if (words.isEmpty()) return emptyList()
        return buildList {
            add(ImportBatch(b.id, b.file, b.localDay, b.count))
            for (p in words.sortedBy { orderOf[it] }) add(Imported(b.id, orderOf[p], text(p), reading(p)))
        }
    }

    private fun first(text: String): Int {
        var slot = text.hashCode() and (table.size - 1)
        while (true) {
            val p = table[slot] - 1
            if (p < 0) return -1
            if (sameText(p, text)) return p
            slot = (slot + 1) and (table.size - 1)
        }
    }

    private fun find(text: String, read: IntArray, live: Boolean): Int {
        var p = first(text)
        while (p >= 0) {
            if ((!live || !gone[p]) && sameSyllables(p, read)) return p
            p = nextSame[p]
        }
        return -1
    }

    private fun sameText(place: Int, text: String): Boolean {
        val from = textAt[place]
        if (textAt[place + 1] - from != text.length) return false
        for (i in text.indices) if (chars[from + i] != text[i]) return false
        return true
    }

    private fun sameSyllables(place: Int, read: IntArray): Boolean {
        if (length(place) != read.size) return false
        for (i in read.indices) if (syllable(place, i) != read[i]) return false
        return true
    }

    private fun append(text: String, read: IntArray): Int {
        val place = places++
        if (place == batchOf.size) grow()
        chars = fit(chars, textAt[place] + text.length)
        text.toCharArray(chars, textAt[place])
        textAt[place + 1] = textAt[place] + text.length
        syllables = fit(syllables, syllablesAt[place] + read.size)
        for ((i, s) in read.withIndex()) syllables[syllablesAt[place] + i] = s.toShort()
        syllablesAt[place + 1] = syllablesAt[place] + read.size
        val head = first(text)
        if (head >= 0) {
            // after the text's first: the table keeps pointing at that
            nextSame[place] = nextSame[head]
            nextSame[head] = place
        } else {
            nextSame[place] = -1
            if (++texts * 2 > table.size) rehash()
            var slot = text.hashCode() and (table.size - 1)
            while (table[slot] != 0) slot = (slot + 1) and (table.size - 1)
            table[slot] = place + 1
        }
        return place
    }

    private fun grow() {
        val n = batchOf.size * 2
        textAt = textAt.copyOf(n + 1)
        syllablesAt = syllablesAt.copyOf(n + 1)
        batchOf = batchOf.copyOf(n)
        orderOf = orderOf.copyOf(n)
        nextSame = nextSame.copyOf(n)
    }

    private fun rehash() {
        val old = table
        table = IntArray(old.size * 2)
        for (p in old) {
            if (p == 0) continue
            var slot = text(p - 1).hashCode() and (table.size - 1)
            while (table[slot] != 0) slot = (slot + 1) and (table.size - 1)
            table[slot] = p
        }
    }

    private fun fit(a: CharArray, n: Int) = if (n <= a.size) a else a.copyOf(maxOf(n, a.size * 2))
    private fun fit(a: ShortArray, n: Int) = if (n <= a.size) a else a.copyOf(maxOf(n, a.size * 2))

    /** [reading]: spelt syllables space-separated; [frequency] if the file gives one. */
    data class Incoming(val text: String, val reading: String, val frequency: Double? = null)

    companion object {
        private const val INITIAL = 16
        private const val NO_BATCH = -1

        // syllable ids; null if not a reading
        private fun syllablesOf(reading: String): IntArray? {
            if (reading.isEmpty()) return null
            val parts = reading.split(' ')
            return IntArray(parts.size) { Syllables.id(parts[it]) }.takeIf { r -> r.all { it >= 0 } }
        }

        /**
         * The records importing [words] of [file] on [localDay] as batch [id]: each word once, those [known]
         * already (dictionary, user dictionaries, the user's own, added or blocked, imported before) left out,
         * the rest by the file's frequency (higher first), else its order. Empty if none is left.
         */
        fun plan(id: Int, file: String, localDay: Int, words: List<Incoming>, known: (String, String) -> Boolean): List<LearnRecord> {
            val seen = HashSet<Word>()
            val kept = words.withIndex().filter { (_, w) -> w.text.isNotEmpty() && seen.add(Word(w.text, w.reading)) && !known(w.text, w.reading) }
            if (kept.isEmpty()) return emptyList()
            val ordered = kept.sortedWith(compareByDescending<IndexedValue<Incoming>> { it.value.frequency ?: Double.NEGATIVE_INFINITY }.thenBy { it.index })
            val out = ArrayList<LearnRecord>(ordered.size + 1)
            out += ImportBatch(id, file, localDay, ordered.size)
            for ((order, w) in ordered.withIndex()) out += Imported(id, order, w.value.text, w.value.reading)
            return out
        }
    }
}
