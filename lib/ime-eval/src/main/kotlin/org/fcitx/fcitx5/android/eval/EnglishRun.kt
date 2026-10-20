/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.NgramModel
import org.fcitx.fcitx5.android.engine.english.EnglishData
import org.fcitx.fcitx5.android.engine.english.EnglishSession
import org.fcitx.fcitx5.android.engine.english.TypingCosts
import org.fcitx.fcitx5.android.engine.pinyin.KeyNeighbours
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Snapshot
import java.util.Locale
import kotlin.random.Random

/**
 * English typed as a user types it into the app: each sentence a word at a time into one session,
 * the letters as written, an apostrophe left out where that is no word (dont for don't, which the
 * session is to put back; not its for it's), then space, or the punctuation after the word, which
 * the session follows with a space. With [noise], each key may slip ([slip]). What space commits is measured against the word meant, as are the candidates the
 * word typed shows and the words offered before it ([Snapshot.predicting]). A word committed
 * wrong is put right before the next, as the user would: a backspace, then the text meant, so the
 * next word follows the text meant. As in the app, the backspace takes back only a correction space
 * made, and the word typed, a slip too, is then the user's for the rest of the sentence; after a
 * correction punctuation made, or a word committed as typed, it takes nothing back, and what was
 * counted of the word stays counted.
 */
