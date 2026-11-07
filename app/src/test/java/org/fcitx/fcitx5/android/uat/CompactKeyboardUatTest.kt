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
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.AltTextKeyView
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.input.keyboard.KeyMarks
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
 * 键盘高度 紧凑 (uat/14 ST-51, 051-compact): the keys' text 0.9 of the size, every mark drawn (symbols
 * and shortcuts; one too low for the bottom edge moved to the first free corner, KeyMarks.onKeys), the
 * keys made anew only as the level goes into or out of it.
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

    // its symbol in a right corner, not under the letter
    private fun AltTextKeyView.inItsCorner(): Boolean =
        altText.visibility == View.VISIBLE && h.box(altText).left >= h.box(mainText).right

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
    fun compactTextIsSmallerAndTheSymbolInItsCornerEvenAtBottom() {
        prefs.set(AppPrefs.getInstance().keyboard.punctuationPosition, KeyMarkPosition.Bottom)
        h.start()
        val size = h.viewKey("q").size()
        val height = h.box(h.viewKey("q")).height()
        assertFalse("standard: under the letter", (h.viewKey("w") as AltTextKeyView).inItsCorner())
        fresh()
        compact()
        h.start()
        assertEquals(size * 0.9f, h.viewKey("q").size(), 0.5f)
        assertTrue(h.box(h.viewKey("q")).height() < height * 0.8)
        val w = h.viewKey("w") as AltTextKeyView
        assertEquals("2", w.altText.text.toString())
        assertTrue("底部 too: in its corner", w.inItsCorner())
        // the swipe types it all the same
        h.swipe(w, 0f, w.height * 1.3f)
        assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey") && "key=2," in it })
    }

    // too low for the bottom edge's middle: the first corner of top left, top right, … the symbols do
    // not take, drawn, none left out (KeyMarks.onKeys)
    @Test
    @Uat("ST-51")
    fun aBottomShortcutInTheFirstCornerFree() {
        prefs.set(AppPrefs.getInstance().keyboard.shortcutMarkPosition, KeyMarkPosition.Bottom)
        compact()
        h.start()
        for (k in listOf("a", "z", "x", "c", "v")) {
            val marks = h.viewKey(k).shortcutMarks
            assertEquals(k, setOf(KeyMarks.Slot.TopLeft), marks.keys)
            assertTrue(k, marks.values.all { it.alpha != 0f })
        }
        fresh()
        prefs.set(AppPrefs.getInstance().keyboard.punctuationPosition, KeyMarkPosition.TopLeft)
        h.start()
        assertEquals(setOf(KeyMarks.Slot.TopRight), h.viewKey("a").shortcutMarks.keys)
    }

    @Test
    @Uat("ST-51")
    fun theShortcutIsDrawnTopLeftBesideTheSymbol() {
        prefs.set(AppPrefs.getInstance().internal.keyShortcuts, KeyShortcuts.Empty.with("full/q", KeyShortcut.Text("你好")).serialize())
        h.start()
        assertTrue("standard: both", "你好" in h.texts())
        val icons = icons(backspace())
        fresh()
        compact()
        h.start()
        // 0.5.1 drew the symbol alone, the only corner then being the top right one
        assertTrue("紧凑: both too", "你好" in h.texts())
        val q = h.viewKey("q") as AltTextKeyView
        assertEquals("1", q.altText.text.toString())
        assertTrue(q.inItsCorner())
        val text = h.box(q.markAt(KeyMarks.Slot.TopLeft)!!)
        assertTrue("$text left of ${h.box(q.altText)}", text.right <= h.box(q.altText).left && text.centerY() < h.box(q.mainText).centerY())
        // the backspace's 清除 (up) and 恢复 (down) both
        assertEquals(icons, icons(backspace()))
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
        assertEquals(h.viewKey("w").let { (it as AltTextKeyView).altText.text.toString() }, "2")
        level(38, 60)
        h.idle()
        assertNotSame("out of it", small, h.keyboard)
    }

    @Test
    @Uat("ST-51")
    fun floatingTakesTheSmallerOfItsScaleAndCompacts() {
        h.start()
        val size = h.viewKey("q").size()
        fresh()
        prefs.set(ThemeManager.prefs.floatingKeyboard, true)
        compact()
        h.start()
        val screen = h.service.resources.configuration.screenWidthDp
        val floating = FloatingKeyboard.textScale(FloatingKeyboard.widthDp(screen), screen)
        // the smaller, not the product (051-compact §1.3)
        assertEquals(size * minOf(KeyTextScale.COMPACT, floating), h.viewKey("q").size(), 0.5f)
        // narrow keys: each letter clear of its symbol in its corner (d and + crossed), the symbols
        // moved into their corners and smaller, the letters where they were (w sat lower, a further
        // left), Shift's capitals too
        val lower = lettersStayClearOfTheirSymbols()
        // @ beside a: into the corner and smaller, as far as it goes; the shortcuts' icons into
        // theirs, top left, 7 dp at least: the letters a hair smaller, all of them
        assertTrue(lower.any { it.altText.scaleX < 1f })
        assertTrue(lower[0].mainText.scaleX.toString(), lower[0].mainText.scaleX >= 0.98f)
        h.tap(h.described("Shift"))
        h.idle()
        assertEquals("Q", h.viewKey("Q").mainText.text.toString())
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
        fresh()
        prefs.set(ThemeManager.prefs.oneHanded, OneHanded.Right)
        h.start()
        // the standard height: the letters their size too
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
            // its shortcut's icon, as drawn
            k.shortcutMarks.values.filter { it.alpha > 0f }.forEach { icon ->
                val box = RectF(0f, 0f, icon.width.toFloat(), icon.height.toFloat()).also { icon.matrix.mapRect(it); it.offset(icon.left.toFloat(), icon.top.toFloat()) }
                assertFalse("${k.mainText.text}: $letter, its shortcut: $box", RectF.intersects(letter, box))
            }
            // the letter where it was: smaller, if at all, about its middle on its baseline
            val laidOut = RectF().also { k.mainText.inkBounds(it) }
            val at = floatArrayOf(laidOut.centerX(), k.mainText.baseline.toFloat()).also { k.mainText.matrix.mapPoints(it) }
            assertEquals(laidOut.centerX(), at[0], 0.5f)
            assertEquals(k.mainText.baseline.toFloat(), at[1], 0.5f)
            assertEquals(letters[0].mainText.scaleX, k.mainText.scaleX, 0.001f)
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
        val size = h.viewKey("q").size()
        val height = h.box(h.viewKey("q")).height()
        fresh()
        compact()
        h.start()
        assertEquals(size * 0.9f, h.viewKey("q").size(), 0.5f)
        // 34% of the height against 标准's 49%
        assertTrue("${h.box(h.viewKey("q")).height()} of $height", h.box(h.viewKey("q")).height() < height * 0.8)
        assertTrue((h.viewKey("w") as AltTextKeyView).inItsCorner())
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
