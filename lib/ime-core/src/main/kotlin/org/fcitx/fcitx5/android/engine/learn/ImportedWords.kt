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
 * The words the user imported from other input methods' lists (U-101, U-102, PLAN §2.9), as the
 * learning record has them: the batches, a file taken in on a day each, and their words by text
 * and reading, each with the batch that brought it and its place there (D24: the file's counts
 * order the imported words among themselves, nothing more). A word is imported once: a later
 * batch that has it again does not take it over. Whether one is the user's word by now is the
 * record's MINE, not asked here: undoing a batch takes its words out, the user's words staying theirs.
 *
 * Packed, as a list of 100 000 words is common: each word a place in arrays (its text's
 * characters, its syllables, its batch and order), found by a table of its text. A word taken out
 * keeps its place, marked gone, till the record is read afresh: the places are the user model's
 * ids of them (`UserModel.attachImports`), and a batch undone can be put back from them ([restore]).
 */
class ImportedWords {
    /** A batch: the [file] it came from, the calendar day it came in on, and how many words it brought. */
    data class Batch(val id: Int, val file: String, val localDay: Int, val count: Int)

    private val batchMap = HashMap<Int, Batch>()
    // those undone, for restore: their words keep their places, gone
    private val undone = HashMap<Int, Batch>()

    // the words by place: text i is chars[textAt[i] until textAt[i + 1]], its syllables likewise
    private var chars = CharArray(INITIAL * 3)
    private var textAt = IntArray(INITIAL + 1)
    private var syllables = ShortArray(INITIAL * 3)
    private var syllablesAt = IntArray(INITIAL + 1)
    private var batchOf = IntArray(INITIAL) // NO_BATCH once forgotten: not put back with its batch
    private var orderOf = IntArray(INITIAL)
    private val gone = BitSet()

    // by text: each slot the first place of a text plus one (0 for none), its other readings by nextSame
    private var table = IntArray(INITIAL * 2)
    private var texts = 0
    private var nextSame = IntArray(INITIAL)

    /** How many places: every word put in since this record was read, gone ones too. */
    var places = 0
        private set

    /** How many words there are, of every reading. */
    var size = 0
        private set

    /** Goes up as words come and go: for what is made of them to know it is stale. */
    var version = 0
        private set

    /**
     * Goes up as words come (a place live again too), not as they go: what holds every live one
     * and tells a gone one by [has] (the user model's start trie) is stale only then.
     */
    var grown = 0
        private set

    /** The highest batch id seen, undone ones too: a new batch is not given an id an undone one may come back as. */
    var lastId = 0
        private set

    val batches: List<Batch> get() = batchMap.values.sortedBy { it.id }

    fun isEmpty() = batchMap.isEmpty() && size == 0

    /** The text of the word at [place]. */
    fun text(place: Int) = String(chars, textAt[place], textAt[place + 1] - textAt[place])

    /** The syllables of the word at [place]. */
    fun syllables(place: Int) = IntArray(length(place)) { syllable(place, it) }

    /** How many syllables the word at [place] has. */
    fun length(place: Int) = syllablesAt[place + 1] - syllablesAt[place]

    /** The [i]th syllable of the word at [place]. */
    fun syllable(place: Int, i: Int) = syllables[syllablesAt[place] + i].toInt()

    /** The reading of the word at [place], syllables apart by spaces. */
    fun reading(place: Int) = (0 until length(place)).joinToString(" ") { Syllables.spelling(syllable(place, it)) }

    /** The batch the word at [place] came in. */
    fun batch(place: Int) = batchOf[place]

    /** Whether the word at [place] is imported still: not undone nor forgotten. */
    fun has(place: Int) = place in 0 until places && !gone[place]

    /** The place of [text] read [syllables], imported still; -1 if it is none. */
    fun find(text: String, syllables: IntArray): Int = find(text, syllables, live = true)

    /** Whether [text] read [reading] (syllables apart by spaces) is imported. */
    fun has(text: String, reading: String): Boolean = syllablesOf(reading)?.let { find(text, it) >= 0 } == true

