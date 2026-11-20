/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.input.keyboard.KeySwipe
import org.fcitx.fcitx5.android.input.scene.SceneMarks
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

// a swipe's text of variables: `vtext:`, the mark kept every way it goes
class SwipeSlotVariablesTest {
    private val now = GregorianCalendar(2026, Calendar.NOVEMBER, 14, 9, 5)
    private val vars = SwipeSlot.Text("\${fullhour}:\$minute", variables = true)

    @Test
    fun storedAsVtextAndReadBack() {
        val slots = SwipeSlots.Default.with("full/q", false, vars).with("full/w", false, SwipeSlot.Text("\$minute"))
        val text = slots.serialize()
        assertTrue(text, "full/q.down\tvtext:\${fullhour}:\$minute" in text.lines())
        assertTrue(text, "full/w.down\ttext:\$minute" in text.lines())
        assertEquals(slots, SwipeSlots.parse(text))
        assertEquals(vars, SwipeSlots.parse(text).resolve("full/q", false))
        assertTrue(slots.fillsIn)
        assertFalse(SwipeSlots.Default.with("full/w", false, SwipeSlot.Text("\$minute")).fillsIn)
    }

    // ticked, stored with variables only where they type it differently: `US$$5` is `US$5` with them
    @Test
    fun tickedIsStoredAsVtextWhereItTypesDifferently() {
        val slots = SwipeSlots.Default.with("full/q", false, SwipeSlot.Text.of("US\$\$5", ticked = true))
            .with("full/w", false, SwipeSlot.Text.of("US\$5", ticked = true))
            .with("full/e", false, SwipeSlot.Text.of("\$5", ticked = true))
            .with("full/r", false, SwipeSlot.Text.of("\$day", ticked = false))
        val lines = slots.serialize().lines()
        assertTrue(lines.toString(), "full/q.down\tvtext:US\$\$5" in lines)
        assertTrue(lines.toString(), "full/w.down\ttext:US\$5" in lines)
        assertTrue(lines.toString(), "full/e.down\ttext:\$5" in lines)
        assertTrue(lines.toString(), "full/r.down\ttext:\$day" in lines)
        assertEquals("US\$5", (slots.resolve("full/q", false).shortcut as KeyShortcut.Text).typed(now))
    }

    @Test
    fun theMarkGoesBothWaysAndTellsTwoSlotsApart() {
        assertEquals(KeyShortcut.Text("\$day", variables = true), SwipeSlot.Text("\$day", variables = true).shortcut)
        assertEquals(SwipeSlot.Text("\$day", variables = true), SwipeSlot.of(KeyShortcut.Text("\$day", variables = true)))
        assertNotEquals(SwipeSlot.Text("x"), SwipeSlot.Text("x", variables = true))
        // set over one of the same text without: another slot, kept
        val plain = SwipeSlots.Default.with("full/q", false, SwipeSlot.Text("x"))
        assertEquals(SwipeSlot.Text("x", variables = true), plain.with("full/q", false, SwipeSlot.Text("x", variables = true)).resolve("full/q", false))
    }

    // 0.5's shortcuts never had it
    @Test
    fun theOldShortcutsNeverHaveIt() {
        val old = KeyShortcuts.parse("full/q\ttext:\$year")
        assertEquals(KeyShortcut.Text("\$year", variables = false), old.resolve("full/q"))
        assertEquals("full/q\ttext:\$year", KeyShortcuts.Empty.with("full/q", KeyShortcut.Text("\$year", variables = true)).serialize())
    }

    @Test
    fun typedAndDrawnFilledIn() {
        val s = vars.shortcut as KeyShortcut.Text
        assertEquals("09:05", s.typed(now))
        assertEquals("\$minute", KeyShortcut.Text("\$minute").typed(now))
        // run twice, each at its own time
        assertEquals("09:06", s.typed(GregorianCalendar(2026, Calendar.NOVEMBER, 14, 9, 6)))
        assertEquals(MarkContent.Text("09…", "0…"), SceneMarks.content(s) { now })
        assertNull(SceneMarks.content(KeyShortcut.Text("\$yaer", variables = true)) { now })
        // the clock asked of a text of variables alone, not of every mark made
        var asked = 0
        val counted = { asked++; now }
        SceneMarks.content(KeyShortcut.Text("\$minute"), counted)
        SceneMarks.content(KeyShortcut.Command(KeyCommand.entries.first()), counted)
        SceneMarks.content(s, counted)
        assertEquals(1, asked)
        val marks = SceneMarks.settings(
            SwipeSlots.Default.with("full/q", false, vars), KeySwipe.Ways.Both, KeyMarks.At.SymbolDefault, KeyMarks.At.ShortcutDefault,
            charShortcuts = true, names = listOf("full/q"), now = { now },
        )
        assertEquals(MarkContent.Text("09…", "0…"), marks.shortcuts["full/q:down"])
    }

    // a board's marks made across a minute's turn show one time, the clock read once, none where no mark has variables
    @Test
    fun aBoardsMarksShowOneTime() {
        var asked = 0
        // each read a minute later than the one before
        val ticking = { GregorianCalendar(2026, Calendar.NOVEMBER, 14, 9, 5 + asked++) }
        fun marks(slots: SwipeSlots) = SceneMarks.settings(
            slots, KeySwipe.Ways.Both, KeyMarks.At.SymbolDefault, KeyMarks.At.ShortcutDefault,
            charShortcuts = true, names = listOf("full/q", "full/w"), now = ticking,
        )
        val minute = SwipeSlot.Text("\$minute", variables = true)
        val both = marks(SwipeSlots.Default.with("full/q", false, minute).with("full/w", false, minute))
        assertEquals(MarkContent.Text(KeyMarkText.of("05"), KeyMarkText.shorter("05")), both.shortcuts["full/q:down"])
        assertEquals(both.shortcuts["full/q:down"], both.shortcuts["full/w:down"])
        assertEquals(1, asked)
        marks(SwipeSlots.Default.with("full/q", false, SwipeSlot.Text("\$minute")))
        assertEquals("no mark of variables: the clock not read", 1, asked)
    }

    // one that fills in nothing is a slot with nothing: a character key's tap
    @Test
    fun aSwipeToNothingFilledInIsATap() {
        val key = KeySwipe.Key(char = true, up = SwipeSlot.First, down = SwipeSlot.Text("\$yaer", variables = true))
        assertEquals(KeySwipe.Outcome.Tap, KeySwipe.decide(key, KeySwipe.Ways.Both, 3, true, false, false, false) { now })
        val set = key.copy(down = vars)
        assertEquals(KeySwipe.Outcome.Shortcut(vars.shortcut), KeySwipe.decide(set, KeySwipe.Ways.Both, 3, true, false, false, false) { now })
        assertEquals(KeySwipe.Preview.Shortcut(vars.shortcut), KeySwipe.preview(set, KeySwipe.Ways.Both, 3, false) { now })
    }
}
