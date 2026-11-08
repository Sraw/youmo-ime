/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.app.Activity
import android.os.Looper
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.nineSpCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 九键双拼 (design §3.4): the nine keys' layout, its digit keys sending [NineKeyLetters.NINE_SP]'s
 * codes, 1 the separator `'` while typing; no 中/英, no 连按.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class T9ShuangpinKeyboardTest {

    private val context = RuntimeEnvironment.getApplication()
    private val keys = KeyActionListener.Source.Keyboard

    private fun code(c: Char) = KeyAction.SymAction(KeySym(UNICODE + c.code), KeyStates.Virtual)

    @Test
    fun theDigitKeysShowTheStandardLettersAndJklSemicolon() {
        val faces = T9Keyboard.layout(NineKeyLetters.STANDARD, shuangpin = true).flatten()
            .mapNotNull { (it.appearance as? KeyDef.Appearance.AltText)?.displayText }
        assertEquals(listOf("，。？！", "ABC", "DEF", "GHI", "JKL;", "MNO", "PQRS", "TUV", "WXYZ"), faces)
    }

    @Test
    fun aDigitKeySendsItsGroupsCodeEveryPressItsOwn() {
        val tap = MultiTap(400)
        for (d in '2'..'9') {
            assertEquals(code(NineKeyLetters.NINE_SP.codes[d - '2']), T9Keyboard.resolveShuangpin(KeyAction.HostKeyAction("$d"), keys, tap, 0L, composing = false))
        }
        // pressed again soon: no 连按, the same code again
        assertEquals(code(nineSpCode('2')), T9Keyboard.resolveShuangpin(KeyAction.HostKeyAction("2"), keys, tap, 10L, composing = true))
        assertEquals(code(nineSpCode('2')), T9Keyboard.resolveShuangpin(KeyAction.HostKeyAction("2"), keys, tap, 20L, composing = true))
    }

    @Test
    fun oneIsTheSeparatorWhileTypingElsePunctuation() {
        val tap = MultiTap(0)
        assertEquals(KeyAction.HostKeyAction("'"), T9Keyboard.resolveShuangpin(KeyAction.HostKeyAction("1"), keys, tap, 0L, composing = true))
        assertEquals(KeyAction.CommitAction("，"), T9Keyboard.resolveShuangpin(KeyAction.HostKeyAction("1"), keys, tap, 0L, composing = false))
    }

    @Test
    fun theLongPressAndOtherKeysAreAsNineKeysHasThem() {
        val tap = MultiTap(0)
        val popup = KeyActionListener.Source.Popup
        assertEquals(KeyAction.CommitAction("j"), T9Keyboard.resolveShuangpin(KeyAction.HostKeyAction("j"), popup, tap, 0L, composing = true))
        assertEquals(KeyAction.CommitAction("5"), T9Keyboard.resolveShuangpin(KeyAction.HostKeyAction("5"), popup, tap, 0L, composing = true))
        assertEquals(KeyAction.HostKeyAction("0"), T9Keyboard.resolveShuangpin(KeyAction.HostKeyAction("0"), keys, tap, 0L, composing = true))
    }

    @Test
    fun noLanguageKeyAndTheColumnOffersShuangpinsSyllablesNotNineKeys() {
        val keyboard = T9Keyboard(context, ThemePreset.PixelDark, shuangpin = true)
        assertTrue(keyboard.laidOut().shownKeyWithId(R.id.button_space) != null)
        assertEquals(true, keyboard.ownsSyllables)
        val items = (0 until keyboard.childCount).map { keyboard.getChildAt(it) }.filterIsInstance<ScrollView>().single().getChildAt(0) as ViewGroup
        fun column() = (0 until items.childCount).map { items.getChildAt(it) as TextView }.filter { it.isVisible }.map { it.text.toString() }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(keyboard)
        val looper = shadowOf(Looper.getMainLooper())
        try {
            // the column: 双拼's syllables while typing, as 九键's
            EngineState.syllablesState.value = EngineState.SyllableState(InputMethodCatalog.SHUANGPIN, composing = true, syllables = listOf("ni", "mi"), id = 1)
            looper.idle()
            assertEquals(listOf("ni", "mi"), column())
            // pinyin's answer is another input method's here: not taken
            EngineState.syllablesState.value = EngineState.SyllableState(InputMethodCatalog.PINYIN, composing = true, syllables = listOf("bu", "nu"), id = 2)
            looper.idle()
            assertEquals(T9Keyboard.labels(EngineState.T9State()), column())
        } finally {
            EngineState.syllablesState.value = EngineState.SyllableState()
        }
    }
}
