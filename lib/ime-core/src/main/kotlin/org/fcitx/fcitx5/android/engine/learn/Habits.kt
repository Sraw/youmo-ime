/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import java.io.IOException

/**
 * The user's habits as a session sees them: what it records commits into and ranks by; never the log
 * itself. [OFF] is 「用我的输入习惯」 off: nothing learned or read (blocked words stay, living in
 * `WordLists`); sessions made with one are remade when the setting changes.
 */
interface Habits {
    val learns: Boolean

    /** The typing day (打字日). */
    val today: Int

    fun record(rows: List<Row>, takes: List<Take> = emptyList(), flags: Int = 0): Commit?

    /** A 联想 pick ([rows]) continued with the whole phrase [continued]: a use of it whole. */
    fun recordContinued(rows: List<Row>, flags: Int, continued: String): Commit?
    fun pin(code: Code, text: String, reading: String, on: Boolean)
    fun forget(text: String)

    /** Forgets the whole phrase [text]: its count, no word of that text. */
    fun forgetPhrase(text: String)

    /** A fault of learning's own, never the commit's: reported, and nothing more learned this run. */
    fun failed(e: RuntimeException) = Unit

    /** In the last 30 typing days. */
    fun uses(code: Code, words: Words): Int

    /** While still in use there. */
    fun corrections(code: Code, words: Words): Int

    fun first(code: Code, prev: String): Words?

    fun pinned(code: Code): Words?

    /** Used under [code] in the last 30 typing days, to inject where the decoder lacks them. */
    fun used(code: Code): List<Words>

    /** The commit that first used [words] under [code] (firstSeq), 0 if none or unknown: breaks ties of uses. */
    fun firstUse(code: Code, words: Words): Long

    /** The commit [text] became the user's word in, or -1: the order of their words. */
    fun mineOrder(text: String): Long

    /** Anything learned of [text]: what forgetting it would forget. */
    fun knows(text: String): Boolean

    /** [ImportedWords.order], -1 if none. */
    fun importOrder(text: String): Long = -1L

    /** A word for [LastWord]: the user's. */
    fun isWord(text: String): Boolean

    /** [words] across one keyboard [kind]'s spellings: uses (last 30 typing days) under codes spelling it in full, corrections under 简拼 ones ([SpelledForm]). */
    fun spelled(kind: String, words: Words): Spelled = Spelled.NONE

    /** [kind]'s words with a [spelled] count, typed under codes starting with [first]: to inject where the decoder lacks them. */
    fun spelledFrom(kind: String, first: Char): List<SpelledWord> = emptyList()

    data class Spelled(val fullUses: Int, val initialsCorrections: Int) {
        companion object {
            val NONE = Spelled(0, 0)
        }
    }

    /** A [spelledFrom] word with its counts and syllables, computed once per record state: every level of every key asks for all words under its first key. */
    class SpelledWord(val words: Words, val spelled: Spelled) {
        val syllables: List<String> = words.reading.split(' ')
        val letters: Int = syllables.sumOf { it.length }
    }

    /** How often each syllable was taken for [digits] on the nine keys of [layout]. */
    fun bar(layout: String, digits: String): Map<String, Int>

    /** Takes [commit] back if one of the last few and in the tail; whether it was. */
    fun undo(commit: Commit): Boolean = false

    /** Which writing of the record this is ([LearnLog.rewrites]): on a change, commits a session kept are no longer the record's. */
    val rewrites: Long get() = 0

    /** Pins under [kind]'s codes and the user's multi-character firsts: what the user's sentences keep. */
    fun kept(kind: String): List<Kept> = emptyList()

    /** A pin ([prev] null), or the user's first after [prev]. */
    class Kept(val code: Code, val prev: String?, val words: Words)

    // no defaults below: a wrapper that forgot one would silently lose the user's blocks or resets

    /** Texts blocked under a table's [code] ([Kinds.table]) alone. */
    fun blockedUnder(code: Code): Set<String>

    /** Blocks [text] under a table's [code] alone, or ([on] false) lets it show there again. */
    fun blockUnder(code: Code, text: String, on: Boolean)

    /** 恢复码表顺序: forgets table [im]'s learned order (uses, firsts); not its pins or blocks. */
    fun resetTableOrder(im: String)

