/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import android.graphics.Rect
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.robolectric.RuntimeEnvironment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ZhuyinKeyboardTest {

    private fun sym(code: Int) = KeyAction.SymAction(KeySym(UNICODE + code), KeyStates.Virtual)

    // the first and as a swipe updates it (back to the key's own, KeySwipes.preview), as the key shows and types it
    @Test
    fun aBubbleShowsThePunctuationAsMapped() {
        val keyboard = ZhuyinKeyboard(RuntimeEnvironment.getApplication(), TestThemes.PixelLight, nine = true)
        keyboard.onPunctuationUpdate(mapOf("?" to "？"))
        val shown = ArrayList<PopupAction>()
        keyboard.popupActionListener = PopupActionListener { shown += it }
        keyboard.onPopupAction(PopupAction.PreviewAction(KeyRef("r2c5"), "?", Rect()))
        keyboard.onPopupAction(PopupAction.PreviewUpdateAction(KeyRef("r2c5"), "?"))
        assertEquals(listOf("？", "？"), shown.map { (it as? PopupAction.PreviewAction)?.content ?: (it as PopupAction.PreviewUpdateAction).content })
    }

    @Test
    @Uat("PY-112", "T9-61")
    fun theSymbolKeysAreNamedForTheirShortcuts() {
        fun shortcuts(nine: Boolean): Map<String, String?> {
            val k = ZhuyinKeyboard(RuntimeEnvironment.getApplication(), TestThemes.PixelLight, nine = nine).laidOut()
            val scene = KeyboardHarness.all(k).filterIsInstance<KeyboardCanvasView>().single().scene!!
            return scene.keys.associate { it.id to k.sceneKey(it)!!.def.shortcut }
        }
        val full = shortcuts(nine = false)
        listOf("1", "q", "a", "z", ";").forEach { assertEquals(it, "zyfull/$it", full[it]) }
        val nine = shortcuts(nine = true)
        assertEquals((1..11).map { "zy9/$it" }, nine.values.filter { it?.matches(Regex("zy9/[0-9]+")) == true }.sortedBy { it!!.substringAfter('/').toInt() })
        assertEquals("comma", nine["r4c4"])
        // a row's first key is its tone, named for the tone rather than a numbered group
        (1..4).forEach { assertEquals("zy9/tone-$it", nine["r${it}c1"]) }
    }

    @Test
    fun theSlotIsTheSeparatorWhileTyping() {
        assertEquals(sym(Keyboard.zhuyinNineTone(0)), ZhuyinKeyboard.slotAction(typing = true))
        assertEquals(KeyAction.SymAction(KeySym(','.code), KeyStates.Virtual), ZhuyinKeyboard.slotAction(typing = false))
    }

    @Test
    fun aGroupOrSymbolMakesTheSlotTheSeparatorATapOfATone() {
        assertEquals(true, ZhuyinKeyboard.typesSymbol(sym(Keyboard.zhuyinNineKey(0))))
        assertEquals(true, ZhuyinKeyboard.typesSymbol(sym(Keyboard.zhuyinNineKey(ZhuyinKeys.GROUPS - 1))))
        assertEquals(true, ZhuyinKeyboard.typesSymbol(sym(Keyboard.zhuyinNineSymbol('ㄅ'))))
        assertEquals(true, ZhuyinKeyboard.typesSymbol(sym(Keyboard.zhuyinNineSymbol('ㄩ'))))
        // a tone with nothing typed types nothing: the slot stays punctuation
        (0..ZhuyinKeys.TONES).forEach { assertEquals(false, ZhuyinKeyboard.typesSymbol(sym(Keyboard.zhuyinNineTone(it)))) }
        assertEquals(false, ZhuyinKeyboard.typesSymbol(sym(Keyboard.zhuyinKey('ㄅ'))))
        assertEquals(false, ZhuyinKeyboard.typesSymbol(KeyAction.HostKeyAction(",")))
    }

    @Test
    fun aLongPressTypesTheSymbolOrToneChosen() {
        val popup = KeyActionListener.Source.Popup
        assertEquals(sym(Keyboard.zhuyinNineSymbol('ㄉ')), ZhuyinKeyboard.resolve(KeyAction.HostKeyAction("ㄉ"), popup, nine = true))
        assertEquals(sym(Keyboard.zhuyinNineTone(4)), ZhuyinKeyboard.resolve(KeyAction.HostKeyAction("ˋ"), popup, nine = true))
        assertEquals(sym(Keyboard.zhuyinTone(2)), ZhuyinKeyboard.resolve(KeyAction.HostKeyAction("ˊ"), popup, nine = false))
        assertEquals(KeyAction.HostKeyAction("%"), ZhuyinKeyboard.resolve(KeyAction.HostKeyAction("%"), popup, nine = true))
        val tap = KeyAction.HostKeyAction("ㄉ")
        assertEquals(tap, ZhuyinKeyboard.resolve(tap, KeyActionListener.Source.Keyboard, nine = true))
    }

    @Test
    fun theLayoutsPlaceholdersAreTheKeyboardsCodes() {
        fun full(name: String, label: String?) = ZhuyinKeyboard.placeholder(name, label, nine = false)
        fun nine(name: String, label: String?) = ZhuyinKeyboard.placeholder(name, label, nine = true)
        assertEquals(sym(Keyboard.zhuyinKey('ㄅ')), full("zhuyin", "ㄅ"))
        assertEquals(sym(Keyboard.zhuyinKey('ㄩ')), full("zhuyin", "ㄩ"))
        (1..ZhuyinKeys.TONES).forEach { assertEquals(sym(Keyboard.zhuyinTone(it)), full("tone-$it", "x")) }
        (1..ZhuyinKeys.TONES).forEach { assertEquals(sym(Keyboard.zhuyinNineTone(it)), nine("tone-$it", null)) }
        ZhuyinKeys.GROUP_SYMBOLS.forEachIndexed { g, symbols -> assertEquals(sym(Keyboard.zhuyinNineKey(g)), nine("zhuyin", symbols)) }
        assertEquals(ZhuyinKeyboard.slotAction(typing = false), nine("zhuyin-slot", "，"))
        listOf(full("zhuyin", "a"), full("zhuyin", "ㄅㄉ"), full("zhuyin", null), full("tone-0", "ˉ"), full("tone-6", "ˉ"), full("tone-", "ˉ"),
            full("zhuyin-slot", "，"), full("group", "ㄅ"), nine("zhuyin", "ㄅ"), nine("zhuyin", "ㄉㄅㄚ")).forEach { assertEquals(null, it) }
        assertEquals("zhuyin", ZhuyinKeyboard.layoutOf(nine = false))
        assertEquals("zhuyin-nine", ZhuyinKeyboard.layoutOf(nine = true))
    }

    @After
    fun noSyllables() {
        EngineState.syllablesState.value = EngineState.SyllableState()
    }
}
