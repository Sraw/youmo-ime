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

/**
 * The 14 and 18 keys drawn by the scene (060-render-p2 S4a, U4 B): every key's tap, swipes and long
 * press, what reaches the engine and the field and what the long press offers, each key wired from its
 * layout's node (letters=, send=, popup), held to the committed record ([GestureSnapshot]).
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
    // in a field of [inputType], text in it and the cursor at its end (what ⌫ and space's swipes act on)
    // [shown] the ids that must be among the keys, [hidden] those that must not
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
            fun sent(what: String) {
                out += "$what: " + h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") } +
                    " field ${h.field.text}|${h.field.selectionStart},${h.field.selectionEnd}"
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
                if (id == "space" || id == "backspace") {
                    // sideways: the cursor moved, or the text before it selected to delete
                    h.swipe(k(), -k().bounds.width() * 2f, 0f)
                    sent("$id left")
                    h.swipe(k(), k().bounds.width() * 2f, 0f)
                    sent("$id right")
                }
                if (id != "backspace") {
                    out += "$id long press: " + h.longPress(k())
                    sent("$id long press")
                }
            }
            // ⌫ held: deletes over and over, at least MIN_REPEATS times; the record keeps what it sends, not
            // how many (that depends on where in a frame the press fell) nor the field it left (R20 F6506)
            h.press(h.keyNamed("backspace"))
            h.after(HOLD_MS)
            h.release()
            val held = h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") }
            val deletes = held.count { it.startsWith("sendKey") }
            assertTrue("$deletes deletes", deletes >= MIN_REPEATS)
            out += "backspace held: ${held.distinct()}"
            h.api.calls.clear()
            out
        }
    }

    // what each key does, the keys [shown] and not [hidden], ⌫ held repeating: as recorded under [name]
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

    // 双拼's 18 keys under a scheme that types `;`: L; shows its semicolon
    @Test
    fun shuangpinsEighteenKeysUnderMicrosoft() {
        prefs.set(AppPrefs.getInstance().keyboard.shuangpinKeyboard, org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard.Keys18)
        EngineState.shuangpinScheme = ShuangpinScheme.MICROSOFT
        matchesRecord("shuangpin18-microsoft", shuangpin)
    }

    // 双拼's 18 keys under a scheme of no `;` (Name18Plain): L alone
    @Test
    fun shuangpinsEighteenKeysPlain() {
        prefs.set(AppPrefs.getInstance().keyboard.shuangpinKeyboard, org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard.Keys18)
        EngineState.shuangpinScheme = ShuangpinScheme.ZIRANMA
        matchesRecord("shuangpin18-plain", shuangpin)
    }

    // an address field's keys: .com and @ for the separator and the rest, their long presses (the domains)
    @Test
    fun theFourteenAndEighteenKeysInAnAddressField() {
        for (keys in listOf(org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard.Keys14, org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard.Keys18)) {
            prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, keys)
            // a URL's / . and domains, no @; an address's @ . and domains, no /; neither a , or 。 of its own
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

        // the groups and the bottom row at least; which ones, the record says
        const val MOST_KEYS_AT_LEAST = 18

        const val TEXT = "hello world, again"

        // ⌫ held this long repeats: some deletes at least
        const val HOLD_MS = 1500L
        const val MIN_REPEATS = 3
    }
}
