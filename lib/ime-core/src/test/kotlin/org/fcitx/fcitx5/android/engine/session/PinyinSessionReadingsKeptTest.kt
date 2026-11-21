/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.pinyinDataOf
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.KeyBoxes
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.TouchBoxes
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Reset
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.session.Action.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The code row's readings kept from key to key read as a session reading every word anew does, whatever is done. */
class PinyinSessionReadingsKeptTest {

    // polyphones, so a kept reading of the wrong keys shows another syllable even where the keys show the spelling
    private val data = pinyinDataOf(
        Triple("可以", "ke yi", -3f), Triple("行", "xing", -2f), Triple("行", "hang", -2.2f), Triple("银行", "yin hang", -3f),
        Triple("长", "chang", -2f), Triple("长", "zhang", -2.3f), Triple("行长", "hang zhang", -3.5f), Triple("长安", "chang an", -3.5f),
        Triple("下面", "xia mian", -3f), Triple("先", "xian", -2f), Triple("西安", "xi an", -3.5f), Triple("你好", "ni hao", -3f),
        Triple("你", "ni", -2f), Triple("好", "hao", -2f), Triple("什么", "shen me", -2f), Triple("时候", "shi hou", -2f),
        Triple("重", "zhong", -2f), Triple("重", "chong", -2.5f), Triple("中", "zhong", -2f), Triple("了", "le", -1.5f),
        Triple("了", "liao", -3f), Triple("和", "he", -2f), Triple("和", "huo", -3f), Triple("我", "wo", -2f),
        Triple("在", "zai", -2f), Triple("见", "jian", -2.5f), Triple("米", "mi", -3f), Triple("吗", "ma", -3f),
    )

    private val syllables = listOf("ke yi", "xing", "hang", "yin hang", "chang", "zhang", "hang zhang", "chang an", "xia mian", "xian", "xi an", "ni hao", "shen me", "shi hou", "zhong", "chong", "le", "liao", "he", "huo", "wo", "zai", "jian", "mi", "ma")

    // a polyphone's other reading, typed over it: the same word on the same places, read otherwise
    private val other = mapOf("xing" to "hang", "hang" to "xing", "chang" to "zhang", "zhang" to "chang", "zhong" to "chong", "chong" to "zhong", "le" to "liao", "he" to "huo")

    private val zhuyin = mapOf(
        "ke" to "ㄎㄜ", "yi" to "ㄧ", "xing" to "ㄒㄧㄥ", "hang" to "ㄏㄤ", "yin" to "ㄧㄣ", "chang" to "ㄔㄤ", "zhang" to "ㄓㄤ", "an" to "ㄢ",
        "xia" to "ㄒㄧㄚ", "mian" to "ㄇㄧㄢ", "xian" to "ㄒㄧㄢ", "xi" to "ㄒㄧ", "ni" to "ㄋㄧ", "hao" to "ㄏㄠ", "shen" to "ㄕㄣ", "me" to "ㄇㄜ",
        "shi" to "ㄕ", "hou" to "ㄏㄡ", "zhong" to "ㄓㄨㄥ", "chong" to "ㄔㄨㄥ", "le" to "ㄌㄜ", "liao" to "ㄌㄧㄠ", "he" to "ㄏㄜ", "huo" to "ㄏㄨㄛ",
        "wo" to "ㄨㄛ", "zai" to "ㄗㄞ", "jian" to "ㄐㄧㄢ", "mi" to "ㄇㄧ", "ma" to "ㄇㄚ",
    )

    // a keyboard: its segmenter, a syllable's keys, any key it has, where its keys are (to tap off centre), its tones
    private class Board(
        val name: String,
        val segmenter: Segmenter,
        val keysOf: (String) -> String,
        val keys: String,
        val boxes: KeyBoxes? = null,
        val keysShown: Boolean = false,
        val spell: Boolean = false,
        val kind: String = Kinds.FULL,
        val tones: String = "",
    )

    private fun grouped(name: String, letters: NineKeyLetters, boxes: KeyBoxes) = Board(
        name, GroupedShuangpinSegmenter(ShuangpinScheme.ZIRANMA, letters, neighbours = true),
        { s -> ShuangpinScheme.ZIRANMA.encode(Syllables.id(s))!!.map(letters::key).joinToString("") },
        letters.codes, boxes, keysShown = true, kind = Kinds.shuangpin("Ziranma"),
    )

