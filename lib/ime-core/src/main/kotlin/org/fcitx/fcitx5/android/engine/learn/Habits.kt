/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import java.io.IOException

/**
 * The user's habits as a session sees them (PLAN §2.8): what it records a commit into and,
 * from step 1b, ranks by; never the log itself. [OFF] is U-93's 「用我的输入习惯」 off: nothing
 * learned, nothing of it read (blocked words stay blocked, as they live in `WordLists`); a
 * session made with one is made anew when the setting changes.
 */
interface Habits {
    /** Whether commits are learned. */
    val learns: Boolean

    /** The typing day it is (PLAN §2.2 打字日). */
    val today: Int

    fun record(rows: List<Row>, takes: List<Take> = emptyList(), flags: Int = 0): Commit?
    fun pin(code: Code, text: String, reading: String, on: Boolean)
    fun forget(text: String)

    /** A fault of learning's own ([e]), never the user's commit's: reported, and nothing more learned this run. */
    fun failed(e: RuntimeException) = Unit

    /** How often [words] were used under [code] in the last 30 typing days. */
    fun uses(code: Code, words: Words): Int

    /** How often [words] were corrected to under [code], while still in use there. */
    fun corrections(code: Code, words: Words): Int

    /** The user's own first under [code] after [prev], if any. */
    fun first(code: Code, prev: String): Words?

    fun pinned(code: Code): Words?

    /** The words used under [code] in the last 30 typing days, to put among the candidates where the decoder lacks them. */
    fun used(code: Code): List<Words>

    /** The commit that first used [words] under [code] (STAT's firstSeq), 0 if none or not known: what breaks a tie of uses. */
    fun firstUse(code: Code, words: Words): Long

    /** The commit [text] became the user's word in, or -1 if it is none of theirs: their words' order (U-10). */
    fun mineOrder(text: String): Long

    /** Whether anything is learned of [text]: what forgetting it would forget (U-84). */
    fun knows(text: String): Boolean

    /** Where [text] goes among the imported words ([ImportedWords.order]), -1 if it is none. */
    fun importOrder(text: String): Long = -1L

    /** Whether [text] is a word for [LastWord]: the user's. */
    fun isWord(text: String): Boolean

    /**
     * [words] across the spellings of one keyboard [kind] (U-20, D32 B): its uses in the last 30
     * typing days under the codes spelling it in full, its corrections under the 简拼 ones ([SpelledForm]).
     */
    fun spelled(kind: String, words: Words): Spelled = Spelled.NONE

    /** The words of [kind] with a count of [spelled], typed under codes whose first key is [first]: to put in where the decoder lacks them. */
    fun spelledFrom(kind: String, first: Char): List<Words> = emptyList()

    /** What [spelled] tells: uses spelt in full, corrections under 简拼. */
    data class Spelled(val fullUses: Int, val initialsCorrections: Int) {
        companion object {
            val NONE = Spelled(0, 0)
        }
    }

    /** How often each syllable was taken for [digits] on the nine keys of [layout]. */
    fun bar(layout: String, digits: String): Map<String, Int>

    /** Takes [commit] back (U-03), if it is one of the last few and in the tail: whether it was. */
    fun undo(commit: Commit): Boolean = false

    /** Which writing of the record this is ([LearnLog.rewrites]): another, and commits a session kept from before are not the record's. */
    val rewrites: Long get() = 0

    /** The pins under codes of [kind] and the user's firsts there of several characters: what the user's sentences keep (U-08, D33). */
    fun kept(kind: String): List<Kept> = emptyList()

    /** [words] kept under [code]: a pin ([prev] null), or the user's first after [prev]. */
    class Kept(val code: Code, val prev: String?, val words: Words)

    // no defaults: a wrapper that forgot one would lose the user's blocks, or drop a block or a reset unsaid

    /** The texts a table's [code] ([Kinds.table]) shows no more, blocked there alone (U-28). */
    fun blockedUnder(code: Code): Set<String>

    /** Blocks [text] under a table's [code] alone, or ([on] false) lets it show there again. */
    fun blockUnder(code: Code, text: String, on: Boolean)

    /** 恢复码表顺序: forgets the order the table [im] learned, its uses and firsts; not its pins, nor its blocks. */
    fun resetTableOrder(im: String)

    object OFF : Habits {
        override val learns get() = false
        override val today get() = 0
        override fun record(rows: List<Row>, takes: List<Take>, flags: Int): Commit? = null
        override fun pin(code: Code, text: String, reading: String, on: Boolean) = Unit
        override fun forget(text: String) = Unit
        override fun uses(code: Code, words: Words) = 0
        override fun corrections(code: Code, words: Words) = 0
        override fun first(code: Code, prev: String): Words? = null
        override fun pinned(code: Code): Words? = null
        override fun used(code: Code): List<Words> = emptyList()
        override fun firstUse(code: Code, words: Words) = 0L
        override fun mineOrder(text: String) = -1L
        override fun knows(text: String) = false
        override fun isWord(text: String) = false
        override fun bar(layout: String, digits: String): Map<String, Int> = emptyMap()
        override fun blockedUnder(code: Code): Set<String> = emptySet()
        override fun blockUnder(code: Code, text: String, on: Boolean) = Unit
        override fun resetTableOrder(im: String) = Unit
    }
}

