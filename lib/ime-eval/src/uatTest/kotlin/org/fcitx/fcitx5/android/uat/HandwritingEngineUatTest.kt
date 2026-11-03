/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.reading.CandidateReadings
import org.fcitx.fcitx5.android.engine.reading.CharCard
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.stroke.StrokeShape
import org.fcitx.fcitx5.android.input.keyboard.PendingInputPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * uat/09-handwriting.md, what the engine makes of what was written: the sentence read with the
 * app's model, space, picks, ⌫, punctuation, 联想 and learning. A character's readings are given
 * as the recognizer hands them (its own reading of strokes is its tests' and the device's HW-03).
 */
class HandwritingEngineUatTest {

    private val da = arrayOf("大", "太", "犬")
    private val ren = arrayOf("人", "入", "八")
    private val wo = arrayOf("我", "找", "伐")

    private fun replay() = EngineReplay(im = InputMethodCatalog.HANDWRITING)

    @Test
    @Uat("HW-03")
    fun spaceCommitsTheSentenceWritten() = with(HostReplay(im = InputMethodCatalog.HANDWRITING)) {
        write(*da)
        write(*ren)
        space()
        expectText("大人")
        assertEquals(-1 to -1, editor.composingStart to editor.composingEnd)
    }

    // chttrans re-sends the composing text (chttrans.cpp:214-215): what is written and not yet in
    // shows traditional at the toggle, with nothing more written; it goes in as shown
    @Test
    @Uat("HW-26")
    fun aCharacterNotYetInIsShownTraditionalAtTheToggle() = with(HostReplay(im = InputMethodCatalog.HANDWRITING)) {
        write("国", "图", "固")
        assertEquals("国", clientPreedit)
        toggle("chttrans")
        assertEquals("國", clientPreedit)
        space()
        assertEquals("國", text)
    }

    @Test
    @Uat("HW-04")
    fun aCharacterWrittenAfterCorrectsTheOneBefore() = with(replay()) {
        // left side between 讠 and 氵: read 淡 first, 谈 second
        write("淡", "谈", "炎")
        expectPreedit("淡")
        assertEquals("谈", candidates[1])
        write("话", "活", "诂")
        expectPreedit("谈话")
    }

    @Test
    @Uat("HW-05")
    fun aCandidatePickedPutsInTheSentenceEndingWithIt() = with(replay()) {
        write(*da)
        write(*ren)
        val c = candidates[1]
        pickAt(1)
        assertTrue("$text, $c", text.length == 2 && text.endsWith(c))
        expectPreedit("")
    }

    @Test
    @Uat("HW-07")
    fun backspaceTakesTheUnwrittenCharacterThenTheText() = with(replay()) {
        focus("好")
        write(*ren)
        backspace()
        expectText("好")
        expectPreedit("")
        backspace()
        expectText("")
    }

    @Test
    @Uat("HW-08")
    fun punctuationPutsTheSentenceInFirst() = with(replay()) {
        write(*ren)
        // the pad's 。 is a commit over the engine (commitOverEngine): what is written first, as its policy says.
        // The policy is applied by hand here; the service's wiring of it is CommonKeyActionListenerTest's
        // (app tests), the policy's own cases PendingInputPolicyTest's: this holds the engine's half
        assertEquals(PendingInputPolicy.Resolution.SelectFirstCandidate, PendingInputPolicy.resolve("zh_CN", hasPreedit = preedit.isNotEmpty()))
        pickAt(0)
        commit("。")
        expectText("人。")
    }

    @Test
    @Uat("HW-10")
    fun aSpaceThenPredictions() = with(replay()) {
        write(*wo)
        space()
        expectText("我")
        val next = page
        assertTrue("predictions $next", next.isNotEmpty())
        pickAt(0)
        expectText("我" + next.first())
    }

    @Test
    @Uat("HW-11")
    fun returnPutsInWithoutPredictions() = with(replay()) {
        write(*wo)
        enter()
        assertTrue(text, text.startsWith("我"))
        assertTrue("predictions $page", page.isEmpty())
    }

    @Test
    @Uat("HW-14")
    fun aCharacterPickedRanksUpTwiceFirst() = with(replay()) {
        write(*ren)
        assertEquals(listOf("人", "入", "八"), candidates.take(3))
        pick("入")
        focus("")
        write(*ren)
        assertEquals(listOf("人", "入"), candidates.take(2))
        pick("入")
        focus("")
        write(*ren)
        assertEquals("入", candidates.first())
    }

    @Test
    @Uat("HW-23")
    fun aCharacterDeletedRightAwayIsNotUsed() = with(replay()) {
        write(*ren)
        val order = candidates.take(3)
        pickAt(2)
        backspace()
        expectText("")
        // written again right away, as the device's step: no new focus
        write(*ren)
        assertEquals(order, candidates.take(3))
    }

    // a character's card with the app's data, made once a test
    private val cards = HashMap<List<String>, CharCard>()

