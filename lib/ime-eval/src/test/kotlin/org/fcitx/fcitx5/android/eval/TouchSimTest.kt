/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

/** Taps simulated with a place (`pinyin --touch`, [TouchSim]; 061-neighbour-correction §5). */
class TouchSimTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val samples = listOf(Sample("nihao", "你好", "t"), Sample("tama", "他吗", "t"), Sample("yama", "呀吗", "t"), Sample("nikanwanle", "你看完了", "t"))

    @Test
    fun cleanTapsKeepTheInputEachInItsOwnKey() {
        val clean = TouchSim.convert(samples, "qwerty:s0.25:clean")
        assertEquals(samples.map { it.input }, clean.map { it.input })
        for (s in clean) {
            assertEquals(s.input.length, s.touches!!.size)
            assertTrue(s.touches!!.all { t -> t != null && abs(t.dx) < 0.5f && abs(t.dy) < 0.5f })
        }
        assertEquals(setOf('b', 'm', 'j'), clean[0].touches!![0]!!.near.map { it.char }.toSet())
    }

    @Test
    fun aSlipLandsOneKeyOnAKeyBesideIt() {
        val slipped = TouchSim.convert(samples, "qwerty:s0.25:slip")
        for ((s, t) in samples.zip(slipped)) {
            val at = s.input.indices.single { s.input[it] != t.input[it] }
            assertTrue(t.input, TouchSim.board("qwerty").boxes.around(s.input[at]).any { it.char == t.input[at] })
            assertEquals("slip", t.tag)
        }
        assertEquals(slipped, TouchSim.convert(samples, "qwerty:s0.25:slip"))
        assertNotEquals(slipped.map { it.touches }, TouchSim.convert(samples, "qwerty:s0.25:slip:2").map { it.touches })
    }

    // the 18 keys' taps with where across the key they fell, as the app sends them (061-neighbour-18key): every
    // key its dx from the left edge, to the nearest of the app's 17 places (a key of one letter's too, for a slip
    // from it); another keyboard's none at all
    @Test
    fun theEighteenKeysTapsGoWithTheirPlace() {
        val g18 = listOf(Sample("nihao".map(NineKeyLetters.G18::key).joinToString(""), "你好", "t"))
        val tapped = TouchSim.convert(g18, "k18:s0.2:clean")
        val placed = TouchSim.placed(tapped, NineKeyLetters.G18).single()
        val taps = placed.taps!!
        assertEquals(5, taps.size)
        // dx + 0.5, to a sixteenth
        for (i in 0 until 5) assertEquals(Math.round((placed.touches!![i]!!.dx + 0.5f) * 16) / 16f, taps[i], 1e-6f)
        assertEquals(tapped, TouchSim.placed(tapped, NineKeyLetters.STANDARD))
    }

    // the set as tapped, for the device's runner: read back the same
    @Test
    fun aSetTappedIsWrittenAndReadBack() {
        val tapped = TouchSim.convert(samples, "qwerty-ms:s0.3:slip") + Sample("a1", "啊1", "t", touches = listOf(TouchSim.board("qwerty").boxes.touch('a', 20f, 80f), null))
        assertEquals(tapped, EvalSet.parse(tapped.asSequence().map(EvalSet::line)))
    }

    // the 18 keys' places too, for the device's runner to send each key as the app does (061-neighbour-18key);
    // a set of places alone (--taps) its touches all none
    @Test
    fun aSetTappedWithPlacesIsWrittenAndReadBack() {
        val g18 = listOf(Sample("nihao".map(NineKeyLetters.G18::key).joinToString(""), "你好", "t"))
        val placed = TouchSim.placed(TouchSim.convert(g18, "k18:s0.2:clean"), NineKeyLetters.G18)
        assertEquals(placed, EvalSet.parse(placed.asSequence().map(EvalSet::line)))
        val taps = listOf(placed.single().copy(touches = null))
        assertEquals(taps, EvalSet.parse(taps.asSequence().map(EvalSet::line)))
    }

    @Test
    fun theHandsAndBoardsAreThoseNamed() {
        assertEquals(TouchSim.Hand(0.2f, dy = 0.15f), TouchSim.hand("s0.2down"))
        assertEquals(TouchSim.Hand(0.3f, 0.1f, 0f, 0.05f), TouchSim.hand("s0.3/0.1/0/0.05"))
        assertEquals(8, TouchSim.board("nine").keys.size)
        assertEquals(14, TouchSim.board("k14").keys.size)
        // the 18 keys: a row's keys as wide as each other, the rows not (a seventh, a sixth, 0.14), each row centred
        val k18 = TouchSim.board("k18").keys
        assertEquals(18, k18.size)
        val q = k18.getValue(NineKeyLetters.G18.key('q'))
        val we = k18.getValue(NineKeyLetters.G18.key('w'))
        val a = k18.getValue(NineKeyLetters.G18.key('a'))
        val m = k18.getValue(NineKeyLetters.G18.key('m'))
        assertEquals(q.w, we.w, 1e-3f)
        assertEquals(TouchSim.KEY_W * 10 / 7, q.w, 1e-3f)
        assertEquals(TouchSim.KEY_W * 10 / 6, a.w, 1e-3f)
        assertEquals(TouchSim.KEY_W * 10, m.right + (m.right - 5 * m.w), 1e-3f)
        assertEquals(";", TouchSim.board("qwerty-ms").keys.keys.filter { it == ';' }.joinToString(""))
    }

    // the gate (§7.2): keys tapped each in its own box read first what they read with no place
    @Test
    fun cleanTapsReadFirstWhatNoPlaceReads() {
        val data = tmp.newFile("pinyin.data").apply {
            writeBytes(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f)
                    .unigram("你好", -3f, 0f).unigram("他", -2.5f, 0f).unigram("呀", -3f, 0f).unigram("吗", -2.5f, 0f)
                    .unigram("你", -2f, 0f).unigram("看完", -3.5f, 0f).unigram("了", -2f, 0f)
                    .entry("你好", syl("ni", "hao")).entry("他", syl("ta")).entry("呀", syl("ya")).entry("吗", syl("ma"))
                    .entry("你", syl("ni")).entry("看完", syl("kan", "wan")).entry("了", syl("le"))
                    .build().toByteArray(),
            )
        }
        val set = tmp.newFile("set.tsv").apply { writeText(samples.joinToString("") { "${it.input}\t${it.expected}\t${it.tag}\n" } + "nkwl\t你看完了\tabbrev\n") }
        fun firsts(vararg more: String): List<String> {
            val result = tmp.newFile()
            assertEquals(0, runCli(arrayOf("pinyin", data.path, set.path, result.path, "--threads", "1", *more), StringBuilder(), StringBuilder()))
            return result.useLines { RunResultFormat.parse(it) }.map { it.candidates.first() }
        }
        val plain = firsts()
        for (seed in 1..3) assertEquals(plain, firsts("--neighbours", "on", "--touch", "qwerty:s0.25:clean:$seed"))
        // 双拼 and 九键 the same, their sets typed in their keys, initials after a whole first syllable too
        fun typedIn(vararg lines: String) = tmp.newFile().apply { writeText(lines.joinToString("") { "$it\tt\n" }) }
        fun firstsOf(set: File, scheme: String, vararg more: String): List<String> {
            val result = tmp.newFile()
            assertEquals(0, runCli(arrayOf("pinyin", data.path, set.path, result.path, "--threads", "1", "--scheme", scheme, *more), StringBuilder(), StringBuilder()))
            return result.useLines { RunResultFormat.parse(it) }.map { it.candidates.first() }
        }
        val xiaohe = typedIn("nihc\t你好", "tama\t他吗", "yama\t呀吗", "nikjwjle\t你看完了", "nikjwl\t你看完了")
        val nine = typedIn("64426\t你好", "8262\t他吗", "9262\t呀吗", "6452692653\t你看完了")
        for ((set, scheme, board) in listOf(Triple(xiaohe, "xiaohe", "qwerty"), Triple(nine, "t9", "nine"))) {
            val none = firstsOf(set, scheme)
            for (seed in 1..3) assertEquals(scheme, none, firstsOf(set, scheme, "--neighbours", "on", "--touch", "$board:s0.25:clean:$seed"))
        }
        // and keys landed beside the ones meant are read as them by where they fell: more read as meant than with no correction
        fun meant(neighbours: String) = firsts("--neighbours", neighbours, "--touch", "qwerty:s0.25:slip").zip(plain).count { (a, b) -> a == b }
        assertTrue(meant("on") > meant("off"))
    }

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()
}
