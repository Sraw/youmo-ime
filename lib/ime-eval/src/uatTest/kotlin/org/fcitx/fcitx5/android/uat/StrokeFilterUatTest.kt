/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.phrase.CharInfo
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.stroke.StrokeShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 笔画筛选 (uat/06-pinyin.md, 03-t9.md, 07-shuangpin.md): strokes drawn on the keyboard after the pinyin, with the shipped data. */
class StrokeFilterUatTest {

    @Test
    @Uat("T9-57")
    fun nineKeysWangThenADot() = with(EngineReplay(im = InputMethodCatalog.PINYIN)) {
        type(EngineReplay.nineKeys("9264"))
        expectFirst("王")
        stroke("n")
        expectPreedit("wang 丶")
        // the readings' characters that start with 丶, in the order they had: 养 of yang first
        expectFirst("养")
        expectOnFirstPage("养", "望", "忘")
        expectInOrder("养", "望", "忘", "羊")
        expectNotAmong("王", "网", "杨")
        // 0 commits the kept first, then is typed
        type(EngineReplay.nineKeys("0"))
        expectText("养0")
        expectPreedit("")
    }

    @Test
    @Uat("ST-120")
    fun nineKeysWangThenAPieKeepsWhatTheMainlandStartsSo() = with(EngineReplay(im = InputMethodCatalog.PINYIN)) {
        type(EngineReplay.nineKeys("9264"))
        stroke("p")
        expectPreedit("wang 丿")
        expectFirst("往")
        // 艳 is 一一一丨丿… as the mainland writes it (its card's order); rime-stroke's first, Taiwan's, starts 丿一一丨
        expectNotAmong("艳", "王", "养")
        assertTrue("腰 (丿㇆…) not kept: $candidates", "腰" in candidates)
        // the first characters of all kept start with 丿 as the mainland writes them
        val mainland = CharInfo.load(UatEngines.data(InputMethodCatalog.CHAR_INFO_DATA).duplicate(), verify = false)
        for (c in candidates.take(60)) {
            val order = mainland.of(c.codePointAt(0))?.order ?: continue
            assertEquals("$c ${StrokeShape.shown(order)}", 3, order.first().kind)
        }
    }

    @Test
    @Uat("PY-103")
    fun xiThenFourStrokesOfXi() = with(EngineReplay()) {
        type("xi")
        assertTrue("曦 already on the first page", "曦" !in page)
        stroke("s")
        expectPreedit("xi 丨")
        expectOnFirstPage("曦")
        stroke("zhh")
        expectPreedit("xi 丨乛一一")
        expectFirst("曦")
        pick("曦")
        expectText("曦")
        expectPreedit("")
    }

    @Test
    @Uat("PY-104")
    fun haoThenADotThenBackspace() = with(EngineReplay()) {
        // nothing typed: a stroke does nothing, types nothing
        stroke("n")
        expectText("")
        type("hao")
        val before = candidates
        assertTrue("灏 already on the first page", "灏" !in page)
        stroke("n")
        expectOnFirstPage("灏")
        expectNotAmong("好", "号")
        // Backspace: the stroke first, the order as it was; then the pinyin
        backspace()
        expectPreedit("hao")
        assertEquals(before.take(20), candidates.take(20))
        backspace()
        expectPreedit("ha")
        expectText("")
        // punctuation commits the kept first, then is typed (as the key sends it: the full-width 「，」 is the host's)
        backspace()
        backspace()
        type("hao")
        stroke("n")
        val kept = candidates.first()
        assertTrue(kept, kept != "好")
        type(",")
        expectText("$kept,")
        expectPreedit("")
        // a kept first of part of the pinyin: a mark picks it, the rest stays typed, the mark is not typed
        type("woxiangqushanghai")
        stroke("h")
        val part = candidates.first()
        assertTrue(part, part.length < 8)
        type(",")
        expectText("$kept,")
        assertTrue(preedit, preedit.startsWith(part) && preedit.length > part.length && "," !in preedit)
    }

    @Test
    @Uat("SP-25")
    fun shuangpinXiThenADownStroke() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        type("xi")
        expectFirst("系")
        stroke("s")
        expectPreedit("xi 丨")
        expectFirst("吸")
        expectOnFirstPage("曦")
        expectNotAmong("系", "西")
        // a key after the strokes drops them: what was shown before them, the key typed after it
        type("u")
        expectPreedit("xi u")
        expectAmong("系数")
    }

    // the same on 双拼's 18 keys and nine keys: the strokes reach the session the keys' codes go to
    @Test
    @Uat("SP-25")
    fun shuangpinXiOnTheGroupedKeysThenADownStroke() = assertRows(
        "the 18 keys" to { onGroupedKeys(NineKeyLetters.G18) },
        "the nine keys" to { onGroupedKeys(NineKeyLetters.NINE_SP) },
    )

    private fun onGroupedKeys(keys: NineKeyLetters) = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        type("xi".map(keys::key).joinToString(""))
        expectAmong("系", "西")
        stroke("s")
        // the keys' other readings among them too: kept, not their place
        expectAmong("吸", "曦")
        expectNotAmong("系", "西")
    }

    // 首页单字: 可以 still first, the first two characters written from 一 after it; the same on the nine keys and 双拼
    @Test
    @Uat("PY-135", "T9-65", "SP-37")
    fun keyiThenAHengPutsTwoCharactersAloneRightAfterTheFirst() = assertRows(
        "全拼" to { singlesAfterTheFirst(InputMethodCatalog.PINYIN, "keyi") },
        "九键" to { singlesAfterTheFirst(InputMethodCatalog.PINYIN, EngineReplay.nineKeys("5394")) },
        // 自然码's ke and yi
        "双拼" to { singlesAfterTheFirst(InputMethodCatalog.SHUANGPIN, "keyi") },
    )

    private fun singlesAfterTheFirst(im: String, keys: String) {
        fun filtered(singles: Int) = with(EngineReplay(im = im)) {
            engines.settings = engines.settings.copy(strokeSingles = singles)
            type(keys)
            stroke("h")
            candidates
        }
        val off = filtered(0)
        val two = filtered(2)
        assertEquals(off.take(5).toString(), "可以", off.first())
        // the first two characters alone after the first, in their order
        val alone = off.drop(1).filter { it.codePointCount(0, it.length) == 1 && Character.isIdeographic(it.codePointAt(0)) }.take(2)
        assertEquals(off.take(20).toString(), 2, alone.size)
        assertTrue("${off.take(5)} already ${alone}", off.subList(1, 3) != alone)
        assertEquals(listOf("可以") + alone, two.take(3))
        // nothing else gone or added, the words passed in their order after them
        assertEquals(off.sorted(), two.sorted())
        assertEquals(off.filter { it !in alone }.take(20), two.filter { it !in alone }.take(20))
        // picked at its place shown: that character, the reading's rest still typed after it
        with(EngineReplay(im = im)) {
            engines.settings = engines.settings.copy(strokeSingles = 2)
            type(keys)
            stroke("h")
            pickAt(1)
            assertTrue("$text|$preedit", (text + preedit).startsWith(alone[0]) && text + preedit != alone[0])
        }
    }
}
