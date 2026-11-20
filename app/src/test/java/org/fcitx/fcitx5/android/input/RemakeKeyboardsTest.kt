/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 youmo-ime Contributors
 */
package org.fcitx.fcitx5.android.input

import android.text.InputType
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.keyboard.LetterCaseChoice
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.keys.UserList
import org.fcitx.fcitx5.android.input.learning.UndoChip
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A keyboard setting remakes the keyboards in the existing input view: what shows must match a fresh view's. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class RemakeKeyboardsTest {

    @After
    fun close() = KeyboardHarness.setKeyboards(ImKeyboards.Chosen())

    private fun KeyboardHarness.seen(): List<Any> {
        relayout()
        val bar = KeyboardHarness.all(inputView).filter { shown(it) && it.contentDescription != null }.map { it.contentDescription.toString() to box(it) }.toList()
        // a mark left as the old setting had it shows here
        val keys = keys().map { k -> listOf(k.label, k.bounds, k.alt, k.drawnIcons, k.drawnTexts.map { it to k.drawn(it) }) }
        return listOf(keys, box(inputView.findViewById<View>(R.id.code_row_text)), bar)
    }

    private fun fresh(chosen: ImKeyboards.Chosen): List<Any> =
        KeyboardHarness(keyboards = chosen).use { it.start(); it.seen() }

    @Test
    fun eachKeyboardPickedShowsAsAViewMadeWithIt() {
        val picks = listOf(ImKeyboard.KEYS14, ImKeyboard.NINE_KEYS, ImKeyboard.KEYS18, ImKeyboard.FULL)
        val expected = picks.associateWith { fresh(ImKeyboards.Chosen(pinyin = it)) }
        KeyboardHarness(keyboards = ImKeyboards.Chosen()).use { h ->
            h.start()
            val view = h.inputView
            for (pick in picks) {
                KeyboardHarness.setKeyboards(ImKeyboards.Chosen(pinyin = pick))
                h.idle()
                assertSame("the view kept", view, h.inputView)
                assertEquals(pick.name, expected.getValue(pick), h.seen())
            }
        }
    }

    @Test
    fun theToolbarFoldsAsANewViewsWould() {
        KeyboardHarness(keyboards = ImKeyboards.Chosen()).use { h ->
            h.start()
            val expand = h.service.getString(R.string.expand_toolbar)
            h.tap(h.described(expand))
            h.after(500)
            KeyboardHarness.setKeyboards(ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14))
            h.after(500)
            h.relayout()
            assertTrue(KeyboardHarness.all(h.inputView).any { h.shown(it) && it.contentDescription == expand })
        }
    }

    @Test
    fun theMarksAndCaseRemakeTheKeysAndTheNumberRow() {
        val prefs = AppPrefs.getInstance().keyboard
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val up = prefs.swipeUpPosition.getValue()
        val case = prefs.letterCase.getValue()
        try {
            val changes = listOf<() -> Unit>(
                { prefs.swipeUpPosition.setValue(KeyMarkPosition.BottomRight) },
                { prefs.letterCase.setValue(LetterCaseChoice.entries.first { it != case }) },
            )
            KeyboardHarness().use { h ->
                h.start(password)
                val view = h.inputView
                for (change in changes) {
                    val row = rowOf(h)
                    change()
                    h.idle()
                    assertSame(view, h.inputView)
                    assertTrue("the number row made anew", rowOf(h) !== row)
                    val expected = KeyboardHarness().use { f -> f.start(password); f.seen() }
                    assertEquals(expected, h.seen())
                }
            }
        } finally {
            prefs.swipeUpPosition.setValue(up)
            prefs.letterCase.setValue(case)
        }
    }

    private fun rowOf(h: KeyboardHarness): View? =
        KeyboardHarness.all(h.inputView).firstOrNull { it is org.fcitx.fcitx5.android.input.bar.ui.idle.NumberRow }

    /** Changed while hidden: next a number field, then a password one. */
    @Test
    fun changedWhileHiddenShowsAsANewViewInTheNextField() {
        val nine = ImKeyboards.Chosen(pinyin = ImKeyboard.NINE_KEYS)
        val fields = listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val expected = fields.map { f -> KeyboardHarness(keyboards = nine).use { it.start(f); it.seen() } }
        KeyboardHarness(keyboards = ImKeyboards.Chosen()).use { h ->
            h.start()
            h.hide()
            KeyboardHarness.setKeyboards(nine)
            h.idle()
            fields.forEachIndexed { i, f ->
                h.start(f)
                assertEquals("field $f", expected[i], h.seen())
            }
        }
    }

    @Test
    fun changedWithAPanelOpenGoesBackToTheKeys() {
        val expected = fresh(ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14))
        KeyboardHarness(keyboards = ImKeyboards.Chosen()).use { h ->
            h.start()
            h.tap(h.described(h.service.getString(R.string.expand_toolbar)))
            h.after(500)
            h.tap(h.described(h.service.getString(R.string.clipboard)))
            h.after(500)
            KeyboardHarness.setKeyboards(ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14))
            h.after(500)
            assertEquals(expected, h.seen())
        }
    }

    /** The letters remake the keyboard; key_lists only redraw it (BaseKeyboard.onKeyListsChanged), a long press reading the list as held. */
    @Test
    fun theNineKeysLettersAndLongPressesShowAsAFreshOne() {
        val prefs = AppPrefs.getInstance().keyboard
        val lists = AppPrefs.getInstance().internal.keyLists
        val nine = ImKeyboards.Chosen(pinyin = ImKeyboard.NINE_KEYS)
        val letters = NineKeyLetters.of(listOf("abc", "def", "gi", "jkl", "mnoh", "pqrs", "tuv", "wxyz")).serialize()
        try {
            KeyboardHarness(keyboards = nine).use { h ->
                h.start()
                for ((pref, value) in listOf(prefs.t9Letters to letters, lists to KeyLists.of(mapOf(KeyId("t9/2") to UserList(off = false, items = listOf("⒉")))).serialize())) {
                    pref.setValue(value)
                    h.idle()
                    val expected = KeyboardHarness(keyboards = nine).use { it.start(); it.seen() }
                    assertEquals(pref.key, expected, h.seen())
                }
            }
        } finally {
            prefs.t9Letters.setValue("")
            lists.setValue(KeyLists.HEADER)
        }
    }

    /** 忘记's 撤销 chip goes with the keyboards it was over. */
    @Test
    fun theUndoChipGoesWithTheKeyboards() {
        KeyboardHarness(keyboards = ImKeyboards.Chosen()).use { h ->
            h.start()
            h.service.say("已忘记", UndoChip.Undo({}))
            h.after(300)
            assertTrue("the chip shown", h.notice() != null)
            KeyboardHarness.setKeyboards(ImKeyboards.Chosen(pinyin = ImKeyboard.NINE_KEYS))
            h.after(300)
            assertEquals(null, h.notice())
        }
    }
}