/**
 * These habits as [HabitRanker] reads them: one view for every ranker, so none drifts from the
 * others (review R11 F2720). Without [pins] nothing is pinned (the tables, handwriting); without
 * [mine] the user's words are no tier (handwriting ranks characters); without [imports] neither
 * are the imported words.
 */
fun Habits.rankView(pins: Boolean = true, mine: Boolean = true, imports: Boolean = true): HabitRanker.View {
    val h = this
    return object : HabitRanker.View {
        override fun pinned(code: Code): Words? = if (pins) h.pinned(code) else null
        override fun first(code: Code, prev: String) = h.first(code, prev)
        override fun uses(code: Code, words: Words) = h.uses(code, words)
        override fun corrections(code: Code, words: Words) = h.corrections(code, words)
        override fun firstUse(code: Code, words: Words) = h.firstUse(code, words)
        override fun mineOrder(text: String) = if (mine) h.mineOrder(text) else -1L
        override fun importOrder(text: String) = if (imports) h.importOrder(text) else -1L
        override fun spelled(kind: String, words: Words) = h.spelled(kind, words)
    }
}

/**
 * [Habits] over [log], opened when first asked for (see [LearnLog.open]). A write that fails
 * other than on the disk (a bug of the record's) turns learning off for the run, reported to
 * [onError] once (review-1a-app S1): typing goes on as if it were off.
 */
class LogHabits(private val log: () -> LearnLog, private val onError: (IOException) -> Unit = {}) : Habits {
    private val state get() = log().state

    private var failed = false

    override val learns get() = !failed
    override val today get() = state.day
    override val rewrites get() = guarded(0L) { log().rewrites }

    override fun record(rows: List<Row>, takes: List<Take>, flags: Int) = guarded(null) { log().record(rows, takes, flags) }
    override fun pin(code: Code, text: String, reading: String, on: Boolean) = guarded(Unit) { log().pin(code, text, reading, on) }
    override fun forget(text: String) = guarded(Unit) { log().forget(text) }

    override fun blockUnder(code: Code, text: String, on: Boolean) = guarded(Unit) {
        log().event { minute, _ -> LearnRecord.TableBlock(minute, Kinds.tableOf(code.kind), code.keys, text, on) }
        Unit
    }

    override fun resetTableOrder(im: String) = guarded(Unit) {
        log().event { minute, _ -> LearnRecord.TableOrderReset(minute, im) }
        Unit
    }

    override fun undo(commit: Commit) = guarded(false) { log().undoRecent(commit.seq, commit) }

    override fun failed(e: RuntimeException) {
        if (failed) return
        failed = true
        onError(IOException("learning off for this run", e))
    }

    private inline fun <T> guarded(otherwise: T, write: () -> T): T {
        if (failed) return otherwise
        return try {
            write()
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            failed(e)
            otherwise
        }
    }

    private fun stat(code: Code, words: Words) = state.stats[LearnState.StatKey(code, words.text, words.reading)]

    override fun uses(code: Code, words: Words) = stat(code, words)?.uses?.sumFrom(state.day - LearnState.WINDOW + 1) ?: 0

    override fun corrections(code: Code, words: Words) = stat(code, words)?.let { it.corrections.sumFrom(Int.MIN_VALUE) + it.older } ?: 0

    override fun first(code: Code, prev: String) = state.firsts[LearnState.FirstKey(code, prev)]?.first

    override fun pinned(code: Code) = state.pins[code]

    override fun used(code: Code): List<Words> {
        val from = state.day - LearnState.WINDOW + 1
        return state.statsOf(code).values.filter { it.uses.sumFrom(from) > 0 }.map { it.words }
    }

    override fun firstUse(code: Code, words: Words) = stat(code, words)?.firstSeq ?: 0L

    override fun mineOrder(text: String) = state.mine[text]?.sinceSeq ?: -1L

    // a long press's question, asked a few times a press (review-int-runtime N5): the texts the rows
    // hold, gone through once a state as it is. A word imported is the user's to delete, typed or not (U-82, D13)
    override fun knows(text: String): Boolean {
        val s = state
        return text in s.mine || s.imports.placesOf(text).isNotEmpty() || text in s.pending || text in s.wordUses || text in rowTexts(s)
    }

    private var rowTextsOf: LearnState? = null
    private var rowTextsAt = -1L
    private var rowTexts = emptySet<String>()

