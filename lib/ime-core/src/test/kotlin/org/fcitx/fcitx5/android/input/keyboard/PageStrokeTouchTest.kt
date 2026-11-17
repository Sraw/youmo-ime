/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.input.keyboard.PageStrokeTouch.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PageStrokeTouchTest {

    // pixels as a 3x screen has them: starts at 12dp, a stroke at 24dp, the cell's slop 8dp, 长按延迟 300ms
    private val start = 36f
    private val lift = 72f
    private val slop = 24f

    private fun touch(longPress: Long = 300) = PageStrokeTouch().apply {
        assertEquals(Route.LIST, down(0, 500f, 400f, 0, start, lift, slop, longPress))
    }

    // the finger moved in [steps] to [dx], [dy] from its down by 16ms each, then lifted there: each move's route, the lift's
    private fun PageStrokeTouch.drawn(dx: Float, dy: Float, steps: Int = 8, t0: Long = 0): Pair<List<Route>, Route> {
        val moves = (1..steps).map { i -> move(500f + dx * i / steps, 400f + dy * i / steps, t0 + 16L * i) }
        return moves to up(500f + dx, 400f + dy, t0 + 16L * steps + 8)
    }

    @Test
    fun aStrokeIsHeldFromTheListTillItStartsThenTheListIsCancelledAndItIsReadAtTheLift() {
        val t = touch()
        val (moves, lifted) = t.drawn(0f, 120f)
        // 15 a move: held till 36px, the list cancelled once, held after
        assertEquals(Route.HOLD, moves.first())
        assertEquals(1, moves.count { it == Route.CANCEL_LIST })
        assertEquals(Route.HOLD, moves.last())
        assertEquals(Route.STROKE, lifted)
        assertEquals('s', t.stroke)
        assertFalse(t.active)
    }

    @Test
    fun eachWayIsItsStrokeTiIncludedAndUpIsNone() {
        class Row(val why: String, val dx: Float, val dy: Float, val route: Route, val stroke: Char?)
        assertRows(
            listOf(
                Row("横 right", 150f, 0f, Route.STROKE, 'h'),
                Row("横 left", -150f, 0f, Route.STROKE, 'h'),
                Row("提 up to the right: 横, as on the keys", 120f, -80f, Route.STROKE, 'h'),
                Row("竖", 0f, 150f, Route.STROKE, 's'),
                Row("撇", -90f, 120f, Route.STROKE, 'p'),
                Row("点", 100f, 100f, Route.STROKE, 'n'),
                Row("straight up: no stroke, and no scroll either", 0f, -150f, Route.NONE, null),
            ),
            { it.why },
        ) { row ->
            val t = touch()
            assertEquals(row.route, t.drawn(row.dx, row.dy).second)
            assertEquals(row.stroke, t.stroke)
        }
    }

    // review: the page's places are the same units both ways, as StrokeShape reads them (GlideInput scales the keys'
    // down by ROW_HEIGHT to be so): 20° off straight down is the edge of 竖 (StrokeShape.STEEP), 撇 past it
    @Test
    fun aStrokeNearTheEdgeOfShuIsReadInTheSameUnitsBothWays() {
        fun at(degreesOffDown: Double): Char? {
            val r = Math.toRadians(degreesOffDown)
            val t = touch()
            t.drawn((-sin(r) * 150).toFloat(), (cos(r) * 150).toFloat())
            return t.stroke
        }
        assertEquals('s', at(18.0))
        assertEquals('p', at(22.0))
        assertEquals('s', at(-18.0))
        assertEquals('n', at(-22.0))
    }

    @Test
    fun aStrokeStartedButLiftedShortIsNothingNotAPick() {
        val t = touch()
        // 50px: started (36), short of 72
        val (moves, lifted) = t.drawn(50f, 0f)
        assertTrue(moves.toString(), Route.CANCEL_LIST in moves)
        assertEquals(Route.NONE, lifted)
        assertNull(t.stroke)
        // out to 80px and back: a stroke by the farthest it went
        val back = touch()
        back.move(540f, 400f, 16)
        back.move(580f, 400f, 32)
        assertEquals(Route.STROKE, back.up(510f, 400f, 48))
    }

    @Test
    fun aTapIsATapOnlyWithinTheSlop() {
        assertEquals(Route.TAP, touch().drawn(10f, 10f, steps = 2).second)
        assertEquals(Route.TAP, touch().up(500f, 400f, 50))
        // 30px: past the slop, short of a stroke's start: nothing
        assertEquals(Route.NONE, touch().drawn(30f, 0f, steps = 3).second)
        // the lift itself past the slop (no move told)
        assertEquals(Route.NONE, touch().up(530f, 400f, 50))
    }

    @Test
    fun theLongPressComingFirstIsTheCellsFromThenOn() {
        val t = touch()
        assertEquals(Route.HOLD, t.move(505f, 400f, 100))
        // a move after 长按延迟 with no stroke started: the list's, and every one after it
        assertEquals(Route.LIST, t.move(560f, 400f, 320))
        assertEquals(Route.LIST, t.move(700f, 400f, 340))
        assertEquals(Route.LIST, t.up(700f, 400f, 360))
        // held still past it and lifted: the cell's lift (its menu), no tap
        assertEquals(Route.LIST, touch().up(500f, 400f, 450))
        // R26 F8291: at 长按延迟 itself not yet, past it as GlideTouch.move has a key's
        assertEquals(Route.HOLD, touch().move(505f, 400f, 300))
        assertEquals(Route.TAP, touch().up(500f, 400f, 300))
        // 长按延迟 450: at 320 the stroke starts still
        val slow = touch(longPress = 450)
        assertEquals(Route.CANCEL_LIST, slow.move(560f, 400f, 320))
    }

    @Test
    fun anotherFingerIsSwallowedTillAllAreUp() {
        val t = touch()
        assertEquals(Route.HOLD, t.otherDown())
        // the first draws on
        t.move(500f, 440f, 16)
        t.move(500f, 520f, 32)
        // the other lifts: nothing of the first's
        assertEquals(Route.HOLD, t.pointerUp(1, 600f, 600f, 40))
        assertEquals(Route.STROKE, t.up(500f, 540f, 48))
        // the first lifts first: its stroke there, the other's lift after swallowed
        val u = touch()
        u.move(500f, 520f, 16)
        u.otherDown()
        assertEquals(Route.STROKE, u.pointerUp(0, 500f, 540f, 32))
        assertTrue(u.active)
        assertEquals(Route.HOLD, u.move(500f, 600f, 40))
        assertEquals(Route.HOLD, u.up(600f, 600f, 48))
        assertFalse(u.active)
    }

    @Test
    fun theTrailShowsOnlyPastTheLiftDistanceAndACancelLeavesNothing() {
        val t = touch()
        t.move(540f, 400f, 16)
        assertFalse("started, short of the lift", t.shown)
        t.move(580f, 400f, 32)
        assertTrue(t.shown)
        assertTrue(t.count >= 6)
        t.cancel()
        assertFalse(t.shown)
        assertFalse(t.active)
        assertEquals(0, t.count)
        assertEquals(Route.HOLD, t.up(580f, 400f, 48))
    }

    @Test
    fun manyPlacesGrowThePath() {
        val t = touch()
        for (i in 1..400) t.add(500f, 400f + i)
        assertEquals(802, t.count)
        assertEquals(500f, t.downX)
        assertEquals(400f, t.downY)
    }

    @Test
    fun theHintShowsTheFirstThreeTimesAPageDraws() {
        assertTrue(PageStrokeTouch.Hint.shows(true, 0))
        assertTrue(PageStrokeTouch.Hint.shows(true, 2))
        assertFalse("three times shown: never again", PageStrokeTouch.Hint.shows(true, 3))
        assertFalse(PageStrokeTouch.Hint.shows(true, 7))
        assertFalse("a page not drawing", PageStrokeTouch.Hint.shows(false, 0))
    }

    // the page by the keys' rule ([PageStrokeTouch.Draws.keys], BaseKeyboard's), and besides none while the keys are
    // written on or a screen reader explores by touch (the keys' own: the ink's, hovering)
    @Test
    fun aPageDrawsAsTheKeysDoAndNotWhereTheyDoNot() {
        class Row(val why: String, val draws: Boolean, val keys: Boolean = draws, val set: PageStrokeTouch.Draws.() -> Unit)
        assertRows(
            listOf(
                Row("笔画筛选, pinyin typed", true) {},
                Row("双拼", true) { im = InputMethodCatalog.SHUANGPIN },
                Row("九键", true) { im = InputMethodCatalog.T9 },
                Row("拼音 14 键", true) { im = InputMethodCatalog.PINYIN_KEYS },
                Row("拼音 18 键", true) { im = InputMethodCatalog.PINYIN_KEYS18 },
                Row("双拼九键", true) { im = InputMethodCatalog.SHUANGPIN_NINE },
                Row("a keyboard drawing none (注音's)", false) { filters = false },
                Row("在键上划 not 笔画筛选", false) { chosen = false },
                Row("a password's field", false) { password = true },
                Row("an address's field", false) { address = true },
                Row("nothing typed (联想 shown)", false) { composing = false },
                Row("快速输入", false) { tempMode = true },
                Row("English", false) { im = InputMethodCatalog.ENGLISH },
                Row("手写 writing on the keys", false, keys = true) { handwritingOpen = true },
                Row("手写 on, no text pack: not writing", true) { handwritingOpen = true; textPack = false },
                Row("手写 on, its recognizer given up: not writing", true) { handwritingOpen = true; recognizerLoadable = false },
                Row("a screen reader's touch exploration", false, keys = true) { exploring = true },
            ),
            { it.why },
        ) { row ->
            val d = PageStrokeTouch.Draws().apply {
                chosen = true
                composing = true
                im = InputMethodCatalog.PINYIN
                textPack = true
                recognizerLoadable = true
                row.set(this)
            }
            assertEquals("the page", row.draws, d.on)
            assertEquals("the keys", row.keys, d.keys)
            assertEquals("the keys' call", row.keys, PageStrokeTouch.Draws.keys(d.chosen, d.password, d.address, d.composing, d.tempMode, d.im, d.filters))
        }
    }
}
