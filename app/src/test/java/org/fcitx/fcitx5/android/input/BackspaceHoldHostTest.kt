/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.host.KeyStates
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ⌫ held while pinyin is typed deletes the pinyin and stops there; held again it deletes the field's. The repeats
 * outrun the host's thread, so the stop must be the host's turn, not the keyboard's lagging view.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi")
class BackspaceHoldHostTest {

    @get:Rule
    val prefs = PrefsRule()

    @get:Rule(order = 1)
    val host = HostKeyboardRule("backspace-hold")
    private val h get() = host.h
    private val fcitx get() = host.fcitx

    private fun start(prefill: String) {
        host.up()
        h.start(prefill = { it.setText(prefill); it.setSelection(prefill.length) })
        host.synced()
    }

    private fun type(keys: String) {
        keys.forEach { h.tap(h.key(it.toString())) }
        host.until("$keys typed") { host.preedit == keys }
    }

    private val backspace: ShownKey get() = h.keyNamed("backspace")

    private fun hold(ms: Long) {
        h.press(backspace)
        h.after(ms)
        h.release()
        host.synced()
    }

    @Test
    @Uat("KB-112")
    fun aHoldBegunTypingPinyinStopsOnceThePinyinIsGone() {
        start("你好")
        type("nihao")
        hold(2000)
        assertEquals("", host.preedit)
        assertEquals("你好", h.field.text.toString())
        hold(1000)
        assertTrue("field [${h.field.text}]", h.field.text.isEmpty())
    }

    @Test
    @Uat("KB-112")
    fun aHoldBegunWithNothingTypedDeletesTheFieldsText() {
        start("今天天气很好")
        hold(1000)
        assertTrue("field [${h.field.text}]", h.field.text.length <= 3)
    }

    @Test
    @Uat("KB-112")
    fun tapsStillDeleteTheFieldsTextOneByOneAfterThePinyin() {
        start("你好")
        type("ni")
        repeat(3) { h.tap(backspace) }
        host.synced()
        assertEquals("", host.preedit)
        assertEquals("你", h.field.text.toString())
    }

    @Test
    @Uat("KB-112")
    fun pinyinCommittedByAnotherFingerBeforeTheFirstRepeatLeavesTheFieldAlone() {
        start("你好")
        type("ni")
        h.press(backspace)
        // within the long press's wait: another finger's Return puts the letters in
        h.after(100)
        h.service.postFcitxJob { sendKey(FcitxKeyMapping.FcitxKey_Return, KeyStates.VIRTUAL.toUInt()) }
        host.until("ni put in") { h.field.text.toString() == "你好ni" }
        h.after(1500)
        h.release()
        host.synced()
        assertEquals("你好ni", h.field.text.toString())
    }

    @Test
    @Uat("KB-112")
    fun aHoldBegunOnEnglishsWordInTheFieldStopsOnceTheWordIsGone() {
        start("你好")
        h.service.postFcitxJob {
            setEnabledIme(arrayOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH))
            activateIme(InputMethodCatalog.ENGLISH)
        }
        host.synced()
        // English composes in the field, not above the candidates
        "hi".forEach { h.tap(h.key(it.toString())) }
        host.until("hi in the field") { fcitx.clientPreeditCached.toString() == "hi" }
        assertEquals("", host.preedit)
        hold(2000)
        assertEquals("", fcitx.clientPreeditCached.toString())
        assertEquals("你好", h.field.text.toString())
    }

    private fun hardwareHold(repeats: Int) {
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, repeat: Int) = KeyEvent(
            down, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_DEL, repeat, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        for (i in 0..repeats) {
            h.service.onKeyDown(KeyEvent.KEYCODE_DEL, event(KeyEvent.ACTION_DOWN, i))
            h.advance(50)
        }
        h.service.onKeyUp(KeyEvent.KEYCODE_DEL, event(KeyEvent.ACTION_UP, 0))
        host.synced()
    }

    @Test
    @Uat("KB-112")
    fun aHardwareBackspaceHeldWhileTypingStopsOnceThePinyinIsGone() {
        start("你好")
        type("ni")
        hardwareHold(repeats = 6)
        assertEquals("", host.preedit)
        assertEquals("你好", h.field.text.toString())
        hardwareHold(repeats = 1)
        assertTrue("field [${h.field.text}]", h.field.text.isEmpty())
    }
}