    private fun rowTexts(s: LearnState): Set<String> {
        if (s !== rowTextsOf || s.version != rowTextsAt) {
            rowTextsOf = s
            rowTextsAt = s.version
            rowTexts = HashSet<String>().apply {
                s.stats.keys.forEach { add(it.text) }
                s.pairs.keys.forEach { add(it.prev); add(it.next) }
                s.firsts.forEach { (k, f) ->
                    add(k.prev)
                    f.first?.let { add(it.text) }
                    f.set.forEach { add(it.text) }
                }
            }
        }
        return rowTexts
    }

    override fun isWord(text: String) = text in state.mine

    // read off the state's rows of the word, and of the keyboard and first key: no index to make
    // again after each commit (R14 F3733)
    override fun spelled(kind: String, words: Words): Habits.Spelled {
        val s = state
        val keyboard = Kinds.keyboard(kind)
        val from = s.day - LearnState.WINDOW + 1
        var uses = 0
        var corrections = 0
        for ((key, stat) in s.statsOf(words.text, words.reading)) {
            if (Kinds.keyboard(key.code.kind) != keyboard) continue
            when (SpelledForm.of(key.code.kind, key.code.keys, key.reading)) {
                SpelledForm.FULL -> uses += stat.uses.sumFrom(from)
                SpelledForm.INITIALS -> corrections += stat.corrections.sumFrom(Int.MIN_VALUE) + stat.older
                SpelledForm.PART, null -> Unit
            }
        }
        return if (uses == 0 && corrections == 0) Habits.Spelled.NONE else Habits.Spelled(uses, corrections)
    }

    // spelledFrom's words by keyboard and first key, of the state as it is: each level of a key asks
    private var fromOf: LearnState? = null
    private var fromAt = -1L
    private var fromDay = 0
    private val fromCache = HashMap<Pair<String, Char>, List<Words>>()

    override fun spelledFrom(kind: String, first: Char): List<Words> {
        val s = state
        // advance() moves the day with no version of its own
        if (s !== fromOf || s.version != fromAt || s.day != fromDay) {
            fromOf = s
            fromAt = s.version
            fromDay = s.day
            fromCache.clear()
        }
        val keyboard = Kinds.keyboard(kind)
        return fromCache.getOrPut(keyboard to first) { spelledFrom(s, kind, keyboard, first) }
    }

    private fun spelledFrom(s: LearnState, kind: String, keyboard: String, first: Char): List<Words> {
        // a digit on the nine keys: the words of each letter on its key
        val firsts = SpelledForm.nineKeyLetters(keyboard, first).ifEmpty { first.toString() }
        val words = LinkedHashSet<Words>()
        for (c in firsts) for (stat in s.spelledFrom(keyboard, c).values) words += stat.words
        // only those that move a word: walked each key, so none that would not
        return words.filter { w -> spelled(kind, w).let { it.fullUses >= HabitRanker.FULL_USES || it.initialsCorrections > 0 } }
    }

    override fun importOrder(text: String) = state.imports.order(text)

    override fun blockedUnder(code: Code): Set<String> = state.tableBlocks[Kinds.tableOf(code.kind) to code.keys].orEmpty()

    // the bar's picks by their keys, worked out once a state as it is: asked each key of the nine
    // keys (review-1b N7)
    private var indexed: LearnState? = null
    private var indexedAt = -1L
    private var barsByKeys = emptyMap<Pair<String, String>, Map<String, Int>>()

    // what sentences keep by kind, worked out once a state as it is: asked each key
    private var keptOf: LearnState? = null
    private var keptAt = -1L
    private var keptByKind = emptyMap<String, List<Habits.Kept>>()

    override fun kept(kind: String): List<Habits.Kept> {
        val s = state
        if (s !== keptOf || s.version != keptAt) {
            keptOf = s
            keptAt = s.version
            // a character alone is the sentence's to read (得 pinned, wodeshu still 我的书): the user, 2026-10-21
            val all = s.pins.mapNotNull { (code, words) -> words.takeIf(::several)?.let { Habits.Kept(code, null, it) } } +
                s.firsts.mapNotNull { (key, f) -> f.first?.takeIf(::several)?.let { Habits.Kept(key.code, key.prev, it) } }
            keptByKind = all.groupBy { it.code.kind }
        }
        return keptByKind[kind].orEmpty()
    }

    private fun several(words: Words) = words.text.codePointCount(0, words.text.length) > 1

    override fun bar(layout: String, digits: String): Map<String, Int> {
        val s = state
        if (s !== indexed || s.version != indexedAt) {
            indexed = s
            indexedAt = s.version
            barsByKeys = s.bars.entries.groupBy({ it.key.layout to it.key.digits }) { it.key.syllable to it.value.count }.mapValues { it.value.toMap() }
        }
        return barsByKeys[layout to digits].orEmpty()
    }
}

/**
 * How a session learns into the record: its [habits]; [kind], what its keys are filed under
 * ([Kinds]: 双拼's scheme; the nine keys' is their layout's, from the session's segmenter);
 * [simplify], traditional characters of the app's text to simplified ones ([Simplifier]).
 */
class LearnTo(val habits: Habits = Habits.OFF, val kind: String = Kinds.FULL, val simplify: (String) -> String = { it })