    private fun card(vararg written: String): CharCard = cards.getOrPut(written.toList()) { cardOf(*written) }

    private fun cardOf(vararg written: String): CharCard = with(HostReplay(im = InputMethodCatalog.HANDWRITING)) {
        write(*written)
        val i = candidates.indexOf(written[0])
        check(i >= 0) { "${written[0]} not among $candidates" }
        checkNotNull(engines.charCard(InputMethodCatalog.HANDWRITING, i, written[0]))
    }

    // a reading's words a dictionary's, not the model's others: 荣和, 去呢, 弦数 are two words run together (the user, 2026-11-02)
    @Test
    @Uat("HW-29")
    fun aCardsWordsAreADictionarys() {
        val he = card("和", "口")
        assertEquals(listOf("hé", "hè", "huò", "hú", "huó", "huo"), he.readings.map { it.pinyin })
        assertEquals(listOf("和谐", "和平"), he.readings[0].examples)
        // 和牌 is no dictionary's: hú has none
        assertEquals(emptyList<String>(), he.readings[3].examples)
        assertEquals(listOf("掺和", "搅和"), he.readings[5].examples)
        assertEquals(listOf("呢喃", "毛呢"), card("呢", "口").readings.single { it.pinyin == "ní" }.examples)
        assertEquals(emptyList<String>(), card("呢", "口").readings.single { it.pinyin == "ne" }.examples)
        val shu = card("数", "口").readings.associate { it.pinyin to it.examples }
        assertEquals(listOf("倒数", "数不胜数"), shu["shǔ"])
        val words = listOf(he, card("呢", "口"), card("数", "口")).flatMap { c -> c.readings.flatMap { it.examples } }
        assertTrue(words.toString(), words.none { it in setOf("荣和", "去呢", "弦数") })
    }

    // the mainland's stroke order (the user, 2026-11-02), where rime-stroke's first is not: 行 ppshhz,
    // 出 szszs, 必 nznnp, 我 phzhznp; each stroke by its shape (the user, 2026-11-02): 行's last a 竖钩
    @Test
    @Uat("HW-29")
    fun aCardsStrokeOrderIsTheMainlands() {
        assertEquals("丿丿丨一一亅", card("行", "口").orderShown())
        assertEquals("㇗丨丨㇗丨", card("出", "口").orderShown())
        assertEquals("丶㇃丶丿丶", card("必", "口").orderShown())
        assertEquals("丿一亅㇀㇂丿丶", card("我", "口").orderShown())
        assertEquals("丶丿一丨丿丶㇛丿一丿一丿㇏", card("数", "口").orderShown())
        // the count the order's: Unihan's 范 9 and 骨 10 are Taiwan's (a 4-stroke 艹; 骨's 冖)
        assertEquals("一丨丨丶丶㇀㇆乚" to 8, card("范", "口").let { it.orderShown() to it.strokes })
        assertEquals("丨㇕㇕丶㇖丨㇆一一" to 9, card("骨", "口").let { it.orderShown() to it.strokes })
        // 皙 with 白's 丿, as corrected in lexicon/stroke-order.tsv (cnchar's left it out); for 國, which it has not, the stroke table's of Unihan's count, 11
        assertEquals("一丨丿丶丿丿一丨丿丨㇕一一" to 13, card("皙", "口").let { it.orderShown() to it.strokes })
        assertEquals("丨乛一丨乛一一乛丶丿一", card("國", "口").orderShown())
    }

    // the turning strokes each their own shape (《GB13000.1字符集汉字折笔规范》), not one 折: cnchar writes
    // 九 乙's 横折弯钩 as 风's 横斜钩, and 横撇 as 横钩, 竖折 as 竖弯, told apart by the part they are in
    @Test
    @Uat("HW-29")
    fun aCardsTurningStrokesEachTheirShape() {
        val shown = mapOf(
            "口" to "丨㇕一", "己" to "㇕一乚", "乙" to "㇈", "九" to "丿㇈", "马" to "㇕㇉一",
            "风" to "丿⺄丿丶", "又" to "㇇㇏", "写" to "丶㇖一㇉一", "冖" to "丶㇖", "欠" to "丿㇖丿㇏", "山" to "丨㇗丨", "四" to "丨㇕丿㇄一",
            "心" to "丶㇃丶丶", "那" to "㇆一一丿㇌丨", "乃" to "㇡丿", "专" to "一一ㄣ丶", "凹" to "丨㇅丨㇕一", "朵" to "丿㇍一丨丿㇏",
        )
        assertEquals(shown, shown.mapValues { (c, _) -> card(c, "口").orderShown() })
    }

