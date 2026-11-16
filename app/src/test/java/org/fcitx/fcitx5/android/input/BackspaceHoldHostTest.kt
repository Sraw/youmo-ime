/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import java.io.File
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.core.ScriptedEngine
import org.fcitx.fcitx5.android.core.keyboardOn
import org.fcitx.fcitx5.android.core.startedHost
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.host.KeyStates
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.fcitx.fcitx5.android.until as waitUntil

/**
 * ⌫ held while pinyin is typed, through a real [KotlinHost] on a scripted engine: the hold deletes the
 * pinyin and stops there, the field's text untouched; let go and held again it deletes the field's
 * (user 2026-11-15). The clock runs the repeats far ahead of the host's thread, so each repeat is sent
 * before the keyboard has heard what the one before it did: the stop is the host's turn's, not the
 * keyboard's lagging view.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi")
class BackspaceHoldHostTest {

    @get:Rule
    val prefs = PrefsRule()

    private lateinit var home: File
    private lateinit var fcitx: KotlinHost
    private lateinit var h: KeyboardHarness

    private fun start(prefill: String) {
        home = File(appContext.filesDir, "backspace-hold").apply { deleteRecursively() }
        fcitx = startedHost(ScriptedEngine(home))
        h = keyboardOn(fcitx)
        h.start(prefill = { it.setText(prefill); it.setSelection(prefill.length) })
        synced()
    }

    @After
    fun tearDown() {
        if (::h.isInitialized) h.close()
        if (::fcitx.isInitialized) fcitx.stop()
        if (::home.isInitialized) home.deleteRecursively()
    }

    private val preedit get() = fcitx.inputPanelCached.preedit.toString()

    private fun until(what: String, done: () -> Boolean) = waitUntil(
        what,
        { "field [${h.field.text}] preedit [$preedit]" },
        step = { h.idle() },
        done = done,
    )

    // what the main thread posted to the host done, and what it told back shown
    private fun synced() {
        var done = false
        h.service.postFcitxJob { done = true }
        until("the host's queue done") { done }
        h.idle()
    }

    private fun type(keys: String) {
        keys.forEach { h.tap(h.key(it.toString())) }
        until("$keys typed") { preedit == keys }
    }

    private val backspace: ShownKey get() = h.keyNamed("backspace")

    private fun hold(ms: Long) {
        h.press(backspace)
        h.after(ms)
        h.release()
        synced()
    }

    @Test
    @Uat("KB-112")
    fun aHoldBegunTypingPinyinStopsOnceThePinyinIsGone() {
        start("你好")
        type("nihao")
        // 2 s: some 30 repeats, five of them for the pinyin
        hold(2000)
        assertEquals("", preedit)
        assertEquals("你好", h.field.text.toString())
        // let go and held again: the field's text, as ever
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
        synced()
        assertEquals("", preedit)
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
        until("ni put in") { h.field.text.toString() == "你好ni" }
        // what was typed as ⌫ went down is gone already: its repeats delete nothing
        h.after(1500)
        h.release()
        synced()
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
        synced()
        // English composes its word in the field (the client's preedit), not above the candidates
        "hi".forEach { h.tap(h.key(it.toString())) }
        until("hi in the field") { fcitx.clientPreeditCached.toString() == "hi" }
        assertEquals("", preedit)
        hold(2000)
        assertEquals("", fcitx.clientPreeditCached.toString())
        assertEquals("你好", h.field.text.toString())
    }

    // a hardware Backspace held: its press, then its auto-repeats (repeatCount 1, 2, …), a key from a keyboard
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
        synced()
    }

    @Test
    @Uat("KB-112")
    fun aHardwareBackspaceHeldWhileTypingStopsOnceThePinyinIsGone() {
        start("你好")
        type("ni")
        hardwareHold(repeats = 6)
        assertEquals("", preedit)
        assertEquals("你好", h.field.text.toString())
        hardwareHold(repeats = 1)
        assertTrue("field [${h.field.text}]", h.field.text.isEmpty())
    }
}
