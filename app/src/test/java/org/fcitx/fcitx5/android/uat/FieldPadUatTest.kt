/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.text.InputType
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.ShownKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

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
        // the blank places: three left of the digits, one right of 9, three in the bottom row; each takes no
        // press, types nothing, has nothing for TalkBack (a blank view disabled and not read, the canvas's no node)
        val shown = h.keys()
        assertTrue(shown.toString(), shown.none { !it.enabled && (it.label.isNotEmpty() || it.description != null) })
        val stretches = blankPlaces(shown)
        // the bottom row's right two side by side make one stretch (no key between): split where the
        // middle one ends, the seven places of the seven blank keys
        val width = h.keyboard.width
        val blanks = stretches.flatMap { r ->
            if (abs(r.width() - width * (BOTTOM_MIDDLE + BOTTOM_RIGHT)) > 2) return@flatMap listOf(r)
            val middle = r.left + (width * BOTTOM_MIDDLE).roundToInt()
            listOf(Rect(r.left, r.top, middle, r.bottom), Rect(middle, r.top, r.right, r.bottom))
        }
        assertEquals(stretches.toString(), 7, blanks.size)
        val wide = listOf(LEFT, LEFT, LEFT, RIGHT, BOTTOM_LEFT, BOTTOM_MIDDLE, BOTTOM_RIGHT)
        val shares = blanks.map { it.width().toFloat() / width }.sorted()
        for ((want, got) in wide.sorted().zip(shares)) assertEquals("$blanks", want, got, 2f / width)
        // drawn nothing (0.6.1, user 2026-11-13): each place's middle the keyboard's fill, as its corner is (a key's
        // face is inset from its corner; 0.6.0 drew a faded one there)
        val at = IntArray(2).also(h.keyboard::getLocationInWindow)
        val board = Bitmap.createBitmap(h.keyboard.width, h.keyboard.height, Bitmap.Config.ARGB_8888).also { h.keyboard.draw(Canvas(it)) }
        for (r in blanks) {
            val ground = board.getPixel(r.left - at[0] + 1, r.top - at[1] + 1)
            assertEquals("$r drawn on", Integer.toHexString(ground), Integer.toHexString(board.getPixel(r.centerX() - at[0], r.centerY() - at[1])))
        }
        h.api.calls.clear()
        val before = h.field.text.toString()
        // each place tapped once: nothing sent, nothing typed
        for (at in blanks) {
            h.press(object : ShownKey by shown.first() {
                override val bounds get() = at
            })
            h.release()
            assertEquals("$at", emptyList<String>(), h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") })
            assertEquals("$at", before, h.field.text.toString())
        }
    }

    // along each row's middle, the stretches no key that takes a press covers (wider than a key's gap): a
    // place of the pad's blank, as its view or the canvas's gap
    private fun blankPlaces(shown: List<ShownKey>): List<Rect> {
        val keys = shown.filter { it.enabled }
        val width = h.keyboard.width
        val rows = keys.map { it.bounds.top to it.bounds.bottom }.distinct()
        return rows.flatMap { (top, bottom) ->
            val y = (top + bottom) / 2
            val left = IntArray(2).also(h.keyboard::getLocationInWindow)[0]
            val free = (left until left + width).map { x -> keys.none { it.bounds.contains(x, y) } }
            // runs of free pixels
            buildList {
                var start = -1
                for (i in 0..free.size) {
                    if (i < free.size && free[i]) {
                        if (start < 0) start = i
                    } else if (start >= 0) {
                        if (i - start > width / BLANK_AT_LEAST) add(Rect(left + start, top, left + i, bottom))
                        start = -1
                    }
                }
            }
        }
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
        // within the field, 返回 then 123 is its own pad again; a text field's 123 the whole one
        h.start(InputType.TYPE_CLASS_PHONE)
        h.tap(h.key(h.service.getString(R.string.picker_back)))
        assertTrue(h.texts().toString(), letters())
        h.tap(h.key("123"))
        assertTrue(h.texts().toString(), "#" in h.texts() && "=" !in h.texts())
        h.start()
        h.tap(h.key("123"))
        assertTrue(h.texts().toString(), "=" in h.texts() && "#" !in h.texts())
    }

    private companion object {
        // a stretch this share of the width at least is a place, not the gap between two keys
        const val BLANK_AT_LEAST = 20

        // number.xml's widths, the nine keys' frame (0.6.1): the left column's, the right one's (right of 9), the
        // bottom row's beside 返回, right of 0, and beside Return
        const val LEFT = 0.16f
        const val RIGHT = 0.18f
        const val BOTTOM_LEFT = 0.08f
        const val BOTTOM_MIDDLE = 0.12f
        const val BOTTOM_RIGHT = 0.1f
    }
}
