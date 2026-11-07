/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.text.InputType
import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The keyboard's half of uat/02, and 05 cases whose engine half is lib/ime-eval's (HostCasesUatTest,
 * EnglishUatTest): what the field gets, where a glide is taken, the bar's sum, on the whole
 * keyboard over a fake engine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class KeyboardFieldsUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private var harness: KeyboardHarness? = null

    @After
    fun close() {
        harness?.close()
    }

    private fun english(inputType: Int = InputType.TYPE_CLASS_TEXT) = KeyboardHarness(im = KeyboardHarness.ENGLISH).also { harness = it }.start(inputType).also {
        it.emit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH))
    }

    // h across to o, the finger over the letters between
    private fun KeyboardHarness.glideHello(lift: Boolean = true) {
        val o = box(key("o"))
        drag(key("h"), o.exactCenterX(), o.exactCenterY(), steps = 12, lift = lift)
    }

    // ------------------------------------------------------------------ 02 keyboard

    // fcitx hands Backspace back with nothing typed; the editor deletes what it takes for one character
    @Test
    @Uat("KB-10")
    fun backspaceTakesAnEmojiWithItsSkinToneWhole() {
        val h = english()
        h.field.setText("👍🏽")
        h.field.setSelection(h.field.text.length)
        h.idle()
        h.tap(h.inputView.findViewById<View>(R.id.button_backspace))
        assertTrue(h.api.calls.toString(), h.api.calls.any { "sym=${FcitxKeyMapping.FcitxKey_BackSpace}," in it })
        h.emit(HostEvent.Key(FcitxKeyMapping.FcitxKey_BackSpace, KeyStates.Virtual.states.toInt(), 0, false, 0))
        assertEquals("", h.field.text.toString())
    }

    @Test
    @Uat("KB-33")
    fun aGlideDrawsTheFingersTrail() {
        prefs.set(AppPrefs.getInstance().keyboard.glideTyping, true)
        val h = english()
        h.glideHello(lift = false)
        // still down: nothing offered yet, the trail drawn over the keys
        assertFalse("offerGlide" in h.api.calls)
        h.capture("KeyboardFieldsUatTest/glide_trail")
    }

    @Test
    @Uat("KB-36")
    fun noGlideInAPasswordAnAddressOrAnEmail() {
        prefs.set(AppPrefs.getInstance().keyboard.glideTyping, true)
        for (variation in listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        )) {
            harness?.close()
            val h = english(InputType.TYPE_CLASS_TEXT or variation)
            if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                // no trail drawn: the keys as they are under the finger
                h.glideHello(lift = false)
                h.capture("KeyboardFieldsUatTest/password_no_trail")
                h.release()
            }
            h.glideHello()
            assertFalse("$variation: ${h.api.calls}", "offerGlide" in h.api.calls)
        }
        harness?.close()
        harness = null
    }

    // what the engine types of it: EnglishUatTest.aGlideInANoSuggestionsFieldAndWithSuggestionsOffTypesTheWord
    @Test
    @Uat("EN-19")
    fun aNoSuggestionsFieldAndSuggestionsOffStillGlide() {
        prefs.set(AppPrefs.getInstance().keyboard.glideTyping, true)
        english(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).glideHello()
        assertEquals(harness!!.api.calls.toString(), 1, harness!!.api.calls.count { it == "offerGlide" })
        harness?.close()
        // 单词提示 is the engine's option; the keyboard reads it as the host last said
        val suggests = EngineState.englishSuggests
        EngineState.englishSuggests = false
        try {
            english().glideHello()
            assertEquals(harness!!.api.calls.toString(), 1, harness!!.api.calls.count { it == "offerGlide" })
        } finally {
            EngineState.englishSuggests = suggests
        }
    }

    // ------------------------------------------------------------------ 05 toolbar

    private fun KeyboardHarness.button(text: String): View {
        val t = KeyboardHarness.all(inputView).firstOrNull { shown(it) && it is TextView && it.text.toString() == text }
            ?: error("no $text in ${texts(inputView)}")
        return generateSequence(t) { it.parent as? View }.first { it.hasOnClickListeners() }
    }

    @Test
    @Uat("TB-26")
    fun theResultReplacesTheWholeSum() {
        prefs.set(AppPrefs.getInstance().keyboard.calculationReplacesSum, true)
        val h = english()
        h.field.setText("1+2*2")
        h.field.setSelection(5)
        h.idle()
        // the engine commits the = typed (English passes keys on as they are)
        h.emit(HostEvent.Commit("=", -1))
        h.tap(h.button("= 5"))
        assertEquals("5", h.field.text.toString())
    }
}
