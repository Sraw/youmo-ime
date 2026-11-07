/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The 14 and 18 keys drawn by the scene do what their views did (060-render-p2 S4a, U4 B): every key's
 * tap, swipes and long press, what reaches the engine and what the long press offers, the same with
 * each key wired from its layout's node (letters=, send=, popup) as from its old KeyDef.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class GroupedSceneGesturesTest {

    @get:Rule
    val prefs = PrefsRule()

    private val shuangpin = InputMethodEntry(InputMethodCatalog.SHUANGPIN, "双拼", "", "", "双", "zh_CN", "androidengine", false)
    private val schemeWas = EngineState.shuangpinScheme

    @After
    fun schemeBack() {
        EngineState.shuangpinScheme = schemeWas
    }

    // what a finger does on each key: the keys that put another keyboard up (符, 123, the globe) left to
    // GroupedKeyboardTest's bottom-row cases, as they would take the rest away
    private fun gestures(canvas: Boolean, im: InputMethodEntry): List<String> {
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, canvas)
        return KeyboardHarness(ThemePreset.PixelLight, im).start().use { h ->
            h.relayout()
            assertEquals(canvas, KeyboardHarness.all(h.keyboard).any { it is KeyboardCanvasView && it.visibility == android.view.View.VISIBLE })
            val ids = h.keys().mapNotNull { it.id }.filter { it !in SWITCHING }
            assertTrue(ids.toString(), ids.size >= MOST_KEYS_AT_LEAST)
            val out = mutableListOf<String>()
            fun sent(what: String) {
                out += "$what: " + h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") }
                h.api.calls.clear()
            }
            for (id in ids) {
                val k = { h.keyNamed(id) }
                h.press(k())
                h.release()
                sent("$id tap")
                h.swipe(k(), 0f, -k().bounds.height().toFloat())
                sent("$id up")
                h.swipe(k(), 0f, k().bounds.height().toFloat())
                sent("$id down")
                if (id != "space" && id != "backspace") {
                    out += "$id long press: " + h.longPress(k())
                    sent("$id long press")
                }
            }
            out
        }
    }

    private fun assertSame(im: InputMethodEntry) {
        val views = gestures(false, im)
        val scene = gestures(true, im)
        assertEquals(views.joinToString("\n"), scene.joinToString("\n"))
    }

    @Test
    fun pinyinsFourteenKeys() {
        prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard.Keys14)
        assertSame(KeyboardHarness.PINYIN)
    }

    @Test
    fun pinyinsEighteenKeys() {
        prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard.Keys18)
        assertSame(KeyboardHarness.PINYIN)
    }

    // 双拼's 18 keys under a scheme that types `;`: L; shows its semicolon
    @Test
    fun shuangpinsEighteenKeysUnderMicrosoft() {
        prefs.set(AppPrefs.getInstance().keyboard.shuangpinKeyboard, org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard.Keys18)
        EngineState.shuangpinScheme = ShuangpinScheme.MICROSOFT
        assertSame(shuangpin)
    }

    @Test
    fun shuangpinsFourteenKeys() {
        prefs.set(AppPrefs.getInstance().keyboard.shuangpinKeyboard, org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard.Keys14)
        assertSame(shuangpin)
    }

    private companion object {
        val SWITCHING = setOf("symbols", "numbers", "lang")

        // the groups and the bottom row: a key missing on one side is caught by the ids compared
        const val MOST_KEYS_AT_LEAST = 18
    }
}