internal class EnglishRun(
    private val data: EnglishData,
    private val costs: TypingCosts = TypingCosts(),
    private val maxCorrection: Float = EnglishSession.MAX_CORRECTION,
    // typed on the nine keys (Action.NineKey), each word's letters as their keys, Shift where a letter is a capital
    private val nineKeys: Boolean = false,
) {
    class Result {
        var words = 0
        var right = 0 // committed as meant
        var top3 = 0 // among the first three candidates once typed
        var predicted = 0 // among the first three offered before a key of it
        var slipped = 0 // typed with a slip
        var fixed = 0 // of those, committed as meant
        var broken = 0 // typed without a slip, committed otherwise
        var absent = 0 // on the nine keys, not among the candidates at all: its letters pinned by long press needed
        val misses = ArrayList<String>() // meant, typed, committed: each word committed otherwise

        fun add(other: Result) {
            words += other.words
            right += other.right
            top3 += other.top3
            predicted += other.predicted
            slipped += other.slipped
            fixed += other.fixed
            broken += other.broken
            absent += other.absent
            misses += other.misses
        }

        fun row(name: String): String {
            fun pct(n: Int, of: Int) = if (of == 0) "-" else String.format(Locale.ROOT, "%.1f%%", n * 100.0 / of)
            return String.format(
                Locale.ROOT, "%-6s %6d %7s %7s %9s %6d %7s %7s",
                name, words, pct(right, words), pct(top3, words), pct(predicted, words),
                slipped, pct(fixed, slipped), pct(broken, words - slipped),
            )
        }

        /** On the nine keys: committed as meant (the first), among the first three, not offered at all. */
        fun nineRow(): String {
            fun pct(n: Int) = if (words == 0) "-" else String.format(Locale.ROOT, "%.1f%%", n * 100.0 / words)
            return String.format(Locale.ROOT, "%-6s %6d %7s %7s %9s %8s", "nine", words, pct(right), pct(top3), pct(predicted), pct(absent))
        }

        companion object {
            const val HEADER = "typing  words   right    top3  predicted  slips   fixed  broken"
            const val NINE_HEADER = "typing  words   first    top3  predicted  absent"
        }
    }

    /** Each sentence's slips are of a seed of its own, [seed] and its index: the same on any [threads]. */
    fun run(sentences: List<String>, noise: Double, seed: Long, threads: Int): Result {
        data.warmUp()
        val each = dealt(sentences.withIndex().toList(), threads, {}) { hand ->
            // the nine keys are pressed as meant: no slip of a neighbour's letter
            hand.map { (i, sentence) -> Result().also { type(sentence, if (nineKeys) 0.0 else noise, Random(seed + i), it) } }
        }
        return Result().apply { each.forEach(::add) }
    }

    private fun type(sentence: String, noise: Double, random: Random, result: Result) {
        val session = EnglishSession(data, pageSize = PAGE, costs = costs, maxCorrection = maxCorrection)
        session.apply(Action.Context(""))
        val meant = StringBuilder()
        var offered = emptyList<String>()
        for (token in sentence.split(' ')) {
            val word = token.trimEnd(*PUNCTUATION)
            val punctuation = token.substring(word.length)
            if (word in offered.take(3)) result.predicted++
            val keys = keysOf(word)
            val typed = if (noise > 0) slip(keys, noise, random) else keys
            var shown: Snapshot? = null
            for (c in typed) shown = session.apply(if (nineKeys) nineKey(c) else Action.Key(c))
            if (shown != null && word in shown.candidates.take(3)) result.top3++
            if (nineKeys && shown != null && word !in offeredBy(session)) result.absent++
            val after = session.apply(Action.Key(punctuation.firstOrNull() ?: ' '))
            val committed = after.commit.trimEnd(' ').removeSuffix(punctuation.take(1))
            result.words++
            if (committed == word) result.right++ else result.misses += "$word\t$typed\t$committed"
            if (typed != keys) {
                result.slipped++
                if (committed == word) result.fixed++
            } else if (committed != word) {
                result.broken++
            }
            meant.append(token).append(' ')
            offered = if (after.predicting) after.candidates else emptyList()
            if (committed != word) {
                // a backspace first, as the user's: right after a correction it takes it back, and
                // what was counted of it with it; then the text meant, as much as the host reads of it
                session.apply(Action.Backspace)
                session.apply(Action.Context(meant.takeLast(Action.Context.HOST_CHARS).toString()))
                offered = emptyList()
            }
        }
    }

    // an apostrophe left out where that is no word too (dont), not where it is (its, were); the nine keys type it
    private fun keysOf(word: String): String {
        val bare = word.replace("'", "")
        if (nineKeys || bare == word) return word
        return if (data.id(bare) == NgramModel.NO_WORD && data.id(bare.lowercase()) == NgramModel.NO_WORD) bare else word
    }

    private fun offeredBy(session: EnglishSession) = session.candidates(0, EnglishSession.SUGGESTED + 1).map { it.text }

    // [c] as the nine keys type it: a letter its key, Shift on for a capital; anything else as itself (1's apostrophe)
    private fun nineKey(c: Char): Action {
        val digit = ('2'..'9').firstOrNull { c.lowercaseChar() in NineKeyLetters.STANDARD.letters(it) }
        return if (digit == null) Action.Key(c) else Action.NineKey(digit, c.isUpperCase())
    }

    companion object {
        private const val PAGE = 10
        private val PUNCTUATION = charArrayOf(',', '.', '!', '?', ';', ':')

        /**
         * [keys] with each key slipping at the rate [noise]: a key beside it in its place, half
         * the slips; left out, one beside it typed after it, or swapped with the next, the rest.
         * Never all left out.
         */
        fun slip(keys: String, noise: Double, random: Random): String {
            val out = StringBuilder()
            var i = 0
            while (i < keys.length) {
                val c = keys[i]
                if (random.nextDouble() >= noise) {
                    out.append(c)
                    i++
                    continue
                }
                val beside = KeyNeighbours.of(c.lowercaseChar()).ifEmpty { c.toString() }
                    .let { it[random.nextInt(it.length)] }
                    .let { if (c.isUpperCase()) it.uppercaseChar() else it }
                val kind = random.nextDouble()
                when {
                    kind < SUBSTITUTED -> out.append(beside)
                    kind < LEFT_OUT -> Unit
                    kind < ADDED -> out.append(c).append(beside)
                    i + 1 < keys.length -> {
                        out.append(keys[i + 1]).append(c)
                        i++
                    }
                    else -> out.append(c)
                }
                i++
            }
            return out.ifEmpty { keys }.toString()
        }

        private const val SUBSTITUTED = 0.5
        private const val LEFT_OUT = 0.7
        private const val ADDED = 0.85
    }
}
