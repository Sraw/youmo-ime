/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.eval.TouchSim
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Each key tapped in its very middle, on the app's data: the first candidate is what it is with the
 * slips not read (061-neighbour-correction §3, PY-138 / SP-41). A key's touch is its centre: the
 * board's `touch(c, x, y)` takes the tap's place on the board, so the place is set after it (a tap
 * at the board's origin is keys away, every key around it as likely as the one hit).
 */
class NeighbourCentreTapTest {

    private val data = UatEngines.pinyinData

    private fun lines(file: String, n: Int) = File(file).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split('\t')[0] }.filter { it.length in 8..30 && it.all { c -> c in 'a'..'z' } }.take(n)

    private fun first(segmenter: Segmenter, board: String?, keys: String, spell: Boolean): String {
        val boxes = board?.let { TouchSim.board(it).boxes }
        val s = PinyinSession(data, segmenter, spell = spell, prediction = false)
        return keys.map { c -> s.apply(Action.Key(c, touch = boxes?.touch(c, 0f, 0f)?.copy(dx = 0f, dy = 0f))) }.last().candidates.first()
    }

    private fun same(what: String, on: Segmenter, off: Segmenter, board: String, inputs: List<String>, spell: Boolean = false) {
        for (keys in inputs) assertEquals("$what $keys", first(off, null, keys, spell), first(on, board, keys, spell))
    }

    @Test
    fun fullPinyinTappedInTheMiddleReadsAsWithTheSlipsOff() =
        same("全拼", PinyinSegmenter(neighbours = true), PinyinSegmenter(), "qwerty", listOf("nikanwanle") + lines("data/pinyin-chat.tsv", 12))

    @Test
    fun shuangpinTappedInTheMiddleReadsAsWithTheSlipsOff() {
        // 自然码 nikwl: n's upper neighbour j reads ji kua le (击垮了) only from a tap keys above n
        same("自然码", ShuangpinSegmenter(ShuangpinScheme.ZIRANMA, neighbours = true), ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), "qwerty", listOf("nikwl", "nikjwjle"))
        same("小鹤", ShuangpinSegmenter(ShuangpinScheme.XIAOHE, neighbours = true), ShuangpinSegmenter(ShuangpinScheme.XIAOHE), "qwerty", lines("data/shuangpin-xiaohe.tsv", 12))
    }

    @Test
    fun theNineKeysTappedInTheMiddleReadAsWithTheSlipsOff() {
        val digits = (listOf("nikanwanle") + lines("data/pinyin-chat.tsv", 12)).map { line -> line.map { NineKeyLetters.STANDARD.key(it) }.joinToString("") }
        same("九键", T9Segmenter(abbreviations = false, neighbours = true), T9Segmenter(abbreviations = false), "nine", digits, spell = true)
    }
}
