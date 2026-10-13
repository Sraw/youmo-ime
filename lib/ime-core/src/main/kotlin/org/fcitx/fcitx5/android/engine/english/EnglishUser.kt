/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.store.RecordFormat
import org.fcitx.fcitx5.android.engine.store.RecordStore
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The English words the user committed, as written, and which followed which: counted as pinyin's
 * user model counts its words (halved past [limit]), and kept by [EnglishUserStore].
 *
 * A word counted [LEARNED] times is the user's: never corrected, as a correction taken back makes
 * the word typed at once. Words the data lacks are offered by their keys from the first count.
 * Halved, a word's count may fall below [LEARNED] again, as in pinyin: a word not typed for long
 * is corrected again. A word added by hand ([add]) is not: it stays the user's until forgotten, as
 * pinyin's added words do.
 */
class EnglishUser(private val limit: Float = DEFAULT_LIMIT) {

    /** Hears each change, to keep it. */
    interface Journal {
        fun learned(prev: String?, word: String, count: Float)
        fun forgot(word: String)
        fun added(word: String) {}
    }

    var journal: Journal? = null

    private val counts = HashMap<String, Float>()
    private val pairs = HashMap<String, HashMap<String, Float>>()
    private val added = HashSet<String>()

    /** The words added by hand ([add]). */
    val addedWords: Set<String> get() = added

    /** The sum of the words' counts. */
    var total = 0f
        private set

    /** Goes up as the words counted change, for a copy of what is known of them to know it is stale. */
    var changes = 0
        private set

    /** How often [word] was committed, as written. */
    fun count(word: String): Float = counts[word] ?: 0f

    /** Whether [word] is the user's: never put right. */
    fun learned(word: String): Boolean = count(word) >= LEARNED

    /**
     * Counts [word], committed after [prev] if given (and itself counted), [count] times; less than
     * none takes back what was counted (a correction undone), a count it leaves at none gone.
     */
    fun learn(prev: String?, word: String, count: Float = 1f) {
        add(word, count)
        if (prev != null && prev in counts) {
            val after = pairs.getOrPut(prev) { HashMap() }
            after.merge(word, count, Float::plus)
            if ((after[word] ?: 0f) <= 0f) after.remove(word)
        }
        if (count(word) <= 0f) dropWords(setOf(word))
        if (total > limit) halve()
        // after halving, as a compaction it makes due writes the counts as they are
        journal?.learned(prev, word, count)
    }

    /**
     * Makes [word] the user's for good, as added on the English page: counted [LEARNED] times at
     * least, and kept so however often halved, until forgotten.
     */
    fun add(word: String) {
        // first, so a halving the count makes due keeps it
        added += word
        val missing = LEARNED - count(word)
        if (missing > 0f) learn(null, word, missing)
        journal?.added(word)
    }

    /** Forgets [word], its count and every pair it is in: a long press on it. */
    fun forget(word: String) = forgetAll(listOf(word))

    /** Forgets each of [words] as [forget] does, in one pass over the pairs: several removed on the English page. */
    fun forgetAll(words: Collection<String>) {
        val gone = words.filterTo(HashSet()) { it in counts }
        if (gone.isEmpty()) return
        dropWords(gone)
        gone.forEach { journal?.forgot(it) }
    }

    private fun dropWords(gone: Set<String>) {
        for (word in gone) {
            counts.remove(word)
            pairs.remove(word)
            added.remove(word)
        }
        pairs.values.forEach { it.keys.removeAll(gone) }
        total = counts.values.sum()
        changes++
    }

    /** A count as compacted: of [word] alone, or after [prev]. */
    fun addCount(prev: String?, word: String, count: Float) {
        if (prev == null) add(word, count) else pairs.getOrPut(prev) { HashMap() }.merge(word, count, Float::plus)
    }

    fun forEachCount(visit: (prev: String?, word: String, count: Float) -> Unit) {
        counts.forEach { (w, c) -> visit(null, w, c) }
        pairs.forEach { (p, after) -> after.forEach { (w, c) -> visit(p, w, c) } }
    }

    /** Each word counted. */
    val words: Set<String> get() = counts.keys

    /** The words committed after [prev]. */
    fun followers(prev: String): Set<String> = pairs[prev]?.keys.orEmpty()

