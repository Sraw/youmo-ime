/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Shift on by itself at a sentence start ([TextKeyboard.applyAutoCaps]), as [AutoCaps] decides: one
 * letter capitalised, then lower case again; off again where no sentence starts; Shift or caps lock
 * the user turned on left as they are. Each state goes to AutoCaps and back as itself, whatever
 * order either enum declares them in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class TextKeyboardCapsTest {

    private val typed = ArrayList<String>()

    private val keyboard = TextKeyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), TestThemes.PixelDark).apply {
        keyActionListener = KeyActionListener { action, _ -> if (action is KeyAction.HostKeyAction) typed += action.act }
    }.laidOut()

    private fun type(letter: String): String {
        // laid out again: the scene made anew for what Shift or a sentence start changed
        keyboard.laidOut().shownKeys().first { it.id == letter.lowercase() }.click()
        return typed.last()
    }

    // Shift tapped, or long pressed (caps lock) as a screen reader does it on its node
    private fun shift(long: Boolean = false) {
        val key = keyboard.shownKeys().first { it.id == "shift" } as SceneShownKey
        if (!long) {
            key.click()
            return
        }
        val c = key.canvas
        c.accessibilityNodeProvider!!.performAction(c.scene!!.keys.indexOfFirst { it.id == "shift" }, AccessibilityNodeInfo.ACTION_LONG_CLICK, null)
    }

    @Test
    fun aSentenceStartCapitalisesOneLetter() {
        keyboard.applyAutoCaps(true)
        assertEquals("Q", type("Q"))
        assertEquals("q", type("Q"))
        // a letter deleted back: no sentence starts there
        keyboard.applyAutoCaps(true)
        keyboard.applyAutoCaps(false)
        assertEquals("q", type("Q"))
    }

    @Test
    fun theUsersShiftAndCapsLockStayWhereNoSentenceStarts() {
        shift()
        keyboard.applyAutoCaps(false)
        assertEquals("Q", type("Q"))
        shift(long = true)
        keyboard.applyAutoCaps(true)
        keyboard.applyAutoCaps(false)
        assertEquals("Q", type("Q"))
        assertEquals("Q", type("Q"))
    }

    // every row's letters, the bottom one too: the 双拼 `;` key after L once ended the pass there
    private fun letters() = keyboard.laidOut().shownKeys().sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        .map { it.label }.filter { it.length == 1 && it[0] in 'a'..'z' || it in ('A'..'Z').map(Char::toString) }.joinToString("")

    @Test
    fun everyLetterFollowsShift() {
        shift()
        assertEquals("QWERTYUIOPASDFGHJKLZXCVBNM", letters())
        shift()
        assertEquals("qwertyuiopasdfghjklzxcvbnm", letters())
    }

    // a key of no name (a theme's letter without shortcut=): its built-in list in capitals under Shift, as its face
    @Test
    fun aKeyOfNoNamesListFollowsShift() {
        val shown = ArrayList<PopupAction>()
        keyboard.popupActionListener = PopupActionListener { shown += it }
        val ref = KeyRef.scene("nameless", 0)
        keyboard.onPopupAction(PopupAction.ShowKeyboardAction(ref, KeyDef.Popup.Keyboard.Preset("q"), Rect()))
        shift()
        keyboard.onPopupAction(PopupAction.ShowKeyboardAction(ref, KeyDef.Popup.Keyboard.Preset("q"), Rect()))
        val presets = shown.filterIsInstance<PopupAction.ShowKeyboardAction>().map { it.keyboard as KeyDef.Popup.Keyboard.Preset }
        assertEquals(listOf(false, true), presets.map { it.caps })
    }
}