    /** The places of [text]'s readings imported still. */
    fun placesOf(text: String): List<Int> {
        val out = ArrayList<Int>(1)
        var p = first(text)
        while (p >= 0) {
            if (!gone[p]) out += p
            p = nextSame[p]
        }
        return out
    }

    /**
     * Where [text] goes among the imported words (D24): its batch, then its place there, the
     * earliest of its readings; -1 if it is none.
     */
    fun order(text: String): Long = placesOf(text).minOfOrNull(::order) ?: -1L

    /** Where the word at [place] goes among the imported words: see [order]. */
    fun order(place: Int): Long = (batchOf[place].toLong() shl Int.SIZE_BITS) or orderOf[place].toLong()

    /** Applies an import's record, or a snapshot's row of one: whether its words changed. */
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
        // a word of a batch not (or no longer) there is not taken: an undo of it was written first
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

    /** Takes [text] out, in every reading (U-82 删除, U-84): whether it was imported. */
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
     * Made what [now] is (a record read afresh: a rewrite), each word in the place it had here, a
     * new one in a new place, what [now] lacks gone: the user model's ids of them stay what they
     * were, so a session over it goes on as typed (an undo while typing). A rewrite (全部清空,
     * 退回恢复前, an act's undo) ends every 撤销, as a fold and a start do: a batch undone before it
     * is not put back by [restore], nor one the rewrite took (review-int-records N3, F2811). Not
     * [endUndo]: a rewrite the user did not make (the older builds' learning landing), which keeps
     * the 撤销 of the batches [now] has undone as well (F3209).
     */
    fun takeFrom(now: ImportedWords, endUndo: Boolean = true) {
        for (p in 0 until places) if (!gone[p]) take(p)
        batchMap.clear()
        for (b in now.batches) putBatch(b)
        for (p in now.places()) add(now.batchOf[p], now.orderOf[p], now.text(p), now.reading(p))
        lastId = maxOf(lastId, now.lastId)
        // cleared, not kept to what [now] has undone: a rewrite that keeps the tail (an act's undo,
        // 清掉最近) replays an earlier ImportUndo into [now], and F2811 ends that 撤销 too (F3075)
        if (endUndo) undone.clear() else undone.keys.retainAll(now.undone.keys)
    }

    /** The places of the words imported, of [batch] alone if given, in place order. */
    fun places(batch: Int? = null): List<Int> = (0 until places).filter { !gone[it] && (batch == null || batchOf[it] == batch) }

    /** As a snapshot's rows: the batches by id, then the words by batch and place. */
    fun rows(): List<LearnRecord> = buildList {
        for (b in batches) add(BatchRow(b.id, b.file, b.localDay, b.count))
        val all = places().map { ImportedRow(batchOf[it], orderOf[it], text(it), reading(it)) }
        addAll(all.sortedWith(compareBy({ it.batch }, { it.order }, { it.text }, { it.reading })))
    }

    /**
     * The records that bring [batch] back as [ImportUndo] took it (撤销 of 撤掉这批, U-102), its
     * words but those forgotten or imported again since; empty if it is not undone, or the record
     * was read afresh or rewritten since (a fold, a start, 全部清空). A word another batch imported again since, that
     * batch undone too, keeps that batch's place and is not brought back here (review-G NIT 5):
     * putting back the later batch brings it.
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

    /** A word of a file to import: [text] read [reading] (spelt syllables apart by spaces), with the file's [frequency], if it gives one. */
    data class Incoming(val text: String, val reading: String, val frequency: Double? = null)

    companion object {
        private const val INITIAL = 16
        private const val NO_BATCH = -1

        // a reading as the record spells it, as syllable ids; null for one that is none
        private fun syllablesOf(reading: String): IntArray? {
            if (reading.isEmpty()) return null
            val parts = reading.split(' ')
            return IntArray(parts.size) { Syllables.id(parts[it]) }.takeIf { r -> r.all { it >= 0 } }
        }

        /**
         * The records that import [words] of [file] on [localDay] as batch [id] (U-101): each word
         * once, those [known] says are words already (the dictionary's, a user dictionary's, the
         * user's own, added or blocked by hand, imported before) left out, the rest in the order
         * D24 gives them, the file's frequency first (higher first), its order where it has none.
         * Empty if none is left.
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
