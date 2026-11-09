/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * A7: the column's longest syllables (shuang, zhuang, chuang) on its narrowest, under the largest
 * font scales, stay one line and whole: drawn smaller to fit the item, never wrapped onto a second
 * line the cell cut off. The text is in dp, so the font scale does not grow it either.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w240dp-h640dp-xxhdpi")
class T9ColumnFitTest {

    @Test
    fun theLongestSyllablesFitTheNarrowestColumnAtFontScale13() = fits(1.3f)

    @Test
    fun theLongestSyllablesFitTheNarrowestColumnAtFontScale20() = fits(2.0f)

    // laid out again at another size: the column in the scene's slot `side` in that same pass (T9Keyboard.onLayout)
    @Test
    fun theLongestSyllablesFitTheNarrowestColumnInItsSlot() = fits(2.0f, moved = true)

    @get:Rule
    val prefs = PrefsRule()

    // no scene yet (never laid out): the column not shown; then shown in its slot, and laid out again at the same
    // size nothing changes: its layout params the same object, not set again (setting them asks for a layout: a loop
    // once, 0bfe7fa6b)
    @Test
    fun theColumnWaitsForTheSceneAndThenStaysPut() {
        val keyboard = T9Keyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), TestThemes.PixelDark, semicolon = false)
        val column = (0 until keyboard.childCount).map { keyboard.getChildAt(it) }.filterIsInstance<ScrollView>().single()
        assertEquals(View.INVISIBLE, column.visibility)
        val density = keyboard.resources.displayMetrics.density
        val w = (NARROWEST_DP * density).toInt()
        val h = (HEIGHT_DP * density).toInt()
        fun pass() {
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            keyboard.layout(0, 0, w, h)
        }
        pass()
        assertEquals(View.VISIBLE, column.visibility)
        pass()
        val params = column.layoutParams
        // laid out again for certain (the same size alone would skip onLayout): the slot unmoved, its params not set again
        keyboard.requestLayout()
        pass()
        assertSame(params, column.layoutParams)
    }

    private fun fits(scale: Float, moved: Boolean = false) {
        RuntimeEnvironment.setFontScale(scale)
        val keyboard = T9Keyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), TestThemes.PixelDark, semicolon = false)
        val longest = listOf("shuang", "zhuang", "chuang", "shuan")
        keyboard.show(EngineState.T9State(composing = true, syllables = longest, id = 1))
        val density = keyboard.resources.displayMetrics.density
        // the narrowest the nine keys are laid out: a 240dp-wide keyboard (one-handed on a small phone)
        val w = (NARROWEST_DP * density).toInt()
        val h = (HEIGHT_DP * density).toInt()
        keyboard.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        keyboard.layout(0, 0, w, h)
        val view = KeyboardHarness.all(keyboard).filterIsInstance<KeyboardCanvasView>().single()
        var height = h
        if (moved) {
            // laid out again at another size: the column moves with the slot in that same pass
            height = h + h / 4
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            keyboard.layout(0, 0, w, height)
        }
        // one measure and layout, no frame after it (no looper run): the column in its slot already, shown
        val slot = checkNotNull(view.scene).slots.single { it.id == "side" }.bounds
        val c = (0 until keyboard.childCount).map { keyboard.getChildAt(it) }.filterIsInstance<ScrollView>().single()
        assertEquals(View.VISIBLE, c.visibility)
        assertEquals(listOf(slot.left, slot.top, slot.right, slot.bottom).map { it.roundToInt() }, listOf(c.left, c.top, c.right, c.bottom))
        val column = (0 until keyboard.childCount).map { keyboard.getChildAt(it) }.filterIsInstance<ScrollView>().single()
        val items = column.getChildAt(0) as ViewGroup
        val ink = RectF()
        var shrunk = false
        for (i in longest.indices) {
            val item = items.getChildAt(i) as AutoScaleTextView
            assertEquals(longest[i], item.text.toString())
            assertEquals("${longest[i]} on one line", 1, item.maxLines)
            assertTrue(item.width > 0 && item.height > 0)
            assertTrue("${longest[i]} drawn", item.inkBounds(ink))
            val inside = ink.left >= item.paddingLeft - 0.5f && ink.right <= item.width - item.paddingRight + 0.5f &&
                ink.top >= -0.5f && ink.bottom <= item.height + 0.5f
            assertTrue("${longest[i]} at font scale $scale: ink $ink in ${item.width}x${item.height}", inside)
            // the text's size is in dp: the font scale does not grow it
            assertEquals(T9Keyboard.ITEM_TEXT_DP * density, item.textSize, 0.5f)
            if (item.paint.measureText(longest[i]) > item.width - item.paddingLeft - item.paddingRight) shrunk = true
        }
        // the column this narrow is narrower than the syllables at full size: the fit was needed
        assertTrue("a syllable drawn smaller", shrunk)
    }

    private companion object {
        const val NARROWEST_DP = 240
        const val HEIGHT_DP = 220
    }
}
