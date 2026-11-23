/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Problems
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Expand
import org.fcitx.fcitx5.android.scene.engine.ExpandedKey
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.Place
import org.fcitx.fcitx5.android.scene.layout.Gap
import org.fcitx.fcitx5.android.scene.layout.Key
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.layout.OnSplit
import org.fcitx.fcitx5.android.scene.layout.Row
import org.fcitx.fcitx5.android.scene.layout.RowSplit
import org.fcitx.fcitx5.android.scene.layout.Slot
import org.fcitx.fcitx5.android.scene.layout.Width
import org.fcitx.fcitx5.android.scene.skin.SkinAction.Letter
import org.fcitx.fcitx5.android.scene.skin.SkinAction.Role
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.appLayouts
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkinLayoutWriterTest {
    private val across = 1080.0

    // one row of boxes from x, each of its width; a null action is a hole
    private fun row(y: Double, h: Double, x0: Double, vararg keys: Pair<SkinAction?, Double>): List<SkinKeyBox> {
        var x = x0
        return keys.mapNotNull { (a, w) -> (if (a != null) SkinKeyBox(x, y, w, h, a) else null).also { x += w } }
    }

    private fun letters(s: String, w: Double) = s.map { Letter(it) to w }.toTypedArray()

    private fun qwerty(
        top: List<SkinKeyBox> = row(0.0, 150.0, 0.0, *letters("qwertyuiop", 108.0)),
        home: List<SkinKeyBox> = row(150.0, 150.0, 54.0, *letters("asdfghjkl", 108.0)),
        third: List<SkinKeyBox> = row(300.0, 150.0, 0.0, Role("shift") to 162.0, *letters("zxcvbnm", 108.0), Role("backspace") to 162.0),
        bottom: List<SkinKeyBox> = row(
            450.0, 150.0, 0.0,
            Role("symbols") to 120.0, Role("numbers") to 120.0, Role("comma") to 100.0,
            Role("space") to 480.0, Role("period") to 100.0, Role("enter") to 160.0,
        ),
    ) = SkinBoard("qwerty", listOf(top, home, third, bottom), across)

    private fun nine(extra: List<SkinKeyBox> = emptyList(), zero: SkinAction = Letter('0')): SkinBoard {
        val side = 172.8
        fun digits(y: Double, s: String, last: SkinAction) = row(y, 150.0, side, *letters(s, 237.6), last to 194.4)
        val rows = listOf(
            digits(0.0, "123", Role("backspace")),
            digits(150.0, "456", Role("reset")),
            digits(300.0, "789", zero),
            row(450.0, 150.0, 0.0, Role("symbols") to 172.8, Role("numbers") to 151.2, Role("emoji") to 100.0, Role("space") to 461.6, Role("enter") to 194.4),
            // the skin's own column of punctuation, drawn and not a key the app has
            listOf(SkinKeyBox(0.0, 0.0, side, 450.0, SkinAction.None)),
        )
        return SkinBoard("nine", rows + listOf(extra), across)
    }

    private fun written(board: SkinBoard): String = assertNotNull(SkinLayoutWriter.write(board, appLayouts)).text

    private fun layout(text: String): Layout = (LayoutReader.layout(text) as Parsed.Ok).value

    private val states = listOf(
        Input(),
        Input(ime = "english"),
        Input(composing = true),
        Input(table = "table", composing = true),
        Input(table = "table", tableExtra = "semicolon"),
        Input(table = "table", tableExtra = "star"),
        Input(table = "table", tableLookUp = true),
        Input(table = "table", tableLookUp = true, lookingUp = true),
        Input(field = "url"),
        Input(field = "email"),
        Input(ime = "shuangpin", schemeTypes = setOf("semicolon")),
    ).map { it.copy(langKey = false) }

    private fun env(input: Input, split: Boolean = false) = Env(width = across, height = 600.0, density = 3.0, split = split, input = input)

    // each key's left and right edge as laid out, in the skin's pixels, by id
    private fun placed(l: Layout, input: Input, split: Boolean = false): Map<String, Pair<Double, Double>> {
        val rows = (Expand.rows(l, emptyMap(), env(input, split)) as Parsed.Ok).value
        val warnings = Problems()
        val p = Place.rows(rows, l.columns, l.onSplit, env(input, split), warnings = warnings, snap = false)
        assertTrue(p is Parsed.Ok && p.warnings.isEmpty() && warnings.isEmpty(), "$input: $p ${warnings}")
        return p.value.filter { it.item is ExpandedKey }.associate { (it.item as ExpandedKey).key.id to (it.cell.left to it.cell.right) }
    }

    private fun assertAt(board: SkinBoard, at: Map<String, Pair<Double, Double>>, idOf: (SkinAction) -> String?) {
        for (b in board.rows.flatten()) {
            val id = idOf(b.action) ?: continue
            val (l, r) = assertNotNull(at[id], "$id in $at")
            assertTrue(abs(l - b.x) < NEAR && abs(r - (b.x + b.w)) < NEAR, "$id at $l–$r, the skin's ${b.x}–${b.x + b.w}")
        }
    }

    private fun idOf(a: SkinAction): String? = when (a) {
        is Letter -> a.char.toString()
        is Role -> a.name.takeIf { it != "emoji" && it != "space" }
        SkinAction.None -> null
    }

    @Test
    fun aPlain26KeysIsTheAppsWithTheSkinsSizes() {
        val text = written(qwerty())
        val l = layout(text)
        val app = layout(testAppText("qwerty"))
        assertEquals(app.columns, l.columns)
        assertEquals(OnSplit.Split, l.onSplit)
        assertEquals(app.family, l.family)
        assertEquals(keys(app).map { it.id to it.attrs.shortcut }, keys(l).map { it.id to it.attrs.shortcut })
        assertTrue(l.parts.filterIsInstance<Row>().take(3).all { it.split == RowSplit.Halves })
        assertTrue("semicolon" in keys(l).map { it.id })
        assertAt(qwerty(), placed(l, states.first()), ::idOf)
        assertEquals(LayoutCheck.minApp(l), l.meta?.minApp)
        assertTrue(LayoutCheck.check(text, appLayouts, "0.6.3", strict = false).errors.isEmpty())
    }

    @Test
    fun everyStateFitsItsRows() {
        val l = layout(written(qwerty()))
        for (s in states) placed(l, s)
        placed(l, states.first(), split = true)
        val n = layout(written(nine()))
        for (s in states) placed(n, s)
    }

    @Test
    fun keysStandingInForOneAnotherHaveTheirCellsWidth() {
        val keys = keys(layout(written(qwerty()))).associateBy { it.id }
        fun w(id: String) = keys.getValue(id).attrs.width
        assertEquals(w("shift"), w("apostrophe"))
        assertEquals(w("shift"), w("lookup"))
        assertEquals(w("shift"), w("lookup-on"))
        assertEquals(w("enter"), w("confirm"))
        val nine = keys(layout(written(nine()))).associateBy { it.id }
        assertEquals(nine.getValue("reset").attrs.width, nine.getValue("shift").attrs.width)
    }

    @Test
    fun aRowSetInIsIndented() {
        val home = row(150.0, 150.0, 80.0, *letters("asdfghjkl", 100.0))
        val board = qwerty(home = home)
        val l = layout(written(board))
        val r = l.parts.filterIsInstance<Row>()[1]
        assertEquals(0.74, r.indent)
        assertEquals(0.93, r.endIndent)
        assertAt(board, placed(l, states.first()), ::idOf)
    }

    @Test
    fun roomBetweenTwoKeysIsAGap() {
        val top = row(0.0, 150.0, 0.0, *letters("qwert", 105.0), null to 30.0, *letters("yuiop", 105.0))
        val board = qwerty(top = top)
        val l = layout(written(board))
        val cells = l.parts.filterIsInstance<Row>()[0].cells
        val gap = cells.indexOfFirst { it is Gap }
        assertEquals("y", (cells[gap + 1] as Key).id)
        assertEquals(0.28, ((cells[gap] as Gap).width as Width.Units).units)
        assertAt(board, placed(l, states.first()), ::idOf)
    }

    @Test
    fun rowsOfTheirOwnHeights() {
        val bottom = row(450.0, 180.0, 0.0, Role("symbols") to 120.0, Role("numbers") to 120.0, Role("comma") to 100.0, Role("space") to 480.0, Role("period") to 100.0, Role("enter") to 160.0)
        val rows = layout(written(qwerty(bottom = bottom))).parts.filterIsInstance<Row>()
        assertEquals(listOf(0.95, 0.95, 0.95, 1.14), rows.map { it.height })
    }

    @Test
    fun aBottomRowsKeyOfItsOwnGoesToTheSpace() {
        val bottom = row(
            450.0, 150.0, 0.0,
            Role("symbols") to 120.0, Role("numbers") to 120.0, Role("comma") to 100.0, Role("emoji") to 100.0,
            Role("space") to 380.0, Role("period") to 100.0, Role("enter") to 160.0,
        )
        assertEquals(written(qwerty()), written(qwerty(bottom = bottom)))
        // at either end too: no indent of it
        val ends = row(
            450.0, 150.0, 0.0,
            Role("emoji") to 100.0, Role("symbols") to 120.0, Role("numbers") to 120.0, Role("comma") to 100.0,
            Role("space") to 280.0, Role("period") to 100.0, Role("enter") to 160.0, Role("settings") to 100.0,
        )
        assertEquals(written(qwerty()), written(qwerty(bottom = ends)))
    }

    @Test
    fun aCellDrawnWithAKeyStandingInForItsIsThatCell() {
        // Baidu draws its pinyin board as one types: the separator where the app shows shift till then
        val third = row(300.0, 150.0, 0.0, Role("apostrophe") to 162.0, *letters("zxcvbnm", 108.0), Role("backspace") to 162.0)
        assertEquals(written(qwerty()), written(qwerty(third = third)))
        // one shown in no state there is not that cell: left out, and the board lacks ⇧
        val other = row(300.0, 150.0, 0.0, Role("emoji") to 162.0, *letters("zxcvbnm", 108.0), Role("backspace") to 162.0)
        assertEquals(listOf(listOf("shift")), assertNotNull(SkinLayoutWriter.write(qwerty(third = other), appLayouts)).lacks.map { it.args })
    }

    private fun lacks(w: SkinLayoutWriter.Written) = w.lacks.map { it.args }

    @Test
    fun keysEitherSideLacksAreLeftOutAndTold() {
        val more = row(150.0, 150.0, 0.0, *letters("asdfghjkl", 108.0), Role("emoji") to 108.0)
        val w = assertNotNull(SkinLayoutWriter.write(qwerty(home = more), appLayouts))
        assertEquals(1, w.dropped)
        assertAt(qwerty(home = more), placed(layout(w.text), states.first()), ::idOf)
        assertEquals(0, assertNotNull(SkinLayoutWriter.write(qwerty(), appLayouts)).dropped)
        assertEquals(emptyList(), lacks(assertNotNull(SkinLayoutWriter.write(qwerty(), appLayouts))))
        // the app's keys the skin has not are not on the board, as a user's own layout may leave them out
        val less = row(150.0, 150.0, 54.0, *letters("asdfghjk", 108.0))
        assertEquals(listOf(listOf("letters", "l")), lacks(assertNotNull(SkinLayoutWriter.write(qwerty(home = less), appLayouts))))
        val noBackspace = row(300.0, 150.0, 0.0, Role("shift") to 162.0, *letters("zxcvbnm", 108.0))
        val nb = assertNotNull(SkinLayoutWriter.write(qwerty(third = noBackspace), appLayouts))
        assertEquals(listOf(listOf("backspace")), lacks(nb))
        assertTrue("backspace" !in keys(layout(nb.text)).map { it.id })
        val noSpace = row(450.0, 150.0, 0.0, Role("symbols") to 120.0, Role("numbers") to 120.0, Role("comma") to 580.0, Role("enter") to 260.0)
        assertTrue(listOf("space") in lacks(assertNotNull(SkinLayoutWriter.write(qwerty(bottom = noSpace), appLayouts))))
        assertNull(SkinLayoutWriter.write(SkinBoard("number", qwerty().rows, across), appLayouts))
    }

    @Test
    fun nineKeysWithoutZeroAreWrittenAndTold() {
        val board = nine(zero = Role("emoji"))
        val w = assertNotNull(SkinLayoutWriter.write(board, appLayouts))
        assertEquals(listOf(listOf("digits", "0")), lacks(w))
        val l = layout(w.text)
        assertTrue("0" !in keys(l).map { it.id })
        assertAt(board, placed(l, states.first()), ::idOf)
        for (s in states) placed(l, s)
        // for an app that takes one lacking keys, though it uses nothing newer
        assertEquals(LayoutCheck.FEATURES["lacks"], w.minApp)
        assertEquals(w.minApp, l.meta?.minApp)
        assertTrue(ThemeReader.newer(w.minApp, assertNotNull(SkinLayoutWriter.write(nine(), appLayouts)).minApp))
    }

    @Test
    fun aKeyOfTheSkinsWhereTheSideEndsIsAGapAfterIt() {
        val rows = nine().rows.toMutableList()
        rows[0] = row(0.0, 150.0, 172.8, Role("settings") to 237.6, *letters("23", 237.6), Role("backspace") to 194.4)
        val w = assertNotNull(SkinLayoutWriter.write(SkinBoard("nine", rows, across), appLayouts))
        assertEquals(listOf(listOf("digits", "1")), lacks(w))
        val cells = layout(w.text).parts.filterIsInstance<Row>()[0].cells
        assertEquals(16.0, ((cells[0] as Slot).width as Width.Units).units)
        assertEquals(22.0, ((cells[1] as Gap).width as Width.Units).units)
        assertEquals("2", (cells[2] as Key).id)
    }

    @Test
    fun aKeyJoinedWhereItLeavesNoRoomIsLeftOut() {
        // a pad of the skin's over the side: its 0 has no room before 7, so the board is written without it
        val rows = nine(zero = Role("emoji")).rows + listOf(listOf(SkinKeyBox(0.0, 160.0, 172.8, 280.0, Letter('0'))))
        val w = assertNotNull(SkinLayoutWriter.write(SkinBoard("nine", rows, across), appLayouts))
        assertEquals(listOf(listOf("digits", "0")), lacks(w))
        assertTrue("0" !in keys(layout(w.text)).map { it.id })
        // its two ⊕ are the skin's own; the 0 left out is the app's key, told as lacking, not counted with them
        assertEquals(2, w.dropped)
    }

    // a pad of digits where the side is: the board's 1 (its marks, the skin's comma), 2 and 3 are the wide keys, the pad's left out
    @Test
    fun aRowsKeyDrawnTwiceIsTheBoardsWidestNotAPadsBesideIt() {
        val side = 172.8
        val main = row(0.0, 150.0, side, Role("comma") to 237.6, *letters("23", 237.6), Role("backspace") to 194.4)
        val pad = row(0.0, 150.0, 0.0, *letters("123", side / 3))
        val rows = nine().rows.toMutableList()
        rows[0] = pad + main
        rows[4] = listOf(SkinKeyBox(0.0, 150.0, side, 300.0, SkinAction.None))
        val w = assertNotNull(SkinLayoutWriter.write(SkinBoard("nine", rows, across), appLayouts))
        assertEquals(emptyList(), lacks(w))
        val one = main.map { if (it.action == Role("comma")) SkinKeyBox(it.x, it.y, it.w, it.h, Letter('1')) else it }
        assertAt(SkinBoard("nine", listOf(one), across), placed(layout(w.text), states.first()), ::idOf)
        // the side is where the pad is; its three told as the skin's own
        assertEquals(written(nine()), written(SkinBoard("nine", nine().rows.toMutableList().also { it[0] = main }, across)))
        assertEquals(assertNotNull(SkinLayoutWriter.write(nine(), appLayouts)).dropped + 3, w.dropped)
    }

    // a side of one digit a row, wider than the board's keys: each row's digit is the board's, in its columns
    @Test
    fun aSidesDigitWiderThanTheBoardsIsNotTheBoards() {
        val side = 300.0
        val key = (across - side - 194.4) / 3
        fun digits(y: Double, s: String, last: SkinAction) = row(y, 150.0, side, *letters(s, key), last to 194.4)
        val main = listOf(digits(0.0, "123", Role("backspace")), digits(150.0, "456", Role("reset")), digits(300.0, "789", Letter('0')))
        val sides = "147".mapIndexed { i, c -> SkinKeyBox(0.0, 150.0 * i, side, 150.0, Letter(c)) }
        val rows = main.mapIndexed { i, r -> listOf(sides[i]) + r } + listOf(nine().rows[3])
        val w = assertNotNull(SkinLayoutWriter.write(SkinBoard("nine", rows, across), appLayouts))
        assertEquals(emptyList(), lacks(w))
        assertAt(SkinBoard("nine", main, across), placed(layout(w.text), states.first()), ::idOf)
    }

    @Test
    fun onlyAKeyOverTheRowAndBesideItsKeysJoinsIt() {
        fun down(r: List<SkinKeyBox>) = r.map { SkinKeyBox(it.x, it.y + 150.0, it.w, it.h, it.action) }
        val board = nine(zero = Role("emoji"))
        val numbers = row(0.0, 150.0, 0.0, *letters("1234567890", 108.0))
        val w = assertNotNull(SkinLayoutWriter.write(SkinBoard("nine", listOf(numbers) + board.rows.map(::down), across), appLayouts))
        assertEquals(written(board), w.text)
        // a ⊕ in a tool row is not the board's
        val tools = row(0.0, 110.0, 0.0, Role("emoji") to 540.0, Role("lang") to 540.0)
        val own = qwerty().rows.map { r -> r.map { SkinKeyBox(it.x, it.y + 110.0, it.w, it.h, it.action) } }
        assertEquals(written(qwerty()), written(SkinBoard("qwerty", listOf(tools) + own, across)))
    }

    @Test
    fun aKeyAcrossTwoRowsJoinsTheRowItIsIn() {
        val rows = nine().rows.toMutableList()
        rows[3] = row(450.0, 150.0, 0.0, Role("symbols") to 172.8, Role("numbers") to 151.2, Role("emoji") to 100.0, Role("space") to 461.6)
        rows += listOf(SkinKeyBox(885.6, 300.0, 194.4, 300.0, Role("enter")))
        val w = assertNotNull(SkinLayoutWriter.write(SkinBoard("nine", rows, across), appLayouts))
        assertEquals(emptyList(), lacks(w))
        val l = layout(w.text)
        assertEquals("enter", l.parts.filterIsInstance<Row>().last().cells.filterIsInstance<Key>().last().id)
        // its rows as high as the skin's, whatever the key across two is
        assertTrue(l.parts.filterIsInstance<Row>().all { it.height == null || it.height == 1.0 })
        for (s in states) placed(l, s)
    }

    @Test
    fun aBoardThatCannotBeDrawnIsStillLeftOut() {
        // a row past the board's edge
        val top = row(0.0, 150.0, 0.0, *letters("qwertyuiop", 120.0))
        assertNull(SkinLayoutWriter.write(qwerty(top = top), appLayouts))
        // no row of the app's
        assertNull(SkinLayoutWriter.write(SkinBoard("qwerty", listOf(row(0.0, 150.0, 0.0, Role("emoji") to 540.0)), across), appLayouts))
    }

    @Test
    fun theBottomRowInTheSkinsOrder() {
        val bottom = row(
            450.0, 150.0, 0.0,
            Role("numbers") to 120.0, Role("lang") to 100.0, Role("comma") to 100.0, Role("space") to 380.0,
            Role("period") to 100.0, Role("symbols") to 120.0, Role("enter") to 160.0,
        )
        val board = qwerty(bottom = bottom)
        val l = layout(written(board))
        val app = layout(testAppText("qwerty"))
        assertAt(board, placed(l, Input(langKey = true)), ::idOf)
        for (s in states) placed(l, s)
        // the keys the app shows in other states go with the one they stand in for
        val ids = l.parts.filterIsInstance<Row>().last().cells.filterIsInstance<Key>().map { it.id }
        assertEquals(listOf("numbers", "lang", "comma", "slash", "at", "space", "period", "dot", "domain", "symbols", "enter", "confirm"), ids)
        assertEquals(keys(app).map { it.id to it.attrs.shortcut }.toSet(), keys(l).map { it.id to it.attrs.shortcut }.toSet())
    }

    @Test
    fun aKeyDrawnInAnotherRowMovesThere() {
        // symbols where the app has ⇧, none in the bottom row; and no period
        val third = row(300.0, 150.0, 0.0, Role("symbols") to 162.0, *letters("zxcvbnm", 108.0), Role("backspace") to 162.0)
        val bottom = row(450.0, 150.0, 0.0, Role("numbers") to 120.0, Role("comma") to 100.0, Role("space") to 700.0, Role("enter") to 160.0)
        val board = qwerty(third = third, bottom = bottom)
        val w = assertNotNull(SkinLayoutWriter.write(board, appLayouts))
        val l = layout(w.text)
        val rows = l.parts.filterIsInstance<Row>().map { r -> r.cells.filterIsInstance<Key>().map { it.id } }
        assertEquals("symbols", rows[2].first())
        // ⇧ gone with the keys shown in its cell as one types
        assertTrue(rows.flatten().none { it in setOf("shift", "apostrophe", "lookup", "lookup-on", "period") })
        assertTrue(listOf("shift") in lacks(w))
        assertAt(board, placed(l, states.first()), ::idOf)
        for (s in states) placed(l, s)
    }

    @Test
    fun aNumberRowAndAToolRowAreLeftOut() {
        fun down(r: List<SkinKeyBox>, by: Double) = r.map { SkinKeyBox(it.x, it.y + by, it.w, it.h, it.action) }
        val own = qwerty().rows.map { down(it, 260.0) }
        val tools = row(0.0, 110.0, 0.0, Role("emoji") to 540.0, Role("settings") to 540.0)
        val numbers = row(110.0, 150.0, 0.0, *letters("1234567890", 108.0))
        val w = assertNotNull(SkinLayoutWriter.write(SkinBoard("qwerty", listOf(tools, numbers) + own, across), appLayouts))
        assertEquals(12, w.dropped)
        assertEquals(written(SkinBoard("qwerty", own, across)), w.text)
    }

    @Test
    fun decorationsAreLeftOut() {
        val board = qwerty()
        val decorated = SkinBoard("qwerty", board.rows + listOf(listOf(SkinKeyBox(500.0, 200.0, 300.0, 300.0, SkinAction.None))), across)
        assertEquals(written(board), written(decorated))
    }

    @Test
    fun nineKeysWithAnEmojiKeyAndTheSkinsSide() {
        val l = layout(written(nine()))
        assertEquals(100.0, l.columns)
        assertAt(nine(), placed(l, states.first()), ::idOf)
    }

    @Test
    fun aLanguageKeyInTheSkinsBottomRowIsTheLangKey() {
        val bottom = row(
            450.0, 150.0, 0.0,
            Role("symbols") to 120.0, Role("numbers") to 120.0, Role("comma") to 100.0, Role("space") to 300.0,
            Role("period") to 100.0, Role("lang") to 100.0, Role("enter") to 240.0,
        )
        val board = qwerty(bottom = bottom)
        val l = layout(written(board))
        assertAt(board, placed(l, Input(langKey = true)), ::idOf)
    }

    // a grouped board's bottom row as grouped-bottom has it, the skin's own widths
    private fun groupedBottom() = row(
        450.0, 150.0, 0.0,
        Role("symbols") to 140.0, Role("numbers") to 120.0, Role("comma") to 100.0,
        Role("space") to 440.0, Role("period") to 100.0, Role("enter") to 180.0,
    )

    private fun k18() = SkinBoard(
        "k18",
        listOf(
            row(0.0, 150.0, 0.0, *letters("qwryuip", across / 7)),
            row(150.0, 150.0, 0.0, *letters("asfhjl", across / 6)),
            row(300.0, 150.0, 0.0, Role("apostrophe") to 180.0, *letters("zxvbm", 151.2), Role("backspace") to 144.0),
            groupedBottom(),
        ),
        across,
    )

    private fun k14() = SkinBoard(
        "k14",
        listOf(
            row(0.0, 150.0, 0.0, *letters("qetuo", 216.0)),
            // narrower and centred, as the app centres a row short of its width
            row(150.0, 150.0, 40.0, *letters("adgjl", 200.0)),
            row(300.0, 150.0, 0.0, Role("apostrophe") to 162.0, *letters("zcbm", 189.0), Role("backspace") to 162.0),
            groupedBottom(),
        ),
        across,
    )

    // a grouped key by any one of its letters
    private fun groupedIds(app: Layout): (SkinAction) -> String? = { a ->
        when (a) {
            is Letter -> keys(app).first { it.letters?.group?.contains(a.char) == true }.id
            is Role -> a.name.takeIf { it != "space" }
            SkinAction.None -> null
        }
    }

    @Test
    fun eighteenKeysByOneLetterOfEachGroup() {
        val l = layout(written(k18()))
        assertEquals(100.0, l.columns)
        // the app's own rows, then its bottom row written out of grouped-bottom
        val own = keys(layout(testAppText("k18"))).map { it.id }
        assertEquals(own, keys(l).map { it.id }.take(own.size))
        assertAt(k18(), placed(l, states.first()), groupedIds(l))
        for (s in states) placed(l, s)
    }

    @Test
    fun eighteenKeysWithShiftDrawnInTheSeparatorsCell() {
        // the app's 18 keys have no ⇧: that cell is the separator's in every state
        val rows = k18().rows.toMutableList()
        rows[2] = row(300.0, 150.0, 0.0, Role("shift") to 180.0, *letters("zxvbm", 151.2), Role("backspace") to 144.0)
        assertEquals(written(k18()), written(SkinBoard("k18", rows, across)))
    }

    @Test
    fun fourteenKeysWithACentredRow() {
        val l = layout(written(k14()))
        assertAt(k14(), placed(l, states.first()), groupedIds(l))
        for (s in states) placed(l, s)
        // a group's letter not first in it is the key all the same
        val byLast = SkinBoard("k14", k14().rows.mapIndexed { i, r -> if (i == 0) row(0.0, 150.0, 0.0, *letters("wryip", 216.0)) else r }, across)
        assertNotNull(SkinLayoutWriter.write(byLast, appLayouts))
    }

    @Test
    fun aBoardWithANumberNotFiniteIsNotWritten() {
        val q = qwerty()
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            val rows = q.rows.mapIndexed { i, r -> if (i == 1) r.mapIndexed { j, b -> if (j == 0) SkinKeyBox(bad, b.y, b.w, b.h, b.action) else b } else r }
            assertNull(SkinLayoutWriter.write(SkinBoard("qwerty", rows, across), appLayouts))
            val tall = q.rows.map { r -> r.map { SkinKeyBox(it.x, it.y, it.w, bad, it.action) } }
            assertNull(SkinLayoutWriter.write(SkinBoard("qwerty", tall, across), appLayouts))
        }
        assertNull(SkinLayoutWriter.write(SkinBoard("qwerty", q.rows, Double.NaN), appLayouts))
    }

    @Test
    fun aBugOfOursLeavesTheBoardOutAndAnErrorIsNotHidden() {
        fun failing(e: Throwable) = LayoutCheck.Sources(
            object : AbstractMap<String, String>() {
                override val entries: Set<Map.Entry<String, String>> get() = throw e
            },
            emptyMap(),
        )
        assertNull(SkinLayoutWriter.write(qwerty(), failing(IllegalStateException())))
        assertFailsWith<NotImplementedError> { SkinLayoutWriter.write(qwerty(), failing(NotImplementedError())) }
    }

    @Test
    fun aBoardLackingKeysOrBlankSomewhereIsForTheAppThatTakesOne() {
        val newer = LayoutCheck.FEATURES.getValue("lacks")
        assertEquals(newer, SkinLayoutWriter.relaxed("0.6.0", listOf(LayoutCheck.Finding(1, "lacks", listOf("shift")))))
        assertEquals(newer, SkinLayoutWriter.relaxed("0.6.0", listOf(LayoutCheck.Finding(1, "blank"))))
        assertEquals("0.6.0", SkinLayoutWriter.relaxed("0.6.0", listOf(LayoutCheck.Finding(1, "other"))))
        assertEquals("9.9.9", SkinLayoutWriter.relaxed("9.9.9", listOf(LayoutCheck.Finding(1, "blank"))))
    }

    private fun keys(l: Layout): List<Key> = l.parts.filterIsInstance<Row>().flatMap { it.cells.filterIsInstance<Key>() }

    private fun testAppText(id: String) = appLayouts.layouts.getValue(id)

    private companion object {
        // widths to a tenth of a column, a column 10.8 px: a row's ends may be off by its keys' roundings
        const val NEAR = 6.0
    }
}
