/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.data.pinyinDataOf
import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.KeyedSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.LatinWords
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.junit.Assert.assertEquals
import org.junit.Test

/** A session's decode going on from its last one (LatticeBuilder collectOn) reads as a fresh session typing the same keys. */
class PinyinSessionGoingOnTest {

    private val data = pinyinDataOf(
        Triple("你", "ni", -2f),
        Triple("米", "mi", -3f),
        Triple("好", "hao", -2.5f),
        Triple("高", "gao", -3f),
        Triple("你好", "ni hao", -2.5f),
        Triple("米糕", "mi gao", -5f),
        Triple("我", "wo", -2f),
        Triple("中国", "zhong guo", -3f),
        Triple("知道", "zhi dao", -3f),
        Triple("小", "xiao", -2.5f),
        Triple("iPhone", "I P H O N E", -5f),
    )

    private val latin = LatinWords.of(data.dictionary)

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    private fun shown(s: Snapshot) = "${s.preedit} ${s.candidates.take(5)}"

    // a syllable taken off the bar puts its letters (and a separator) for its keys, a backspace gives them back:
    // the input longer, then shorter, by several characters at once
    @Test
    fun aSyllableTakenAndGivenBackReadsAsTheKeysTypedAfresh() {
        val keyboards = listOf<Triple<String, () -> KeyedSegmenter, String>>(
            Triple("nine keys", { T9Segmenter(abbreviations = false, latin = latin) }, "96 64426 4"),
            Triple("nine keys, an initial", { T9Segmenter(abbreviations = false, latin = latin) }, "9 4664486 3"),
            Triple("14 keys", { T9Segmenter(abbreviations = false, letters = NineKeyLetters.G14, latin = latin) }, "wo nihao h"),
            Triple("双拼 on nine keys", { GroupedShuangpinSegmenter(ShuangpinScheme.ZIRANMA, NineKeyLetters.NINE_SP) }, "wo nihk x"),
            Triple("双拼 on 18 keys", { GroupedShuangpinSegmenter(ShuangpinScheme.ZIRANMA, NineKeyLetters.G18) }, "wo nihk x"),
        )
        assertRows(keyboards, { it.first }) { (_, segmenter, script) ->
            val (before, word, after) = script.split(' ')
            val letters = segmenter().letters
            fun keys(s: String) = if (letters.isCode(s[0])) s else s.map(letters::key).joinToString("")
            fun fresh(typed: String) = shown(PinyinSession(data, segmenter(), spell = true, prediction = false).type(typed))
            val session = PinyinSession(data, segmenter(), spell = true, prediction = false)
            session.type(keys(before) + keys(word))
            var typed = keys(before) + keys(word)
            // each syllable the bar offers, taken and given back, then a key more
            for (i in 0 until 3) {
                val offered = session.apply(Backspace).let { session.type(typed.takeLast(1)) }.syllables
                if (i >= offered.size) break
                session.apply(Action.Syllable(i))
                assertEquals("given back after ${offered[i]}", fresh(typed), shown(session.apply(Backspace)))
                typed += keys(after)
                assertEquals("a key after ${offered[i]}", fresh(typed), shown(session.type(keys(after))))
            }
        }
    }
}
