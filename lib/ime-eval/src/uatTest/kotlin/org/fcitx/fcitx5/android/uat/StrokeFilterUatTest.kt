/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
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
        // a kept first of part of the pinyin (the sentence's 我 starts with 丿, not 一): a mark picks it,
        // the rest stays typed after it, the mark is not typed
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
}