    private val boards = listOf(
        grouped("18 keys", NineKeyLetters.G18, TouchBoxes.G18),
        grouped("14 keys", NineKeyLetters.G14, TouchBoxes.G14),
        grouped("nine keys 双拼", NineKeyLetters.NINE_SP, TouchBoxes.NINE_SP),
        Board("full pinyin", PinyinSegmenter(neighbours = true), { it }, ('a'..'z').joinToString(""), TouchBoxes.QWERTY),
        Board(
            "小鹤 full", ShuangpinSegmenter(ShuangpinScheme.XIAOHE, neighbours = true), { ShuangpinScheme.XIAOHE.encode(Syllables.id(it))!! },
            ('a'..'z').joinToString(""), TouchBoxes.QWERTY, spell = true, kind = Kinds.shuangpin("Xiaohe"),
        ),
        Board(
            "nine keys 全拼", T9Segmenter(neighbours = true), { s -> s.map(NineKeyLetters.STANDARD::key).joinToString("") },
            "23456789", TouchBoxes.NINE, spell = true,
        ),
        Board(
            "大千", T9Segmenter(abbreviations = false, letters = ZhuyinKeys.DACHEN), { s -> zhuyin.getValue(s).map(ZhuyinKeys::symbolCode).joinToString("") },
            ZhuyinKeys.SYMBOLS.map(ZhuyinKeys::symbolCode).joinToString(""), spell = true, kind = Kinds.ZHUYIN_DACHEN,
            tones = (1..ZhuyinKeys.TONES).map(ZhuyinKeys::toneCode).joinToString(""),
        ),
        Board(
            "注音九键", T9Segmenter(abbreviations = false, letters = ZhuyinKeys.NINE),
            { s -> zhuyin.getValue(s).map { c -> ZhuyinKeys.groupCode(ZhuyinKeys.GROUP_SYMBOLS.indexOfFirst { c in it }) }.joinToString("") },
            (0 until ZhuyinKeys.GROUPS).map(ZhuyinKeys::groupCode).joinToString(""), spell = true, kind = Kinds.ZHUYIN_NINE,
        ),
    )

    private fun session(b: Board, learner: Learner) = PinyinSession(
        data, b.segmenter, spell = b.spell, keysShown = b.keysShown, pageSize = 20, prediction = false,
        user = learner.model, learnTo = learner.learnTo(b.kind),
    )

    // words typed whole, by initials or a syllable's first key, with stray keys between; edits, cursor moves, picks
    private fun Random.action(b: Board, typing: ArrayDeque<Char>, shown: Snapshot): Action {
        val n = nextInt(100)
        return when {
            n < 10 -> Backspace
            n < 18 -> Step(-1 - nextInt(3))
            n < 21 -> Step(1 + nextInt(2))
            n < 29 && shown.candidates.isNotEmpty() -> Select(nextInt(minOf(6, shown.candidates.size)))
            n < 31 -> Reset
            else -> {
                if (typing.isEmpty()) {
                    val word = syllables[nextInt(syllables.size)].split(' ')
                    val alone = nextInt(4) == 0
                    word.forEach { s -> b.keysOf(s).let { typing.addAll((if (alone) it.take(1) else it).toList()) } }
                    word.singleOrNull()?.let(other::get)?.takeIf { !alone && nextInt(2) == 0 }?.let { o ->
                        repeat(typing.size) { typing.add(RUB_OUT) }
                        typing.addAll(b.keysOf(o).toList())
                    }
                    if (b.tones.isNotEmpty() && nextInt(3) == 0) typing.add(b.tones[nextInt(b.tones.length)])
                }
                val key = if (nextInt(6) == 0) b.keys[nextInt(b.keys.length)] else typing.removeFirst()
                if (key == RUB_OUT) return Backspace
                // a tap off centre, which may read the key as its neighbour, so a later key's place changes an earlier key's reading
                val touch = b.boxes?.takeIf { nextInt(3) == 0 }?.touch(key, 0f, 0f)?.copy(dx = nextFloat() - 0.5f, dy = nextFloat() - 0.5f)
                Key(key, at = if (nextBoolean()) nextFloat() else Float.NaN, touch = touch)
            }
        }
    }

    private fun Session.readings(shown: Snapshot) = (0 until minOf(6, shown.candidates.size)).map { i ->
        readingOf(i)?.joinToString(" ") { "${it.text}:${it.word}:${it.syllables.joinToString(",") { s -> Syllables.spelling(s) }}" }
    }

    @Test
    fun keptReadingsReadAsReadAnew() {
        for (b in boards) {
            for (seed in 1..5) {
                val kept = session(b, Learner(data))
                val anew = session(b, Learner(data)).also { it.keepsReadings = false }
                val random = Random(seed)
                val typing = ArrayDeque<Char>()
                val done = ArrayList<Action>()
                var shown = anew.apply(Reset)
                kept.apply(Reset)
                repeat(400) {
                    val action = random.action(b, typing, shown)
                    done += action
                    val where = "${b.name} seed $seed after ${done.takeLast(12)}"
                    shown = anew.apply(action)
                    assertEquals(where, shown, kept.apply(action))
                    assertEquals(where, anew.readings(shown), kept.readings(shown))
                }
                // the kept readings were used: fewer walked
                assertTrue("${b.name} seed $seed: ${kept.readingsWalked} of ${anew.readingsWalked}", kept.readingsWalked < anew.readingsWalked)
            }
        }
    }
}

private const val RUB_OUT = '\b'
