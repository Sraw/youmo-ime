/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
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
 * The keyboard's half of the toolbar's 手写 (061-handwriting-toolbar): while it is on, a stroke or a tap over the
 * letters (Shift too) is put on the keyboard's pad, read, and offered as the input method's handwriting
 * (offerInk), no key pressed; the piece finished at the pause; ⌫ takes a stroke back; space commits the piece.
 * Not in a password, not in English, not in quick input, not while TalkBack explores by touch; URL and email
 * fields are written on. What the engine does with it is InkOver's (ime-core).
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
        prefs.set(AppPrefs.getInstance().internal.handwritingOpen, true)
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

    private fun inks() = h.api.calls.filter { it.startsWith("offerInk(") }
    private fun keysSent() = h.api.calls.filter { it.startsWith("sendKey") && "up=false" in it }
    private val backspace = "sym=${FcitxKeyMapping.FcitxKey_BackSpace},"

    @Uat("HW-32", "HW-41")
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
        until("finished") { h.api.calls.any { it.startsWith("inkFinished(") } }
        assertEquals(0, board().keyInkForTest!!.pad.finished.size)
    }

    @Uat("HW-34", "HW-42")
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

    @Uat("HW-37")
    @Test
    fun englishKeysGoStraightOnTheRecognizerNotMade() {
        harness(KeyboardHarness.ENGLISH)
        touchTap(h.key("t"))
        h.tap(h.describedKey("Backspace"))
        assertFalse("not written on", board().keyInkForTest!!.writtenOn)
        assertEquals(keysSent().toString(), 2, keysSent().size)
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

    // each row from the test's own state (the reader, the pinyin keyboard, touch exploration off), whatever the rows
    // before it set; a close that fails after the row's own failure is added to it, not put in its place
    private fun onEach(rows: List<Board>, check: () -> Unit) {
        val pinyinKeyboard = AppPrefs.getInstance().keyboard.pinyinKeyboard
        val keys = pinyinKeyboard.getValue()
        // the test's reader (人 入), not the native one [reader] keeps for after the test
        val twoReadings = shared.reader
        assertRows(rows, Board::why) { row ->
            val failed = runCatching {
                val k = row.shown()
                h.api.calls.clear()
                draw(k)
                check()
            }.exceptionOrNull()
            val o = open
            open = null
            shared.reader = twoReadings
            prefs.set(pinyinKeyboard, keys)
            val closing = runCatching {
                o?.let { shadowOf(it.keyboard.context.getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(false) }
                o?.close()
            }.exceptionOrNull()
            if (failed != null) throw failed.apply { closing?.let(::addSuppressed) }
            if (closing != null) throw closing
        }
    }

    @Uat("HW-36", "HW-44")
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
            Board("双拼's 26 keys") {
                harness(InputMethodEntry(InputMethodCatalog.SHUANGPIN, "双拼", "", "", "双", "zh_CN", "androidengine", false))
                assertTrue(KeyboardHarness.find<TextKeyboard>(h.keyboard) != null)
                h.key("t")
            },
            Board("the 18 keys") {
                prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, PinyinKeyboard.Keys18)
                oneReading()
                harness()
                assertTrue(KeyboardHarness.find<GroupedKeyboard>(h.keyboard) != null)
                h.key("BN")
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

    // URL and email fields are written on (061-handwriting-toolbar Q11), as on any other
    @Uat("HW-37")
    @Test
    fun urlAndEmailFieldsAreWrittenOn() = onEach(
        listOf(
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
        ),
    ) { until("read") { inks().isNotEmpty() } }

    private fun quickInput(on: Boolean) {
        val aux = if (on) FormattedText(arrayOf("快速输入: "), intArrayOf(0), -1) else FormattedText.Empty
        h.emit(HostEvent.InputPanel(FormattedText.Empty, aux, FormattedText.Empty, quickPhrase = on))
        h.idle()
    }

    @Uat("HW-37", "HW-49", "AX-13")
    @Test
    fun notInAPasswordFieldNorEnglishNorQuickInputNorWhileTalkBackExplores() = onEach(
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
            Board("quick input") {
                harness()
                quickInput(true)
                h.key("t")
            },
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

    @Uat("HW-32", "HW-50")
    @Test
    fun aShortSwipeIsAStrokeNotTheKeysCorner() {
        harness()
        h.api.calls.clear()
        // nothing typed: a short swipe down on w is written, not w's corner symbol
        draw(h.key("w"), down = 0.6f)
        until("read") { inks().isNotEmpty() }
        assertEquals(emptyList<String>(), keysSent())
    }

    // Shift is part of the letter area (Q13); ⌫ stays a key
    @Uat("HW-43")
    @Test
    fun shiftIsWrittenOn() {
        harness()
        h.api.calls.clear()
        draw(h.describedKey("Shift"), down = -0.5f)
        until("read") { inks().isNotEmpty() }
        assertEquals(emptyList<String>(), keysSent())
        // a tap on it a dot written, the case as it was
        val case = h.describedKey("Shift").state
        val strokes = board().keyInkForTest!!.pad.finished.size
        touchTap(h.describedKey("Shift"))
        h.idle()
        assertEquals(strokes + 1, board().keyInkForTest!!.pad.finished.size)
        assertEquals(case, h.describedKey("Shift").state)
        assertEquals(emptyList<String>(), keysSent())
    }

    // the keys round the letters are keys (，), and a stroke that starts on the letters is a stroke wherever it goes:
    // across space it types no space
    @Uat("HW-43")
    @Test
    fun theCommaIsAKeyAndAStrokeAcrossSpaceTypesNoSpace() {
        harness()
        h.api.calls.clear()
        touchTap(h.keyNamed("comma"))
        h.idle()
        assertEquals(h.api.calls.toString(), 1, keysSent().size)
        assertEquals(emptyList<String>(), inks())
        assertFalse(board().keyInkForTest!!.writtenOn)
        h.api.calls.clear()
        val v = h.box(h.key("v"))
        val space = h.box(h.keyNamed("space"))
        draw(h.key("v"), down = (space.exactCenterY() - v.exactCenterY()) / v.height())
        until("read") { inks().isNotEmpty() }
        assertEquals(emptyList<String>(), keysSent())
    }

    // pointers [points] (id, x, y in the window) as a device sends them to the input view, [action] of pointer [index]
    private fun fingers(action: Int, index: Int, downAt: Long, vararg points: Triple<Int, Float, Float>) {
        val root = IntArray(2).also(h.inputView::getLocationInWindow)
        val props = points.map { p -> MotionEvent.PointerProperties().apply { id = p.first; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = points.map { p -> MotionEvent.PointerCoords().apply { x = p.second - root[0]; y = p.third - root[1]; pressure = 1f; size = 1f } }
        val masked = if (points.size > 1 && action != MotionEvent.ACTION_MOVE) action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val e = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), masked, points.size, props.toTypedArray(), coords.toTypedArray(), 0, 0, 1f, 1f, 0, 0, 0, 0)
        h.inputView.dispatchTouchEvent(e)
        e.recycle()
        h.advance(16)
    }

    // a thumb resting on the letters while another finger writes (061-handwriting-toolbar §9: a second finger is
    // not heard; down again after a lift is the next stroke, as 0.6.0's pad had it): each stroke of the writing
    // finger is one, the thumb's moves no ink and its lifting no stroke
    @Uat("HW-53")
    @Test
    fun aFingerRestingThroughSeveralStrokesIsNoInk() {
        harness()
        h.api.calls.clear()
        val t = h.box(h.key("t"))
        val g = h.box(h.key("g"))
        val m = h.box(h.key("m"))
        val pad = board().keyInkForTest!!.pad
        val downAt = SystemClock.uptimeMillis()
        fun pen(x: Float, y: Float) = Triple(0, x, y)
        fun thumb(dx: Float = 0f) = Triple(1, m.exactCenterX() + dx, m.exactCenterY())
        fingers(MotionEvent.ACTION_DOWN, 0, downAt, pen(t.exactCenterX(), t.exactCenterY()))
        fingers(MotionEvent.ACTION_POINTER_DOWN, 1, downAt, pen(t.exactCenterX(), t.exactCenterY()), thumb())
        for (i in 1..6) fingers(MotionEvent.ACTION_MOVE, 0, downAt, pen(t.exactCenterX(), t.exactCenterY() + i * t.height() / 6f), thumb(i.toFloat()))
        fingers(MotionEvent.ACTION_POINTER_UP, 0, downAt, pen(t.exactCenterX(), t.bottom.toFloat()), thumb(6f))
        assertEquals(1, pad.finished.size)
        // down again while the thumb rests
        fingers(MotionEvent.ACTION_POINTER_DOWN, 0, downAt, pen(g.exactCenterX(), g.exactCenterY()), thumb(6f))
        for (i in 1..6) fingers(MotionEvent.ACTION_MOVE, 0, downAt, pen(g.exactCenterX() + i * g.width() / 6f, g.exactCenterY()), thumb(8f))
        fingers(MotionEvent.ACTION_POINTER_UP, 0, downAt, pen(g.right.toFloat(), g.exactCenterY()), thumb(8f))
        assertEquals(2, pad.finished.size)
        // each stroke where the pen was, not the thumb
        val kb = h.box(board())
        for ((stroke, key) in pad.finished.zip(listOf(t, g))) {
            assertTrue("${stroke.toList().take(2)} in $key", key.contains((stroke[0] + kb.left).toInt(), (stroke[1] + kb.top).toInt()))
        }
        fingers(MotionEvent.ACTION_UP, 0, downAt, thumb(8f))
        assertEquals(2, pad.finished.size)
        assertEquals(emptyList<String>(), keysSent())
    }

    // a thumb holding a key that is no letter (space) while another finger writes: the letters only write, so each
    // stroke is one and no key is typed, the space's either (let go of as the first stroke starts)
    @Uat("HW-53")
    @Test
    fun aThumbHoldingSpaceLetsTheLettersBeWrittenOn() {
        harness()
        h.api.calls.clear()
        val t = h.box(h.key("t"))
        val g = h.box(h.key("g"))
        val space = h.box(h.keyNamed("space"))
        val pad = board().keyInkForTest!!.pad
        val downAt = SystemClock.uptimeMillis()
        fun thumb(dx: Float = 0f) = Triple(0, space.exactCenterX() + dx, space.exactCenterY())
        fun pen(x: Float, y: Float) = Triple(1, x, y)
        fingers(MotionEvent.ACTION_DOWN, 0, downAt, thumb())
        fingers(MotionEvent.ACTION_POINTER_DOWN, 1, downAt, thumb(), pen(t.exactCenterX(), t.exactCenterY()))
        for (i in 1..6) fingers(MotionEvent.ACTION_MOVE, 0, downAt, thumb(1f), pen(t.exactCenterX(), t.exactCenterY() + i * t.height() / 6f))
        fingers(MotionEvent.ACTION_POINTER_UP, 1, downAt, thumb(1f), pen(t.exactCenterX(), t.bottom.toFloat()))
        assertEquals(1, pad.finished.size)
        fingers(MotionEvent.ACTION_POINTER_DOWN, 1, downAt, thumb(1f), pen(g.exactCenterX(), g.exactCenterY()))
        for (i in 1..6) fingers(MotionEvent.ACTION_MOVE, 0, downAt, thumb(2f), pen(g.exactCenterX() + i * g.width() / 6f, g.exactCenterY()))
        fingers(MotionEvent.ACTION_POINTER_UP, 1, downAt, thumb(2f), pen(g.right.toFloat(), g.exactCenterY()))
        fingers(MotionEvent.ACTION_UP, 0, downAt, thumb(2f))
        h.idle()
        assertEquals(2, pad.finished.size)
        assertEquals(emptyList<String>(), keysSent())
    }

    // the thumb holding space lifted in the middle of a stroke, the pen lifted last: the stroke is one, and neither
    // the space nor a letter is typed
    @Uat("HW-53")
    @Test
    fun theThumbLiftedMidStrokeLeavesTheStrokeAlone() {
        harness()
        h.api.calls.clear()
        val t = h.box(h.key("t"))
        val space = h.box(h.keyNamed("space"))
        val pad = board().keyInkForTest!!.pad
        val downAt = SystemClock.uptimeMillis()
        val thumb = Triple(0, space.exactCenterX(), space.exactCenterY())
        fun pen(i: Int) = Triple(1, t.exactCenterX(), t.exactCenterY() + i * t.height() / 6f)
        fingers(MotionEvent.ACTION_DOWN, 0, downAt, thumb)
        fingers(MotionEvent.ACTION_POINTER_DOWN, 1, downAt, thumb, pen(0))
        for (i in 1..3) fingers(MotionEvent.ACTION_MOVE, 0, downAt, thumb, pen(i))
        fingers(MotionEvent.ACTION_POINTER_UP, 0, downAt, thumb, pen(3))
        for (i in 4..6) fingers(MotionEvent.ACTION_MOVE, 0, downAt, pen(i))
        fingers(MotionEvent.ACTION_UP, 0, downAt, pen(6))
        h.idle()
        assertEquals(1, pad.finished.size)
        assertEquals(emptyList<String>(), keysSent())
    }

    // another finger down on the letters while a stroke is written, and up again: not heard, the stroke one
    @Uat("HW-53")
    @Test
    fun aFingerDownAndUpWhileWritingIsNotHeard() {
        harness()
        h.api.calls.clear()
        val t = h.box(h.key("t"))
        val g = h.box(h.key("g"))
        val pad = board().keyInkForTest!!.pad
        val downAt = SystemClock.uptimeMillis()
        fun pen(i: Int) = Triple(0, t.exactCenterX(), t.exactCenterY() + i * t.height() / 6f)
        val other = Triple(2, g.exactCenterX(), g.exactCenterY())
        fingers(MotionEvent.ACTION_DOWN, 0, downAt, pen(0))
        for (i in 1..2) fingers(MotionEvent.ACTION_MOVE, 0, downAt, pen(i))
        fingers(MotionEvent.ACTION_POINTER_DOWN, 1, downAt, pen(2), other)
        fingers(MotionEvent.ACTION_MOVE, 0, downAt, pen(3), other)
        fingers(MotionEvent.ACTION_POINTER_UP, 1, downAt, pen(3), other)
        for (i in 4..6) fingers(MotionEvent.ACTION_MOVE, 0, downAt, pen(i))
        fingers(MotionEvent.ACTION_UP, 0, downAt, pen(6))
        h.idle()
        assertEquals(1, pad.finished.size)
        assertEquals(emptyList<String>(), keysSent())
    }

    // ⌫ taking a stroke back times the pause again (KeyInk.undo): what is left is not finished before the one taken
    // back can be written again
    @Uat("HW-06")
    @Test
    fun backspaceTimesThePauseAgain() {
        harness()
        val pause = AppPrefs.getInstance().keyboard.handwritingPause.getValue().millis
        fun finished() = h.api.calls.count { it.startsWith("inkFinished(") }
        draw(h.key("t"))
        draw(h.key("g"))
        until("read") { inks().isNotEmpty() }
        h.advance(pause * 6 / 10)
        h.tap(h.describedKey("Backspace"))
        until("taken back") { board().keyInkForTest!!.pad.finished.size == 1 }
        h.advance(pause * 6 / 10)
        assertEquals("timed again from ⌫", 0, finished())
        h.advance(pause * 5 / 10)
        until("finished") { finished() == 1 }
    }

    // quick input over, the letters are written on again
    @Uat("HW-49", "QU-12")
    @Test
    fun outOfQuickInputTheLettersAreWrittenOnAgain() {
        harness()
        quickInput(true)
        touchTap(h.key("t"))
        h.idle()
        assertTrue(h.api.calls.toString(), keysSent().isNotEmpty())
        quickInput(false)
        h.api.calls.clear()
        draw(h.key("t"))
        until("read") { inks().isNotEmpty() }
    }

    // turned off, the letters are typed again
    @Uat("HW-41")
    @Test
    fun turnedOffTheLettersAreTyped() {
        harness()
        prefs.set(AppPrefs.getInstance().internal.handwritingOpen, false)
        h.idle()
        h.api.calls.clear()
        touchTap(h.key("q"))
        h.idle()
        assertEquals(emptyList<String>(), inks())
        assertTrue(h.api.calls.toString(), keysSent().isNotEmpty())
    }

    // kept across input methods (§15): English types its letters, no 手写 on its bar (Q3); back in pinyin it writes
    @Uat("HW-44", "HW-47")
    @Test
    fun anotherInputMethodAndBackTheToggleIsKept() {
        harness()
        // 123 and back: the letters still written on
        h.tap(h.key("123"))
        h.tap(h.key(h.service.getString(R.string.picker_back)))
        h.api.calls.clear()
        draw(h.key("t"))
        until("read after 123") { inks().isNotEmpty() }
        h.after(2_000)
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH))
        assertFalse("no 手写 in English", toolbarHandwritingShown())
        h.api.calls.clear()
        touchTap(h.key("q"))
        h.idle()
        assertEquals(emptyList<String>(), inks())
        assertTrue(h.api.calls.toString(), keysSent().isNotEmpty())
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.PINYIN))
        assertTrue(AppPrefs.getInstance().internal.handwritingOpen.getValue())
        h.api.calls.clear()
        draw(h.key("t"))
        until("read") { inks().isNotEmpty() }
    }

    // remembered (Q1): the keyboard hidden and shown again, or its view made anew, still writes
    @Uat("HW-48", "LC-35")
    @Test
    fun theKeyboardHiddenAndShownAgainStillWrites() {
        harness()
        h.hide()
        h.restart()
        h.api.calls.clear()
        draw(h.key("t"))
        until("read") { inks().isNotEmpty() }
        h.recreate()
        h.api.calls.clear()
        draw(h.key("t"))
        until("read again") { inks().isNotEmpty() }
    }

    // while it writes, a finger over the letters is a stroke, not a glide (滑行输入 waits); turned off, a glide again
    @Uat("HW-50")
    @Test
    fun glideTypingWaitsWhileHandwritingIsOn() {
        prefs.set(AppPrefs.getInstance().keyboard.keyDrawing, KeyDrawing.Glide)
        harness()
        val end = h.box(h.key("p"))
        h.api.calls.clear()
        h.drag(h.key("t"), end.exactCenterX(), end.exactCenterY())
        until("read") { inks().isNotEmpty() }
        assertFalse("no glide: ${h.api.calls}", "offerGlide" in h.api.calls)
        h.after(2_000)
        prefs.set(AppPrefs.getInstance().internal.handwritingOpen, false)
        h.idle()
        h.api.calls.clear()
        h.drag(h.key("t"), end.exactCenterX(), end.exactCenterY())
        h.idle()
        assertEquals(emptyList<String>(), inks())
        assertTrue("glided: ${h.api.calls}", "offerGlide" in h.api.calls)
    }

    // landscape's split keyboard: a stroke may start in the gap between its halves
    @Uat("HW-52")
    @Test
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun aStrokeStartsInTheSplitGap() {
        harness()
        val t = h.box(h.keysLabelled("t").first())
        val y = h.box(h.keysLabelled("y").last())
        assertTrue("split: $t $y", y.left - t.right > t.width())
        val gap = Rect(t.right + t.width() / 4, t.top, y.left - t.width() / 4, t.bottom)
        h.api.calls.clear()
        h.drag(gap, gap.exactCenterX(), gap.exactCenterY() + 2 * t.height())
        until("read") { inks().isNotEmpty() }
        assertEquals(emptyList<String>(), keysSent())
    }

    private fun toolbarHandwritingShown() = KeyboardHarness.all(h.inputView).filterIsInstance<ToolButton>()
        .any { h.shown(it) && it.contentDescription == h.service.getString(R.string.handwriting) }
}
