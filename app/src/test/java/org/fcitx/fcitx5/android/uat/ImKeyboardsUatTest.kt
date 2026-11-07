/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineBridge
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.AltTextKeyView
import org.fcitx.fcitx5.android.input.keyboard.CodeRowHeight
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The keyboards I1 adds (uat/06 PY-105, uat/07 SP-26): pinyin's 18 keys and 双拼's 14, each its
 * own input method's choice, their keys sending codes of their own (Keyboard.pinyin18Key,
 * Keyboard.shuangpin14Key) that no other session reads. What the engine makes of them is
 * EnginesImKeyboardsTest's (ime-core) and the measured `t9 --keys 18`, `shuangpin --keys 14` (ime-eval).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ImKeyboardsUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private var harness: KeyboardHarness? = null
    private val h: KeyboardHarness get() = harness!!

    private val shuangpin = InputMethodEntry(InputMethodCatalog.SHUANGPIN, "双拼", "", "", "双", "zh_CN", "androidengine", false)

    // the process's scheme as the test found it: a later test reads it
    private val schemeWas = EngineState.shuangpinScheme

    @After
    fun close() {
        harness?.close()
        EngineState.shuangpinScheme = schemeWas
    }

    // the engine told [settings], as the host tells it a change (EngineBridge is no AutoCloseable)
    private fun configured(settings: EngineSettings) {
        val bridge = EngineBridge()
        try {
            bridge.configure(settings)
        } finally {
            bridge.close()
        }
    }

    private fun start(im: InputMethodEntry): KeyboardHarness = KeyboardHarness(ThemePreset.PixelLight, im).also {
        harness = it
        it.api.availableIme = arrayOf(KeyboardHarness.PINYIN, shuangpin, KeyboardHarness.ENGLISH)
        it.api.enabledIme = it.api.availableIme
        it.start()
    }

    // the keys' faces, in order
    private fun faces() = KeyboardHarness.all(h.keyboard).filterIsInstance<AltTextKeyView>().filter { h.shown(it) }
        .map { (it.def as KeyDef.Appearance.AltText).displayText }.toList()

    private fun face(group: String) = KeyboardHarness.all(h.keyboard).filterIsInstance<AltTextKeyView>()
        .first { h.shown(it) && (it.def as KeyDef.Appearance.AltText).displayText == group }

    // the keysyms sent down, as `sym=<n>,`
    private fun sents() = h.api.calls.filter { it.startsWith("sendKey") && "up=false" in it }.map { Regex("sym=\\d+,").find(it)?.value ?: it }

    private fun sym(code: Int) = "sym=${UNICODE + code},"

    private fun row(): Float = h.box(h.inputView.findViewById<View>(R.id.code_row_text)).height() / h.inputView.resources.displayMetrics.density

    @Test
    @Uat("PY-105")
    fun pinyinsEighteenKeysShowTheGroupsAndSendTheirOwnCodes() {
        prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, PinyinKeyboard.Keys18)
        prefs.set(AppPrefs.getInstance().keyboard.shuangpinKeyboard, ShuangpinKeyboard.Full)
        start(KeyboardHarness.PINYIN)
        // L alone: pinyin has no ; (双拼's 18 keys keep L;)
        val groups = NineKeyLetters.G18.tag().split(',').map { it.replace(";", "").uppercase() }
        assertEquals(groups, faces())
        h.capture("ImKeyboardsUatTest/pinyin18")
        // B N: the group's code as pinyin's 18 keys send it, not 双拼's
        h.tap(face("BN"))
        assertEquals(listOf(sym(Keyboard.pinyin18Key(NineKeyLetters.G18.key('n')))), sents())
        // the syllable strip's tall code row, as on the 14 keys
        h.relayout()
        assertEquals(CodeRowHeight.STRIP_DP.toFloat(), row(), 1f)
        // 双拼 keeps its own keyboard, the full one
        h.api.inputMethodEntryCached = shuangpin
        h.emit(HostEvent.InputMethodChanged(shuangpin))
        assertTrue(h.texts().toString(), listOf("q", "w", "e").all { it in h.texts() })
    }

    @Test
    @Uat("SP-26")
    fun shuangpinsFourteenKeysSendTheirOwnCodesAndAreNoneUnderMicrosoft() {
        prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, PinyinKeyboard.Full)
        prefs.set(AppPrefs.getInstance().keyboard.shuangpinKeyboard, ShuangpinKeyboard.Keys14)
        start(shuangpin)
        val groups = NineKeyLetters.G14.tag().split(',').map { it.uppercase() }
        assertEquals(groups, faces())
        h.capture("ImKeyboardsUatTest/shuangpin14")
        h.tap(face("BN"))
        assertEquals(listOf(sym(Keyboard.shuangpin14Key(NineKeyLetters.G14.key('n')))), sents())
        h.relayout()
        assertEquals(CodeRowHeight.STRIP_DP.toFloat(), row(), 1f)
        // 微软 types ; (ing), no key of the 14 has it: the engine configured so (as the host does on the
        // settings page's change) stores the 18 keys, the nearest, and the input view made anew for that
        // preference (FcitxInputMethodService.recreateInputViewPrefs) shows them; HostCore tells the change
        // as the keyboard's too (ChineseModulesHostTest)
        configured(EngineSettings(shuangpin = "MS"))
        assertEquals(ShuangpinScheme.MICROSOFT, EngineState.shuangpinScheme)
        assertEquals(ShuangpinKeyboard.Keys18, AppPrefs.getInstance().keyboard.shuangpinKeyboard.getValue())
        h.close()
        start(shuangpin)
        assertEquals(NineKeyLetters.G18.tag().split(',').map { it.uppercase() }, faces())
        // a scheme with the 14 keys again leaves the 18 keys as stored
        configured(EngineSettings())
        assertEquals(ShuangpinKeyboard.Keys18, AppPrefs.getInstance().keyboard.shuangpinKeyboard.getValue())
    }
}
