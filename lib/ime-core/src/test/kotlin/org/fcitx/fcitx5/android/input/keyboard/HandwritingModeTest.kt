/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.input.Contrast
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.TextShadow
import org.fcitx.fcitx5.android.scene.TextStroke
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The toolbar's 手写 (061-handwriting-toolbar §3.3, §3.4): where it is offered, when it writes, its ink and the letters faded. */
class HandwritingModeTest {

    @Test
    @Uat("HW-44")
    fun offeredInEveryChineseInputMethodNotEnglishNorAPassword() {
        for (im in listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN, InputMethodCatalog.WUBI, InputMethodCatalog.CANGJIE)) {
            assertTrue(im, HandwritingMode.offered(im, password = false))
            assertFalse(im, HandwritingMode.offered(im, password = true))
        }
        // the user's Q3: English's keys are typed
        assertFalse(HandwritingMode.offered(InputMethodCatalog.ENGLISH, password = false))
        // a keyboard not told its input method yet
        assertFalse(HandwritingMode.offered("", password = false))
    }

    private data class Row(
        val what: String,
        val open: Boolean = true,
        val im: String = InputMethodCatalog.PINYIN,
        val password: Boolean = false,
        val tempMode: Boolean = false,
        val loadable: Boolean = true,
        val exploring: Boolean = false,
        val writes: Boolean,
    )

    @Test
    fun writesOnlyOpenInAnInputMethodThatWritesAndNothingElseTakingTheKeys() = assertRows(
        listOf(
            Row("open in pinyin", writes = true),
            Row("open in 五笔", im = InputMethodCatalog.WUBI, writes = true),
            Row("closed", open = false, writes = false),
            Row("English", im = InputMethodCatalog.ENGLISH, writes = false),
            Row("a password field", password = true, writes = false),
            Row("快速输入 or Unicode", tempMode = true, writes = false),
            Row("the recognizer not loadable", loadable = false, writes = false),
            // the user's Q7: TalkBack's single taps find keys
            Row("touch exploration", exploring = true, writes = false),
        ),
        { it.what },
    ) { r ->
        assertEquals(r.writes, HandwritingMode.writes(r.open, r.im, r.password, r.tempMode, r.loadable, r.exploring))
    }

    @Test
    @Uat("ST-123")
    fun theThemesInkOnlyWhereItStandsOutFromTheLetters() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val grey = 0xFFEEEEEE.toInt()
        val red = 0xFFD32F2F.toInt()
        // a pad's white ink on light keys: lost, the letters' colour instead
        assertTrue(Contrast.ratio(white, grey) < HandwritingMode.INK_CONTRAST)
        assertEquals(black, HandwritingMode.inkColor(white, keyText = black, letterFill = grey))
        // red on the same keys stands out: the theme's
        assertEquals(red, HandwritingMode.inkColor(red, keyText = black, letterFill = grey))
        // none set: the letters' colour
        assertEquals(black, HandwritingMode.inkColor(null, keyText = black, letterFill = grey))
    }

    @Test
    fun fadedLettersAreTheirTextsAndIconsAtAThirdTheFaceAsItIs() {
        val black = Argb(0xFF000000.toInt())
        val half = Argb(0x80FF0000.toInt())
        val face = Op.Fill(Shape.Rect(Box(0.0, 0.0, 10.0, 10.0), listOf(0.0, 0.0, 0.0, 0.0)), black)
        val text = Op.Text("q", 5.0, 8.0, "system", 400, 20.0, black, stroke = TextStroke(1.0, half), shadow = TextShadow(0.0, 1.0, 0.0, black))
        val icon = Op.Icon("x", Box(1.0, 1.0, 9.0, 9.0), half)
        val out = HandwritingMode.faded(listOf(face, text, icon))
        assertEquals(face, out[0])
        val t = out[1] as Op.Text
        assertEquals(Argb(0x59000000), t.color)
        assertEquals(Argb(0x2DFF0000), t.stroke?.color)
        assertEquals(Argb(0x59000000), t.shadow?.color)
        assertEquals("q", t.text)
        assertEquals(Argb(0x2DFF0000), (out[2] as Op.Icon).tint)
    }
}
