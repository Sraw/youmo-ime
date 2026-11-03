/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.AltTextKeyView
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkYield
import org.fcitx.fcitx5.android.input.keyboard.KeyTextScale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 键盘高度 紧凑 (uat/14 ST-51, 051-compact): the keys' text 0.9 of the size, one mark a key in its top
 * right corner, the keys made anew only as the level goes into or out of it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class CompactKeyboardUatTest {

    private var made: KeyboardHarness? = null
    private val h get() = made ?: fresh()
    private var settings: SettingsHarness? = null

    private fun fresh(): KeyboardHarness {
        made?.close()
        return KeyboardHarness().also { made = it }
    }

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun close() {
        made?.close()
        settings?.close()
    }

    private val keyboard get() = AppPrefs.getInstance().keyboard

    // as the level list sets them: both numbers
    private fun level(portrait: Int, landscape: Int) {
        prefs.set(keyboard.keyboardHeightPercent, portrait)
        prefs.set(keyboard.keyboardHeightPercentLandscape, landscape)
    }

    private fun compact() = level(20, 34)

    private fun TextKeyView.size() = mainText.textSize

    private fun AltTextKeyView.cornerTopRight(): Boolean =
        altText.visibility == View.VISIBLE && h.box(altText).centerY() < h.box(mainText).centerY() &&
            h.box(altText).left >= h.box(mainText).right

    private fun backspace(): KeyView =
        KeyboardHarness.all(h.inputView).filterIsInstance<KeyView>().first { it.def.viewId == R.id.button_backspace }

    // its icon and each corner's
    private fun icons(v: View): Int = when (v) {
        is ImageView -> if (v.visibility == View.VISIBLE && v.drawable != null) 1 else 0
        is ViewGroup -> (0 until v.childCount).sumOf { icons(v.getChildAt(it)) }
        else -> 0
    }

    @Test
    @Uat("ST-51")
    fun compactTextIsSmallerAndTheSymbolTopRightEvenAtBottom() {
        prefs.set(ThemeManager.prefs.punctuationPosition, PunctuationPosition.Bottom)
        h.start()
        val size = h.key("q").size()
        val height = h.box(h.key("q")).height()
        assertFalse("standard: under the letter", (h.key("w") as AltTextKeyView).cornerTopRight())
        fresh()
        compact()
        h.start()
        assertEquals(size * 0.9f, h.key("q").size(), 0.5f)
        assertTrue(h.box(h.key("q")).height() < height * 0.8)
        val w = h.key("w") as AltTextKeyView
        assertEquals("2", w.altText.text.toString())
        assertTrue("底部 too: top right", w.cornerTopRight())
        // the swipe types it all the same
        h.swipe(w, 0f, w.height * 1.3f)
        assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey") && "key=2," in it })
    }

    @Test
    @Uat("ST-51")
    fun ofASymbolAndAShortcutOnlyTheSymbolIsDrawn() {
        prefs.set(AppPrefs.getInstance().internal.keyShortcuts, KeyShortcuts.Empty.with("full/q", KeyShortcut.Text("你好")).serialize())
        h.start()
        assertTrue("standard: both", "你好" in h.texts())
        val icons = icons(backspace())
        fresh()
        compact()
        h.start()
        assertFalse("紧凑: the symbol alone", "你好" in h.texts())
        val q = h.key("q") as AltTextKeyView
        assertEquals("1", q.altText.text.toString())
        assertTrue(q.cornerTopRight())
        // the backspace's up mark (清除) alone, its restore (down) not drawn
        assertEquals(icons - 1, icons(backspace()))
        // the shortcut still swipes
        h.swipe(q, 0f, -q.height * 1.3f)
        h.idle()
        assertEquals("你好", h.field.text.toString())
    }

    @Test
    @Uat("ST-51")
    fun theKeysAreMadeAnewOnlyIntoOrOutOfCompact() {
        h.start()
        val standard = h.keyboard
        level(26, 42)
        h.idle()
        assertSame("矮: resized only", standard, h.keyboard)
        compact()
        h.idle()
        val small = h.keyboard
        assertNotSame(standard, small)
        assertEquals(h.key("w").let { (it as AltTextKeyView).altText.text.toString() }, "2")
        level(38, 60)
        h.idle()
        assertNotSame("out of it", small, h.keyboard)
    }

    @Test
    @Uat("ST-51")
    fun floatingTakesTheSmallerOfItsScaleAndCompacts() {
        h.start()
        val size = h.key("q").size()
        fresh()
        prefs.set(ThemeManager.prefs.floatingKeyboard, true)
        compact()
        h.start()
        val screen = h.service.resources.configuration.screenWidthDp
        val floating = FloatingKeyboard.textScale(FloatingKeyboard.widthDp(screen), screen)
        // the smaller, not the product (051-compact §1.3)
        assertEquals(size * minOf(KeyTextScale.COMPACT, floating), h.key("q").size(), 0.5f)
        // narrow keys: each letter clear of its symbol in the top right (d and + crossed), the
        // symbols moved into their corners and smaller, the letters where they were (w sat lower, a
        // further left), Shift's capitals too
        val lower = lettersStayClearOfTheirSymbols()
        // @ beside a: into the corner and smaller, as far as it goes, the letters as they were
        assertTrue(lower.any { it.altText.scaleX < 1f })
        assertEquals(1f, lower[0].mainText.scaleX, 0f)
        h.tap(h.described("Shift"))
        h.idle()
        assertEquals("Q", h.key("Q").mainText.text.toString())
        // A taller than a, @ as small as it goes: every letter as much smaller
        val upper = lettersStayClearOfTheirSymbols()
        assertTrue(upper[0].mainText.scaleX.toString(), upper[0].mainText.scaleX in (1f - KeyMarkYield.MAX_LETTER_SHRINK)..0.95f)
    }

    @Test
    @Uat("ST-51")
    fun oneHandedLettersStayInTheirRows() {
        prefs.set(ThemeManager.prefs.oneHanded, OneHanded.Right)
        compact()
        h.start()
        // @, +, / and \ nudged into their corners, none smaller, the letters as they were
        val lower = lettersStayClearOfTheirSymbols()
        assertEquals(setOf("a", "d", "h", "m"), lower.filter { it.altText.moved() }.map { it.mainText.text.toString() }.toSet())
        assertTrue(lower.all { it.altText.scaleX == 1f && it.mainText.scaleX == 1f })
        h.tap(h.described("Shift"))
        h.idle()
        assertTrue(lettersStayClearOfTheirSymbols().any { it.altText.moved() })
        // the standard height: the letters their size too
        fresh()
        prefs.set(ThemeManager.prefs.oneHanded, OneHanded.Right)
        h.start()
        assertTrue(lettersStayClearOfTheirSymbols().all { it.mainText.scaleX == 1f })
    }

    private fun View.moved() = scaleX < 1f || translationX != 0f || translationY != 0f

    // each letter and its symbol's ink apart; the letters unmoved, one size, a row's on one baseline
    private fun lettersStayClearOfTheirSymbols(): List<AltTextKeyView> {
        val letters = KeyboardHarness.all(h.inputView).filterIsInstance<AltTextKeyView>().filter { h.shown(it) && it.altText.text.isNotEmpty() }.toList()
        assertTrue(letters.size.toString(), letters.size >= 26)
        for (k in letters) {
            val letter = ink(k.mainText)
            val symbol = ink(k.altText)
            assertFalse("${k.mainText.text}: $letter, ${k.altText.text}: $symbol", RectF.intersects(letter, symbol))
            assertEquals(0f, k.mainText.translationX, 0f)
            assertEquals(0f, k.mainText.translationY, 0f)
            assertEquals(letters[0].mainText.scaleX, k.mainText.scaleX, 0f)
            if (k.mainText.scaleX < 1f) assertEquals(k.mainText.baseline.toFloat(), k.mainText.pivotY, 0f)
        }
        for ((_, row) in letters.groupBy { h.box(it).top }) {
            val baselines = row.map { h.box(it.mainText).top + it.mainText.baseline }.distinct()
            assertEquals(row.joinToString { it.mainText.text }, 1, baselines.size)
        }
        return letters
    }

    // [v]'s glyphs as drawn, moved and scaled, in its parent's coordinates
    private fun ink(v: AutoScaleTextView): RectF = RectF().also {
        assertTrue(v.inkBounds(it))
        v.matrix.mapRect(it)
        it.offset(v.left.toFloat(), v.top.toFloat())
    }

    @Test
    @Uat("ST-51")
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun turnedItIsCompactToo() {
        h.start()
        val size = h.key("q").size()
        val height = h.box(h.key("q")).height()
        fresh()
        compact()
        h.start()
        assertEquals(size * 0.9f, h.key("q").size(), 0.5f)
        // 34% of the height against 标准's 49%
        assertTrue("${h.box(h.key("q")).height()} of $height", h.box(h.key("q")).height() < height * 0.8)
        assertTrue((h.key("w") as AltTextKeyView).cornerTopRight())
    }

    @Test
    @Uat("ST-51")
    fun thePreviewIsCompactAsTheLevelIsPicked() {
        val st = SettingsHarness().also { settings = it }
        st.open(SettingsRoute.Theme)
        fun q() = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<TextKeyView>()
            .first { it.isShown && it.mainText.text.toString() == "q" }
        val size = q().mainText.textSize
        compact()
        st.idle()
        assertEquals(size * 0.9f, q().mainText.textSize, 0.5f)
        level(30, 49)
        st.idle()
        assertEquals(size, q().mainText.textSize, 0.01f)
    }
}
