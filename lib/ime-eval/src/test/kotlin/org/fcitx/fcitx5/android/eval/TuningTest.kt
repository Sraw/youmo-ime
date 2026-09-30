/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.lattice.Penalties
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

class TuningTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bytes = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("中国", -3f, 0f)
        .unigram("宗", -2f, 0f)
        .entry("中国", intArrayOf(Syllables.id("zhong"), Syllables.id("guo")))
        .entry("宗", intArrayOf(Syllables.id("zong")))
        .build().toByteArray()

    private val clean = Sample("zhongguo", "中国", "daily")
    private val slips = listOf(Sample("zongguo", "中国", "fuzzy-z_zh"), Sample("zhognguo", "中国", "typo-gn"))

    @Test
    fun eachRunCountsTheSamplesReadRight() {
        val tuning = Tuning(PinyinData.load(ByteBuffer.wrap(bytes)), listOf(clean), slips)
        val half = Halves.of(clean)
        // zong alone is 宗 then guo as typed; with the pair on, 中国 at a fuzzy penalty wins
        val point = tuning.measure(Penalties(), half)
        assertEquals(point.toString(), listOf(1.0, 1.0, 1.0, 1.0), point.rates)
        // a penalty past what 宗 plus guo-as-typed costs gives the fuzzy sample away
        assertEquals(0, tuning.measure(Penalties(fuzzy = -20f), half).hits[2])
        val grid = tuning.search(listOf(-1f, -20f), half)
        assertEquals(4, grid.size)
        assertEquals(-1f, grid.first().penalties.fuzzy)
        assertEquals(-20f, grid.last().penalties.fuzzy)
        // the other half has none of these: no rates, and no mean to speak of
        val other = tuning.measure(Penalties(), Halves.NAMES.first { it != half })
        assertEquals(listOf<Double?>(null, null, null, null), other.rates)
        assertNull(other.mean)
    }

    @Test
    fun theDefaultStandsUnlessBeatenByTheMargin() {
        val default = Tuning.Point(Penalties(), listOf(100, 100), listOf(200, 200))
        val better = Tuning.Point(Penalties(fuzzy = -2f), listOf(102, 102), listOf(200, 200))
        val barely = Tuning.Point(Penalties(fuzzy = -3f), listOf(101, 100), listOf(200, 200))
        val tuning = Tuning(PinyinData.load(ByteBuffer.wrap(bytes)), emptyList(), emptyList())
        assertEquals(better, tuning.choose(listOf(better, barely), default))
        assertEquals(default, tuning.choose(listOf(barely), default))
        assertEquals(default, tuning.choose(emptyList(), default))
    }

    @Test
    fun theCommandPrintsTheGridAndTheHeldOutHalf() {
        val data = tmp.newFile("pinyin.data").apply { writeBytes(bytes) }
        val set = tmp.newFile("set.tsv").apply { writeText("${clean.input}\t${clean.expected}\t${clean.tag}\n") }
        val slipSet = tmp.newFile("slips.tsv").apply { writeText(slips.joinToString("") { "${it.input}\t${it.expected}\t${it.tag}\n" }) }
        val out = StringBuilder()
        assertEquals(0, runCli(arrayOf("tune", data.path, set.path, slipSet.path), out, StringBuilder()))
        val lines = out.lines().filter { it.isNotBlank() }
        assertEquals(1 + Tuning.GRID.size * Tuning.GRID.size + 3, lines.size)
        assertTrue(lines[0], lines[0].startsWith("fuzzy typo (tune)"))
        val heldOut = lines[1 + Tuning.GRID.size * Tuning.GRID.size]
        // 中国 falls in the held-out half: the sizes say so
        assertTrue(heldOut, heldOut.startsWith("held-out") && "clean/1" in heldOut && "clean/0" in lines[0])
        // 中国 is in one half only: the other shows no rates rather than NaN
        assertTrue(lines.none { "NaN" in it })
    }
}
