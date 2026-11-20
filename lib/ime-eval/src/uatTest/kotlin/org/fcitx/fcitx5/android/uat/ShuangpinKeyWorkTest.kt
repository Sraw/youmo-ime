/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.engine.lattice.Decoding
import org.fcitx.fcitx5.android.engine.lattice.PinyinDecoder
import org.fcitx.fcitx5.android.engine.lattice.WordScorer
import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.eval.Sample
import org.fcitx.fcitx5.android.eval.TouchSim
import org.fcitx.fcitx5.android.eval.TypedHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A 双拼 key's decoder work on the app's data, held under a ceiling, and its reading equal to a fresh decoder's. */
class ShuangpinKeyWorkTest {

    // 小鹤 sentences, their syllables on end: [keys] keys a line, whole or the first whole and the rest by initials
    private fun inputs(keys: Int, lines: Int, initials: Boolean): List<String> {
        val pairs = File("data/shuangpin-xiaohe.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }.filter { it[0].length == 2 * it[1].length }.flatMap { it[0].chunked(2) }
        val each = if (initials) keys - 1 else keys / 2
        return (0 until lines).map { n ->
            val line = pairs.subList(n * each, (n + 1) * each)
            if (initials) line[0] + line.drop(1).joinToString("") { it.take(1) } else line.joinToString("")
        }
    }

    // one array of the nodes' likelier words for every decoder: a decoder a key otherwise works them out again
    private fun decoder(user: UserModel? = null) = UatEngines.pinyinData.let { PinyinDecoder(it.dictionary, it.vocabulary, WordScorer.of(it.model), user = user, extensions = { extensions }) }

    // a user who typed the [words] likeliest words of the dictionary
    private fun typed(words: Int) = UatEngines.pinyinData.let { data ->
        UserModel(data.dictionary, data.vocabulary, index = data.wordIndex).also { assertEquals(words, TypedHistory.count(it, data, TypedHistory.likeliest(data, words))) }
    }

    private fun describe(d: Decoding) = (d.sentences + listOf(null) + d.words).map { c -> c?.let { "${it.text} ${it.end} ${it.score} ${it.words.toList()}" } }

    private class Taken(val arcs: Double, val steps: Double, val scored: Double, val first: Double) {
        override fun toString() = "a key: %.0f arcs, %.0f steps, %.0f scored, %.0f first words scored".format(arcs, steps, scored, first)
    }

    // types [inputs] a key at a time on one decoder, each key read as a fresh decoder reads it; [tapped] with a place
    private fun type(inputs: List<String>, abbreviations: Boolean = true, user: UserModel? = null, tapped: Boolean = false, letters: NineKeyLetters? = null): Taken {
        // the taps are the full keyboard's, and a grouped keyboard's segmenter reads no key next to another
        require(letters == null || !tapped) { "tapped on the full keyboard only" }
        val segmenter = if (letters == null) {
            ShuangpinSegmenter(ShuangpinScheme.XIAOHE, abbreviations = abbreviations, neighbours = tapped)
        } else {
            GroupedShuangpinSegmenter(ShuangpinScheme.XIAOHE, letters, abbreviations = abbreviations)
        }
        val touches = if (tapped) TouchSim.convert(inputs.map { Sample(it, it, "") }, "qwerty:s0.2:clean").associate { it.input to it.touches!! } else null
        val kept = decoder(user)
        var arcs = 0L
        var steps = 0L
        var scored = 0L
        var first = 0L
        var keys = 0
        for (input in inputs) {
            kept.reset()
            for (length in 1..input.length) {
                val graph = segmenter.segment(input.substring(0, length), null, touches?.getValue(input)?.subList(0, length)?.toTypedArray())
                val read = describe(kept.decode(graph))
                kept.work.let {
                    arcs += it.arcs
                    steps += it.steps
                    scored += it.scored
                    first += it.firstScored
                }
                keys++
                // the words between the last positions too, where a key changes them, in their order
                fun between(d: PinyinDecoder) = (maxOf(0, length - 6) until length).map { a -> d.arcsBetween(graph, a, length).map { "${it.text} ${it.score}" } }
                val keptBetween = between(kept)
                val fresh = decoder(user)
                assertEquals(input.substring(0, length), describe(fresh.decode(graph)), read)
                assertEquals(input.substring(0, length), between(fresh), keptBetween)
            }
        }
        return Taken(arcs.toDouble() / keys, steps.toDouble() / keys, scored.toDouble() / keys, first.toDouble() / keys)
    }

    @Test
    fun aKeyInWholeSyllablesTakesNoMoreThanItDid() {
        val taken = type(inputs(KEYS, LINES, initials = false), user = typed(HISTORY))
        println("双拼 whole syllables, $HISTORY words typed, $taken")
        assertTrue("$taken", taken.steps <= WHOLE_STEPS && taken.scored <= WHOLE_SCORED && taken.arcs <= WHOLE_ARCS && taken.first <= WHOLE_FIRST)
    }

    @Test
    fun aKeyByInitialsTakesNoMoreThanItDid() {
        val taken = type(inputs(KEYS, LINES, initials = true), user = typed(HISTORY))
        println("双拼 initials, $HISTORY words typed, $taken")
        assertTrue("$taken", taken.steps <= INITIALS_STEPS && taken.scored <= INITIALS_SCORED && taken.arcs <= INITIALS_ARCS && taken.first <= INITIALS_FIRST)
    }

    // both other readings at once, as a phone types: words typed, and each key tapped in a place
    @Test
    fun aKeyTappedWithWordsTypedTakesNoMoreThanItDid() {
        // both readings', asserted once
        val over = ArrayList<String>()
        for (initials in listOf(false, true)) {
            val taken = type(inputs(KEYS, LINES, initials), user = typed(HISTORY), tapped = true)
            println("双拼 ${if (initials) "initials" else "whole syllables"}, $HISTORY words typed, tapped, $taken")
            val within = if (initials) {
                taken.steps <= TAPPED_INITIALS_STEPS && taken.scored <= TAPPED_INITIALS_SCORED && taken.arcs <= TAPPED_INITIALS_ARCS
            } else {
                taken.steps <= TAPPED_WHOLE_STEPS && taken.scored <= TAPPED_WHOLE_SCORED && taken.arcs <= TAPPED_WHOLE_ARCS
            }
            if (!within) over += "${if (initials) "initials" else "whole syllables"} $taken"
        }
        assertEquals(emptyList<String>(), over)
    }

    // the 18, 14 and nine keys read a key alone too, for typed words only
    @Test
    fun aKeyOnTheGroupedKeyboardsTakesNoMoreThanItDid() {
        // each keyboard's, asserted once: the first over its cap hid the rest
        val over = ArrayList<String>()
        for ((letters, caps) in GROUPED_CAPS) {
            for ((initials, cap) in listOf(false to caps.first, true to caps.second)) {
                val taken = type(inputs(KEYS, LINES, initials).map { line -> line.map(letters::key).joinToString("") }, user = typed(HISTORY), letters = letters)
                println("双拼 $letters ${if (initials) "initials" else "whole syllables"}, $HISTORY words typed, $taken")
                if (taken.arcs > cap.first || taken.steps > cap.second) over += "$letters $initials $taken"
            }
        }
        assertEquals(emptyList<String>(), over)
    }

    // what marking every typed word's nodes anew takes, said, not asserted: a wall-clock comparison a loaded machine could turn over
    @Test
    fun markingTheTypedWordsNodesAnewIsTimed() {
        val data = UatEngines.pinyinData
        val root = data.dictionary.root
        val word = UserModel.Entry("什么时候", "shen me shi hou".split(' ').map(Syllables::id).toIntArray())
        for (words in listOf(HISTORY, MANY)) {
            val user = typed(words)
            user.typedBelow(root)
            var anew = Long.MAX_VALUE
            var added = Long.MAX_VALUE
            repeat(ROUNDS) {
                user.listByHand(word)
                var t = System.nanoTime()
                user.typedBelow(root)
                added = minOf(added, System.nanoTime() - t)
                user.unlistByHand(word)
                t = System.nanoTime()
                user.typedBelow(root)
                anew = minOf(anew, System.nanoTime() - t)
            }
            println("双拼 typed nodes, $words words typed: marked anew in %.2f ms, a word added in %.3f ms".format(anew / 1e6, added / 1e6))
        }
    }

    @Test
    fun aKeyWithNothingTypedTakesLittleMoreThanWithoutTheKeysReadAlone() {
        val user = UatEngines.pinyinData.let { UserModel(it.dictionary, it.vocabulary, index = it.wordIndex) }
        val taken = type(inputs(KEYS, LINES, initials = false), user = user)
        println("双拼 whole syllables, nothing typed, $taken")
        assertTrue("$taken", taken.steps <= NONE_STEPS && taken.scored <= NONE_SCORED && taken.arcs <= NONE_ARCS && taken.first <= NONE_FIRST)
    }

    @Test
    fun withoutTheKeysReadAloneAKeyTakesAFractionOfIt() {
        // what 简拼 in the middle costs, said: the reading before it, kept for measuring against
        val taken = type(inputs(KEYS, LINES, initials = false), abbreviations = false)
        println("双拼 whole syllables, no key read alone, $taken")
        assertTrue("$taken", taken.steps <= OFF_STEPS && taken.scored <= OFF_SCORED && taken.arcs <= OFF_ARCS && taken.first <= OFF_FIRST)
    }

    private companion object {
        const val KEYS = 40
        const val LINES = 8
        const val HISTORY = 3000
        const val MANY = 12000
        const val ROUNDS = 20

        // tapped in a place: a tenth over what each was when measured
        const val TAPPED_WHOLE_ARCS = 2790.0
        const val TAPPED_WHOLE_STEPS = 107.0
        const val TAPPED_WHOLE_SCORED = 4170.0
        const val TAPPED_INITIALS_ARCS = 4430.0
        const val TAPPED_INITIALS_STEPS = 173.0
        const val TAPPED_INITIALS_SCORED = 7680.0
        const val NONE_ARCS = 2030.0
        const val NONE_STEPS = 72.0
        const val NONE_SCORED = 3600.0
        const val NONE_FIRST = 68.0

        // a quarter over what each was when measured (specs/engine.md, Performance, has the numbers)
        const val WHOLE_ARCS = 2930.0
        const val WHOLE_STEPS = 115.0
        const val WHOLE_SCORED = 4550.0
        const val INITIALS_ARCS = 4690.0
        const val INITIALS_STEPS = 185.0
        const val INITIALS_SCORED = 8440.0
        const val OFF_ARCS = 680.0
        const val OFF_STEPS = 22.0
        const val OFF_SCORED = 3600.0
        const val WHOLE_FIRST = 68.0
        const val INITIALS_FIRST = 79.0
        const val OFF_FIRST = 68.0

        // a quarter over what each was when measured (arcs, steps), whole syllables and by initials
        val GROUPED_CAPS = mapOf(
            NineKeyLetters.G18 to ((3920.0 to 174.0) to (4830.0 to 216.0)),
            NineKeyLetters.G14 to ((5430.0 to 269.0) to (5830.0 to 276.0)),
            NineKeyLetters.NINE_SP to ((10530.0 to 619.0) to (10210.0 to 641.0)),
        )

        val extensions: Array<IntArray?> by lazy { arrayOfNulls<IntArray>(UatEngines.pinyinData.dictionary.nodeCount) }
    }
}
