/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.input.keyboard.NineKeySymbols
import org.fcitx.fcitx5.android.input.keyboard.T9Keyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupSceneView
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupScenes
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.engine.Popups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.ceil

/**
 * A key of the long-press popup is typed where it is drawn. With more columns than a short top
 * row fills (13 entries: 5 columns, 3 rows), the empty slots fall at both ends when the popup is
 * centred over its key; laid out by gravity, the top row's keys were drawn a column or two off
 * from where the finger selects them, and the neighbour was typed.
 *
 * The keys the engine's scene (060-render-p2 S2), read from the scene's keys (the views' were read the
 * same till S8a deleted them).
 */
@RunWith(RobolectricTestRunner::class)
// the canvas's scenes read the theme's look (ThemeManager, so AppPrefs)
@Config(application = FcitxApplication::class)
class PopupKeyboardUiTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private val keyWidth = 40
    private val keyHeight = 50
    private val outer = Rect(0, 0, 1000, 600)

    private fun popup(count: Int, trigger: Rect): PopupKeyboardUi =
        popup(Array(count) { ('a' + it).toString() }, trigger)

    private fun popup(
        keys: Array<String>, trigger: Rect, outerBounds: Rect = outer, minKeyWidth: Int = keyWidth, split: Int = keys.size,
        asIs: Boolean = false, inRow: Int = -1, onFocusMoved: (View) -> Unit = {}, onDismiss: () -> Unit = {},
        faceInset: Int = 0,
    ): PopupKeyboardUi {
        return PopupKeyboardUi(
            ctx = RuntimeEnvironment.getApplication(),
            theme = TestThemes.PixelDark,
            outerBounds = outerBounds,
            triggerBounds = trigger,
            onDismissSelf = { onDismiss() },
            minKeyWidth = minKeyWidth,
            keyHeight = keyHeight,
            lift = 10,
            keys = keys,
            labels = keys,
            split = split,
            asIs = asIs,
            inRow = inRow,
            onFocusMoved = onFocusMoved,
            scenes = PopupScenes(View(context), TestThemes.PixelDark),
            faceInset = faceInset,
        )
    }


    // the canvas's: the scene drawn
    private fun PopupKeyboardUi.scene(): Scene = (root as PopupSceneView).scene!!

    /** How many rows it has, drawn. */
    private fun PopupKeyboardUi.rows(): Int = (scene().height / keyHeight).toInt()

    /** Every key drawn, by its label, at (row from the bottom, column), as laid out. */
    private fun drawn(ui: PopupKeyboardUi): Map<String, Pair<Int, Int>> {
        val s = ui.scene()
        return s.keys.associate { k -> k.label!! to (((s.height - k.bounds.bottom) / keyHeight).toInt() to (k.bounds.left / ui.keyWidth).toInt()) }
    }

    private val started = mutableSetOf<PopupKeyboardUi>()

    // the finger moved from where it was when the popup opened, a step a key ([PopupFocus.STEP]):
    // the first move is where it starts, the key in focus at first (row 0)
    private fun PopupKeyboardUi.select(row: Int, column: Int): KeyAction? {
        // from over the key in focus: room either side for whole steps (slideTo, from the finger)
        val x0 = keyWidth * (focusColumn + 0.5f)
        if (started.add(this)) onChangeFocus(x0, 0f)
        onChangeFocus(x0 + (column - focusColumn) * keyWidth * PopupFocus.STEP, -row * keyHeight * PopupFocus.STEP)
        return onTrigger()
    }

    private fun assertTypedWhereDrawn(count: Int, trigger: Rect) {
        val ui = popup(count, trigger)
        val keys = drawn(ui)
        assertEquals("every key is drawn", count, keys.size)
        keys.forEach { (label, cell) ->
            assertEquals(
                "$count keys: the key drawn at $cell is the one typed there",
                KeyAction.HostKeyAction(label),
                ui.select(cell.first, cell.second)
            )
        }
    }

    @Test
    fun aCentredPopupTypesTheKeyUnderTheFinger() {
        val centre = Rect(480, 500, 520, 550)
        // the user's own list (PopupOverrides) can be any length
        for (count in 1..30) assertTypedWhereDrawn(count, centre)
    }

    @Test
    fun theKeysSetApartAreRowsOfTheirOwnAboveTheOthers() {
        // 九键's 7: its digit and letters under the finger as before, its symbols above
        val keys = arrayOf("7", "p", "q", "r", "s", "*", "%", "&", "^", "$", "#", "!")
        val ui = popup(keys, Rect(480, 500, 520, 550), split = 5)
        val drawn = drawn(ui)
        assertEquals(keys.size, drawn.size)
        assertEquals(keys.take(5).toSet(), drawn.filterValues { it.first == 0 }.keys)
        assertTrue("7 symbols in rows 1 and 2", drawn.filterKeys { it in keys.drop(5) }.values.all { it.first in 1..2 })
        // the first symbol right above the digit, the key under the finger
        assertEquals(drawn["7"]!!.second, drawn["*"]!!.second)
        drawn.forEach { (label, cell) -> assertEquals(KeyAction.HostKeyAction(label), ui.select(cell.first, cell.second)) }
        // no line between them: the scene's base the panel only
        assertEquals(listOf("Shadow", "Fill"), ui.scene().base.map { it::class.simpleName })
    }

    @Test
    fun aRowInOrderIsDrawnSoTheKeyGivenUnderTheFinger() {
        // 九键's 2: A B C 2 a b c in one row, the digit under the finger, its symbols above round it
        val keys = arrayOf("A", "B", "C", "2", "a", "b", "c", "@", "!", "\"")
        val trigger = Rect(480, 500, 520, 550)
        val ui = popup(keys, trigger, split = 7, inRow = 3)
        val drawn = drawn(ui)
        assertEquals((0 until 7).map { 0 to it }, keys.take(7).map { drawn[it] })
        assertEquals(KeyAction.HostKeyAction("2"), ui.onTrigger())
        assertEquals(drawn["2"]!!.second, drawn["@"]!!.second)
        assertTrue(keys.drop(7).all { drawn[it]!!.first == 1 })
        // the digit centred over the key, room enough either side
        assertEquals(trigger.left + (trigger.width() - ui.keyWidth) / 2, trigger.left + ui.focusedKeyLeft)
        // too many to fit across at full width: narrower, all in the one row still
        val narrow = popup(keys, trigger, outerBounds = Rect(0, 0, 7 * keyWidth / 2, 2000), split = 7, inRow = 3)
        assertEquals(keyWidth / 2, narrow.keyWidth)
        assertEquals(KeyAction.HostKeyAction("2"), narrow.onTrigger())
    }

    @Test
    fun underTwoRowsOfLettersTheSymbolsStillHaveRowsOfTheirOwn() {
        // a 9 the user gave 6 letters: its digit and letters take 2 rows, the symbols the 3rd
        val keys = arrayOf("9", "w", "x", "y", "z", "ä", "ö", ":", "'", "~")
        val ui = popup(keys, Rect(480, 500, 520, 550), split = 7)
        val drawn = drawn(ui)
        assertEquals(keys.size, drawn.size)
        assertTrue(drawn.filterKeys { it in keys.take(7) }.values.all { it.first in 0..1 })
        assertTrue(drawn.filterKeys { it in keys.drop(7) }.values.all { it.first == 2 })
        assertEquals(3, ui.rows())
    }

    @Test
    fun withNoneSetApartAPopupIsAsBefore() {
        // none set apart, the split at either end (PopupComponent passes the key count); 7 keys: 4 columns, the 2nd in focus
        // (centre, right, left, right), the top row's last slot empty
        val keys = Array(7) { ('a' + it).toString() }
        val expected = mapOf(
            "a" to (0 to 1), "b" to (0 to 2), "c" to (0 to 0), "d" to (0 to 3),
            "e" to (1 to 1), "f" to (1 to 2), "g" to (1 to 0),
        )
        assertEquals(expected, drawn(popup(keys, Rect(480, 500, 520, 550), split = Int.MAX_VALUE)))
        assertEquals(expected, drawn(popup(keys, Rect(480, 500, 520, 550), split = 0)))
        assertEquals(expected, drawn(popup(keys, Rect(480, 500, 520, 550), split = keys.size)))
    }

    // 九键's 1: . committed as it is, not a key the engine would make 。
    @Test
    fun asIsEveryKeyIsCommittedAsItIs() {
        val ui = popup(arrayOf(".", "-"), Rect(480, 500, 520, 550), asIs = true)
        assertEquals(KeyAction.CommitAction("."), ui.onTrigger())
        assertEquals(KeyAction.HostKeyAction("."), popup(arrayOf(".", "-"), Rect(480, 500, 520, 550)).onTrigger())
    }

    @Test
    fun atAnEdgeTheFirstSymbolIsStillAboveTheDigit() {
        // 九键's 2 with ten symbols: a short bottom row, two of symbols above
        val keys = arrayOf("2", "a", "b", "c") + Array(10) { "#$it" }
        for (trigger in listOf(Rect(0, 500, 40, 550), Rect(960, 500, 1000, 550))) {
            val ui = popup(keys, trigger, split = 4)
            val drawn = drawn(ui)
            assertEquals(keys.size, drawn.size)
            assertEquals(setOf("2", "a", "b", "c"), drawn.filterValues { it.first == 0 }.keys)
            assertEquals(drawn["2"]!!.second, drawn["#0"]!!.second)
            assertEquals(1, drawn["#0"]!!.first)
            drawn.forEach { (label, cell) -> assertEquals(PopupKeyboardUi.keyAction(label), ui.select(cell.first, cell.second)) }
        }
    }

    @Test
    fun aPopupAtAnEdgeTypesTheKeyUnderTheFinger() {
        // a short top row: one empty slot (7), two (13, 18), three (17)
        for (count in listOf(7, 13, 17, 18)) {
            assertTypedWhereDrawn(count, Rect(0, 500, 40, 550))
            assertTypedWhereDrawn(count, Rect(960, 500, 1000, 550))
        }
    }

    @Test
    fun aRowInOrderAtAnEdgeHasItsOwnKeyAtThatEnd() {
        // 18 键's L (L - l under 5 symbols, the row shorter), 九键's 2 (longer than its symbols), 九键双拼's
        // 5 and 9 (060-edge-popup): at an edge the key's own is at that end of the row, the rest in order
        // inward, as the full keyboard's own key at an edge; mid-screen the row in order round it
        val cases = listOf(
            arrayOf("L", "-", "l", "#0", "#1", "#2", "#3", "#4") to 1,
        ) + listOf(nine('2', false), nine('5', true), nine('9', true)).map { it.items to it.inRow }
        val right = Rect(960, 500, 1000, 550)
        val left = Rect(0, 500, 40, 550)
        val centre = Rect(480, 500, 520, 550)
        for ((keys, inRow) in cases) {
            val split = 2 * inRow + 1
            val others = keys.take(split).filterIndexed { i, _ -> i != inRow }
            for (trigger in listOf(right, left, centre)) {
                val ui = popup(keys, trigger, split = split, inRow = inRow)
                val drawn = drawn(ui)
                val what = "${keys[inRow]} at ${trigger.left}"
                assertEquals(what, keys.size, drawn.size)
                val row = drawn.filterValues { it.first == 0 }.entries.sortedBy { it.value.second }.map { it.key }
                val expected = when (trigger) {
                    right -> others + keys[inRow]
                    left -> listOf(keys[inRow]) + others
                    else -> keys.take(split)
                }
                assertEquals(what, expected, row)
                // the key's own in focus at first, the first symbol above it
                assertEquals(what, ui.focusColumn, drawn[keys[inRow]]!!.second)
                assertEquals(what, KeyAction.HostKeyAction(keys[inRow]), ui.onTrigger())
                assertEquals(what, ui.focusColumn, drawn[keys[split]]!!.second)
                if (trigger == centre) assertEquals(what, trigger.left + (trigger.width() - ui.keyWidth) / 2, trigger.left + ui.focusedKeyLeft)
                drawn.forEach { (label, cell) -> assertEquals(what, PopupKeyboardUi.keyAction(label), ui.select(cell.first, cell.second)) }
            }
        }
    }

    // 九键's (and 九键双拼's) own long press of [digit], as T9Keyboard makes it
    private fun nine(digit: Char, shuangpin: Boolean): KeyDef.Popup.Keyboard.Explicit = T9Keyboard.ninePopup(
        digit, T9Keyboard.lettersOn(digit, NineKeyLetters.STANDARD, english = false, shuangpin = shuangpin),
        NineKeySymbols.STANDARD.symbols(digit),
    )

    private val density get() = context.resources.displayMetrics.density

    // a key's face inset in its cell, as the default theme draws it: the popup is placed by the face
    // (KeyboardCanvasView.popupBounds), the finger's x is from the cell (SceneTouch)
    private fun Rect.face(inset: Int = (3 * density).toInt()) = Rect(this).apply { inset(inset, 0) }

    // [ui]'s focus after the finger, from [cell]'s middle, slides a pixel at a time to [screenX] (the
    // screen's) across, as a device reports it
    private fun PopupKeyboardUi.slideTo(cell: Rect, screenX: Int): KeyAction? {
        val y = -offsetY.toFloat() + cell.height() / 2f
        val from = cell.centerX()
        val dir = if (screenX >= from) 1 else -1
        var x = from
        onChangeFocus(x - cell.left - offsetX.toFloat(), y)
        while (x != screenX) {
            x += dir
            onChangeFocus(x - cell.left - offsetX.toFloat(), y)
        }
        return onTrigger()
    }

    @Test
    fun atTheScreensEdgeEveryKeyIsReachedSlidingInward() {
        // keys wide enough that 7 of them are pushed in from the edge (060-edge-popup): the finger,
        // already by the edge, only slides inward, a key a step; outward it stays on the key's own
        val wide = 120
        val right = Rect(800, 500, 1000, 550)
        val left = Rect(0, 500, 200, 550)
        for (insetDp in listOf(3, 9)) for (shuangpin in listOf(false, true)) for ((digits, cell) in listOf("369" to right, "47" to left)) for (d in digits) {
            assertReachedInward(d, shuangpin, cell, cell == right, (insetDp * density).toInt(), wide)
        }
    }

    // [d]'s popup over [cell] by the screen's right ([toRight]) or left edge: its digit first from that
    // edge, each key inward a step more, the edge itself still the digit
    private fun assertReachedInward(d: Char, shuangpin: Boolean, cell: Rect, toRight: Boolean, inset: Int, wide: Int) {
        val popup = nine(d, shuangpin)
        val ui = { popup(popup.items, cell.face(inset), minKeyWidth = wide, split = popup.symbolsFrom, inRow = popup.inRow, faceInset = inset) }
        val what = "$d${if (shuangpin) " 双拼" else ""} at ${cell.left}, inset ${inset / density}"
        val inward = if (toRight) -1 else 1
        val step = ui().keyWidth * PopupFocus.STEP
        val drawn = drawn(ui()).filterValues { it.first == 0 }.entries.sortedBy { inward * it.value.second }.map { it.key }
        assertEquals(what, d.toString(), drawn[0])
        // the k-th key inward a little past k steps (half a step and the slack before it)
        drawn.forEachIndexed { k, label ->
            val x = cell.centerX() + inward * ((k * step).toInt() + 2)
            assertEquals("$what $k", PopupKeyboardUi.keyAction(label), ui().slideTo(cell, x))
        }
        // to the screen's edge: still the key's own
        assertEquals(what, PopupKeyboardUi.keyAction(d.toString()), ui().slideTo(cell, if (toRight) outer.right - 1 else outer.left))
    }

    @Test
    fun aPopupNotInOrderKeepsWholeStepsAtTheEdge() {
        // the full keyboard's: no row in order, so no shorter steps by the edge (none given), the
        // finger a key and a half from it, a key between; a step is a key
        val narrow = Rect(0, 0, 5 * keyWidth, 600)
        val cell = Rect(keyWidth, 500, 2 * keyWidth, 550)
        val ui = { popup(Array(5) { ('a' + it).toString() }, cell, outerBounds = narrow) }
        val keys = drawn(ui())
        val focus = ui().focusColumn
        assertTrue("$focus", focus >= 1)
        val leftOne = keys.entries.first { it.value == (0 to focus - 1) }.key
        val step = (ui().keyWidth * PopupFocus.STEP * 0.65f).toInt() + 1
        assertEquals(KeyAction.HostKeyAction(leftOne), ui().slideTo(cell, cell.centerX() - step))
    }

    @Test
    fun awayFromTheEdgesAStepIsAsBefore() {
        val cell = Rect(450, 500, 550, 550)
        val popup = nine('5', shuangpin = false)
        val row = popup.items.take(popup.symbolsFrom)
        val ui = { popup(popup.items, cell.face(), split = popup.symbolsFrom, inRow = popup.inRow) }
        // the next key past half a step and the slack (0.65 of a step), not before
        val next = (ui().keyWidth * PopupFocus.STEP * 0.65f).toInt() + 1
        assertEquals(PopupKeyboardUi.keyAction(row[popup.inRow + 1]), ui().slideTo(cell, cell.centerX() + next))
        assertEquals(PopupKeyboardUi.keyAction(row[popup.inRow - 1]), ui().slideTo(cell, cell.centerX() - next))
        assertEquals(PopupKeyboardUi.keyAction("5"), ui().slideTo(cell, cell.centerX() + next - 2))
        assertEquals(PopupKeyboardUi.keyAction("5"), ui().slideTo(cell, cell.centerX() - next + 2))
    }

    @Test
    fun theTopRowKeepsItsEmptySlots() {
        // 13 keys, 5 columns: the top row's 3 are drawn in the middle columns, not packed to one side
        val keys = drawn(popup(13, Rect(480, 500, 520, 550)))
        assertEquals(setOf(1, 2, 3), keys.values.filter { it.first == 2 }.map { it.second }.toSet())
    }

    /** The long press and a letter's swipe (its first) type a pick the same way. */
    @Test
    fun oneCharacterIsAKeyAndAnythingLongerIsText() {
        assertEquals(KeyAction.HostKeyAction("x"), PopupKeyboardUi.keyAction("x"))
        assertEquals(KeyAction.HostKeyAction("é"), PopupKeyboardUi.keyAction("é"))
        // one code point, two chars
        assertEquals(KeyAction.HostKeyAction("😀"), PopupKeyboardUi.keyAction("😀"))
        assertEquals(KeyAction.CommitAction("Émile"), PopupKeyboardUi.keyAction("Émile"))
        // spells a key name, still text
        assertEquals(KeyAction.CommitAction("Tab"), PopupKeyboardUi.keyAction("Tab"))
        // one letter to the eye (Yiddish ײַ), two code points: text
        assertEquals(KeyAction.CommitAction("\u05F2\u05B7"), PopupKeyboardUi.keyAction("\u05F2\u05B7"))
    }

    /** A key's label as drawn: [size] px, [width] its text's at it, [drawn] how wide it is drawn (a view's measured width, the scene's squeezed). */
    private class Label(val text: String, val size: Float, val width: Float, val drawn: Float)

    private fun PopupKeyboardUi.labels(): List<Label> {
        val paint = Paint()
        return scene().keys.flatMap { it.ops(false) }.filterIsInstance<Op.Text>().map {
            paint.textSize = it.size.toFloat()
            val w = paint.measureText(it.text)
            Label(it.text, it.size.toFloat(), w, ceil(w * it.scaleX).toFloat())
        }
    }

    /** `.com`'s long press: its labels each fitted to a fixed width came out at four sizes. */
    @Test
    fun keysOfLabelsOfManyLengthsAreOneWidthAndOneTextSize() {
        val domains = arrayOf(".com", ".cn", ".net", ".org")
        val ui = popup(domains, Rect(480, 500, 520, 550))
        val labels = ui.labels()
        assertEquals(4, labels.size)
        assertEquals(1, labels.map { it.size }.toSet().size)
        // wide enough for the widest, so none is made smaller
        assertTrue(labels.all { it.width < ui.keyWidth })
        assertTrue(ui.keyWidth >= keyWidth)
    }

    /**
     * The engine squeezes a label too wide for its key, its text size unchanged: one text size held,
     * `.com` too wide for its 38 dp. Under a bubble narrower
     * than any label, every label fits its key with its padding (6 dp a side, at 1 dp = 1 px) and is
     * measured as wide as its text: none is drawn smaller.
     */
    @Test
    fun everyLabelFitsItsKeySoNoneIsDrawnSmaller() {
        val domains = arrayOf(".com", ".cn", ".net", ".org")
        val ui = popup(domains, Rect(480, 500, 520, 550), minKeyWidth = 1)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        ui.root.measure(unspecified, unspecified)
        for (label in ui.labels()) {
            val text = label.width
            assertTrue("${label.text}: $text in ${ui.keyWidth}", text + 12 <= ui.keyWidth)
            assertEquals(label.text, ceil(text).toInt(), label.drawn.toInt())
            // the scene's at its full size (Popups' 20 dp; Robolectric's density is 1)
            assertEquals(label.text, Popups.KEY_TEXT_DP.toFloat(), label.size)
        }
    }

    /** Where the popup starts and ends across, as PopupComponent places it over [trigger]. */
    private fun PopupKeyboardUi.across(trigger: Rect, columns: Int) =
        (trigger.left + offsetX).let { it to it + columns * keyWidth }

    // keys as wide as the bubble may be wider than the key pressed: at an edge no column centred over it fits
    @Test
    fun aPopupOfKeysWiderThanItsTriggerStaysOnScreen() {
        // nine keys' 9: five keys of a fifth of the width, over a key a little wider
        val nine = Rect(600, 500, 820, 550)
        val ui = popup(Array(5) { ('a' + it).toString() }, nine, minKeyWidth = 220)
        assertEquals(200, ui.keyWidth)
        assertEquals(0 to 1000, ui.across(nine, 5))
        // moved in by 10: the first key starts at the trigger's left, not 10 right of it, and is still under the finger
        assertEquals(0, ui.focusedKeyLeft)
        ui.changeFocus(nine.width() / 2f, nine.height() / 2f)
        assertEquals(KeyAction.HostKeyAction("a"), ui.onTrigger())
        // a long word over Q, the key at either edge
        for (q in listOf(Rect(0, 500, 40, 550), Rect(960, 500, 1000, 550))) {
            val (left, right) = popup(arrayOf("q", "hello"), q, minKeyWidth = 80).across(q, 2)
            assertTrue("$q: $left to $right", left >= 0 && right <= 1000)
        }
    }

    // pressed before the popups' root is laid out, its bounds still empty: keys of no width hung the column search
    @Test
    fun boundsNotYetLaidOutLeaveTheKeysTheirWidth() {
        val ui = popup(arrayOf("a", "b", "c"), Rect(480, 500, 520, 550), Rect())
        assertEquals(keyWidth, ui.keyWidth)
    }

    @Test
    fun keysTooWideToFitAcrossAreNarrowerAndAllTheirLabelsSmallerAlike() {
        val long = arrayOf("example", "ex", "examples", "e", "exam")
        val roomy = popup(long, Rect(480, 500, 520, 550))
        // five across, each half as wide as the widest label and its padding (6 dp a side, at 1 dp = 1 px)
        val fitting = roomy.labels().maxOf { it.width }.toInt() + 12
        val narrow = Rect(0, 0, 5 * (fitting / 2), 600)
        val ui = popup(long, Rect(0, 500, 40, 550), narrow)
        assertEquals(fitting / 2, ui.keyWidth)
        val sizes = ui.labels().map { it.size }.toSet()
        assertEquals(1, sizes.size)
        assertTrue(sizes.single() < roomy.labels().first().size)
    }

    @Test
    fun aSmallMoveIsAKeyAndOnTheKeyPressedTheBottomRow() {
        val ui = popup(10, Rect(480, 500, 520, 550))
        val step = keyWidth * PopupFocus.STEP
        ui.onChangeFocus(100f, 100f)
        val first = ui.onTrigger()
        // under half a step: the same key
        ui.onChangeFocus(100f + step / 3, 100f)
        assertEquals(first, ui.onTrigger())
        // a step: the next key, a step back the first again
        ui.onChangeFocus(100f + step, 100f)
        val next = ui.onTrigger()
        assertTrue(next != first)
        ui.onChangeFocus(100f, 100f)
        assertEquals(first, ui.onTrigger())
        // down, still on the key pressed: the bottom row, nothing cancelled
        assertFalse(ui.onChangeFocus(100f, 140f))
        assertEquals(first, ui.onTrigger())
    }

    @Test
    fun onlyDownOffTheKeyPressedPicksNothing() {
        var dismissed = 0
        val ui = popup(Array(7) { ('a' + it).toString() }, Rect(480, 500, 520, 550), onDismiss = { dismissed++ })
        // the key's own coordinates, as BaseKeyboard passes them
        ui.changeFocus(20f, 20f)
        // far up, left, right: a key still
        for ((x, y) in listOf(20f to -900f, -900f to 20f, 900f to 20f, 900f to -900f)) {
            assertFalse(ui.changeFocus(x, y))
            assertTrue(ui.onTrigger() != null)
        }
        assertEquals(0, dismissed)
        // below the key's bottom edge (50)
        assertTrue(ui.changeFocus(20f, 51f))
        assertEquals(1, dismissed)
    }

    @Test
    fun aPressNearTheKeysBottomCancelsJustBelowIt() {
        var dismissed = 0
        val ui = popup(Array(7) { ('a' + it).toString() }, Rect(480, 500, 520, 550), onDismiss = { dismissed++ })
        ui.changeFocus(20f, 45f)
        assertFalse(ui.changeFocus(20f, 49f))
        assertEquals(0, dismissed)
        // 7 down, not a whole key's height: off the key
        assertTrue(ui.changeFocus(20f, 52f))
        assertEquals(1, dismissed)
    }

    @Test
    fun atAnEdgeTheDigitIsInFocusAndAStepPicksItsNeighbours() {
        // 九键's 2 at the left: seven keys fit across only narrower, the row fills the screen with its
        // digit first (060-edge-popup); the finger steps right from it, a key a step
        val keys = arrayOf("A", "B", "C", "2", "a", "b", "c")
        val ui = popup(keys, Rect(0, 500, 40, 550), outerBounds = Rect(0, 0, 7 * keyWidth / 2, 2000), split = 7, inRow = 3)
        val step = ui.keyWidth * PopupFocus.STEP
        assertFalse(ui.changeFocus(25f, 20f))
        assertEquals(KeyAction.HostKeyAction("2"), ui.onTrigger())
        // left to the screen's edge: nothing there, still 2
        ui.changeFocus(0f, 20f)
        assertEquals(KeyAction.HostKeyAction("2"), ui.onTrigger())
        ui.changeFocus(25f + step + 2, 20f)
        assertEquals(KeyAction.HostKeyAction("A"), ui.onTrigger())
        ui.changeFocus(25f + 5 * step + 2, 20f)
        assertEquals(KeyAction.HostKeyAction("b"), ui.onTrigger())
    }

    @Test
    fun eachKeyComingIntoFocusBuzzesOnce() {
        var buzzes = 0
        val keys = arrayOf("A", "B", "C", "2", "a", "b", "c")
        val ui = popup(keys, Rect(480, 500, 520, 550), split = 7, inRow = 3, onFocusMoved = { buzzes++ })
        val step = ui.keyWidth * PopupFocus.STEP
        ui.onChangeFocus(0f, 0f)
        ui.onChangeFocus(step / 4, 0f)
        assertEquals(0, buzzes)
        ui.onChangeFocus(step, 0f)
        ui.onChangeFocus(step * 1.1f, 0f)
        assertEquals(1, buzzes)
        ui.onChangeFocus(step * 3, 0f)
        assertEquals(2, buzzes)
    }

    // the scene's: a focus moved is the scene drawn again, that key selected, nothing else changed
    @Test
    fun theKeyInFocusIsDrawnSelected() {
        val ui = popup(arrayOf("a", "b", "c"), Rect(480, 500, 520, 550))
        fun selected() = ui.scene().keys.filter { KeyState.Selected in it.states }.map { it.label }
        assertEquals(listOf("a"), selected())
        val before = ui.scene()
        ui.onChangeFocus(0f, 0f)
        ui.onChangeFocus(ui.keyWidth * PopupFocus.STEP, 0f)
        val picked = (ui.onTrigger() as KeyAction.HostKeyAction).act
        assertEquals(listOf(picked), selected())
        assertEquals(before.keys.map { it.bounds }, ui.scene().keys.map { it.bounds })
    }

    // TalkBack reads each key of the scene, the one in focus selected, as it moves (M2: a basic node
    // a key, its words and place; the keyboard's whole treatment is S3's)
    @Test
    fun talkBackReadsEachKeyOfTheScene() {
        val ui = popup(arrayOf("a", "b", "c"), Rect(480, 500, 520, 550))
        val view = ui.root as PopupSceneView
        fun read() = view.spokenKeys().associate { it.contentDescription.toString() to it.isSelected }
        assertEquals(mapOf("a" to true, "b" to false, "c" to false), read())
        ui.onChangeFocus(0f, 0f)
        ui.onChangeFocus(ui.keyWidth * PopupFocus.STEP, 0f)
        val picked = (ui.onTrigger() as KeyAction.HostKeyAction).act
        assertEquals(listOf(picked), read().filterValues { it }.keys.toList())
        // each where it is drawn: its box past the shadow's room
        val a = ui.scene().keys.first { it.label == "a" }.bounds
        @Suppress("DEPRECATION")
        val box = android.graphics.Rect().also { view.spokenKeys().first { n -> n.contentDescription == "a" }.getBoundsInParent(it) }
        assertEquals(a.left.toInt() + view.outset.left, box.left)
        assertEquals(a.top.toInt() + view.outset.top, box.top)
    }

    @Test
    fun aKeyOfNoLettersStillHasItsSymbolsFiveARow() {
        // a 1 the user left with its digit alone: its ten symbols two rows of five, not ten of one
        val keys = arrayOf("1") + Array(10) { "#$it" }
        val ui = popup(keys, Rect(480, 500, 520, 550), split = 1, inRow = 0)
        val drawn = drawn(ui)
        assertEquals(3, ui.rows())
        // centred, room either side: the digit in the middle column, under the first symbol
        assertEquals(0 to 2, drawn["1"])
        assertEquals(1 to 2, drawn["#0"])
        assertEquals(KeyAction.HostKeyAction("1"), ui.onTrigger())
    }
}
