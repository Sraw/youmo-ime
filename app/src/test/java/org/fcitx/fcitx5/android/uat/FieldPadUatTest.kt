/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.text.InputType
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.engine.assertRows
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
            assertTrue("$variation: $keys", keys.containsAll(digits + listOf("-", "/", ":", ".")) && "q" !in keys)
            assertTrue("$variation: $keys", listOf("+", "*", "=").none { it in keys })
            assertEquals(listOf("-", "/", ":"), column())
        }
        // the blank places take no press, type nothing, have nothing for TalkBack
        val shown = h.keys()
        assertTrue(shown.toString(), shown.none { !it.enabled && (it.label.isNotEmpty() || it.description != null) })
        val blanks = blanks(shown, listOf(RIGHT))
        // drawn nothing: each place's middle the keyboard's fill (a key's face is inset from its corner)
        val at = IntArray(2).also(h.keyboard::getLocationInWindow)
        val board = Bitmap.createBitmap(h.keyboard.width, h.keyboard.height, Bitmap.Config.ARGB_8888).also { h.keyboard.draw(Canvas(it)) }
        for (r in blanks) {
            val ground = board.getPixel(r.left - at[0] + 1, r.top - at[1] + 1)
            assertEquals("$r drawn on", Integer.toHexString(ground), Integer.toHexString(board.getPixel(r.centerX() - at[0], r.centerY() - at[1])))
        }
        pressedNothing(shown, blanks)
    }

    private fun blanks(shown: List<ShownKey>, wide: List<Float>): List<Rect> {
        val blanks = blankPlaces(shown)
        val width = h.keyboard.width
        assertEquals(blanks.toString(), wide.size, blanks.size)
        val shares = blanks.map { it.width().toFloat() / width }.sorted()
        for ((want, got) in wide.sorted().zip(shares)) assertEquals("$blanks", want, got, 2f / width)
        return blanks
    }

    private fun pressedNothing(shown: List<ShownKey>, blanks: List<Rect>) {
        h.api.calls.clear()
        val before = h.field.text.toString()
        for (at in blanks) {
            h.press(object : ShownKey by shown.first() {
                override val bounds get() = at
            })
            h.release()
            assertEquals("$at", emptyList<String>(), h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") })
            assertEquals("$at", before, h.field.text.toString())
        }
    }

    @Test
    @Uat("PAN-17")
    fun phoneFieldsGetPlusStarAndHash() {
        val keys = keys(InputType.TYPE_CLASS_PHONE)
        assertTrue(keys.toString(), keys.containsAll(digits) && "q" !in keys)
        assertEquals(listOf("+", "*", "#", "-", "(", ")", ","), column())
        assertTrue(keys.toString(), listOf("/", "=", ".").none { it in keys })
        val shown = h.keys()
        pressedNothing(shown, blanks(shown, listOf(RIGHT, DOT)))
    }

    private fun columnView() = KeyboardHarness.all(h.keyboard).filterIsInstance<ScrollView>().first { h.shown(it) }

    private fun column(): List<String> = (columnView().getChildAt(0) as ViewGroup).children.filterIsInstance<TextView>().filter { it.isVisible }.map { it.text.toString() }.toList()

    // the stretches along each row's middle no key covers, wider than a key's gap
    private fun blankPlaces(shown: List<ShownKey>): List<Rect> {
        // the scroll column is no key but no blank either
        val keys = shown.filter { it.enabled }.map { it.bounds } + h.box(columnView())
        val width = h.keyboard.width
        val rows = shown.filter { it.enabled }.map { it.bounds.top to it.bounds.bottom }.distinct()
        return rows.flatMap { (top, bottom) ->
            val y = (top + bottom) / 2
            val left = IntArray(2).also(h.keyboard::getLocationInWindow)[0]
            val free = (left until left + width).map { x -> keys.none { it.contains(x, y) } }
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

    private val full = digits + listOf("+", "-", "*", "/", ",", "=", ".")

    @Test
    @Uat("PAN-18")
    fun pinFieldsGetTheFullPad() {
        val keys = keys(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        assertTrue(keys.toString(), keys.containsAll(full) && "q" !in keys)
    }

    // what a field declares is no list of what it takes (an amount declared decimal takes a minus): the field drops the rest
    @Test
    @Uat("PAN-19", "PAN-35")
    fun everyNumberFieldGetsTheFullPadItsMinusSent() = assertRows(
        listOf(
            "a number field that says no more" to 0,
            "a decimal field" to InputType.TYPE_NUMBER_FLAG_DECIMAL,
            "a signed field" to InputType.TYPE_NUMBER_FLAG_SIGNED,
            "a signed decimal field" to (InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED),
        ),
        { it.first },
    ) { (_, flags) ->
        val keys = keys(InputType.TYPE_CLASS_NUMBER or flags)
        assertTrue(keys.toString(), keys.containsAll(full) && "q" !in keys)
        h.api.calls.clear()
        h.tap(KeyboardHarness.all(columnView()).filterIsInstance<TextView>().first { it.text.toString() == "-" })
        assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey") && "sym=${FcitxKeyMapping.FcitxKey_KP_Subtract}," in it })
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
        h.start(InputType.TYPE_CLASS_PHONE)
        h.tap(h.key(h.service.getString(R.string.picker_back)))
        assertTrue(h.texts().toString(), letters())
        h.tap(h.key("123"))
        assertTrue(h.texts().toString(), "#" in h.texts() && "%" !in h.texts())
        h.start()
        h.tap(h.key("123"))
        assertTrue(h.texts().toString(), "=" in h.texts() && "%" in h.texts())
    }

    private companion object {
        // a stretch this share of the width at least is a place, not the gap between two keys
        const val BLANK_AT_LEAST = 20

        // number.xml's widths: = and . (in 9's column)
        const val RIGHT = 0.18f
        const val DOT = 0.22f
    }
}
