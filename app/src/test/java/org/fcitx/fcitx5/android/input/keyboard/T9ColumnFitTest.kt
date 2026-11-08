/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
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

    // the canvas on: the column in the scene's slot `side` (placed on each scene made, T9Keyboard.onSceneBuilt)
    @Test
    fun theLongestSyllablesFitTheNarrowestColumnInItsSlot() = fits(2.0f, canvas = true)

    @get:Rule
    val prefs = PrefsRule()

    private fun fits(scale: Float, canvas: Boolean = false) {
        RuntimeEnvironment.setFontScale(scale)
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, canvas)
        val keyboard = T9Keyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), ThemePreset.PixelDark)
        val longest = listOf("shuang", "zhuang", "chuang", "shuan")
        keyboard.show(EngineState.T9State(composing = true, syllables = longest, id = 1))
        val density = keyboard.resources.displayMetrics.density
        // the narrowest the nine keys are laid out: a 240dp-wide keyboard (one-handed on a small phone)
        val w = (NARROWEST_DP * density).toInt()
        val h = (HEIGHT_DP * density).toInt()
        keyboard.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        keyboard.layout(0, 0, w, h)
        if (canvas) {
            // the column put elsewhere (the views' cell is where the slot is), then a scene made anew (the keyboard
            // laid out again, as it is drawn): the column placed in its slot after, then laid out there
            val view = KeyboardHarness.all(keyboard).filterIsInstance<KeyboardCanvasView>().single()
            view.scene
            shadowOf(Looper.getMainLooper()).idle()
            (0 until keyboard.childCount).map { keyboard.getChildAt(it) }.filterIsInstance<ScrollView>().single()
                .layoutParams = ConstraintLayout.LayoutParams(1, 1)
            val taller = h + h / 4
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(taller, View.MeasureSpec.EXACTLY))
            keyboard.layout(0, 0, w, taller)
            val slot = checkNotNull(view.scene).slots.single { it.id == "side" }.bounds
            shadowOf(Looper.getMainLooper()).idle()
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(taller, View.MeasureSpec.EXACTLY))
            keyboard.layout(0, 0, w, taller)
            val c = (0 until keyboard.childCount).map { keyboard.getChildAt(it) }.filterIsInstance<ScrollView>().single()
            assertEquals(View.VISIBLE, c.visibility)
            assertEquals(listOf(slot.left, slot.top, slot.right, slot.bottom).map { it.roundToInt() }, listOf(c.left, c.top, c.right, c.bottom))
        }
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