    /**
     * The chance the user types [word] after [prev], from the counts alone, as pinyin's user model
     * has it: the pair's share of what followed [prev], leaning on the word's own share where
     * [prev] was seldom typed. 0 for a word never committed.
     */
    fun probability(prev: String?, word: String): Float {
        val count = count(word)
        if (count == 0f) return 0f
        val alone = count / (total + UNSEEN)
        val before = prev?.let(::count) ?: 0f
        if (before == 0f) return alone
        return ((pairs[prev]?.get(word) ?: 0f) + PRIOR * alone) / (before + PRIOR)
    }

    /** [base], a model's score of [word] after [prev], with the user's chance of it added: see pinyin's UserScorer. */
    fun score(base: Float, prev: String?, word: String): Float {
        val p = probability(prev, word)
        if (p == 0f) return minOf(0f, base)
        return minOf(0f, log10(10f.pow(base) + WEIGHT * p))
    }

    private fun add(word: String, count: Float) {
        if (counts.merge(word, count, Float::plus) == count) changes++
        total += count
    }

    private fun halve() {
        // an added word kept at LEARNED, so never dropped below
        counts.replaceAll { w, c -> if (w in added) maxOf(c * HALF, LEARNED) else c * HALF }
        counts.values.removeIf { it < MIN_COUNT }
        pairs.values.forEach { after ->
            after.replaceAll { _, c -> c * HALF }
            after.values.removeIf { it < MIN_COUNT }
        }
        pairs.keys.retainAll(counts.keys)
        pairs.values.removeIf { it.isEmpty() }
        total = counts.values.sum()
        changes++
    }

    companion object {
        /** Committed this often, a word is the user's, as in pinyin. */
        const val LEARNED = 2f

        const val DEFAULT_LIMIT = 20_000f
        private const val HALF = 0.5f
        private const val MIN_COUNT = 0.25f
        // as pinyin's user model and scorer have them
        private const val UNSEEN = 20f
        private const val PRIOR = 2f
        private const val WEIGHT = 0.1f
    }
}

/**
 * Keeps an [EnglishUser] in [file], a log a [RecordStore] keeps: [open] replays it, then each word
 * learned, added or forgotten is one write, compacted to the counts now and then (past [compactAt] bytes).
 */
class EnglishUserStore(
    file: File,
    private val user: EnglishUser,
    onError: (IOException) -> Unit = {},
    background: Executor? = null,
    compactAt: Long = RecordStore.DEFAULT_COMPACT_AT,
) : Closeable {
    private val store = RecordStore(file, FORMAT, compactAt = compactAt, onError = onError, background = background)

    fun open() {
        store.open(
            replay = ::replay,
            counts = { write ->
                user.forEachCount { prev, word, count -> write(record(COUNT, prev, word, count)) }
                // after the counts: replayed, each finds its word at LEARNED already and adds none
                user.addedWords.forEach { write(addedRecord(it)) }
            },
        )
        user.journal = object : EnglishUser.Journal {
            override fun learned(prev: String?, word: String, count: Float) = store.append(record(LEARNED, prev, word, count))
            override fun forgot(word: String) = store.append(FORMAT.record(FORGOT) { writeUTF(word) })
            override fun added(word: String) = store.append(addedRecord(word))
        }
    }

    /** Rewrites the log as the counts. */
    internal fun compact() = store.compact()

    override fun close() {
        user.journal = null
        store.close()
    }

    private fun replay(type: Byte, input: DataInputStream) {
        when (type) {
            LEARNED -> {
                val prev = input.prev()
                user.learn(prev, input.readUTF(), input.readFloat())
            }
            COUNT -> {
                val prev = input.prev()
                user.addCount(prev, input.readUTF(), input.readFloat())
            }
            FORGOT -> user.forget(input.readUTF())
            ADDED -> user.add(input.readUTF())
            // a type from a later version: what it held is lost, the rest still reads
            else -> Unit
        }
    }

    private fun DataInputStream.prev(): String? = if (readBoolean()) readUTF() else null

    companion object {
        val FORMAT = RecordFormat("FXEU", 1)
        private const val LEARNED: Byte = 1
        private const val COUNT: Byte = 2
        private const val FORGOT: Byte = 3
        // a version without it skips it, as a later version's type
        private const val ADDED: Byte = 4

        private fun addedRecord(word: String) = FORMAT.record(ADDED) { writeUTF(word) }

        // a count of [word], after [prev] if given: LEARNED as learned, COUNT as compacted
        private fun record(type: Byte, prev: String?, word: String, count: Float) = FORMAT.record(type) {
            writeBoolean(prev != null)
            if (prev != null) writeUTF(prev)
            writeUTF(word)
            writeFloat(count)
        }
    }
}
