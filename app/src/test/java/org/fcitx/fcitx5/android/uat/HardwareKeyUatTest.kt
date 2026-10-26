/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.learn.control.WordKind
import org.fcitx.fcitx5.android.input.CandidatesView
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesOrientation
import org.fcitx.fcitx5.android.input.keyboard.HandwritingKeyboard
import org.fcitx.fcitx5.android.input.keyboard.T9Keyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UAT cases of a hardware keyboard (uat/17): its key events into the real service, as the system
 * hands them, on a fake engine. The configuration says a keyboard is there (`qwerty-keysexposed`),
 * as the system's own evaluation of the touch keyboard reads it. What the engine makes of the keys
 * it is sent (你好 from n i h a o) is the engine's, not asserted here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi-keysexposed-qwerty")
class HardwareKeyUatTest {

    private var h: KeyboardHarness? = null

    private fun entry(name: String, label: String = name) = InputMethodEntry(name, name, "", "", label, "zh_CN", "androidengine", false)
    private val wubi = entry(Engines.WUBI, "五")
    private val handwriting = entry(Engines.HANDWRITING, "写")

    private fun harness(vararg enabled: InputMethodEntry = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH), im: InputMethodEntry = enabled.first()) =
        KeyboardHarness(im = im).also {
            h = it
            it.api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, wubi, KeyboardHarness.T9, handwriting)
            it.api.enabledIme = arrayOf(*enabled)
        }

    @After
    fun close() {
        h?.close()
    }

    /** The keyboard the keys come from. */
    private var device = KeyCharacterMap.VIRTUAL_KEYBOARD

    private fun event(action: Int, code: Int, meta: Int, repeat: Int = 0): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(now, now, action, code, repeat, meta, device, 0, 0, InputDevice.SOURCE_KEYBOARD)
    }

    /** [code] goes down with [meta] held; whether the service took it. */
    private fun KeyboardHarness.down(code: Int, meta: Int = 0, repeat: Int = 0): Boolean =
        service.onKeyDown(code, event(KeyEvent.ACTION_DOWN, code, meta, repeat)).also { advance(20) }

    private fun KeyboardHarness.up(code: Int, meta: Int = 0): Boolean =
        service.onKeyUp(code, event(KeyEvent.ACTION_UP, code, meta)).also { advance(20) }

    /** A key typed: down and up, then what fcitx says of the input method (an [FcitxEvent.IMChangeEvent] when it changed). */
    private fun KeyboardHarness.press(vararg codes: Int) = codes.forEach {
        down(it)
        up(it)
        synced()
    }

    private fun KeyboardHarness.type(text: String) = text.forEach { press(KeyEvent.keyCodeFromString("KEYCODE_" + if (it == ' ') "SPACE" else it.uppercase())) }

    private var told: String? = null

    // fcitx tells of a switch with an IMChangeEvent; the fake engine only changes what it is on
    private fun KeyboardHarness.synced() {
        val now = api.inputMethodEntryCached
        if (now.uniqueName != told) {
            told = now.uniqueName
            emit(FcitxEvent.IMChangeEvent(now))
        }
    }

    private fun KeyboardHarness.shiftTap() {
        down(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        up(KeyEvent.KEYCODE_SHIFT_LEFT)
        synced()
    }

    private fun KeyboardHarness.ctrlSpace() {
        down(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON)
        down(KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON)
        up(KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON)
        up(KeyEvent.KEYCODE_CTRL_LEFT)
        synced()
    }

    private fun KeyboardHarness.ctrlShift() {
        down(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON)
        down(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        up(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_CTRL_ON)
        up(KeyEvent.KEYCODE_CTRL_LEFT)
        synced()
    }

    private val KeyboardHarness.current get() = api.inputMethodEntryCached.uniqueName

    // the keys fcitx was sent, as their names, Space as " "
    private fun KeyboardHarness.sent() = api.calls.filter { it.startsWith("sendKey") && "up=false" in it }
        .map { Regex("sym=(\\d+)").find(it)!!.groupValues[1].toInt() }
        // Shift, Ctrl and the like go to fcitx too, as on a device: not what was typed
        .filter { it !in 0xffe1..0xffee }
        .map { if (it == FcitxKeyMapping.FcitxKey_space) " " else if (it in 0x21..0x7e) it.toChar().toString() else "#$it" }

    private val KeyboardHarness.touchKeyboardUp get() = inputView.visibility == View.VISIBLE

    private val KeyboardHarness.candidatesView: CandidatesView
        get() = KeyboardHarness.find(service.window.window!!.decorView) ?: error("no CandidatesView")

    // the floating window's keyboard button
    private fun KeyboardHarness.touchKeyboardButton() {
        val button = KeyboardHarness.all(service.window.window!!.decorView)
            .first { it.contentDescription == service.getString(R.string.show_touch_keyboard) }
        check(button.performClick())
        idle()
        relayout()
    }

    private inline fun <reified T : View> KeyboardHarness.showing(): Boolean = KeyboardHarness.all(inputView).any { it is T && shown(it) }

    @Test
    @Uat("HK-02")
    fun keysGoToTheEngineAndTheTouchKeyboardGoes() {
        val h = harness().start()
        assertTrue(h.touchKeyboardUp)
        h.type("nihao ")
        h.type("shi")
        h.press(KeyEvent.KEYCODE_3)
        assertEquals("nihao shi3".map { it.toString() }, h.sent())
        // typed on: the touch keyboard put away, candidates by the cursor
        assertFalse(h.touchKeyboardUp)
    }

    @Test
    @Uat("HK-04")
    fun enterAndEscapeGoToTheEngine() {
        val h = harness().start()
        h.type("abc")
        h.press(KeyEvent.KEYCODE_ENTER)
        h.type("xyz")
        h.press(KeyEvent.KEYCODE_ESCAPE)
        val m = FcitxKeyMapping
        assertEquals(listOf("a", "b", "c", "#${m.FcitxKey_Return}", "x", "y", "z", "#${m.FcitxKey_Escape}"), h.sent())
    }

    @Test
    @Uat("HK-05")
    fun backspaceGoesToTheEngine() {
        val h = harness().start()
        h.type("nihao")
        h.press(KeyEvent.KEYCODE_DEL)
        assertEquals("nihao".map { it.toString() } + "#${FcitxKeyMapping.FcitxKey_BackSpace}", h.sent())
    }

    @Test
    @Uat("HK-09")
    fun aShiftTapSwitchesChineseAndEnglish() {
        val h = harness().start()
        h.shiftTap()
        assertEquals(Engines.ENGLISH, h.current)
        h.type("hi ")
        h.shiftTap()
        assertEquals(Engines.PINYIN, h.current)
        h.type("ni ")
        assertEquals("hi ni ".map { it.toString() }, h.sent())
    }

    @Test
    @Uat("HK-10")
    fun shiftWhileTypingSwitchesToEnglish() {
        val h = harness().start()
        h.type("ni")
        h.shiftTap()
        // the engine puts what was typed in as it is when it is switched away (fcitx's own)
        assertEquals(Engines.ENGLISH, h.current)
    }

    @Test
    @Uat("HK-11")
    fun shiftHeldTypesCapitalsAndSwitchesNothing() {
        val h = harness().start()
        h.shiftTap()
        h.api.calls.clear()
        h.down(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON)
        h.down(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON)
        h.up(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON)
        h.up(KeyEvent.KEYCODE_SHIFT_LEFT)
        h.press(KeyEvent.KEYCODE_B)
        assertEquals(Engines.ENGLISH, h.current)
        assertFalse(h.api.calls.toString(), h.api.calls.any { it.startsWith("activateIme") })
        assertTrue(h.sent().toString(), h.sent().containsAll(listOf("A", "b")))
    }

    @Test
    @Uat("HK-12")
    fun ctrlSpaceSwitchesAndTypesNoSpace() {
        val h = harness().start()
        h.ctrlSpace()
        assertEquals(Engines.ENGLISH, h.current)
        h.type("ok")
        h.ctrlSpace()
        assertEquals(Engines.PINYIN, h.current)
        h.type("ni ")
        // no space went in for either Ctrl+Space
        assertEquals("okni ".map { it.toString() }, h.sent())
    }

    @Test
    @Uat("HK-13")
    fun shiftBackFromEnglishIsWubiNotPinyin() {
        val h = harness(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, wubi, im = wubi).start()
        h.synced()
        h.shiftTap()
        assertEquals(Engines.ENGLISH, h.current)
        h.shiftTap()
        assertEquals(Engines.WUBI, h.current)
    }

    @Test
    @Uat("HK-14")
    fun ctrlShiftGoesRoundSkippingHandwritingAndNineKeys() {
        val h = harness(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, wubi, KeyboardHarness.T9, handwriting).start()
        // typed on first: the hardware keyboard's turn, where the nine keys and handwriting take no keys
        h.type("a")
        val seen = (1..4).map { h.ctrlShift(); h.current }
        assertEquals(listOf(Engines.ENGLISH, Engines.WUBI, Engines.PINYIN, Engines.ENGLISH), seen)
    }

    @Test
    @Uat("HK-15")
    fun shiftThenCtrlToo() {
        val h = harness().start()
        h.down(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON)
        h.down(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON)
        h.up(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_SHIFT_ON)
        h.up(KeyEvent.KEYCODE_SHIFT_LEFT)
        h.synced()
        assertEquals(Engines.ENGLISH, h.current)
    }

    @Test
    @Uat("HK-16")
    fun ctrlCAndCtrlVAreForTheApp() {
        val h = harness().start()
        h.api.calls.clear()
        for (code in listOf(KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V)) {
            h.down(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON)
            h.down(code, KeyEvent.META_CTRL_ON)
            h.up(code, KeyEvent.META_CTRL_ON)
            h.up(KeyEvent.KEYCODE_CTRL_LEFT)
        }
        // no switch; fcitx passes what it does not take on to the app (forwarded keys)
        assertEquals(Engines.PINYIN, h.current)
        assertFalse(h.api.calls.toString(), h.api.calls.any { it.startsWith("activateIme") || it.startsWith("enumerateIme") })
    }

    @Test
    @Uat("HK-18")
    fun handwritingTypedOnIsPinyin() {
        val h = harness(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, handwriting, im = handwriting).start()
        h.synced()
        assertTrue(h.showing<HandwritingKeyboard>())
        h.type("ni ")
        assertEquals(Engines.PINYIN, h.current)
        assertFalse(h.touchKeyboardUp)
        assertEquals("ni ".map { it.toString() }, h.sent())
    }

    @Test
    @Uat("HK-19")
    fun theTouchKeyboardBackIsHandwriting() {
        val h = harness(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, handwriting, im = handwriting).start()
        h.synced()
        h.type("ni ")
        h.touchKeyboardButton()
        h.synced()
        assertTrue(h.touchKeyboardUp)
        assertEquals(Engines.HANDWRITING, h.current)
        assertTrue(h.showing<HandwritingKeyboard>())
    }

    @Test
    @Uat("HK-20")
    fun nineKeysTypedOnAndBack() {
        val h = harness(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, KeyboardHarness.T9, im = KeyboardHarness.T9).start()
        h.synced()
        h.type("2026hao ")
        assertEquals("2026hao ".map { it.toString() }, h.sent())
        h.touchKeyboardButton()
        h.synced()
        assertTrue(h.touchKeyboardUp)
        assertTrue(h.showing<T9Keyboard>())
    }

    @Test
    @Uat("HK-22")
    fun aShortcutsChoiceIsKeptWhenTheTouchKeyboardIsBack() {
        val h = harness(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, handwriting, im = handwriting).start()
        h.synced()
        h.type("ni ")
        h.ctrlShift()
        val chosen = h.current
        h.press(KeyEvent.KEYCODE_ESCAPE)
        h.touchKeyboardButton()
        h.synced()
        assertEquals(chosen, h.current)
        assertFalse(h.showing<HandwritingKeyboard>())
    }

    @Test
    @Uat("HK-25")
    fun noCandidatesWindowKeepsTheTouchKeyboard() {
        val mode = AppPrefs.getInstance().candidates.mode
        mode.setValue(FloatingCandidatesMode.Disabled)
        try {
            val h = harness().start()
            h.type("ni ")
            assertTrue(h.touchKeyboardUp)
            assertEquals("ni ".map { it.toString() }, h.sent())
        } finally {
            mode.setValue(mode.defaultValue)
        }
    }

    @Test
    @Uat("HK-25a")
    fun shortcutsOffSwitchNothing() {
        val on = AppPrefs.getInstance().candidates.switchShortcuts
        on.setValue(false)
        try {
            val h = harness(KeyboardHarness.PINYIN, wubi, KeyboardHarness.ENGLISH).start()
            h.shiftTap()
            h.ctrlShift()
            h.ctrlSpace()
            assertEquals(Engines.PINYIN, h.current)
            h.type("ni ")
            // the shortcuts off switched nothing: what is typed after reaches the engine as typed
            assertEquals("ni ".map { it.toString() }, h.sent().takeLast(3))
            on.setValue(true)
            h.shiftTap()
            assertEquals(Engines.ENGLISH, h.current)
        } finally {
            on.setValue(on.defaultValue)
        }
    }

    @Test
    @Uat("HK-26")
    fun theCandidatesWindowStandsUp() {
        val orientation = AppPrefs.getInstance().candidates.orientation
        orientation.setValue(FloatingCandidatesOrientation.Vertical)
        try {
            val h = harness().start()
            h.type("shi")
            val words = arrayOf("是", "时", "事").map { CandidateWord("", it, "") }.toTypedArray()
            h.emit(FcitxEvent.PagedCandidateEvent(FcitxEvent.PagedCandidateEvent.Data(words, 0, FcitxEvent.PagedCandidateEvent.LayoutHint.NotSet, false, true)))
            val cells = KeyboardHarness.all(h.candidatesView).filterIsInstance<TextView>()
                .filter { it.text.toString() in listOf("是", "时", "事") }.toList()
            assertEquals(cells.toString(), 3, cells.size)
            val ys = cells.map { IntArray(2).also(it::getLocationInWindow)[1] }
            val xs = cells.map { IntArray(2).also(it::getLocationInWindow)[0] }
            // one under another: each lower, all at one left edge
            assertTrue("$xs $ys", ys.zipWithNext().all { (a, b) -> b > a } && xs.distinct().size == 1)
        } finally {
            orientation.setValue(orientation.defaultValue)
        }
    }

    @Test
    @Uat("HK-27")
    fun unpluggedTheTouchKeyboardComesBack() {
        val h = harness().start()
        // a keyboard of its own: the virtual id is also where the tracking starts, so would pass untracked
        device = 7
        h.type("ni ")
        assertFalse(h.touchKeyboardUp)
        val listener = h.service.inputDeviceListener
        // another device gone: not the one typed on
        listener.onInputDeviceRemoved(8)
        h.idle()
        assertFalse(h.touchKeyboardUp)
        // the system's word the keyboard typed on is gone
        listener.onInputDeviceRemoved(7)
        h.idle()
        assertTrue(h.touchKeyboardUp)
        h.tap(h.key("h"))
        assertTrue(h.api.calls.toString(), h.api.calls.last().startsWith("sendKey"))
    }

    @Test
    @Uat("HK-28")
    @Config(qualifiers = "w851dp-h393dp-land-440dpi-keysexposed-qwerty")
    fun landscapeSplitBack() {
        val h = harness().start()
        h.type("ni ")
        assertFalse(h.touchKeyboardUp)
        h.touchKeyboardButton()
        assertTrue(h.touchKeyboardUp)
        val keyboard = KeyboardHarness.find<TextKeyboard>(h.inputView)
        assertNotNull(keyboard)
        // split: G and V on both halves
        assertEquals(2, h.texts().count { it == "g" })
    }

    // the engine's menu for 点了: one the user chose, 忘掉学到的 offered; the forget taken back with 3
    private fun learned(op: LearningOp): LearningResult {
        val inner = (op as? LearningOp.OnPage)?.op ?: op
        return if (inner is LearningOp.Menu) {
            LearningResult.Menu(
                inner.text, WordKind.DICTIONARY,
                listOf(MenuItem.DELETE, MenuItem.BLOCK),
                null, BlockScope.EVERYWHERE,
            )
        } else {
            LearningResult.Done(3)
        }
    }

    private fun KeyboardHarness.forgets() = api.calls.filter { it.startsWith("learning(") && "Menu(" !in it }

    @Test
    @Uat("HK-31")
    fun shiftDeleteForgetsTheHighlightedOnceHeld() {
        val h = harness().start()
        h.api.learned = ::learned
        h.type("dl")
        val words = arrayOf("带来", "点了").map { CandidateWord("", it, "") }.toTypedArray()
        h.emit(FcitxEvent.PagedCandidateEvent(FcitxEvent.PagedCandidateEvent.Data(words, 1, FcitxEvent.PagedCandidateEvent.LayoutHint.NotSet, false, false)))
        h.api.calls.clear()
        h.down(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON)
        // held: the key repeats
        for (r in 0..10) h.down(KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.META_SHIFT_ON, repeat = r)
        h.up(KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.META_SHIFT_ON)
        h.up(KeyEvent.KEYCODE_SHIFT_LEFT)
        h.synced()
        val forgets = h.forgets()
        assertEquals(h.api.calls.toString(), 1, forgets.size)
        assertTrue(forgets.single(), "点了" in forgets.single())
        // no Delete to the engine, no switch
        assertFalse(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey(sym=${FcitxKeyMapping.FcitxKey_Delete}") })
        assertEquals(Engines.PINYIN, h.current)
    }

    @Test
    @Uat("HK-32")
    fun aQuickShiftDeleteIsNoShiftTap() {
        val h = harness().start()
        h.api.learned = ::learned
        h.type("dl")
        val words = arrayOf("带来", "点了").map { CandidateWord("", it, "") }.toTypedArray()
        h.emit(FcitxEvent.PagedCandidateEvent(FcitxEvent.PagedCandidateEvent.Data(words, 1, FcitxEvent.PagedCandidateEvent.LayoutHint.NotSet, false, false)))
        h.api.calls.clear()
        h.service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON))
        h.service.onKeyDown(KeyEvent.KEYCODE_FORWARD_DEL, event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.META_SHIFT_ON))
        h.service.onKeyUp(KeyEvent.KEYCODE_FORWARD_DEL, event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.META_SHIFT_ON))
        h.service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0))
        h.idle()
        h.synced()
        assertEquals(h.api.calls.toString(), 1, h.forgets().size)
        assertEquals(Engines.PINYIN, h.current)
    }
}
