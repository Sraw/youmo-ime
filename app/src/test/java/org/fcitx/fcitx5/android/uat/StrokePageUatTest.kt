/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.learn.control.WordKind
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateLayout
import org.fcitx.fcitx5.android.input.candidates.expanded.StrokePageRecyclerView
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognition
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyDrawing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 笔画筛选 drawn on 展开候选's page (uat/06 PY-145–149, uat/16 LS-52, uat/20 AX-20;
 * dev/design/061-stroke-on-candidate-page.md): while pinyin is typed the whole list draws strokes, sent as the keys'
 * are, the page staying; no drag scrolls it then, a tap still picks and a long press still opens the menu. Where the
 * keys draw none, the list is as before. The touch's rules are PageStrokeTouchTest's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class StrokePageUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private var open: KeyboardHarness? = null
    private val h get() = open!!

    @After
    fun close() {
        HandwritingRecognition.shared.reader = reader
        open?.close()
        open = null
    }

    private val keyboard = AppPrefs.getInstance().keyboard

    // the native model's, put back after a row that swapped it
    private val reader = HandwritingRecognition.shared.reader

    // 展开候选 opened over 80 candidates of [pinyin] typed (none: 联想's), 在键上划 [drawing]
    private fun page(drawing: KeyDrawing = KeyDrawing.StrokeFilter, pinyin: String = "hao", aux: String = ""): StrokePageRecyclerView {
        prefs.set(keyboard.keyDrawing, drawing)
        open = KeyboardHarness(TestThemes.PixelLight).start()
        typed(pinyin, aux)
        candidates(*Array(80) { "字$it" })
        h.tap(h.described(h.service.getString(R.string.expand_candidates_list)))
        return list()!!
    }

    private fun list() = KeyboardHarness.find<ExpandedCandidateLayout>(h.inputView)?.recyclerView?.takeIf { h.shown(it) }

    private fun typed(pinyin: String, aux: String = "") {
        val above = if (aux.isEmpty()) FormattedText.Empty else FormattedText(arrayOf(aux), intArrayOf(0), -1)
        val preedit = if (pinyin.isEmpty()) FormattedText.Empty else FormattedText(arrayOf(pinyin), intArrayOf(0), pinyin.length)
        h.api.inputPanelCached = HostEvent.InputPanel(preedit, above, FormattedText.Empty)
        h.emit(h.api.inputPanelCached)
    }

    private fun candidates(vararg words: String) {
        h.api.candidates = Array(words.size) { CandidateWord("", words[it], "") }
        h.emit(HostEvent.Candidates(words.size, h.api.candidates.toList()))
    }

    private fun cell(word: String): View {
        val text = KeyboardHarness.all(list()!!).filterIsInstance<TextView>().first { h.shown(it) && it.text.toString() == word }
        return generateSequence(text as View) { it.parent as? View }.first { it.hasOnClickListeners() }
    }

    // the candidate at [position] of the list
    private fun word(rv: StrokePageRecyclerView, position: Int): String =
        KeyboardHarness.all(rv.findViewHolderForAdapterPosition(position)!!.itemView).filterIsInstance<TextView>().first().text.toString()

    private fun strokesSent() = h.api.calls.filter { call -> "up=false" in call && Keyboard.STROKES.any { "sym=${UNICODE + it}," in call } }

    private fun stroke(c: Char) = "sym=${UNICODE + Keyboard.strokeKey(c)},"

    private fun picked() = h.api.calls.filter { it.startsWith("select(") }

    // the first candidate wholly in sight
    private fun first(rv: StrokePageRecyclerView): Int =
        (0 until rv.childCount).map(rv::getChildAt).filter { it.top >= 0 }.minOf(rv::getChildAdapterPosition)

    // a finger down in [word]'s cell's middle, moved in [steps] of 16ms to [dx], [dy] dp from it, lifted there
    private fun draw(word: String, dx: Float, dy: Float, steps: Int = 8) {
        val v = cell(word)
        h.touch(v, *(0..steps).map { i -> h.at(v, dx * i / steps, dy * i / steps, if (i == 0) 0 else 16) }.toTypedArray())
    }

    // a finger dragged up the list from its lower part by 200dp: the list scrolled where its own drag is
    private fun dragUp(rv: StrokePageRecyclerView) {
        val b = h.box(rv)
        h.drag(b, b.exactCenterX(), b.exactCenterY() - 200 * h.service.resources.displayMetrics.density, steps = 16)
        h.after(500)
        h.relayout()
    }

    @Test
    @Uat("PY-145")
    fun aStrokeDrawnOnThePageIsSentAndThePageStaysAndScrollsNot() {
        val rv = page()
        h.api.calls.clear()
        draw("字20", 0f, 40f)
        assertEquals(h.api.calls.toString(), 1, strokesSent().size)
        assertTrue(strokesSent().single().contains(stroke('s')))
        assertEquals(listOf<String>(), picked())
        assertNotNull("the page stays", list())
        // 横 and 撇, the whole list (a cell and the blank by them alike)
        h.api.calls.clear()
        draw("字7", 40f, 0f)
        draw("字9", -25f, 35f)
        assertEquals(listOf(stroke('h'), stroke('p')), strokesSent().map { c -> stroke("hspnz".first { stroke(it) in c }) })
        // a drag up the page: not its scroll now, nor a stroke (straight up is none)
        h.api.calls.clear()
        dragUp(rv)
        assertEquals(0, first(rv))
        assertEquals(listOf<String>(), strokesSent() + picked())
    }

    // the keys and the page by one rule (PageStrokeTouch.Draws): over each of pinyin's keyboards the page draws where
    // its keys do; 注音's keys draw none, nor the page over them
    @Test
    @Uat("PY-145")
    fun thePageDrawsOverEachKeyboardAsItsKeysDo() {
        prefs.set(AppPrefs.getInstance().internal.zhuyinTraditionalTold, true)
        class Row(val why: String, val board: PinyinKeyboard, val draws: Boolean)
        assertRows(
            listOf(
                Row("26 keys", PinyinKeyboard.Full, true),
                Row("14 keys", PinyinKeyboard.Keys14, true),
                Row("18 keys", PinyinKeyboard.Keys18, true),
                Row("nine keys", PinyinKeyboard.NineKeys, true),
                Row("注音", PinyinKeyboard.ZhuyinFull, false),
            ),
            { it.why },
        ) { row ->
            // closed whether the row passes or not: the next row starts on its own keyboard (review R26 F8133)
            try {
                prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, row.board)
                prefs.set(keyboard.keyDrawing, KeyDrawing.StrokeFilter)
                open = KeyboardHarness(TestThemes.PixelLight).start()
                typed("hao")
                val keys = KeyboardHarness.all(h.keyboard).filterIsInstance<BaseKeyboard>().first { h.shown(it) }
                assertEquals("the keys", row.draws, keys.drawsStrokes())
                candidates(*Array(80) { "字$it" })
                h.tap(h.described(h.service.getString(R.string.expand_candidates_list)))
                h.api.calls.clear()
                draw("字20", 0f, 40f)
                assertEquals("the page: ${h.api.calls}", row.draws, strokesSent().isNotEmpty())
            } finally {
                close()
            }
        }
    }

    @Test
    @Uat("PY-146")
    fun aTapPicksADriftOrAStrokeLiftedShortPicksNothing() {
        page()
        // still: picked
        h.api.calls.clear()
        draw("字20", 0f, 0f, steps = 2)
        assertTrue(h.api.calls.toString(), picked().single().startsWith("select(20"))
        assertEquals(listOf<String>(), strokesSent())
        class Row(val why: String, val dx: Float, val dy: Float, val picks: Boolean)
        // the cell's touch slop, dp: 8 on a device, 16 under Robolectric, where no drift is past it and short of 12
        val slop = ViewConfiguration.get(h.service).scaledTouchSlop / h.service.resources.displayMetrics.density
        assertRows(
            listOfNotNull(
                Row("within the cell's slop, short of a stroke's start: a tap", minOf(slop, 12f) - 2, 0f, true),
                Row("past the slop, no stroke started", (slop + 12) / 2, 0f, false).takeIf { slop < 11 },
                Row("12dp across: a stroke started, short of 笔画筛选灵敏度's 24", 13f, 0f, false),
                Row("18dp down: a stroke started, short of 笔画筛选灵敏度's 24", 0f, 18f, false),
            ),
            { it.why },
        ) { row ->
            typed("hao")
            h.api.calls.clear()
            draw("字21", row.dx, row.dy)
            assertEquals(h.api.calls.toString(), row.picks, picked().isNotEmpty())
            assertEquals(listOf<String>(), strokesSent())
            assertNotNull(list())
        }
    }

    @Test
    @Uat("PY-146")
    fun aLongPressStillOpensTheCandidatesMenu() {
        page()
        h.api.learned = { LearningResult.Menu("字20", WordKind.DICTIONARY, listOf(MenuItem.BLOCK, MenuItem.PIN), null, BlockScope.EVERYWHERE) }
        h.api.calls.clear()
        val v = cell("字20")
        val f = h.finger(v, h.at(v))
        h.after(keyboard.longPressDelay.getValue().toLong() + 50)
        // moved after it: the menu's touch still, no stroke
        f.move(h.at(v, 0f, 40f))
        f.up(h.at(v, 0f, 40f))
        assertNotNull("the menu", h.inputView.candidateActionMenu)
        assertEquals(listOf<String>(), strokesSent() + picked())
    }

    @Test
    @Uat("PY-148")
    fun whereTheKeysDrawNoStrokeThePageScrollsAsBefore() {
        class Row(val why: String, val drawing: KeyDrawing = KeyDrawing.StrokeFilter, val pinyin: String = "hao", val aux: String = "", val set: () -> Unit = {})
        assertRows(
            listOf(
                Row("在键上划 off", drawing = KeyDrawing.Off),
                Row("滑行输入", drawing = KeyDrawing.Glide),
                Row("nothing typed: 联想's", pinyin = ""),
                Row("快速输入", aux = "快速输入"),
                Row("手写 writing on the keys", set = {
                    // the reader in place before the switch turns on: a keyboard still hearing it warms the reader up,
                    // and the app's cannot load in a JVM test, turning the switch off again on its worker
                    HandwritingRecognition.shared.reader = object : HandwritingRecognition.Reader {
                        override val loadable = true
                        override fun load() = Unit
                        override fun read(strokes: List<FloatArray>) = InkReading(listOf("人"), doubleArrayOf(-1.0))
                    }
                    prefs.set(AppPrefs.getInstance().internal.handwritingOpen, true)
                }),
            ),
            { it.why },
        ) { row ->
            // put back whether the row passes or not: no handwriting or fake reader carried into the next (review R26 F8133)
            try {
                row.set()
                val rv = page(row.drawing, row.pinyin, row.aux)
                h.api.calls.clear()
                draw("字20", 0f, 40f)
                assertEquals(listOf<String>(), strokesSent())
                dragUp(rv)
                assertTrue("scrolled", first(rv) > 0)
            } finally {
                close()
                prefs.set(AppPrefs.getInstance().internal.handwritingOpen, false)
            }
        }
    }

    // a mouse's drag (a tablet's, a trackpad's pointer) and a trackpad's two-finger swipe down the page: the list's, no
    // 竖 drawn (a finger's would be one)
    @Test
    @Uat("PY-148")
    fun aMouseDragOrATrackpadSwipeDrawsNone() {
        // a classification is given an event from 34 (MotionEvent.obtain's); at 29 there is none to give
        // a finger's the same way: the 竖. Each given the touchscreen's source: what tells them apart here is the tool
        // and the classification alone (one of a mouse's source never reaches the list under Robolectric)
        for (way in listOfNotNull("finger", "mouse", "trackpad".takeIf { Build.VERSION.SDK_INT >= 34 })) {
            val rv = page()
            h.api.calls.clear()
            val b = h.box(rv)
            val root = IntArray(2).also(h.inputView::getLocationInWindow)
            val x = b.exactCenterX() - root[0]
            val y0 = b.exactCenterY() - root[1]
            val down = SystemClock.uptimeMillis()
            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = if (way == "mouse") MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
            })
            fun send(action: Int, y: Float) {
                val c = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y })
                val now = SystemClock.uptimeMillis()
                val e = if (way == "finger") {
                    MotionEvent.obtain(down, now, action, 1, props, c, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                } else if (way == "mouse") {
                    MotionEvent.obtain(down, now, action, 1, props, c, 0, MotionEvent.BUTTON_PRIMARY, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                } else {
                    @Suppress("NewApi")
                    MotionEvent.obtain(down, now, action, 1, props, c, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0, 0, TWO_FINGER_SWIPE)!!
                }
                h.inputView.dispatchTouchEvent(e)
                e.recycle()
                h.idle()
            }
            send(MotionEvent.ACTION_DOWN, y0)
            for (i in 1..16) {
                h.after(16)
                send(MotionEvent.ACTION_MOVE, y0 + i * 10f)
            }
            send(MotionEvent.ACTION_UP, y0 + 16 * 10f)
            assertEquals(way, if (way == "finger") 1 else 0, strokesSent().size)
            close()
        }
    }

    // the line atop the page the first three times it opens drawing strokes: said once, gone in 2 s, taking no touch
    @Test
    @Uat("PY-147")
    fun theFirstThreePagesDrawingSayHowTheyTurn() {
        val shown = AppPrefs.getInstance().internal.strokePageHintShown
        fun hint() = KeyboardHarness.find<ExpandedCandidateLayout>(h.inputView)!!.hint
        fun closeAndOpen() {
            typed("")
            candidates()
            assertNull(list())
            typed("hao")
            candidates(*Array(80) { "字$it" })
            h.tap(h.described(h.service.getString(R.string.expand_candidates_list)))
        }
        // where the keys draw none: no line, none counted
        page(drawing = KeyDrawing.Glide)
        assertFalse(h.shown(hint()))
        assertEquals(0, shown.getValue())
        prefs.set(keyboard.keyDrawing, KeyDrawing.StrokeFilter)
        for (n in 1..3) {
            closeAndOpen()
            val shownAt = SystemClock.uptimeMillis()
            assertTrue("open $n", h.shown(hint()))
            assertEquals(n, shown.getValue())
            if (n == 1) {
                // a stroke drawn from under it: the list's, sent
                h.api.calls.clear()
                val b = h.box(hint())
                h.drag(b, b.exactCenterX(), b.exactCenterY() + 40 * h.service.resources.displayMetrics.density, steps = 8)
                assertEquals(h.api.calls.toString(), 1, strokesSent().size)
                assertTrue("whatever the finger did", h.shown(hint()))
                typed("hao")
                candidates(*Array(80) { "字$it" })
            }
            // from when it showed (the drag above took some of it)
            h.after(shownAt + 1000 - SystemClock.uptimeMillis())
            assertTrue("open $n: still at 1 s, ${SystemClock.uptimeMillis() - shownAt}", h.shown(hint()))
            h.after(shownAt + 2500 - SystemClock.uptimeMillis())
            assertFalse("gone after 2 s", h.shown(hint()))
        }
        // the fourth time: none, and never again
        closeAndOpen()
        assertFalse(h.shown(hint()))
        assertEquals(3, shown.getValue())
    }

    @Test
    @Uat("PY-149")
    fun aStrokeLeavingNoCandidateKeepsThePageAndItsBackspaceTakesItBack() {
        page()
        draw("字20", 40f, 0f)
        // the engine: nothing of 横 among them, the pinyin there still
        typed("hao")
        candidates()
        assertNotNull("the page stays, empty", list())
        assertEquals(0, list()!!.adapter!!.itemCount)
        // the column's ⌫: the engine told it
        h.api.calls.clear()
        val side = KeyboardHarness.find<ExpandedCandidateLayout.Keyboard>(h.inputView)!!
        val backspace = h.keys(side).single { it.description == h.service.getString(R.string.a11y_key_backspace) }
        h.press(backspace)
        h.release()
        assertTrue(h.api.calls.toString(), h.api.calls.any { "sym=65288," in it && "up=false" in it })
        // the stroke taken back: the candidates again on the page
        candidates(*Array(80) { "字$it" })
        h.relayout()
        assertNotNull(list())
        assertTrue(cell("字20").isShown)
        // the pinyin gone: the page closed as ever
        typed("")
        candidates()
        assertNull(list())
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    @Uat("LS-52")
    fun inLandscapeThePageDrawsAsWell() {
        val rv = page()
        h.api.calls.clear()
        val first = first(rv)
        // the page's second row: the bar holds more of them turned
        val at = rv.getChildAdapterPosition(rv.getChildAt(rv.childCount / 2))
        draw(word(rv, at), 0f, 40f)
        assertTrue(h.api.calls.toString(), strokesSent().single().contains(stroke('s')))
        dragUp(rv)
        assertEquals(first, first(rv))
        draw(word(rv, at + 1), 0f, 0f, steps = 2)
        assertEquals(h.api.calls.toString(), 1, picked().size)
    }

    // TalkBack's touch exploration: its two-finger drag comes as one finger's, the list's scroll; no stroke drawn
    @Test
    @Uat("AX-20")
    fun aScreenReadersTouchExplorationDrawsNone() {
        val rv = page()
        shadowOf(h.service.getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(true)
        h.api.calls.clear()
        draw("字20", 0f, 40f)
        assertEquals(listOf<String>(), strokesSent())
        dragUp(rv)
        assertTrue("scrolled", first(rv) > 0)
        shadowOf(h.service.getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(false)
    }

    // a second finger down: nothing of it, the first's stroke going on; the first lifted first ends it there
    @Test
    fun aSecondFingerIsSwallowed() {
        page()
        for (firstLifts in listOf(false, true)) {
            h.api.calls.clear()
            val b = h.box(cell("字20"))
            h.drag(b, b.exactCenterX(), b.exactCenterY() + 40 * h.service.resources.displayMetrics.density, lift = false)
            h.secondFingerThenLift(b.exactCenterX() + 300, b.exactCenterY(), firstLifts)
            assertEquals("$firstLifts ${h.api.calls}", 1, strokesSent().size)
            assertEquals(listOf<String>(), picked())
        }
    }

    // a finger down while ↓ still turns the page: the list's touch (it stops there), no stroke and no pick
    @Test
    fun aTouchWhileThePageTurnsIsTheLists() {
        val rv = page()
        // RecyclerView's own scroller (移除动画 is not its): settling from here
        rv.smoothScrollBy(0, 2000)
        assertEquals(RecyclerView.SCROLL_STATE_SETTLING, rv.scrollState)
        h.api.calls.clear()
        // 40dp down, straight to the list with no looper run between (the harness's idling runs the whole scroll and
        // the long press time with it)
        val x = rv.width / 2f
        val y = rv.height / 3f
        val t = SystemClock.uptimeMillis()
        for ((i, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).withIndex()) {
            val e = MotionEvent.obtain(t, t + 16L * i, action, x, y + minOf(i, 2) * 20 * h.service.resources.displayMetrics.density, 0)
            rv.dispatchTouchEvent(e)
            e.recycle()
        }
        h.idle()
        assertEquals(listOf<String>(), strokesSent() + picked())
    }

    @Test
    fun theTrailIsDrawnPastTheLiftDistanceOnly() {
        val rv = page()
        val v = cell("字20")
        val f = h.finger(v, h.at(v))
        f.move(h.at(v, 0f, 16f))
        assertFalse(rv.trailShown)
        f.move(h.at(v, 0f, 30f))
        assertTrue(rv.trailShown)
        f.up(h.at(v, 0f, 30f))
        assertFalse(rv.trailShown)
    }

    private companion object {
        // MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE
        const val TWO_FINGER_SWIPE = 3
    }
}