    object OFF : Habits {
        override val learns get() = false
        override val today get() = 0
        override fun record(rows: List<Row>, takes: List<Take>, flags: Int): Commit? = null
        override fun recordContinued(rows: List<Row>, flags: Int, continued: String): Commit? = null
        override fun pin(code: Code, text: String, reading: String, on: Boolean) = Unit
        override fun forget(text: String) = Unit
        override fun forgetPhrase(text: String) = Unit
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
 * These habits as [HabitRanker] reads them: one view for every ranker so none drifts. Without [pins]
 * nothing is pinned (tables, handwriting); without [mine] the user's words are no tier (handwriting ranks
 * characters); without [imports] neither are imported words.
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
 * [Habits] over [log], opened on first use ([LearnLog.open]). A write failing other than on disk (a
 * record bug) turns learning off for the run, reported to [onError] once: typing goes on as if off.
 */
class LogHabits(private val log: () -> LearnLog, private val onError: (IOException) -> Unit = {}) : Habits {
    private val state get() = log().state

    private var failed = false

    override val learns get() = !failed
    override val today get() = state.day
    override val rewrites get() = guarded(0L) { log().rewrites }

    override fun record(rows: List<Row>, takes: List<Take>, flags: Int) = guarded(null) { log().record(rows, takes, flags) }
    override fun recordContinued(rows: List<Row>, flags: Int, continued: String) = guarded(null) { log().record(rows, flags = flags, continued = continued) }
    override fun pin(code: Code, text: String, reading: String, on: Boolean) = guarded(Unit) { log().pin(code, text, reading, on) }
    override fun forget(text: String) = guarded(Unit) { log().forget(text) }
    override fun forgetPhrase(text: String) = guarded(Unit) { log().forgetPhrase(text) }

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

    // asked a few times per long press: the texts the rows hold, gathered once per state. An imported word is
    // the user's to delete, typed or not
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

    // read off the state's rows for the word, keyboard and first key: no index rebuilt after each commit
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

    // spelledFrom's words by keyboard and first key, per state: every level of a key asks
    private var fromOf: LearnState? = null
    private var fromAt = -1L
    private var fromDay = 0
    private val fromCache = HashMap<Pair<String, Char>, List<Habits.SpelledWord>>()

    override fun spelledFrom(kind: String, first: Char): List<Habits.SpelledWord> {
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

    private fun spelledFrom(s: LearnState, kind: String, keyboard: String, first: Char): List<Habits.SpelledWord> {
        // a nine-key digit: the words of each letter on its key
        val firsts = SpelledForm.nineKeyLetters(keyboard, first).ifEmpty { first.toString() }
        val words = LinkedHashSet<Words>()
        for (c in firsts) for (stat in s.spelledFrom(keyboard, c).values) words += stat.words
        // only those that move a word: walked every key. Counts as the keyboard has them (kind's own)
        return words.mapNotNull { w ->
            spelled(kind, w).takeIf { it.fullUses >= HabitRanker.FULL_USES || it.initialsCorrections > 0 }?.let { Habits.SpelledWord(w, it) }
        }
    }

    override fun importOrder(text: String) = state.imports.order(text)

    override fun blockedUnder(code: Code): Set<String> = state.tableBlocks[Kinds.tableOf(code.kind) to code.keys].orEmpty()

    // the bar's picks by keys, once per state: asked on every nine-key key
    private var indexed: LearnState? = null
    private var indexedAt = -1L
    private var barsByKeys = emptyMap<Pair<String, String>, Map<String, Int>>()

    // what sentences keep, by kind, once per state: asked every key
    private var keptOf: LearnState? = null
    private var keptAt = -1L
    private var keptByKind = emptyMap<String, List<Habits.Kept>>()

    override fun kept(kind: String): List<Habits.Kept> {
        val s = state
        if (s !== keptOf || s.version != keptAt) {
            keptOf = s
            keptAt = s.version
            // a lone character is the sentence's to read (得 pinned, wodeshu still 我的书)
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
 * How a session learns into the record: [kind] files its keys ([Kinds]: 双拼's scheme; the nine keys'
 * layout); [simplify] maps the app's traditional characters to simplified ([Simplifier]).
 */
class LearnTo(val habits: Habits = Habits.OFF, val kind: String = Kinds.FULL, val simplify: (String) -> String = { it })
