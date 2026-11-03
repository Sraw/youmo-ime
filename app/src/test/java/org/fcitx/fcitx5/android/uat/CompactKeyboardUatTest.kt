/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.AltTextKeyView
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
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
        altText.visibility == View.VISIBLE && h.box(altText).bottom <= h.box(mainText).centerY()

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