    // every example character of the standard's table of turning strokes (GF 2001-2001, 《GB13000.1字符集
    // 汉字折笔规范》, its 25 shapes: http://www.moe.gov.cn/ewebeditor/uploadfile/2015/01/12/20150112170016626.pdf)
    // has its shape: a rule wrong for a whole part shows here. Not here: 為 夗 朮 卍 卐 亞 吳 奊 夨 (none of the
    // mainland's simplified ones: rime-stroke's five kinds), 撇钩's 乄. The card writes 楷体 as taught; the
    // standard analyses 宋体, and where 宋体 alone has a shape, it is not followed: those, each with its stroke
    @Test
    @Uat("HW-29")
    fun theStandardsExamplesEachTheirTurningStroke() {
        val examples = mapOf(
            "HZ" to "口见舆己罗马丑贯敢", "HP" to "又祭之社登令了", "HG" to "买宝皮饭", "SZ" to "山世岵母互乐发牙降",
            "SW" to "四西", "ST" to "长瓜鼠以瓦叫收", "PZ" to "公离云红乡亥车东", "PD" to "女巡", "WG" to "犹家", "XG" to "代戈",
            "HZZ" to "凹", "HZW" to "朵", "HZT" to "计颓鸠", "HZG" to "同门却永万母仓也", "HXG" to "飞风", "SZZ" to "鼎",
            "SZP" to "专", "SWG" to "己匕电", "HZZZ" to "凸", "HZZP" to "及延", "HZWG" to "几丸艺亿", "HPWG" to "阳部",
            "SZWG" to "马与钙号弓", "HZZZG" to "乃杨",
        )
        val missing = examples.flatMap { (code, chars) ->
            val shape = StrokeShape.of(code)!!
            chars.map { it.toString() }.filter { c -> card(c, "口").strokeOrder?.contains(shape) != true }.map { "$it $code" }
        }
        assertEquals(emptyList<String>(), missing)
        // where the standard's example is not the letter's shape, every stroke: the one at its place
        val corrected = mapOf(
            "敢" to "㇕一丨丨一一㇀丿一丿㇏", "互" to "一㇗㇇一", "彖" to "㇗㇇一丿㇁丿丿丿㇏", "牙" to "一㇗亅丿",
            "发" to "㇗丿㇇㇏丶", "降" to "㇌丨丿㇇㇏一㇗丨", "鸠" to "丿㇊丿㇆丶㇉一",
        )
        assertEquals(corrected, corrected.mapValues { (c, _) -> card(c, "口").orderShown() })
        val notFollowed = mapOf(
            "心" to (1 to StrokeShape.BXG), // 5.19 竖弯钩: 楷体's 卧钩, the standard's 26th shape, 楷体 only
            "达" to (4 to StrokeShape.HZZP), // 5.1 横折: 辶's 横折折撇 as taught (点 横折折撇 捺)
            "要" to (2 to StrokeShape.HZ), // 5.15 横折钩: 覀's 横折 as taught, no hook, as 西 票's
            "执" to (4 to StrokeShape.HZWG), // 5.16 横斜钩: 丸's 横折弯钩 as taught, as 丸 alone (5.22)
        )
        assertEquals(
            notFollowed.mapValues { (_, it) -> it.second },
            notFollowed.mapValues { (c, it) -> card(c, "口").strokeOrder!![it.first] },
        )
    }

    // a character's own neutral tone (头 tou, 和 huo) the card's only: never on the candidate (the user, 2026-11-02)
    @Test
    @Uat("HW-27")
    fun theCandidatesLeaveOutACharactersOwnNeutralTone() {
        val data = PinyinData.load(UatEngines.data("engine/pinyin.data"))
        val readings = CandidateReadings(data, data.tones) { Predictor.charReadingsOf(data.dictionary, data.vocabulary) }
        assertEquals(listOf("tóu"), readings.ordered('头'.code).map { it.pinyin })
        val he = readings.ordered('和'.code).map { it.pinyin }
        assertTrue(he.toString(), he.first() == "hé" && "huo" !in he && he.size >= 2)
        assertEquals(listOf("tóu"), readings.allOf("头", ReadingStyle.PINYIN))
        assertEquals(listOf("hé/hè…"), readings.allOf("和", ReadingStyle.PINYIN))
        assertEquals(listOf("tóu"), readings.of("头", ReadingStyle.PINYIN))
        assertEquals(listOf("hé"), readings.of("和", ReadingStyle.PINYIN))
        assertEquals(listOf("ㄊㄡˊ"), readings.allOf("头", ReadingStyle.ZHUYIN))
        // the card has them, last
        assertEquals("huo", card("和", "口").readings.last().pinyin)
        assertEquals(listOf("tóu", "tou"), card("头", "口").readings.map { it.pinyin })
        // and so through the engine: handwriting's 头 one reading
        with(HostReplay(im = InputMethodCatalog.HANDWRITING)) {
            engines.settings = engines.settings.copy(candidateReading = ReadingStyle.PINYIN)
            write("头", "口")
            assertEquals(listOf("tóu"), engines.candidates(InputMethodCatalog.HANDWRITING, 0, 1).single().readings)
        }
    }
}
