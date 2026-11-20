/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The 14 and 18 keys: every key's tap, swipes and long press, held to the committed record ([GestureSnapshot]). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class GroupedSceneGesturesTest {

    @get:Rule
    val prefs = PrefsRule()

    private val shuangpin = InputMethodEntry(InputMethodCatalog.SHUANGPIN, "双拼", "", "双", "zh_CN")
    private val schemeWas = EngineState.shuangpinScheme

    @After
    fun schemeBack() {
        EngineState.shuangpinScheme = schemeWas
    }

    // the keys that put another keyboard up (符, 123, the globe) are left out: they would take the rest away
    private fun gestures(
        im: InputMethodEntry,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        shown: Set<String> = emptySet(),
        hidden: Set<String> = emptySet(),
    ): List<String> {
        val prefill = { e: android.widget.EditText ->
            e.setText(TEXT)
            e.setSelection(TEXT.length)
        }
        return KeyboardHarness(TestThemes.PixelLight, im).start(inputType, prefill = prefill).use { h ->
            h.relayout()
            assertTrue(KeyboardHarness.all(h.keyboard).any { it is KeyboardCanvasView && it.visibility == android.view.View.VISIBLE })
            val ids = h.keys().mapNotNull { it.id }.filter { it !in SWITCHING }
            assertTrue(ids.toString(), ids.size >= MOST_KEYS_AT_LEAST)
            assertTrue("$ids", ids.containsAll(shown) && ids.none { it in hidden })
            val out = mutableListOf<String>()
            h.recordGestures(out, ids, held = { it != "backspace" })
            h.backspaceHeld(out, HOLD_MS, MIN_REPEATS) { it.startsWith("sendKey") || it.startsWith("commit") }
            out
        }
    }

    private fun matchesRecord(
        name: String,
        im: InputMethodEntry,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        shown: Set<String> = emptySet(),
        hidden: Set<String> = emptySet(),
    ) = GestureSnapshot.check("grouped-$name", gestures(im, inputType, shown, hidden))

    @Test
    fun pinyinsFourteenKeys() {
        prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard.Keys14)
        matchesRecord("pinyin14", KeyboardHarness.PINYIN)
    }

    @Test
    fun pinyinsEighteenKeys() {
        prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard.Keys18)
        matchesRecord("pinyin18", KeyboardHarness.PINYIN)
    }

    @Test
    fun shuangpinsEighteenKeysUnderMicrosoft() {
        prefs.set(AppPrefs.getInstance().keyboard.shuangpinKeyboard, org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard.Keys18)
        EngineState.shuangpinScheme = ShuangpinScheme.MICROSOFT
        matchesRecord("shuangpin18-microsoft", shuangpin)
    }

    @Test
    fun shuangpinsEighteenKeysPlain() {
        prefs.set(AppPrefs.getInstance().keyboard.shuangpinKeyboard, org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard.Keys18)
        EngineState.shuangpinScheme = ShuangpinScheme.ZIRANMA
        matchesRecord("shuangpin18-plain", shuangpin)
    }

    @Test
    fun theFourteenAndEighteenKeysInAnAddressField() {
        for (keys in listOf(org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard.Keys14, org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard.Keys18)) {
            prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, keys)
            matchesRecord("$keys-url", KeyboardHarness.PINYIN, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, setOf("slash", "dot", "domain"), setOf("at", "comma", "period"))
            matchesRecord("$keys-email", KeyboardHarness.PINYIN, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, setOf("at", "dot", "domain"), setOf("slash", "comma", "period"))
        }
    }

    @Test
    fun shuangpinsFourteenKeys() {
        prefs.set(AppPrefs.getInstance().keyboard.shuangpinKeyboard, org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard.Keys14)
        matchesRecord("shuangpin14", shuangpin)
    }

    private companion object {
        val SWITCHING = setOf("symbols", "numbers", "lang")

        const val MOST_KEYS_AT_LEAST = 18

        const val TEXT = "hello world, again"

        const val HOLD_MS = 1500L
        const val MIN_REPEATS = 3
    }
}
