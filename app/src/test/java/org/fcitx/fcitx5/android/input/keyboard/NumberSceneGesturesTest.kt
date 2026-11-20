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
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.picker.PickerLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupSceneView
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * On each of the seven pads: every key's gestures, ⌫ held, a tap on each blank place, held to the committed record
 * ([GestureSnapshot]); no key pops a bubble, a blank sends nothing; Return's long press offers Emoji.
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
        return KeyboardHarness(TestThemes.PixelLight).start(inputType, prefill = prefill).also { h ->
            h.relayout()
            assertTrue(KeyboardHarness.all(h.keyboard).any { it is KeyboardCanvasView && it.visibility == View.VISIBLE })
        }
    }

    private fun KeyboardHarness.poppedUp(): Boolean =
        KeyboardHarness.all(inputView).any { it is PopupSceneView && it.isShown }

    // 返回 and 符 left to [theSwitchingKeysSwitch]: they would take the rest away
    private fun gestures(inputType: Int): List<String> = harness(inputType).use { h ->
        val ids = h.keys().mapNotNull { it.id }.filter { it !in SWITCHING }
        assertTrue(ids.toString(), ids.size >= MOST_KEYS_AT_LEAST)
        val out = mutableListOf("keys $ids")
        // ⌫ last: its swipe up clears the field the others' swipes act on
        h.recordGestures(out, ids.sortedBy { it == "backspace" }, held = { it != "backspace" && it != "enter" }) {
            assertFalse("$it pressed shows a popup", h.poppedUp())
        }
        for ((name, at) in blanks(h)) {
            h.press(at)
            h.release()
            assertTrue("blank $name: ${h.api.calls}", h.api.calls.none { it.startsWith("sendKey") || it.startsWith("commit") })
            h.sent(out, "blank $name tap")
        }
        h.backspaceHeld(out, HOLD_MS, MIN_REPEATS)
        out
    }

    // where the pad has no key, by the keys around (the full pad has none)
    private fun blanks(h: KeyboardHarness): List<Pair<String, android.graphics.Rect>> {
        val keys = h.keys()
        fun of(id: String) = h.keyNamed(id).bounds
        fun covered(x: Int, y: Int) = keys.any { it.enabled && it.bounds.contains(x, y) }
        val places = listOf(
            "left 1" to of("1").let { b -> android.graphics.Rect(0, b.top, b.left, b.bottom) },
            "left 4" to of("4").let { b -> android.graphics.Rect(0, b.top, b.left, b.bottom) },
            "left 7" to of("7").let { b -> android.graphics.Rect(0, b.top, b.left, b.bottom) },
            "right 9" to of("9").let { b -> android.graphics.Rect(b.right, b.top, of("space").right, b.bottom) },
            // as wide as number.xml's: 符 16 返回 14 | 8 | 0 22 | 12 | 10 | ⏎ 18
            "left 0" to of("0").let { b -> android.graphics.Rect(of("back").right, b.top, b.left, b.bottom) },
            "right 0" to of("0").let { b -> android.graphics.Rect(b.right, b.top, b.right + b.width() * 12 / 22, b.bottom) },
            "left enter" to of("enter").let { b -> android.graphics.Rect(b.left - of("0").width() * 10 / 22, b.top, b.left, b.bottom) },
        )
        return places.filter { (_, r) -> r.width() > 0 && !covered(r.centerX(), r.centerY()) }
    }

    private fun KeyboardHarness.press(at: android.graphics.Rect) = press(object : org.fcitx.fcitx5.android.input.ShownKey by keyNamed("1") {
        override val bounds get() = at
    })

    @Test
    fun everyPadsKeysDoWhatTheyDid() = assertRows(FIELDS.entries, { (pad, _) -> "$pad" }) { (pad, inputType) ->
        GestureSnapshot.check("number-$pad", gestures(inputType))
    }

    @Test
    fun theSwitchingKeysSwitch() = assertRows(FIELDS.entries, { (pad, _) -> "$pad" }) { (_, inputType) ->
        for (id in listOf("back", "symbols")) {
            harness(inputType).use { h ->
                h.tap(h.keyNamed(id))
                val picker = KeyboardHarness.find<PickerLayout>(h.inputView)?.isShown == true
                if (id == "back") assertTrue("返回: the letters", "q" in h.texts()) else assertTrue("符: the symbols' page", picker)
                assertTrue("$id: ${h.api.calls}", h.api.calls.none { it.startsWith("sendKey") || it.startsWith("commit") })
            }
        }
    }

    @Test
    fun returnSaysTheFieldsAction() {
        val actions = listOf(EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT, 0)
        val said = actions.map { action ->
            KeyboardHarness(TestThemes.PixelLight).start(InputType.TYPE_CLASS_NUMBER, imeOptions = action).use { h ->
                h.relayout()
                h.keyNamed("enter").let { "${it.description} ${it.drawnIcons}" }
            }
        }
        assertTrue(said.toString(), said.toSet().size >= 4)
    }

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

    // picked as the finger lifts on it (the views' pad swallowed the menu with its previews)
    @Test
    fun returnsLongPressOffersEmojiOnTheCanvasAlone() {
        for ((pad, inputType) in FIELDS) {
            harness(inputType).use { h ->
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
                assertTrue("$pad: ${h.api.calls}", h.api.calls.none { it.startsWith("sendKey") })
            }
        }
    }

    private companion object {
        val SWITCHING = setOf("back", "symbols")

        val FIELDS = mapOf(
            NumberPad.FULL to InputType.TYPE_CLASS_NUMBER,
            NumberPad.DIGITS to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
            NumberPad.SIGNED to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED),
            NumberPad.DECIMAL to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL),
            NumberPad.SIGNED_DECIMAL to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL),
            NumberPad.PHONE to InputType.TYPE_CLASS_PHONE,
            NumberPad.DATETIME to (InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_NORMAL),
        )

        const val MOST_KEYS_AT_LEAST = 13

        const val TEXT = "12 34"

        const val HOLD_MS = 1500L
        const val MIN_REPEATS = 3
    }
}
