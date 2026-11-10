/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.os.Looper
import android.view.accessibility.AccessibilityManager
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognition
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.GroupedKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.KeyDrawing
import org.fcitx.fcitx5.android.input.keyboard.T9Keyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.fcitx.fcitx5.android.until as waitUntil

/**
 * The keyboard's half of 键盘手写 (design 060-kbd-handwriting, S3): a stroke drawn over the letters is put
 * on the keyboard's pad, read, and offered as the input method's own handwriting (offerInk onKeys), no key
 * pressed; the piece finished at the pause; a tap just before taken back as its first dot (its letter
 * deleted); ⌫ takes a stroke back; space commits the piece. Not in a password, not in English, not while
 * TalkBack explores by touch. What the engine does with it is InkOver's (ime-core).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class KeyHandwritingUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private var open: KeyboardHarness? = null
    private val h get() = open!!

    // the native model swapped for one reading 人 always
    private val shared = HandwritingRecognition.shared
    private val reader = shared.reader.also {
        shared.reader = object : HandwritingRecognition.Reader {
            override val loadable = true
            override fun load() = Unit
            override fun read(strokes: List<FloatArray>) = InkReading(listOf("人", "入"), doubleArrayOf(-1.0, -2.0))
        }
    }

    @After
    fun close() {
        shared.reader = reader
        open?.close()
    }

    private fun harness(im: InputMethodEntry = KeyboardHarness.PINYIN, keyboards: ImKeyboards.Chosen? = null): KeyboardHarness {
        prefs.set(AppPrefs.getInstance().keyboard.keyDrawing, KeyDrawing.Handwriting)
        return KeyboardHarness(im = im, keyboards = keyboards).also { open = it }.start()
    }

    private fun until(what: String, test: () -> Boolean) =
        waitUntil(what, { h.api.calls }, 5_000, step = { shadowOf(Looper.getMainLooper()).idle() }, done = test)

    private fun board(): BaseKeyboard = KeyboardHarness.find<TextKeyboard>(h.keyboard) ?: error("no keyboard")

    // a finger down on [k], moved [across] and [down] of its size, lifted
    private fun draw(k: ShownKey, across: Float = 0f, down: Float = 1f) {
        val b = h.box(k)
        h.drag(k, b.exactCenterX() + across * b.width(), b.exactCenterY() + down * b.height())
    }

    // a tap through the keyboard's touch, not a click: the glide sees it
    private fun touchTap(k: ShownKey) {
        val b = h.box(k)
        h.drag(k, b.exactCenterX(), b.exactCenterY(), steps = 1)
    }

    private fun inks() = h.api.calls.filter { it.startsWith("offerInk(") && ", keys," in it }
    private fun keysSent() = h.api.calls.filter { it.startsWith("sendKey") && "up=false" in it }
    private val backspace = "sym=${FcitxKeyMapping.FcitxKey_BackSpace},"

    @Test
    fun aStrokeOnTheLettersIsReadAsTheInputMethodsHandwritingNoKeyPressed() {
        harness()
        h.api.calls.clear()
        draw(h.key("t"))
        until("read") { inks().isNotEmpty() }
        assertTrue(inks().toString(), inks().all { "人" in it })
        assertEquals(emptyList<String>(), keysSent())
        assertTrue("ink shown", board().keyInkForTest!!.shown)
        // the pause: the piece finished
        h.advance(1500)
        until("finished") { h.api.calls.any { it.startsWith("inkFinished(") && "keys" in it } }
        assertEquals(0, board().keyInkForTest!!.pad.finished.size)
    }

    @Test
    fun whileWritingATapIsAStrokeBackspaceTakesOneBackAndSpaceCommits() {
        harness()
        draw(h.key("t"))
        touchTap(h.key("g"))
        assertEquals("the tap a dot", 2, board().keyInkForTest!!.pad.finished.size)
        h.api.calls.clear()
        h.tap(h.describedKey("Backspace"))
        until("taken back") { board().keyInkForTest!!.pad.finished.size == 1 }
        assertFalse(keysSent().toString(), keysSent().any { backspace in it })
        h.tap(h.keys().first { it.description?.let { d -> d == "Space" || d.startsWith("Space,") } == true })
        until("space sent") { keysSent().isNotEmpty() }
        assertEquals("wiped", 0, board().keyInkForTest!!.pad.finished.size)
    }

    @Test
    fun aTapThenAStrokeWithinThePauseTakesTheTapBackAsTheFirstDot() {
        harness()
        touchTap(h.key("d"))
        assertTrue(keysSent().toString(), keysSent().isNotEmpty())
        h.api.calls.clear()
        draw(h.key("t"))
        until("read") { inks().isNotEmpty() }
        assertEquals(keysSent().toString(), 1, keysSent().count { backspace in it })
        assertEquals("the dot, then the stroke", 2, board().keyInkForTest!!.pad.finished.size)
    }

    @Test
    fun pinyinTypedAStrokeIsNoWriting() {
        harness()
        touchTap(h.key("d"))
        // the engine answered: its own input typed
        EngineState.keysTypedState.value =
            EngineState.KeysTyped(EngineState.keysTyped.value.answer + 1, true)
        h.idle()
        h.advance(1500)
        h.api.calls.clear()
        draw(h.key("t"))
        h.advance(100)
        assertEquals(emptyList<String>(), inks())
        EngineState.keysTypedState.value =
            EngineState.KeysTyped(EngineState.keysTyped.value.answer + 1, false)
    }

    @Test
    fun theNineAndFourteenKeysAreWrittenOnToo() {
        harness(keyboards = KeyboardHarness.NINE_KEYS)
        assertTrue(KeyboardHarness.find<T9Keyboard>(h.keyboard) != null)
        draw(h.nineKey("5"))
        until("read on the nine keys") { inks().isNotEmpty() }
        close()
        prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, PinyinKeyboard.Keys14)
        shared.reader = object : HandwritingRecognition.Reader {
            override val loadable = true
            override fun load() = Unit
            override fun read(strokes: List<FloatArray>) = InkReading(listOf("人"), doubleArrayOf(-1.0))
        }
        harness()
        assertTrue(KeyboardHarness.find<GroupedKeyboard>(h.keyboard) != null)
        h.api.calls.clear()
        draw(h.key("GH"))
        until("read on the 14 keys") { inks().isNotEmpty() }
    }

    @Test
    fun notInAPasswordNorEnglishNorWhileTalkBackExplores() {
        harness()
        board().inPasswordField = true
        h.api.calls.clear()
        draw(h.key("t"))
        h.advance(100)
        assertEquals(emptyList<String>(), inks())
        board().inPasswordField = false
        val a11y = board().context.getSystemService(AccessibilityManager::class.java)
        shadowOf(a11y).setTouchExplorationEnabled(true)
        draw(h.key("t"))
        h.advance(100)
        assertEquals(emptyList<String>(), inks())
        shadowOf(a11y).setTouchExplorationEnabled(false)
        close()
        harness(KeyboardHarness.ENGLISH)
        assertTrue(KeyboardHarness.find<TextKeyboard>(h.keyboard) != null)
        h.api.calls.clear()
        draw(h.key("t"))
        h.advance(100)
        assertEquals(emptyList<String>(), inks())
    }
}
