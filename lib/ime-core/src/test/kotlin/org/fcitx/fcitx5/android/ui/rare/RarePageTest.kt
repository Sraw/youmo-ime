/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.rare

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.data.CharLevels.Level
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.ReadingSource
import org.fcitx.fcitx5.android.engine.data.TestCharLevels
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.RareChoice
import org.fcitx.fcitx5.android.engine.user.RareChoice.Change
import org.fcitx.fcitx5.android.engine.user.RareGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** 生僻字屏蔽's page logic (dev/design/061-rare-char-block.md §6): the grid, its rows, the drag, the edits. */
class RarePageTest {

    private val levels = TestCharLevels.levels
    private val ext = TestCharLevels.EXT
    private val out = TestCharLevels.OUT
    private val noReading = 0x20001 // 扩展 B's, the dictionary's, no reading known

    private fun s(cp: Int) = String(Character.toChars(cp))

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("的", -1f, 0f).entry("的", intArrayOf(Syllables.id("de")))
                .unigram(s(noReading), -9f, 0f).entry(s(noReading), intArrayOf(Syllables.id("a")))
                .charReading(ReadingSource.MANDARIN, '的'.code, Syllables.id("de"), 5)
                .charReading(ReadingSource.MANDARIN, out, Syllables.id("da"), 2)
                .charReading(ReadingSource.MANDARIN, out, Syllables.id("ta"), 4)
                .charReading(ReadingSource.MANDARIN, ext, Syllables.id("da"), 1)
                .charReading(ReadingSource.MANDARIN, 0x20000, Syllables.id("he"), 1)
                .charReading(ReadingSource.MANDARIN, TestCharLevels.AT_8104, Syllables.id("ben"), 1)
                .charReading(ReadingSource.MANDARIN, TestCharLevels.AT_6501, Syllables.id("zhou"), 4)
                .charReading(ReadingSource.MANDARIN, TestCharLevels.TRAD, Syllables.id("fa"), 4)
                .charReading(ReadingSource.MANDARIN, 0x9F97, Syllables.id("da"), 1)
                .build().toByteArray(),
        ),
    )

    private val grid = RareGrid.copy(levels, data.charReadings, sequenceOf(noReading, noReading, '的'.code, ext))
    private val sorted = grid.sorted()
    private fun at(cp: Int) = grid.indexOf(cp)

    @Test
    fun theGridIsTheGroupsCharactersWithReadingsSorted() = assertRows(
        "一级 has no cell" to { assertEquals(-1, at('的'.code)) },
        "each group's in order of first reading, tone, code point" to {
            assertEquals(listOf(0x9F97, out), sorted.getValue(Level.OUT).map { grid.chars[it] })
            assertEquals(listOf(ext, 0x20000), sorted.getValue(Level.EXT).map { grid.chars[it] })
        },
        "a polyphone's readings, the first first" to { assertEquals(listOf("da", "ta"), grid.readings(at(out)).map { Syllables.spelling(it.syllable) }) },
        "a character with no reading counted once in its group, not one with a cell" to {
            assertEquals(listOf(0, 0, 0, 1), grid.unread.toList())
        },
        "of 扩展 B only what something types: 0x20000 not, 𤝰 yes; 其它表外字 whatever is typed" to {
            val typed = RareGrid.copy(levels, data.charReadings, emptySequence()) { it == ext }
            assertEquals(listOf(-1, true, true), listOf(typed.indexOf(0x20000), typed.indexOf(ext) >= 0, typed.indexOf(out) >= 0))
        },
        "found by any reading, toneless, across groups" to {
            assertEquals(listOf(at(0x9F97), at(out), at(ext)), grid.find("da", sorted))
            assertEquals(listOf(at(out)), grid.find("TA", sorted))
        },
    )

    // the page turned on shows a group with characters held at its top: 扩展区, the default's, first (it was last,
    // under 三级字's 0 held); the rest as before. Only the page's order: the grid and the file keep GRID's
    @Test
    fun thePageListsTheGroupsHeldByDefaultFirstTheRestInTheirOrder() = assertRows(
        "扩展区 first, then 三级字, 繁体字, 其它表外字" to { assertEquals(listOf(Level.EXT, Level.L3, Level.TRAD, Level.OUT), RareLayout.ORDER) },
        "the default's groups ahead of every other" to {
            val held = RareLayout.ORDER.takeWhile { it in RareChoice.DEFAULT }
            assertEquals(RareChoice.DEFAULT, held.toSet())
            assertEquals(Level.GRID.filter { it !in RareChoice.DEFAULT }, RareLayout.ORDER.drop(held.size))
        },
        "the grid's groups unchanged" to { assertEquals(listOf(Level.L3, Level.TRAD, Level.OUT, Level.EXT), Level.GRID) },
    )

    @Test
    fun theRowsAreFoundByBinarySearch() {
        val layout = RareLayout(
            listOf(
                RareLayout.Group(Level.L3, sorted.getValue(Level.L3), { grid.spelling(it)[0] }, open = true),
                RareLayout.Group(Level.OUT, sorted.getValue(Level.OUT), { grid.spelling(it)[0] }, open = true),
                RareLayout.Group(Level.EXT, sorted.getValue(Level.EXT), { grid.spelling(it)[0] }, open = false),
            ),
        )
        // L3: head, b, 犇, z, 㑇; OUT: head, d, its two; EXT: head
        assertRows(
            "the rows: heads, letters, cells" to {
                assertEquals(
                    listOf("HEAD", "LETTER", "CELL", "LETTER", "CELL", "HEAD", "LETTER", "CELL", "CELL", "HEAD"),
                    (0 until layout.size).map { layout.kind(it).name },
                )
            },
            "a shut group shows its head alone" to { assertEquals(9, layout.headOf(Level.EXT)) },
            "a group's cells' rows" to { assertEquals(7..8, layout.cellsOf(Level.OUT)) },
            "a cell's character" to { assertEquals(TestCharLevels.AT_8104, grid.chars[layout.cell(2)]) },
            "a head takes a row, a cell a column" to {
                assertEquals(3, layout.spanSize(0, 3))
                assertEquals(1, layout.spanSize(2, 3))
            },
            "the second cell of a row of two is column 1" to { assertEquals(1, layout.spanIndex(8, 3)) },
            "the screen rows: one each but cells, the columns' worth a row" to {
                assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 7, 8), (0 until layout.size).map { layout.spanGroupIndex(it, 3) })
                assertEquals(8, layout.spanGroupIndex(8, 1))
            },
            "the fast scroller: the letters of the open groups" to { assertEquals(listOf('b', 'd', 'z'), layout.letters) },
            "a letter goes to its row in the group at the top: z in 三级字" to { assertEquals(3, layout.positionOf('z', 0)) },
            "a letter the group has not goes to the one before it there: d, 三级字's b, not 其它表外字's d" to {
                assertEquals(1, layout.positionOf('d', 0))
            },
            "其它表外字 at the top: z stays in it, its d, not back to 三级字's z" to { assertEquals(6, layout.positionOf('z', 7)) },
            "its head at the top is in it too: before its first letter, its first" to { assertEquals(6, layout.positionOf('b', 5)) },
            "a shut group's head at the top, none open after: the first open group's" to { assertEquals(1, layout.positionOf('d', 9)) },
            "before the first, the first" to { assertEquals(1, layout.positionOf('a')) },
            "a cell's row found from its grid index" to { assertEquals(4, layout.positionOf(at(TestCharLevels.AT_6501))) },
            "a shut group's cell shows not" to { assertEquals(-1, layout.positionOf(at(ext))) },
            "a row past the end refused" to { assertThrows(IllegalArgumentException::class.java) { layout.kind(10) } },
        )
    }

    @Test
    @Uat("WD-52")
    fun aDragSetsTheRangeToTheStartsOppositeAndLeavesTheRestAsTheyWere() {
        // rows 0..9, row 4 a head; held: 1 and 6
        val held = setOf(1, 6)
        val cell = { p: Int -> p != 4 }
        assertRows(
            "forward: the range takes the start's opposite" to {
                val d = DragRange(2, held::contains, cell)
                assertTrue(d.target)
                assertEquals(listOf(3), d.moveTo(3))
                assertEquals(listOf(2, 3), d.changes())
            },
            "backward: as forward" to {
                val d = DragRange(2, held::contains, cell)
                d.moveTo(0)
                assertEquals(listOf(0, 2), d.changes())
                assertTrue(d.shown(0))
            },
            "dragged back, the cells left show as they were" to {
                val d = DragRange(2, held::contains, cell)
                d.moveTo(7)
                assertEquals(listOf(5, 7), d.moveTo(3).sorted())
                assertFalse(d.shown(5))
                assertTrue(d.shown(6))
            },
            "across a group's head: the head passed over, the next group's cells on" to {
                val d = DragRange(3, held::contains, cell)
                d.moveTo(5)
                assertEquals(listOf(3, 5), d.changes())
            },
            "from a held cell: let through" to {
                val d = DragRange(6, held::contains, cell)
                assertFalse(d.target)
                d.moveTo(8)
                assertEquals(listOf(6), d.changes())
            },
        )
    }

    @Test
    fun thePagesEditsAreLaidOverTheChoiceAsMade() {
        val read = RareChoice.parse("# youmo-rare-chars 1\non\t1\ngroups\tEXT\n")
        val edits = RareEdits(grid, levels, read)
        assertRows(
            "as read: 扩展 B all held, the others none" to {
                assertEquals(RareEdits.Box.ALL, edits.box(Level.EXT))
                assertEquals(RareEdits.Box.NONE, edits.box(Level.OUT))
                assertEquals(2, edits.heldCount())
            },
            "a cell toggled: its group partly held" to {
                edits.toggle(at(ext))
                assertEquals(RareEdits.Box.SOME, edits.box(Level.EXT))
                assertEquals(listOf<Change>(Change.One(ext, false)), edits.changes)
            },
            "a group held whole: undone, as it was" to {
                val undo = edits.setGroup(Level.OUT, true)
                assertEquals(3, edits.heldCount())
                edits.undo(undo)
                assertEquals(RareEdits.Box.NONE, edits.box(Level.OUT))
                assertEquals(1, edits.changes.size)
            },
            "a drag's cells one undo" to {
                val undo = edits.set(listOf(at(out), at(0x9F97)), true)
                assertEquals(2, edits.heldIn(Level.OUT))
                edits.undo(undo)
                assertEquals(0, edits.heldIn(Level.OUT))
            },
            "恢复默认: 扩展 B alone" to {
                edits.reset()
                assertEquals(RareEdits.Box.ALL, edits.box(Level.EXT))
            },
            "off: none counted" to {
                edits.setOn(false)
                assertEquals(0, edits.heldCount())
            },
            "saved: the changes made since kept to save, a step before it no more to take back" to {
                val before = edits.toggle(at(out))
                val sent = edits.changes.size
                val later = edits.toggle(at(0x9F97))
                // the file as another writer left it: 龘 let through again (as saved), 𤝰 held
                edits.saved(RareChoice.parse("# youmo-rare-chars 1\non\t1\ngroups\tEXT\nblock\t${s(out)}\n"), sent)
                assertEquals(listOf<Change>(Change.One(0x9F97, true)), edits.changes)
                assertEquals(2, edits.heldIn(Level.OUT))
                assertFalse(edits.undo(before))
                assertFalse(edits.undo(later))
                val after = edits.toggle(at(0x9F97))
                assertEquals(1, edits.heldIn(Level.OUT))
                assertTrue(edits.undo(after))
                assertEquals(2, edits.heldIn(Level.OUT))
            },
            "a later version's file: nothing changes" to {
                val newer = RareEdits(grid, levels, RareChoice.parse("# youmo-rare-chars 2\non\t1\n"))
                assertTrue(newer.readOnly)
                assertThrows(IllegalStateException::class.java) { newer.toggle(at(ext)) }
            },
        )
    }

    @Test
    fun aSearchFindsASpellingOrThePastedCharacter() = assertRows(
        "a spelling: its cells" to { assertEquals(RareEdits.Found.Cells(listOf(at(TestCharLevels.AT_8104))), search("ben")) },
        "the character pasted: its cell" to { assertEquals(RareEdits.Found.Cells(listOf(at(ext))), search(s(ext))) },
        "一级: common, no cell" to { assertEquals(RareEdits.Found.Common('的'.code), search("的")) },
        "not ours" to { assertEquals(RareEdits.Found.NotOurs('〇'.code), search("〇")) },
        "its group's, no reading" to { assertEquals(RareEdits.Found.NoCell(noReading, Level.EXT), search(s(noReading))) },
        "nothing typed: nothing" to { assertEquals(RareEdits.Found.Cells(emptyList()), search(" ")) },
    )

    private fun search(q: String) = RareEdits.search(q, grid, levels, sorted)
}
