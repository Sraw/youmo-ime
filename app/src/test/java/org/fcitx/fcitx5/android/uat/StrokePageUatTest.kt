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
import org.fcitx.fcitx5.android.input.learning.UndoChip
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
 * 笔画筛选 drawn on 展开候选's page: while pinyin is typed the whole list draws strokes, the page staying; no drag
 * scrolls it then, a tap still picks and a long press still opens the menu. The touch's rules are PageStrokeTouchTest's.
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

    private val reader = HandwritingRecognition.shared.reader

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

    private fun word(rv: StrokePageRecyclerView, position: Int): String =
        KeyboardHarness.all(rv.findViewHolderForAdapterPosition(position)!!.itemView).filterIsInstance<TextView>().first().text.toString()

    private fun strokesSent() = h.api.calls.filter { call -> "up=false" in call && Keyboard.STROKES.any { "sym=${UNICODE + it}," in call } }

    private fun stroke(c: Char) = "sym=${UNICODE + Keyboard.strokeKey(c)},"

    private fun picked() = h.api.calls.filter { it.startsWith("select(") }

    private fun first(rv: StrokePageRecyclerView): Int =
        (0 until rv.childCount).map(rv::getChildAt).filter { it.top >= 0 }.minOf(rv::getChildAdapterPosition)

    private fun draw(word: String, dx: Float, dy: Float, steps: Int = 8) {
        val v = cell(word)
        h.touch(v, *(0..steps).map { i -> h.at(v, dx * i / steps, dy * i / steps, if (i == 0) 0 else 16) }.toTypedArray())
    }

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

    // the keys and the page by one rule (PageStrokeTouch.Draws): the page draws where its keys do
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
            // closed whether the row passes or not: the next row starts on its own keyboard
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
        h.api.calls.clear()
        draw("字20", 0f, 0f, steps = 2)
        assertTrue(h.api.calls.toString(), picked().single().startsWith("select(20"))
        assertEquals(listOf<String>(), strokesSent())
        class Row(val why: String, val dx: Float, val dy: Float, val picks: Boolean)
        // 8 dp on a device, 16 under Robolectric, where no drift is past it and short of 12
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
                    // in place before the switch turns on: the app's reader cannot load in a JVM test, turning the switch off again
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
            // put back whether the row passes or not: no fake reader carried into the next
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

    // a mouse's drag and a trackpad's two-finger swipe down the page: the list's, no 竖 drawn
    @Test
    @Uat("PY-148")
    fun aMouseDragOrATrackpadSwipeDrawsNone() {
        // a classification is given an event from 34; at 29 there is none to give. What tells them apart here is the tool
        // and the classification alone (a mouse's source never reaches the list under Robolectric)
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

    private fun said() = h.notice()?.let { n ->
        val line = h.service.getString(R.string.stroke_page_hint)
        KeyboardHarness.all(n.contentView).filterIsInstance<TextView>().any { it.visibility == View.VISIBLE && it.text.toString() == line }
    } == true

    private fun typedOn(): View {
        typed("hao")
        candidates(*Array(80) { "字$it" })
        return h.described(h.service.getString(R.string.expand_candidates_list))
    }

    private fun closePage() {
        typed("")
        candidates()
        assertNull(list())
    }

    private fun closeAndOpen() {
        closePage()
        h.tap(typedOn())
    }

    // the first three times the page opens drawing strokes: above the keyboard, off the list, gone in 2 s
    @Test
    @Uat("PY-147")
    fun theFirstThreePagesDrawingSayHowTheyTurn() {
        val shown = AppPrefs.getInstance().internal.strokePageHintShown
        page(drawing = KeyDrawing.Glide)
        assertFalse(said())
        assertEquals(0, shown.getValue())
        prefs.set(keyboard.keyDrawing, KeyDrawing.StrokeFilter)
        for (n in 1..3) {
            closeAndOpen()
            val shownAt = SystemClock.uptimeMillis()
            assertNotNull("open $n: the page", list())
            assertTrue("open $n", said())
            assertEquals(n, shown.getValue())
            val chip = h.noticeBox()
            assertTrue("open $n: $chip over ${h.box(h.bar)}", chip.bottom <= h.box(h.bar).top + 2)
            // its own 2 s, not a said line's 1 s
            h.after(shownAt + 1500 - SystemClock.uptimeMillis())
            assertTrue("open $n: still at 1.5 s, ${SystemClock.uptimeMillis() - shownAt}", said())
            h.after(shownAt + 2500 - SystemClock.uptimeMillis())
            assertFalse("gone after 2 s", said())
        }
        closeAndOpen()
        assertFalse(said())
        assertEquals(3, shown.getValue())
    }

    @Test
    @Uat("PY-147")
    fun theHintGoesAsAStrokeIsDrawnAndIsCountedOnlyOnceSaid() {
        val shown = AppPrefs.getInstance().internal.strokePageHintShown
        page()
        val shownAt = SystemClock.uptimeMillis()
        assertTrue(said())
        assertEquals(1, shown.getValue())
        draw("字20", 0f, 40f)
        typed("hao 丨")
        h.after(shownAt + 1500 - SystemClock.uptimeMillis())
        assertFalse("put away by the stroke", said())
        closePage()
        val expand = typedOn()
        h.inputView.visibility = View.INVISIBLE
        h.tap(expand)
        h.inputView.visibility = View.VISIBLE
        h.relayout()
        assertFalse(said())
        assertEquals(1, shown.getValue())
        closePage()
        // a notice with 撤销 up: not said over it, which would lapse its act, nor counted
        val acts = mutableListOf<String>()
        val expandAgain = typedOn()
        h.inputView.say("删掉了", UndoChip.Undo({ acts += "undone" }, { acts += "lapsed" }))
        h.tap(expandAgain)
        assertNotNull("the page", list())
        assertFalse(said())
        assertEquals(1, shown.getValue())
        val undo = KeyboardHarness.all(h.notice()!!.contentView).filterIsInstance<TextView>()
            .first { it.text.toString() == h.service.getString(R.string.learning_undo) }
        assertTrue(undo.performClick())
        h.idle()
        assertEquals(listOf("undone"), acts)
        closePage()
        h.tap(typedOn())
        assertTrue(said())
        assertEquals(2, shown.getValue())
    }

    @Test
    @Uat("PY-149")
    fun aStrokeLeavingNoCandidateKeepsThePageAndItsBackspaceTakesItBack() {
        page()
        draw("字20", 40f, 0f)
        typed("hao")
        candidates()
        assertNotNull("the page stays, empty", list())
        assertEquals(0, list()!!.adapter!!.itemCount)
        h.api.calls.clear()
        val side = KeyboardHarness.find<ExpandedCandidateLayout.Keyboard>(h.inputView)!!
        val backspace = h.keys(side).single { it.description == h.service.getString(R.string.a11y_key_backspace) }
        h.press(backspace)
        h.release()
        assertTrue(h.api.calls.toString(), h.api.calls.any { "sym=65288," in it && "up=false" in it })
        candidates(*Array(80) { "字$it" })
        h.relayout()
        assertNotNull(list())
        assertTrue(cell("字20").isShown)
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
        val at = rv.getChildAdapterPosition(rv.getChildAt(rv.childCount / 2))
        draw(word(rv, at), 0f, 40f)
        assertTrue(h.api.calls.toString(), strokesSent().single().contains(stroke('s')))
        dragUp(rv)
        assertEquals(first, first(rv))
        draw(word(rv, at + 1), 0f, 0f, steps = 2)
        assertEquals(h.api.calls.toString(), 1, picked().size)
    }

    // TalkBack's touch exploration: its two-finger drag comes as one finger's, the list's scroll
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

    @Test
    fun aTouchWhileThePageTurnsIsTheLists() {
        val rv = page()
        // RecyclerView's own scroller (移除动画 is not its)
        rv.smoothScrollBy(0, 2000)
        assertEquals(RecyclerView.SCROLL_STATE_SETTLING, rv.scrollState)
        h.api.calls.clear()
        // straight to the list with no looper run between (the harness's idling runs the whole scroll and the long press time)
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
