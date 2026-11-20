/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
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
 * A popup key is typed where it is drawn: with a short top row (13 entries: 5 columns, 3 rows) laid out by gravity,
 * the top row was drawn a column or two off from where the finger selects, and the neighbour was typed.
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
        )
    }


    private fun PopupKeyboardUi.scene(): Scene = (root as PopupSceneView).scene!!

    private fun PopupKeyboardUi.rows(): Int = (scene().height / keyHeight).toInt()

    /** Every key drawn, by its label, at (row from the bottom, column). */
    private fun drawn(ui: PopupKeyboardUi): Map<String, Pair<Int, Int>> {
        val s = ui.scene()
        return s.keys.associate { k -> k.label!! to (((s.height - k.bounds.bottom) / keyHeight).toInt() to (k.bounds.left / ui.keyWidth).toInt()) }
    }

    private val started = mutableSetOf<PopupKeyboardUi>()

    // a step a key ([PopupFocus.STEP]) from where the finger was as it opened
    private fun PopupKeyboardUi.select(row: Int, column: Int): KeyAction? {
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
        for (count in 1..30) assertTypedWhereDrawn(count, centre)
    }

    @Test
    fun theKeysSetApartAreRowsOfTheirOwnAboveTheOthers() {
        val keys = arrayOf("7", "p", "q", "r", "s", "*", "%", "&", "^", "$", "#", "!")
        val ui = popup(keys, Rect(480, 500, 520, 550), split = 5)
        val drawn = drawn(ui)
        assertEquals(keys.size, drawn.size)
        assertEquals(keys.take(5).toSet(), drawn.filterValues { it.first == 0 }.keys)
        assertTrue("7 symbols in rows 1 and 2", drawn.filterKeys { it in keys.drop(5) }.values.all { it.first in 1..2 })
        assertEquals(drawn["7"]!!.second, drawn["*"]!!.second)
        drawn.forEach { (label, cell) -> assertEquals(KeyAction.HostKeyAction(label), ui.select(cell.first, cell.second)) }
        assertEquals(listOf("Shadow", "Fill"), ui.scene().base.map { it::class.simpleName })
    }

    @Test
    fun aKeysOwnIsUnderTheFingerAndTheRestRoundItAsListed() {
        // 九键's 2 mid-screen: 2 under the finger, the rest right, left, right ... as listed: c a B 2 A C b
        val keys = arrayOf("A", "B", "C", "2", "a", "b", "c", "@", "!", "\"")
        val trigger = Rect(480, 500, 520, 550)
        val ui = popup(keys, trigger, split = 7, inRow = 3)
        val drawn = drawn(ui)
        assertEquals(listOf("c", "a", "B", "2", "A", "C", "b"), (0 until 7).map { c -> drawn.entries.first { it.value == (0 to c) }.key })
        assertEquals(KeyAction.HostKeyAction("2"), ui.onTrigger())
        assertEquals(drawn["2"]!!.second, drawn["@"]!!.second)
        assertTrue(keys.drop(7).all { drawn[it]!!.first == 1 })
        assertEquals(trigger.left + (trigger.width() - ui.keyWidth) / 2, trigger.left + ui.focusedKeyLeft)
        val narrow = popup(keys, trigger, outerBounds = Rect(0, 0, 7 * keyWidth / 2, 2000), split = 7, inRow = 3)
        assertEquals(keyWidth / 2, narrow.keyWidth)
        assertEquals(KeyAction.HostKeyAction("2"), narrow.onTrigger())
    }

    @Test
    fun underTwoRowsOfLettersTheSymbolsStillHaveRowsOfTheirOwn() {
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
        // none set apart, the split at either end; 7 keys: 4 columns, the 2nd in focus, the top row's last slot empty
        val keys = Array(7) { ('a' + it).toString() }
        val expected = mapOf(
            "a" to (0 to 1), "b" to (0 to 2), "c" to (0 to 0), "d" to (0 to 3),
            "e" to (1 to 1), "f" to (1 to 2), "g" to (1 to 0),
        )
        assertEquals(expected, drawn(popup(keys, Rect(480, 500, 520, 550), split = Int.MAX_VALUE)))
        assertEquals(expected, drawn(popup(keys, Rect(480, 500, 520, 550), split = 0)))
        assertEquals(expected, drawn(popup(keys, Rect(480, 500, 520, 550), split = keys.size)))
    }

    @Test
    fun asIsEveryKeyIsCommittedAsItIs() {
        val ui = popup(arrayOf(".", "-"), Rect(480, 500, 520, 550), asIs = true)
        assertEquals(KeyAction.CommitAction("."), ui.onTrigger())
        assertEquals(KeyAction.HostKeyAction("."), popup(arrayOf(".", "-"), Rect(480, 500, 520, 550)).onTrigger())
    }

    @Test
    fun atAnEdgeTheFirstSymbolIsStillAboveTheDigit() {
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
        for (count in listOf(7, 13, 17, 18)) {
            assertTypedWhereDrawn(count, Rect(0, 500, 40, 550))
            assertTypedWhereDrawn(count, Rect(960, 500, 1000, 550))
        }
    }

    @Test
    @Uat("PY-89b")
    fun atAnEdgeTheKeysOwnIsAtThatEndAndTheRestInward() {
        // at an edge the key's own at that end, the rest inward as listed; mid-screen the key's own in focus, its first symbol above
        val cases = listOf(
            arrayOf("L", "-", "l", "#0", "#1", "#2", "#3", "#4") to 1,
        ) + listOf(nine('2', false), nine('5', true), nine('9', true)).map { it.items to it.inRow }
        val right = Rect(960, 500, 1000, 550)
        val left = Rect(0, 500, 40, 550)
        val middle = Rect(480, 500, 520, 550)
        for ((keys, inRow) in cases) {
            val split = 2 * inRow + 1
            val others = keys.take(split).filterIndexed { i, _ -> i != inRow }
            for (trigger in listOf(right, left, middle)) {
                val ui = popup(keys, trigger, split = split, inRow = inRow)
                val drawn = drawn(ui)
                val what = "${keys[inRow]} at ${trigger.left}"
                assertEquals(what, keys.size, drawn.size)
                if (trigger != middle) {
                    val row = drawn.filterValues { it.first == 0 }.entries.sortedBy { it.value.second }.map { it.key }
                    assertEquals(what, if (trigger == right) (others.reversed() + keys[inRow]) else (listOf(keys[inRow]) + others), row)
                }
                assertEquals(what, 0 to ui.focusColumn, drawn[keys[inRow]])
                assertEquals(what, KeyAction.HostKeyAction(keys[inRow]), ui.onTrigger())
                assertEquals(what, ui.focusColumn, drawn[keys[split]]!!.second)
                drawn.forEach { (label, cell) -> assertEquals(what, PopupKeyboardUi.keyAction(label), ui.select(cell.first, cell.second)) }
            }
        }
    }

    @Test
    @Uat("T9-60b")
    fun aGroupOfNoKeysOwnHasItsFirstAtTheEdge() {
        // 注音九键's group (no inRow): by the screen's edge the one a lift types at that end, the rest inward
        val keys = arrayOf("ㄓ", "ㄔ", "ㄕ", "ㄖ")
        for ((trigger, toRight) in listOf(Rect(960, 500, 1000, 550) to true, Rect(0, 500, 40, 550) to false)) {
            val ui = popup(keys, trigger)
            val row = drawn(ui).filterValues { it.first == 0 }.entries.sortedBy { it.value.second }.map { it.key }
            assertEquals("$toRight", if (toRight) listOf("ㄖ", "ㄕ", "ㄔ", "ㄓ") else keys.toList(), row)
            assertEquals(KeyAction.HostKeyAction("ㄓ"), ui.onTrigger())
        }
    }

    @Test
    @Uat("T9-29")
    fun aKeysOwnAloneGoesInWithItsSymbolsNoSlotLeftEmpty() {
        val nineOne = arrayOf("1", "……", "：", "；", "～", "。", "-", "@", "、", "“")
        val tenOwn = arrayOf("1") + Array(10) { "#$it" }
        val middle = Rect(480, 500, 520, 550)
        val left = Rect(0, 500, 40, 550)
        val right = Rect(960, 500, 1000, 550)
        fun check(keys: Array<String>, trigger: Rect, rows: Int, columns: Int) = {
            val ui = popup(keys, trigger, split = 1, inRow = 0)
            val drawn = drawn(ui)
            assertEquals(rows, ui.rows())
            assertEquals(keys.size, drawn.values.toSet().size)
            assertTrue(drawn.values.all { (r, c) -> r in 0 until rows && c in 0 until columns })
            assertEquals(0 to ui.focusColumn, drawn["1"])
            assertEquals(KeyAction.HostKeyAction("1"), ui.onTrigger())
        }
        assertRows(
            "九键 1, centred: 5×2, every slot a key" to check(nineOne, middle, 2, 5),
            "九键 1 by the left edge: pushed in, still 5×2" to check(nineOne, left, 2, 5),
            "九键 1 by the right edge: pushed in, still 5×2" to check(nineOne, right, 2, 5),
            "digit and ten symbols, centred: 4 a row in 3 rows" to check(tenOwn, middle, 3, 4),
            "digit and ten symbols by the left edge: the same 3 rows" to check(tenOwn, left, 3, 4),
            "digit and ten symbols by the right edge: the same 3 rows" to check(tenOwn, right, 3, 4),
        )
    }

    private fun nine(digit: Char, shuangpin: Boolean): KeyDef.Popup.Keyboard.Explicit {
        val letters = T9Keyboard.lettersOn(digit, NineKeyLetters.STANDARD, english = false, shuangpin = shuangpin)
        val row = letters.map { it.uppercase() } + digit.toString() + letters.lowercase().map { it.toString() }
        return KeyDef.Popup.Keyboard.Explicit((row + listOf("@", "!", "\"")).toTypedArray(), symbolsFrom = row.size, inRow = letters.length)
    }

    private val density get() = context.resources.displayMetrics.density

    // the popup is placed by the face (KeyboardCanvasView.popupBounds), the finger's x is from the cell (SceneTouch)
    private fun Rect.face(inset: Int = (3 * density).toInt()) = Rect(this).apply { inset(inset, 0) }

    // a pixel at a time to [screenX], as a device reports it
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
        // wide enough that 7 are pushed in from the edge: the finger slides only inward, a key a step
        val wide = 120
        val right = Rect(800, 500, 1000, 550)
        val left = Rect(0, 500, 200, 550)
        for (shuangpin in listOf(false, true)) for ((digits, cell) in listOf("369" to right, "47" to left)) for (d in digits) {
            assertReachedInward(d, shuangpin, cell, cell == right, (3 * density).toInt(), wide)
        }
    }

    private fun assertReachedInward(d: Char, shuangpin: Boolean, cell: Rect, toRight: Boolean, inset: Int, wide: Int) {
        val popup = nine(d, shuangpin)
        val ui = { popup(popup.items, cell.face(inset), minKeyWidth = wide, split = popup.symbolsFrom, inRow = popup.inRow) }
        val what = "$d${if (shuangpin) " 双拼" else ""} at ${cell.left}, inset ${inset / density}"
        val inward = if (toRight) -1 else 1
        val step = ui().keyWidth * PopupFocus.STEP
        val drawn = drawn(ui()).filterValues { it.first == 0 }.entries.sortedBy { inward * it.value.second }.map { it.key }
        assertEquals(what, d.toString(), drawn[0])
        // half a step and the slack before it past k steps
        drawn.forEachIndexed { k, label ->
            val x = cell.centerX() + inward * ((k * step).toInt() + 2)
            assertEquals("$what $k", PopupKeyboardUi.keyAction(label), ui().slideTo(cell, x))
        }
        assertEquals(what, PopupKeyboardUi.keyAction(d.toString()), ui().slideTo(cell, if (toRight) outer.right - 1 else outer.left))
    }

    @Test
    fun aPopupKeepsWholeStepsAtTheEdge() {
        // no shorter steps by the edge: a step is a key
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
        // past half a step and the slack (0.65 of a step), not before
        val next = (ui().keyWidth * PopupFocus.STEP * 0.65f).toInt() + 1
        assertEquals(PopupKeyboardUi.keyAction(row[0]), ui().slideTo(cell, cell.centerX() + next))
        assertEquals(PopupKeyboardUi.keyAction(row[1]), ui().slideTo(cell, cell.centerX() - next))
        assertEquals(PopupKeyboardUi.keyAction("5"), ui().slideTo(cell, cell.centerX() + next - 2))
        assertEquals(PopupKeyboardUi.keyAction("5"), ui().slideTo(cell, cell.centerX() - next + 2))
    }

    @Test
    fun theTopRowKeepsItsEmptySlots() {
        // 13 keys, 5 columns: the top row's 3 in the middle columns, not packed to one side
        val keys = drawn(popup(13, Rect(480, 500, 520, 550)))
        assertEquals(setOf(1, 2, 3), keys.values.filter { it.first == 2 }.map { it.second }.toSet())
    }

    /** The long press and a letter's swipe type a pick the same way. */
    @Test
    fun oneCharacterIsAKeyAndAnythingLongerIsText() {
        assertEquals(KeyAction.HostKeyAction("x"), PopupKeyboardUi.keyAction("x"))
        assertEquals(KeyAction.HostKeyAction("é"), PopupKeyboardUi.keyAction("é"))
        // one code point, two chars
        assertEquals(KeyAction.HostKeyAction("😀"), PopupKeyboardUi.keyAction("😀"))
        assertEquals(KeyAction.CommitAction("Émile"), PopupKeyboardUi.keyAction("Émile"))
        assertEquals(KeyAction.CommitAction("Tab"), PopupKeyboardUi.keyAction("Tab"))
        // one letter to the eye (Yiddish ײַ), two code points: text
        assertEquals(KeyAction.CommitAction("\u05F2\u05B7"), PopupKeyboardUi.keyAction("\u05F2\u05B7"))
    }

    /** [drawn]: a view's measured width, the scene's squeezed. */
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
        assertTrue(labels.all { it.width < ui.keyWidth })
        assertTrue(ui.keyWidth >= keyWidth)
    }

    /** The engine squeezes a label too wide for its key at one text size: under a narrow bubble every label fits with its 6 dp padding a side. */
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
            // Robolectric's density is 1
            assertEquals(label.text, Popups.KEY_TEXT_DP.toFloat(), label.size)
        }
    }

    private fun PopupKeyboardUi.across(trigger: Rect, columns: Int) =
        (trigger.left + offsetX).let { it to it + columns * keyWidth }

    @Test
    fun aPopupOfKeysWiderThanItsTriggerStaysOnScreen() {
        val nine = Rect(600, 500, 820, 550)
        val ui = popup(Array(5) { ('a' + it).toString() }, nine, minKeyWidth = 220)
        assertEquals(200, ui.keyWidth)
        assertEquals(0 to 1000, ui.across(nine, 5))
        assertEquals(0, ui.focusedKeyLeft)
        ui.changeFocus(nine.width() / 2f, nine.height() / 2f)
        assertEquals(KeyAction.HostKeyAction("a"), ui.onTrigger())
        for (q in listOf(Rect(0, 500, 40, 550), Rect(960, 500, 1000, 550))) {
            val (left, right) = popup(arrayOf("q", "hello"), q, minKeyWidth = 80).across(q, 2)
            assertTrue("$q: $left to $right", left >= 0 && right <= 1000)
        }
    }

    // pressed before the popups' root is laid out: keys of no width hung the column search
    @Test
    fun boundsNotYetLaidOutLeaveTheKeysTheirWidth() {
        val ui = popup(arrayOf("a", "b", "c"), Rect(480, 500, 520, 550), Rect())
        assertEquals(keyWidth, ui.keyWidth)
    }

    @Test
    fun keysTooWideToFitAcrossAreNarrowerAndAllTheirLabelsSmallerAlike() {
        val long = arrayOf("example", "ex", "examples", "e", "exam")
        val roomy = popup(long, Rect(480, 500, 520, 550))
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
        ui.onChangeFocus(100f + step / 3, 100f)
        assertEquals(first, ui.onTrigger())
        ui.onChangeFocus(100f + step, 100f)
        val next = ui.onTrigger()
        assertTrue(next != first)
        ui.onChangeFocus(100f, 100f)
        assertEquals(first, ui.onTrigger())
        assertFalse(ui.onChangeFocus(100f, 140f))
        assertEquals(first, ui.onTrigger())
    }

    @Test
    fun onlyDownOffTheKeyPressedPicksNothing() {
        var dismissed = 0
        val ui = popup(Array(7) { ('a' + it).toString() }, Rect(480, 500, 520, 550), onDismiss = { dismissed++ })
        // the key's own coordinates, as BaseKeyboard passes them
        ui.changeFocus(20f, 20f)
        for ((x, y) in listOf(20f to -900f, -900f to 20f, 900f to 20f, 900f to -900f)) {
            assertFalse(ui.changeFocus(x, y))
            assertTrue(ui.onTrigger() != null)
        }
        assertEquals(0, dismissed)
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
        val keys = arrayOf("A", "B", "C", "2", "a", "b", "c")
        val ui = popup(keys, Rect(0, 500, 40, 550), outerBounds = Rect(0, 0, 7 * keyWidth / 2, 2000), split = 7, inRow = 3)
        val step = ui.keyWidth * PopupFocus.STEP
        assertFalse(ui.changeFocus(25f, 20f))
        assertEquals(KeyAction.HostKeyAction("2"), ui.onTrigger())
        ui.changeFocus(0f, 20f)
        assertEquals(KeyAction.HostKeyAction("B"), ui.onTrigger())
        ui.changeFocus(25f + step + 2, 20f)
        assertEquals(KeyAction.HostKeyAction("A"), ui.onTrigger())
        ui.changeFocus(25f + 5 * step + 2, 20f)
        assertEquals(KeyAction.HostKeyAction("c"), ui.onTrigger())
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
        val a = ui.scene().keys.first { it.label == "a" }.bounds
        @Suppress("DEPRECATION")
        val box = android.graphics.Rect().also { view.spokenKeys().first { n -> n.contentDescription == "a" }.getBoundsInParent(it) }
        assertEquals(a.left.toInt() + view.outset.left, box.left)
        assertEquals(a.top.toInt() + view.outset.top, box.top)
    }

    // rows of five too tall for the room above (16 in landscape): wider rows till they fit
    @Test
    fun rowsTooTallForTheRoomAboveAreMadeWider() {
        val keys = Array(16) { ('a' + it).toString() }
        val trigger = Rect(480, 500, 520, 550)
        val ui = popup(keys, trigger, outerBounds = Rect(0, 500 - 10 - 2 * keyHeight, 1000, 600))
        assertEquals(2, ui.rows())
        assertEquals(keys.size, drawn(ui).values.toSet().size)
        assertEquals(4, popup(keys, trigger).rows())
    }
}
