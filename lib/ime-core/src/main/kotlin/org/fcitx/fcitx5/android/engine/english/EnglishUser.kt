/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnCommit
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnWordRow
import org.fcitx.fcitx5.android.engine.learn.LearnState
import kotlin.math.log10
import kotlin.math.pow

/**
 * The user's English words and pairs as `learn.log`'s EN_COMMIT records, so undo, clearing and backups cover
 * English as pinyin. Counts are uses in the last 30 typing days. Words whose correction was taken back or added
 * by hand are never corrected, surviving 清空学到的 till 恢复 or [forget]. Without a [log] nothing is learned.
 */
class EnglishUser(private val log: (() -> LearnLog)? = inMemory()) {

    val learns: Boolean get() = log != null

    private class Known(
        val counts: Map<String, Float>,
        val pairs: Map<String, Map<String, Float>>,
        val kept: Set<String>,
        val added: Set<String>,
        val total: Float,
    )

    private var known = Known(emptyMap(), emptyMap(), emptySet(), emptySet(), 0f)
    private var knownOf: LearnState? = null
    private var knownAt = -1L
    private var knownDay = -1

    private var changed = 0

    /** Changes as the counted words change, so a cached copy knows it is stale. */
    val changes: Int
        get() {
            known()
            return changed
        }

    private fun known(): Known {
        val state = log?.invoke()?.state ?: return known
        // English's own version: pinyin commits leave the maps as they are, and a rebuild walks every English pair
        if (state === knownOf && state.englishVersion == knownAt && state.day == knownDay) return known
        val made = make(state)
        if (made.counts.keys != known.counts.keys) changed++
        known = made
        knownOf = state
        knownAt = state.englishVersion
        knownDay = state.day
        return made
    }

    private fun make(state: LearnState): Known {
        val from = state.day - LearnState.WINDOW + 1
        val counts = HashMap<String, Float>()
        val kept = HashSet<String>()
        val added = HashSet<String>()
        for ((word, w) in state.enWords) {
            val uses = w.uses.sumFrom(from).toFloat()
            val isAdded = w.flags and EnWordRow.ADDED != 0
            if (isAdded) added += word
            if (isAdded || w.flags and EnWordRow.NEVER_CORRECT != 0) kept += word
            val count = if (isAdded) maxOf(uses, ADDED_COUNT) else uses
            if (count > 0f) counts[word] = count
        }
        val pairs = HashMap<String, HashMap<String, Float>>()
        for ((key, c) in state.enPairs) {
            val (prev, word) = key
            val n = minOf(c.count.toFloat(), counts[prev] ?: 0f)
            if (n > 0f && word in counts) pairs.getOrPut(prev) { HashMap() }[word] = n
        }
        return Known(counts, pairs, kept, added, counts.values.sum())
    }

    fun count(word: String): Float = known().counts[word] ?: 0f

    fun neverCorrected(word: String): Boolean = word in known().kept

    val neverCorrectedWords: Set<String> get() = known().kept

    val addedWords: Set<String> get() = known().added

    val total: Float get() = known().total

    val words: Set<String> get() = known().counts.keys

    fun followers(prev: String): Set<String> = known().pairs[prev]?.keys.orEmpty()

    /** Returns the commit's number for [undo], or null if nothing learned. */
    fun commit(prev: String?, word: String, typed: String = word, how: Int = EnCommit.TYPED, flags: Int = 0): Long? =
        log?.invoke()?.recordEnglish(prev.orEmpty(), word, typed, how, flags)?.seq

    fun undo(seq: Long): Boolean = log?.invoke()?.undo(seq) ?: false

    fun add(word: String) {
        if (word !in addedWords) log?.invoke()?.addEnglish(word, on = true)
    }

    /** 恢复: corrected again, no longer added, its uses and pairs kept. */
    fun restoreCorrection(words: Collection<String>) {
        val log = log?.invoke() ?: return
        val kept = neverCorrectedWords
        for (word in words.toSet()) if (word in kept) log.correctEnglish(word)
    }

    fun forget(word: String) = forgetAll(listOf(word))

    fun forgetAll(words: Collection<String>) {
        val log = log?.invoke() ?: return
        val state = log.state
        for (word in words.toSet()) if (word in state.enWords) log.forgetEnglish(word)
    }

    /** The user's chance of [word] after [prev] from counts alone, as pinyin's UserModel.probability. */
    fun probability(prev: String?, word: String): Float {
        val k = known()
        val count = k.counts[word] ?: return 0f
        val alone = count / (k.total + UNSEEN)
        val before = prev?.let { k.counts[it] } ?: 0f
        if (before == 0f) return alone
        return ((k.pairs[prev]?.get(word) ?: 0f) + PRIOR * alone) / (before + PRIOR)
    }

    fun score(base: Float, prev: String?, word: String): Float {
        val p = probability(prev, word)
        if (p == 0f) return minOf(0f, base)
        return minOf(0f, log10(10f.pow(base) + WEIGHT * p))
    }

    companion object {
        const val ADDED_COUNT = 2f

        private const val UNSEEN = 20f
        private const val PRIOR = 2f
        private const val WEIGHT = 0.1f

        val OFF get() = EnglishUser(null)

        private fun inMemory(): () -> LearnLog {
            val log = LearnLog(null)
            return { log }
        }
    }
}
