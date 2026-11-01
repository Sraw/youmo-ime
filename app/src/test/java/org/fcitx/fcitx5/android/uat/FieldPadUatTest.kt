/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.text.InputType
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** uat/04-panels.md's number pads of a field's own type (NumberPad): the keys each field gets, and the keyboard after it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class FieldPadUatTest {

    private val h = KeyboardHarness()

    @After
    fun close() = h.close()

    private val digits = (0..9).map { it.toString() }

    private fun keys(inputType: Int) = h.start(inputType).texts()

    private fun letters() = "q" in h.texts() && "m" in h.texts()

    @Test
    @Uat("PAN-16")
    fun dateAndTimeFieldsGetTheirSeparators() {
        for (variation in listOf(InputType.TYPE_DATETIME_VARIATION_DATE, InputType.TYPE_DATETIME_VARIATION_TIME, InputType.TYPE_DATETIME_VARIATION_NORMAL)) {
            val keys = keys(InputType.TYPE_CLASS_DATETIME or variation)
            assertTrue("$variation: $keys", keys.containsAll(digits + listOf("-", "/", ":")) && "q" !in keys)
            // what a date has no use for
            assertTrue("$variation: $keys", listOf("+", "*", "=").none { it in keys })
        }
    }

    @Test
    @Uat("PAN-17")
    fun phoneFieldsGetPlusStarAndHash() {
        val keys = keys(InputType.TYPE_CLASS_PHONE)
        assertTrue(keys.toString(), keys.containsAll(digits + listOf("+", "*", "#")))
        assertTrue(keys.toString(), listOf("/", "=").none { it in keys })
    }

    @Test
    @Uat("PAN-18")
    fun pinFieldsGetDigitsOnly() {
        val keys = keys(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        assertTrue(keys.toString(), keys.containsAll(digits))
        assertTrue(keys.toString(), listOf("+", "-", "*", "/", ".", ",", "=").none { it in keys })
        // the blank keys in their places: no press, nothing for TalkBack
        val blank = KeyboardHarness.all(h.keyboard).filterIsInstance<TextKeyView>()
            .filter { !it.isEnabled && it.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO }.toList()
        // three left of the digits, one right of 3, three in the bottom row
        assertEquals(7, blank.size)
        assertTrue(blank.all { it.mainText.text.isEmpty() })
    }

    @Test
    @Uat("PAN-19")
    fun decimalAndSignedFieldsKeepTheDotAndTheMinus() {
        val number = InputType.TYPE_CLASS_NUMBER
        fun has(flags: Int) = keys(number or flags).let { ("." in it) to ("-" in it) }
        // a plain number field (a price's maybe, a Compose or Flutter one filters nothing): the whole pad
        assertTrue(has(0) == (true to true))
        assertTrue(keys(number).containsAll(listOf("+", "*", "/", "=")))
        assertTrue(has(InputType.TYPE_NUMBER_FLAG_DECIMAL) == (true to false))
        assertTrue(has(InputType.TYPE_NUMBER_FLAG_SIGNED) == (false to true))
        assertTrue(has(InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED) == (true to true))
    }

    @Test
    @Uat("PAN-20")
    fun leavingANumberFieldGoesBackToTheLetters() {
        h.start()
        assertTrue(h.texts().toString(), letters())
        h.start(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE)
        assertTrue(h.texts().toString(), "/" in h.texts())
        h.start()
        assertTrue(h.texts().toString(), letters())
        // within the field, ABC then 123 is its own pad again; a text field's 123 the whole one
        h.start(InputType.TYPE_CLASS_PHONE)
        h.tap(h.key("ABC"))
        assertTrue(h.texts().toString(), letters())
        h.tap(h.key("123"))
        assertTrue(h.texts().toString(), "#" in h.texts() && "=" !in h.texts())
        h.start()
        h.tap(h.key("123"))
        assertTrue(h.texts().toString(), "=" in h.texts() && "#" !in h.texts())
    }
}
