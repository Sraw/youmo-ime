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
 * The English words the user committed, as written, and which followed which, as the learning
 * record ([log], `learn.log`) has them (PLAN §2.2, U-31): each word committed is one EN_COMMIT
 * there, so taking back the last few, clearing the last hour or all that was learned, and a
 * backup cover English as they cover pinyin. A word's count is its uses in the last 30 typing
 * days ([LearnState.WINDOW]); a word not typed for so long is forgotten, as pinyin's are.
 *
 * Never corrected ([neverCorrected]): a word whose correction the user took back once
 * ([EnCommit.REVERTED], D11), and one added by hand ([add]). Those stay through 清空学到的 and
 * however long unused, until the English page's 恢复 ([restoreCorrection]) gives the word back to
 * correction, its uses kept, or it is forgotten ([forget]). A word added counts as typed [ADDED_COUNT] times, typed or not.
 *
 * Without a [log] (U-93's 「用我的输入习惯」 off, [OFF]) nothing is learned and nothing known. Read
 * on the fcitx thread, as the log is; what is read of it is made again only when the log's state
 * changed ([LearnState.version]).
 */
class EnglishUser(private val log: (() -> LearnLog)? = inMemory()) {

    /** Whether words committed are learned. */
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

    /**
     * Goes up as the words counted change, for a copy of what is known of them to know it is stale:
     * read from the log as asked, so a word committed a moment ago counts already.
     */
    val changes: Int
        get() {
            known()
            return changed
        }

    private fun known(): Known {
        val state = log?.invoke()?.state ?: return known
        // English's own version, not every record's: pinyin's commits leave the maps as they are
        // and rebuilding them is a walk over every English word and pair (R11 F2693)
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
            // never more of a word after [prev] than of [prev] itself, as pinyin's pairs (HabitCounts)
            val n = minOf(c.count.toFloat(), counts[prev] ?: 0f)
            if (n > 0f && word in counts) pairs.getOrPut(prev) { HashMap() }[word] = n
        }
        return Known(counts, pairs, kept, added, counts.values.sum())
    }

    /** How often [word] was committed lately, as written. */
    fun count(word: String): Float = known().counts[word] ?: 0f

    /** Whether [word] is never put right: a correction of it taken back, or added by hand. */
    fun neverCorrected(word: String): Boolean = word in known().kept

    /** The words never corrected ([neverCorrected]), typed lately or not. */
    val neverCorrectedWords: Set<String> get() = known().kept

    /** The words added by hand ([add]). */
    val addedWords: Set<String> get() = known().added

    /** The sum of the words' counts. */
    val total: Float get() = known().total

    /** Each word counted. */
    val words: Set<String> get() = known().counts.keys

    /** The words committed after [prev]. */
    fun followers(prev: String): Set<String> = known().pairs[prev]?.keys.orEmpty()

    /**
     * [word] committed after [prev] (null for none), [typed] as the keys spelt it, [how] it came
     * ([EnCommit.how]), [flags] the field's ([LearnRecord.Commit.NO_PAIRS]): the commit's number,
     * for [undo], or null if nothing was learned.
     */
    fun commit(prev: String?, word: String, typed: String = word, how: Int = EnCommit.TYPED, flags: Int = 0): Long? =
        log?.invoke()?.recordEnglish(prev.orEmpty(), word, typed, how, flags)?.seq

    /** Takes the commit [seq] back, if it is one of the last few: whether it was. */
    fun undo(seq: Long): Boolean = log?.invoke()?.undo(seq) ?: false

    /** Makes [word] the user's for good, as added on the English page: never corrected, offered by its keys. */
    fun add(word: String) {
        // added already: no record more
        if (word !in addedWords) log?.invoke()?.addEnglish(word, on = true)
    }

    /**
     * Has each of [words] corrected again (U-31 恢复 on the English page): not never corrected nor
     * added any more, its uses and pairs kept, so it is still offered as typed.
     */
    fun restoreCorrection(words: Collection<String>) {
        val log = log?.invoke() ?: return
        val kept = neverCorrectedWords
        for (word in words.toSet()) if (word in kept) log.correctEnglish(word)
    }

    /** Forgets [word], its count, every pair it is in and that it is never corrected: a long press on it, 删除 on the page. */
    fun forget(word: String) = forgetAll(listOf(word))

    /** Forgets each of [words] as [forget] does. */
    fun forgetAll(words: Collection<String>) {
        val log = log?.invoke() ?: return
        val state = log.state
        for (word in words.toSet()) if (word in state.enWords) log.forgetEnglish(word)
    }

    /**
     * The chance the user types [word] after [prev], from the counts alone, as pinyin's user model
     * has it: the pair's share of what followed [prev], leaning on the word's own share where
     * [prev] was seldom typed. 0 for a word not committed lately.
     */
    fun probability(prev: String?, word: String): Float {
        val k = known()
        val count = k.counts[word] ?: return 0f
        val alone = count / (k.total + UNSEEN)
        val before = prev?.let { k.counts[it] } ?: 0f
        if (before == 0f) return alone
        return ((k.pairs[prev]?.get(word) ?: 0f) + PRIOR * alone) / (before + PRIOR)
    }

    /** [base], a model's score of [word] after [prev], with the user's chance of it added: see pinyin's UserScorer. */
    fun score(base: Float, prev: String?, word: String): Float {
        val p = probability(prev, word)
        if (p == 0f) return minOf(0f, base)
        return minOf(0f, log10(10f.pow(base) + WEIGHT * p))
    }

    companion object {
        /** What a word added by hand counts as, typed or not: as typed twice. */
        const val ADDED_COUNT = 2f

        // as pinyin's user model and scorer have them
        private const val UNSEEN = 20f
        private const val PRIOR = 2f
        private const val WEIGHT = 0.1f

        /** Nothing learned, nothing known: U-93's switch off. */
        val OFF get() = EnglishUser(null)

        // a learning record of the session's own, in memory: a session made without the host's, a test
        private fun inMemory(): () -> LearnLog {
            val log = LearnLog(null)
            return { log }
        }
    }
}
