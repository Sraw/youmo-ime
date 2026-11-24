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
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.Near
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.pinyin.nineSpCode
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 九键双拼: digit keys send [NineKeyLetters.NINE_SP]'s codes, 1 the separator `'` while typing; no 中/英, no 连按. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class T9ShuangpinKeyboardTest {

    private val context = RuntimeEnvironment.getApplication()
    private val keys = KeyActionListener.Source.Keyboard

    private fun code(c: Char) = KeyAction.SymAction(KeySym(UNICODE + c.code), KeyStates.Virtual)

    @Test
    fun theDigitKeysShowTheStandardLetters() {
        fun keys(semicolon: Boolean) = T9Keyboard(context, TestThemes.PixelDark, shuangpin = true, semicolon = semicolon).laidOut().shownKeys()
        val digits = keys(true).filter { it.alt != null }
        assertEquals(listOf(",.?!", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ"), digits.map { it.label })
        assertEquals("1", digits.first().alt)
        assertEquals("1", keys(false).first { it.alt != null }.alt)
    }

    @Test
    fun aDigitKeySendsItsGroupsCodeEveryPressItsOwn() {
        val tap = MultiTap(400)
        for (d in '2'..'9') {
            assertEquals(code(NineKeyLetters.NINE_SP.codes[d - '2']), T9Keyboard.resolveShuangpin(KeyAction.HostKeyAction("$d"), keys, tap, 0L, composing = false, semicolon = false))
        }
        assertEquals(code(nineSpCode('2')), T9Keyboard.resolveShuangpin(KeyAction.HostKeyAction("2"), keys, tap, 10L, composing = true, semicolon = false))
        assertEquals(code(nineSpCode('2')), T9Keyboard.resolveShuangpin(KeyAction.HostKeyAction("2"), keys, tap, 20L, composing = true, semicolon = false))
    }

    // a digit's tap goes with the digits around as their NINE_SP codes; 1 none
    @Test
    @Uat("SP-50")
    fun aDigitKeysTapGoesWithWhereItFellAndTheKeysAroundItByTheirCodes() {
        val touch = Touch(0f, -0.45f, 72f, 50f, listOf(Near(nineSpCode('6'), 0f, -1f)))
        val sent = T9Keyboard.resolveShuangpin(KeyAction.HostKeyAction("9", touch = touch), keys, MultiTap(0), 0L, composing = true, semicolon = false)
        assertEquals(code(nineSpCode('9')).copy(touch = touch), sent)
        val keyboard = T9Keyboard(context, TestThemes.PixelDark, shuangpin = true, semicolon = true)
        assertEquals(('2'..'9').map { nineSpCode(it) to KeyRef(it.toString()) }, keyboard.typingKeys())
        assertEquals(('2'..'9').toList(), T9Keyboard(context, TestThemes.PixelDark, semicolon = false).typingKeys().map { it.first })
    }

    private val zh = mapOf("," to "，")

    @Test
    fun oneIsTheSeparatorWhileTypingElsePunctuation() {
        val tap = MultiTap(0)
        assertEquals(KeyAction.HostKeyAction("'"), T9Keyboard.resolveShuangpin(KeyAction.HostKeyAction("1"), keys, tap, 0L, composing = true, semicolon = false))
        assertEquals(KeyAction.CommitAction("，", punctuation = true), T9Keyboard.resolveShuangpin(KeyAction.HostKeyAction("1"), keys, tap, 0L, composing = false, semicolon = false, marks = zh))
    }

    // 微软's: 1 while typing is ;'s code (ing or the separator); else punctuation as ever
    @Test
    fun oneIsTheSemicolonsCodeWhileTypingOnMicrosofts() {
        val tap = MultiTap(0)
        val one = KeyAction.HostKeyAction("1")
        assertEquals(code(NineKeyLetters.NINE_SP_SEMICOLON), T9Keyboard.resolveShuangpin(one, keys, tap, 0L, composing = true, semicolon = true))
        assertEquals(KeyAction.CommitAction("，", punctuation = true), T9Keyboard.resolveShuangpin(one, keys, tap, 0L, composing = false, semicolon = true, marks = zh))
    }

    @Test
    fun theLongPressAndOtherKeysAreAsNineKeysHasThem() {
        val tap = MultiTap(0)
        val popup = KeyActionListener.Source.Popup
        assertEquals(KeyAction.CommitAction("j"), T9Keyboard.resolveShuangpin(KeyAction.HostKeyAction("j"), popup, tap, 0L, composing = true, semicolon = false))
        assertEquals(KeyAction.CommitAction("5"), T9Keyboard.resolveShuangpin(KeyAction.HostKeyAction("5"), popup, tap, 0L, composing = true, semicolon = false))
        assertEquals(KeyAction.HostKeyAction("0"), T9Keyboard.resolveShuangpin(KeyAction.HostKeyAction("0"), keys, tap, 0L, composing = true, semicolon = false))
    }

    @Test
    fun noLanguageKeyAndTheColumnOffersShuangpinsSyllablesNotNineKeys() {
        val keyboard = T9Keyboard(context, TestThemes.PixelDark, shuangpin = true, semicolon = true)
        assertTrue(keyboard.laidOut().shownKeyWithId(R.id.button_space) != null)
        assertEquals(true, keyboard.ownsSyllables)
        val items = (0 until keyboard.childCount).map { keyboard.getChildAt(it) }.filterIsInstance<ScrollView>().single().getChildAt(0) as ViewGroup
        fun column() = (0 until items.childCount).map { items.getChildAt(it) as TextView }.filter { it.isVisible }.map { it.text.toString() }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(keyboard)
        val looper = shadowOf(Looper.getMainLooper())
        try {
            EngineState.syllablesState.value = EngineState.SyllableState(InputMethodCatalog.SHUANGPIN, composing = true, syllables = listOf("ni", "mi"), id = 1)
            looper.idle()
            assertEquals(listOf("ni", "mi"), column())
            // 双拼's keys shown: TalkBack reads the pinyin
            EngineState.syllablesState.value = EngineState.SyllableState(InputMethodCatalog.SHUANGPIN, composing = true, syllables = listOf("ua", "u"), id = 3, said = listOf("sha", "sh"))
            looper.idle()
            assertEquals(listOf("ua", "u"), column())
            assertEquals(listOf("sha", "sh"), (0..1).map { (items.getChildAt(it) as TextView).contentDescription?.toString() })
            EngineState.syllablesState.value = EngineState.SyllableState(InputMethodCatalog.PINYIN, composing = true, syllables = listOf("bu", "nu"), id = 2)
            looper.idle()
            assertEquals(T9Keyboard.labels(T9State()), column())
        } finally {
            EngineState.syllablesState.value = EngineState.SyllableState()
        }
    }
}
