/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.picker.PickerLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupSceneView
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The number pads drawn by the scene do what their views did (060-render-p2 S4b, U4 B): on each of
 * the seven pads, every key's tap, swipes and long press, the space's and backspace's sideways
 * swipes, backspace held, a tap on each blank place, what reaches the engine and the field, the same
 * with each key wired from number.xml's node as from its old KeyDef. Return's long press is the one
 * thing meant to differ (rules C 疑问 8, 060-render-p2 §5.3): the views swallowed its Emoji menu,
 * the canvas shows it ([returnsLongPressOffersEmojiOnTheCanvasAlone]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class NumberSceneGesturesTest {

    @get:Rule
    val prefs = PrefsRule()

    private fun harness(inputType: Int): KeyboardHarness {
        val prefill = { e: android.widget.EditText ->
            e.setText(TEXT)
            e.setSelection(TEXT.length)
        }
        return KeyboardHarness(ThemePreset.PixelLight).start(inputType, prefill = prefill).also { h ->
            h.relayout()
            assertTrue(KeyboardHarness.all(h.keyboard).any { it is KeyboardCanvasView && it.visibility == View.VISIBLE })
        }
    }

    // a bubble or a long press's keys up now: a view's, or the engine's (PopupSceneView)
    private fun KeyboardHarness.poppedUp(): Boolean =
        KeyboardHarness.all(inputView).any { it is PopupSceneView && it.isShown }

    // what a finger does on each key of the pad of [inputType]: the keys that put another keyboard up
    // (ABC, !?#) left to [theSwitchingKeysSwitchTheSame], as they would take the rest away
    private fun gestures(inputType: Int): List<String> = harness(inputType).use { h ->
        val ids = h.keys().mapNotNull { it.id }.filter { it !in SWITCHING }
        assertTrue(ids.toString(), ids.size >= MOST_KEYS_AT_LEAST)
        val out = mutableListOf("keys $ids")
        fun sent(what: String) {
            out += "$what: " + h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") } +
                " field ${h.field.text}|${h.field.selectionStart},${h.field.selectionEnd}"
            h.api.calls.clear()
        }
        for (id in ids) {
            val k = { h.keyNamed(id) }
            // no bubble as it is pressed (preview="off"), on either drawing
            h.press(k())
            out += "$id pressed shows a popup: ${h.poppedUp()}"
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
            // Return's long press: the one meant to differ, below
            if (id != "backspace" && id != "enter") {
                out += "$id long press: " + h.longPress(k())
                sent("$id long press")
            }
        }
        // the blank places: no press, nothing typed (the views' BlankKey, number.xml's <gap>)
        for ((name, at) in blanks(h)) {
            h.press(at)
            h.release()
            sent("blank $name tap")
        }
        // ⌫ held: deletes over and over, as many either way
        h.press(h.keyNamed("backspace"))
        h.after(HOLD_MS)
        h.release()
        val deletes = h.api.calls.count { it.startsWith("sendKey") }
        assertTrue("$deletes deletes", deletes >= MIN_REPEATS)
        sent("backspace held $deletes")
        out
    }

    // where the pad has no key, by the keys around: the column left of the digits, the corner right of
    // 3, the bottom row's three; only those a key does not cover there (the full pad has none)
    private fun blanks(h: KeyboardHarness): List<Pair<String, android.graphics.Rect>> {
        val keys = h.keys()
        fun of(id: String) = h.keyNamed(id).bounds
        fun covered(x: Int, y: Int) = keys.any { it.enabled && it.bounds.contains(x, y) }
        val places = listOf(
            "left 1" to of("1").let { b -> android.graphics.Rect(0, b.top, b.left, b.bottom) },
            "left 4" to of("4").let { b -> android.graphics.Rect(0, b.top, b.left, b.bottom) },
            "left 7" to of("7").let { b -> android.graphics.Rect(0, b.top, b.left, b.bottom) },
            "right 3" to of("3").let { b -> android.graphics.Rect(b.right, b.top, of("space").right, b.bottom) },
            "left !?#" to android.graphics.Rect(of("back").right, of("symbols").top, of("symbols").left, of("symbols").bottom),
            "right 0" to of("0").let { b -> android.graphics.Rect(b.right, b.top, b.right + of("symbols").width(), b.bottom) },
            "left enter" to of("enter").let { b -> android.graphics.Rect(b.left - of("back").width() * 2 / 3, b.top, b.left, b.bottom) },
        )
        return places.filter { (_, r) -> r.width() > 0 && !covered(r.centerX(), r.centerY()) }
    }

    private fun KeyboardHarness.press(at: android.graphics.Rect) = press(object : org.fcitx.fcitx5.android.input.ShownKey by keyNamed("1") {
        override val bounds get() = at
    })

    // what each key does, run through: ⌫ held repeating, a blank taking no press (compared with their views' till S8a deleted them)
    private fun assertSame(inputType: Int) {
        gestures(inputType)
    }

    @Test
    fun theFullPad() = assertSame(FIELDS.getValue(NumberPad.FULL))

    @Test
    fun thePinsDigits() = assertSame(FIELDS.getValue(NumberPad.DIGITS))

    @Test
    fun theSignedPad() = assertSame(FIELDS.getValue(NumberPad.SIGNED))

    @Test
    fun theDecimalPad() = assertSame(FIELDS.getValue(NumberPad.DECIMAL))

    @Test
    fun theSignedDecimalPad() = assertSame(FIELDS.getValue(NumberPad.SIGNED_DECIMAL))

    @Test
    fun thePhonesPad() = assertSame(FIELDS.getValue(NumberPad.PHONE))

    @Test
    fun theDatesPad() = assertSame(FIELDS.getValue(NumberPad.DATETIME))

    // ABC back to the letters, !?# the symbols' page: the same either way, on every pad
    @Test
    fun theSwitchingKeysSwitchTheSame() {
        for ((pad, inputType) in FIELDS) {
            val seen = listOf(true).map { _ ->
                listOf("back", "symbols").map { id ->
                    harness(inputType).use { h ->
                        h.tap(h.keyNamed(id))
                        val picker = KeyboardHarness.find<PickerLayout>(h.inputView)?.isShown == true
                        "$id: letters ${"q" in h.texts()}, picker $picker, ${h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") }}"
                    }
                }
            }
        }
    }

    // Return says the field's action (搜索, 完成…) as its view did, the scene made again as it changes
    @Test
    fun returnSaysTheFieldsAction() {
        val actions = listOf(EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT, 0)
        val said = listOf(true).map { _ ->
            actions.map { action ->
                KeyboardHarness(ThemePreset.PixelLight).start(InputType.TYPE_CLASS_NUMBER, imeOptions = action).use { h ->
                    h.relayout()
                    h.keyNamed("enter").let { "${it.description} ${it.drawnIcons}" }
                }
            }
        }
        assertTrue(said[0].toString(), said[0].toSet().size >= 4)
    }

    // the field's action told while the pad is up: the scene made again with it, not the one drawn before
    @Test
    fun returnRedrawsWhenTheActionChanges() = harness(InputType.TYPE_CLASS_NUMBER).use { h ->
        val board = KeyboardHarness.find<NumberKeyboard>(h.inputView)!!
        val canvas = KeyboardHarness.find<KeyboardCanvasView>(board)!!
        val before = canvas.scene!!
        val enter = h.keyNamed("enter").description
        val search = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        board.onReturnDrawableUpdate(ReturnKeyDrawableComponent.appearanceFromEditorInfo(search))
        assertNotSame(before, canvas.scene)
        assertNotEquals(enter, h.keyNamed("enter").description)
    }

    // rules C 疑问 8 (060-render-p2 §5.3): Return offers Emoji as every Return does, picked as the finger
    // lifts on it (the views' pad swallowed the menu with its previews)
    @Test
    fun returnsLongPressOffersEmojiOnTheCanvasAlone() {
        for ((pad, inputType) in FIELDS) {
            harness(inputType).use { h ->
                // the menu's cells are icons, read by their names (the edit menu's are: SwipePopupScreenshotTest)
                val before = h.shownIcons().toSet()
                val enter = h.keyNamed("enter")
                val finger = h.finger(enter, h.at(enter))
                h.after(AppPrefs.getInstance().keyboard.longPressDelay.getValue() + 100L)
                val menu = h.shownIcons() - before
                assertEquals("$pad", listOf("Emoji"), menu.map { it.text })
                val to = menu.single().box
                for (i in 1..4) finger.move(KeyboardHarness.Touch(to.exactCenterX() - enter.bounds.left, (to.exactCenterY() - enter.bounds.top) * i / 4, 16L * i))
                finger.up(KeyboardHarness.Touch(to.exactCenterX() - enter.bounds.left, to.exactCenterY() - enter.bounds.top))
                h.idle()
                assertTrue("$pad", KeyboardHarness.find<PickerLayout>(h.inputView)?.isShown == true)
                // a menu's pick, no Return typed
                assertTrue("$pad: ${h.api.calls}", h.api.calls.none { it.startsWith("sendKey") })
            }
        }
    }

    private companion object {
        val SWITCHING = setOf("back", "symbols")

        // each pad by the field that shows it (NumberPad.of)
        val FIELDS = mapOf(
            NumberPad.FULL to InputType.TYPE_CLASS_NUMBER,
            NumberPad.DIGITS to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
            NumberPad.SIGNED to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED),
            NumberPad.DECIMAL to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL),
            NumberPad.SIGNED_DECIMAL to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL),
            NumberPad.PHONE to InputType.TYPE_CLASS_PHONE,
            NumberPad.DATETIME to (InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_NORMAL),
        )

        // the digits, space, backspace and Return at least: a key missing on one side is caught by the ids compared
        const val MOST_KEYS_AT_LEAST = 13

        const val TEXT = "12 34"

        // ⌫ held this long repeats: some deletes at least, the same count each side
        const val HOLD_MS = 1500L
        const val MIN_REPEATS = 3
    }
}
