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
import org.fcitx.fcitx5.android.scene.layout.Width
import org.fcitx.fcitx5.android.scene.skin.SkinAction.Letter
import org.fcitx.fcitx5.android.scene.skin.SkinAction.Role
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.appLayouts
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun nine(extra: List<SkinKeyBox> = emptyList()): SkinBoard {
        val side = 172.8
        fun digits(y: Double, s: String, last: SkinAction) = row(y, 150.0, side, *letters(s, 237.6), last to 194.4)
        val rows = listOf(
            digits(0.0, "123", Role("backspace")),
            digits(150.0, "456", Role("reset")),
            digits(300.0, "789", Letter('0')),
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
    }

    @Test
    fun aKeyMoreOrLessIsNoLayout() {
        val more = row(150.0, 150.0, 0.0, *letters("asdfghjkl", 108.0), Role("emoji") to 108.0)
        assertNull(SkinLayoutWriter.write(qwerty(home = more), appLayouts))
        val less = row(150.0, 150.0, 54.0, *letters("asdfghjk", 108.0))
        assertNull(SkinLayoutWriter.write(qwerty(home = less), appLayouts))
        assertNull(SkinLayoutWriter.write(SkinBoard("number", qwerty().rows, across), appLayouts))
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

    private fun keys(l: Layout): List<Key> = l.parts.filterIsInstance<Row>().flatMap { it.cells.filterIsInstance<Key>() }

    private fun testAppText(id: String) = appLayouts.layouts.getValue(id)

    private companion object {
        // widths to a tenth of a column, a column 10.8 px: a row's ends may be off by its keys' roundings
        const val NEAR = 6.0
    }
}
