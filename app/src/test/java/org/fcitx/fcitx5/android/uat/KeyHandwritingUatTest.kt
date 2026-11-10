/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.os.Looper
import android.text.InputType
import android.view.accessibility.AccessibilityManager
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
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
import org.fcitx.fcitx5.android.input.keyboard.ZhuyinKeyboard
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
 * deleted by the engine, the first reading marked takesBack, no backspace sent); ⌫ takes a stroke back; space commits the piece. Not in a password, not in English, not while
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
        // whatever a test left the engine's answer at: not typed, as a fresh process starts
        EngineState.keysTypedState.value = EngineState.KeysTyped(EngineState.keysTyped.value.answer + 1, false)
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
        until("finished") { h.api.calls.any { it.startsWith("inkFinished(") && ", keys" in it } }
        assertEquals(0, board().keyInkForTest!!.pad.finished.size)
    }

    @Uat("HW-34")
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
        // the engine deletes the letter, if that tap alone is typed (InkOver): no backspace from here
        assertEquals(keysSent().toString(), 0, keysSent().count { backspace in it })
        assertTrue(inks().toString(), ", takesBack" in inks().first())
        assertTrue(inks().toString(), inks().drop(1).none { ", takesBack" in it })
        assertEquals("the dot, then the stroke", 2, board().keyInkForTest!!.pad.finished.size)
    }

    @Test
    fun aStrokeWithNoTapBeforeTakesNothingBack() {
        harness()
        draw(h.key("t"))
        until("read") { inks().isNotEmpty() }
        assertTrue(inks().toString(), inks().none { ", takesBack" in it })
    }

    @Uat("HW-37")
    @Test
    fun englishKeysGoStraightOnTheRecognizerNotMade() {
        harness(KeyboardHarness.ENGLISH)
        touchTap(h.key("t"))
        h.tap(h.describedKey("Backspace"))
        assertFalse("not written on", board().keyInkForTest!!.writtenOn)
        assertEquals(keysSent().toString(), 2, keysSent().size)
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
        // the reads run on the recognizer's own thread: drained, so a read let through lands before this
        h.idle()
        assertEquals(emptyList<String>(), inks())
    }

    // one reading only (人): the keyboards a stroke of one reading is written on
    private fun oneReading() {
        shared.reader = object : HandwritingRecognition.Reader {
            override val loadable = true
            override fun load() = Unit
            override fun read(strokes: List<FloatArray>) = InkReading(listOf("人"), doubleArrayOf(-1.0))
        }
    }

    // a keyboard shown ([shown] opens its harness and returns the key to draw on), checked, its harness closed
    private class Board(val why: String, val shown: () -> ShownKey)

    private fun onEach(rows: List<Board>, check: () -> Unit) = assertRows(rows, Board::why) { row ->
        try {
            val k = row.shown()
            h.api.calls.clear()
            draw(k)
            check()
        } finally {
            open?.close()
            open = null
        }
    }

    @Uat("HW-36")
    @Test
    fun theOtherKeyboardsAreWrittenOnToo() = onEach(
        listOf(
            Board("the nine keys") {
                harness(keyboards = KeyboardHarness.NINE_KEYS)
                assertTrue(KeyboardHarness.find<T9Keyboard>(h.keyboard) != null)
                h.nineKey("5")
            },
            Board("a table's keys (五笔)") {
                harness(InputMethodEntry(InputMethodCatalog.WUBI, "五笔", "", "", "五", "zh_CN", "androidengine", false))
                h.key("t")
            },
            Board("the 14 keys") {
                prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, PinyinKeyboard.Keys14)
                oneReading()
                harness()
                assertTrue(KeyboardHarness.find<GroupedKeyboard>(h.keyboard) != null)
                h.key("GH")
            },
            Board("the Dachen zhuyin keys") {
                prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, PinyinKeyboard.ZhuyinFull)
                oneReading()
                harness()
                assertTrue(KeyboardHarness.find<ZhuyinKeyboard>(h.keyboard)?.keyInkForTest != null)
                h.key("ㄅ")
            },
            Board("the zhuyin nine keys") {
                prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, PinyinKeyboard.ZhuyinNine)
                oneReading()
                harness()
                assertTrue(KeyboardHarness.find<ZhuyinKeyboard>(h.keyboard)?.keyInkForTest != null)
                h.key("ㄅㄉㄚ")
            },
        ),
    ) { until("read") { inks().isNotEmpty() } }

    @Uat("HW-37", "AX-13")
    @Test
    fun notInAPasswordUrlOrEmailFieldNorEnglishNorWhileTalkBackExplores() = onEach(
        listOf(
            Board("a password field") {
                harness()
                board().inPasswordField = true
                h.key("t")
            },
            Board("English") {
                harness(KeyboardHarness.ENGLISH)
                assertTrue(KeyboardHarness.find<TextKeyboard>(h.keyboard) != null)
                h.key("t")
            },
            Board("a URL field") {
                harness()
                h.restart(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
                h.idle()
                h.key("t")
            },
            Board("an email field") {
                harness()
                h.restart(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
                h.idle()
                h.key("t")
            },
            // last: touch exploration stays on for the rest of the test
            Board("TalkBack exploring by touch") {
                harness()
                shadowOf(board().context.getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(true)
                h.key("t")
            },
        ),
    ) {
        h.advance(100)
        // the reads run on the recognizer's own thread: drained, so a read let through lands before this
        h.idle()
        assertEquals(emptyList<String>(), inks())
    }

    @Test
    fun aShortSwipeIsAStrokeNotTheKeysCorner() {
        harness()
        h.api.calls.clear()
        // nothing typed: a short swipe down on w is written, not w's corner symbol
        draw(h.key("w"), down = 0.6f)
        until("read") { inks().isNotEmpty() }
        assertEquals(emptyList<String>(), keysSent())
    }

    @Test
    fun aShortSwipeWhileTypingIsTheKeysCorner() {
        harness()
        touchTap(h.key("n"))
        // the engine answered: its own input typed
        EngineState.keysTypedState.value =
            EngineState.KeysTyped(EngineState.keysTyped.value.answer + 1, true)
        h.idle()
        h.advance(1500)
        h.api.calls.clear()
        // typing: a short swipe down on w is w's corner symbol, not a stroke
        draw(h.key("w"), down = 0.6f)
        h.advance(100)
        h.idle()
        assertEquals(emptyList<String>(), inks())
        assertTrue(h.api.calls.toString(), keysSent().isNotEmpty())
    }
}
