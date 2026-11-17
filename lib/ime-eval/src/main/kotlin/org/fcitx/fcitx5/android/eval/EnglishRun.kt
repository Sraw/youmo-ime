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
import org.fcitx.fcitx5.android.engine.pinyin.Touch
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
 *
 * With a [hand] each key is tapped with a place ([TouchSim], `qwerty` or `nine`): a key typed as
 * meant lands in its own box, one slipped in the key it slipped to, aimed at the key meant; the
 * session is given the place unless [readsTouch] is off (as 0.6.1 before 061-neighbour-english, which read none).
 * [oneSlip] in [run]: a key of each word slips, landing where the hand's spread takes it in a key
 * next to it (TouchSim's `slip`), in place of [slip]'s.
 */
internal class EnglishRun(
    private val data: EnglishData,
    private val costs: TypingCosts = TypingCosts(),
    private val maxCorrection: Float = EnglishSession.MAX_CORRECTION,
    // typed on the nine keys (Action.NineKey), each word's letters as their keys, Shift where a letter is a capital
    private val nineKeys: Boolean = false,
    private val hand: TouchSim.Hand? = null,
    private val readsTouch: Boolean = true,
) {
    private val board = hand?.let { TouchSim.board(if (nineKeys) "nine" else "qwerty") }

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
        fun nineRow(name: String = "nine"): String {
            fun pct(n: Int, of: Int = words) = if (of == 0) "-" else String.format(Locale.ROOT, "%.1f%%", n * 100.0 / of)
            return String.format(
                Locale.ROOT, "%-6s %6d %7s %7s %9s %8s %6d %7s %7s",
                name, words, pct(right), pct(top3), pct(predicted), pct(absent), slipped, pct(fixed, slipped), pct(broken, words - slipped),
            )
        }

        companion object {
            const val HEADER = "typing  words   right    top3  predicted  slips   fixed  broken"
            const val NINE_HEADER = "typing  words   first    top3  predicted  absent  slips   fixed  broken"
        }
    }

    /** Each sentence's slips are of a seed of its own, [seed] and its index: the same on any [threads]. */
    fun run(sentences: List<String>, noise: Double, seed: Long, threads: Int, oneSlip: Boolean = false): Result {
        data.warmUp()
        require(!oneSlip || board != null) { "a slip a word needs taps with a place" }
        val each = dealt(sentences.withIndex().toList(), threads, {}) { part ->
            // the nine keys are pressed as meant: no slip of a neighbour's letter but where they land
            part.map { (i, sentence) ->
                Result().also { type(sentence, if (nineKeys || oneSlip) 0.0 else noise, Random(seed + i), java.util.Random(seed * TAP_SEED + i), oneSlip, it) }
            }
        }
        return Result().apply { each.forEach(::add) }
    }

    private fun type(sentence: String, noise: Double, random: Random, taps: java.util.Random, oneSlip: Boolean, result: Result) {
        val session = EnglishSession(data, pageSize = PAGE, costs = costs, maxCorrection = maxCorrection)
        session.apply(Action.Context(""))
        val meant = StringBuilder()
        var offered = emptyList<String>()
        for (token in sentence.split(' ')) {
            val word = token.trimEnd(*PUNCTUATION)
            val punctuation = token.substring(word.length)
            if (word in offered.take(3)) result.predicted++
            val keys = keysOf(word)
            val aimed = if (noise > 0) slipAimed(keys, noise, random) else keys.map { it to it }
            val tapped = tapped(aimed, taps, oneSlip)
            val typed = tapped.joinToString("") { it.first.toString() }
            var shown: Snapshot? = null
            for ((i, tap) in tapped.withIndex()) {
                val c = tap.first
                // not read: as 0.6.1 before, no place given
                val touch = tap.second?.takeIf { readsTouch }
                // a digit tapped() put for a letter is a key slipped to; one the text has is typed as a digit
                shown = session.apply(if (nineKeys) nineKey(c, touch, aimed[i].second.isUpperCase(), slid = c != aimed[i].first) else Action.Key(c, touch = touch))
            }
            if (shown != null && word in shown.candidates.take(3)) result.top3++
            if (nineKeys && shown != null && word !in offeredBy(session)) result.absent++
            val after = session.apply(Action.Key(punctuation.firstOrNull() ?: ' '))
            val committed = after.commit.trimEnd(' ').removeSuffix(punctuation.take(1))
            result.words++
            if (committed == word) result.right++ else result.misses += "$word\t$typed\t$committed"
            if (typed != keys && nineKey(typed) != nineKey(keys)) {
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

    // [c] as the nine keys type it: a letter its key, Shift on for a capital, a key [slid] to its digit, Shift on where
    // it was meant for one ([upper]); anything else as itself (1's apostrophe, a digit of the text's own, as 0.6.0
    // typed it: told by the tap, not the character, review F8255)
    private fun nineKey(c: Char, touch: Touch?, upper: Boolean, slid: Boolean): Action {
        if (slid) return Action.NineKey(c, upper, touch)
        val digit = digitOf(c)
        return if (digit == null) Action.Key(c) else Action.NineKey(digit, c.isUpperCase(), touch)
    }

    private fun digitOf(c: Char) = ('2'..'9').firstOrNull { c.lowercaseChar() in NineKeyLetters.STANDARD.letters(it) }

    // [keys] as the nine keys' digits: a word slipped there is one whose digits differ
    private fun nineKey(keys: String) = if (nineKeys) keys.map { digitOf(it) ?: it }.joinToString("") else keys

    /**
     * The keys [aimed] (each typed, and the key meant) tapped: each with a place where there is a
     * [board] (on the nine keys, the digit; a slipped letter, the digit it landed on); [oneSlip], one
     * key of the word landing in a key next to its own instead.
     */
    private fun tapped(aimed: List<Pair<Char, Char>>, taps: java.util.Random, oneSlip: Boolean): List<Pair<Char, Touch?>> {
        val board = board ?: return aimed.map { it.first to null }
        fun key(c: Char): Char? = (if (nineKeys) digitOf(c) else c.lowercaseChar())?.takeIf { it in board.keys }
        val on = aimed.indices.filter { key(aimed[it].second) != null }
        val slipAt = if (oneSlip && on.isNotEmpty()) on[taps.nextInt(on.size)] else -1
        return aimed.mapIndexed { i, (typed, meant) ->
            val k = key(meant)
            val t = key(typed)
            if (k == null || t == null) return@mapIndexed typed to null
            val onto = if (i == slipAt) board.boxes.around(k).map { it.char } else listOf(t)
            val (hit, touch) = TouchSim.tap(board, k, onto, hand!!, taps) ?: return@mapIndexed typed to null
            when {
                hit == t -> typed to touch
                nineKeys -> hit to touch
                else -> (if (typed.isUpperCase()) hit.uppercaseChar() else hit) to touch
            }
        }
    }

    companion object {
        private const val PAGE = 10
        private const val TAP_SEED = 1_000_003L
        private val PUNCTUATION = charArrayOf(',', '.', '!', '?', ';', ':')

        /**
         * [keys] with each key slipping at the rate [noise]: a key beside it in its place, half
         * the slips; left out, one beside it typed after it, or swapped with the next, the rest.
         * Never all left out.
         */
        fun slip(keys: String, noise: Double, random: Random): String = slipAimed(keys, noise, random).joinToString("") { it.first.toString() }

        /** [slip]'s keys, each with the key meant: a key beside it typed in its place or after it, aimed at it. */
        fun slipAimed(keys: String, noise: Double, random: Random): List<Pair<Char, Char>> {
            val out = ArrayList<Pair<Char, Char>>()
            var i = 0
            while (i < keys.length) {
                val c = keys[i]
                if (random.nextDouble() >= noise) {
                    out += c to c
                    i++
                    continue
                }
                val beside = KeyNeighbours.of(c.lowercaseChar()).ifEmpty { c.toString() }
                    .let { it[random.nextInt(it.length)] }
                    .let { if (c.isUpperCase()) it.uppercaseChar() else it }
                val kind = random.nextDouble()
                when {
                    kind < SUBSTITUTED -> out += beside to c
                    kind < LEFT_OUT -> Unit
                    kind < ADDED -> {
                        out += c to c
                        out += beside to c
                    }
                    i + 1 < keys.length -> {
                        out += keys[i + 1] to keys[i + 1]
                        out += c to c
                        i++
                    }
                    else -> out += c to c
                }
                i++
            }
            return out.ifEmpty { keys.map { it to it } }
        }

        private const val SUBSTITUTED = 0.5
        private const val LEFT_OUT = 0.7
        private const val ADDED = 0.85
    }
}
