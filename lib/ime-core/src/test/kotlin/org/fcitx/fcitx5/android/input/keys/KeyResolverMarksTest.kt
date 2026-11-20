/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.input.scene.SceneData
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.keys.Commit
import org.fcitx.fcitx5.android.scene.keys.DefaultMarks
import org.fcitx.fcitx5.android.scene.keys.Item
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant.Form
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

// a key's items marked 使用变量 and 不转中文标点
class KeyResolverMarksTest {

    private class Clock(var at: Long) : LearnClock {
        override fun now() = at
        override fun offset(at: Long) = 0
    }

    private fun at(day: Int, hour: Int, minute: Int): Long =
        GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, Calendar.NOVEMBER, day, hour, minute) }.timeInMillis

    private val q = KeyId("full/q")
    private val comma = KeyId("fn/comma")
    private val zh = KeyState(punctuation = mapOf("." to "。", "," to "，"))

    private fun resolver(lists: Map<KeyId, UserList>, clock: Clock, slots: SwipeSlots = SwipeSlots.Default): KeyResolver {
        val layout = (LayoutReader.layout(SceneData.read("qwerty.xml")) as Parsed.Ok).value
        return KeyResolver(KeyboardVariant(Form.Full), KeyLists.of(lists), slots, DefaultMarks.nodes(layout, emptyMap()), clock)
    }

    private fun list(vararg items: Pair<String, ItemMark?>, first: First = First.Default) =
        UserList(false, items.map { it.first }, first, items.mapNotNull { (t, m) -> m?.let { t to it } }.toMap())

    private fun popup(r: ResolvedKey) = r.popup as KeyPopup.Items

    @Test
    fun anItemOfVariablesIsFilledInTypedAsItIsAndNotInCapitals() {
        val clock = Clock(at(14, 23, 59))
        val r = resolver(mapOf(q to list("\$month月\$day日" to ItemMark.Variables, "x" to null, "\$day" to null)), clock)
        val p = popup(r.resolve(q, KeyState()))
        assertEquals(listOf(Item("11月14日", Commit.AsIs), Item("x"), Item("\$day")), p.items)
        assertEquals(listOf("\$month月\$day日", null, null), p.templates)
        val k = r.resolve(q, KeyState())
        assertEquals("11月14日", k.firstShown)
        assertEquals("\$month月\$day日", k.firstTemplate)
        assertEquals(MarkContent.Symbol("11…"), k.upMark)
        // Shift: the others in capitals, the one of variables as filled in
        val caps = popup(r.resolve(q, KeyState(caps = true)))
        assertEquals(listOf("11月14日", "X", "\$DAY"), caps.items.map { it.text })
        // the editor gets it as written
        assertEquals(listOf("\$month月\$day日", "x", "\$day"), r.edit(q).items)
        assertEquals("\$month月\$day日", r.edit(q).first)
    }

    @Test
    fun theSameValueAsAnotherIsShownOnceTheFirstKept() {
        val r = resolver(mapOf(q to list("14" to null, "\$day" to ItemMark.Variables, "a" to null)), Clock(at(14, 12, 0)))
        val p = popup(r.resolve(q, KeyState()))
        assertEquals(listOf(Item("14"), Item("a")), p.items)
        assertTrue(p.templates.isEmpty())
        // the editor keeps both
        assertEquals(listOf("14", "\$day", "a"), r.edit(q).items)
    }

    @Test
    fun anItemFillingInNothingIsNotShownNorTheFirst() {
        val r = resolver(mapOf(q to list("\$yaer" to ItemMark.Variables, "a" to null, first = First.Literal("\$yaer"))), Clock(at(14, 12, 0)))
        val k = r.resolve(q, KeyState())
        assertEquals(listOf(Item("a")), popup(k).items)
        assertNull(k.first)
        // the default first: the first shown
        val d = resolver(mapOf(q to list("\$yaer" to ItemMark.Variables, "a" to null)), Clock(at(14, 12, 0))).resolve(q, KeyState())
        assertEquals(Item("a"), d.first)
    }

    // past midnight, the item of variables types the new date; the plain 14 types 14
    @Test
    fun outputFillsInAgainAsPicked() {
        val clock = Clock(at(14, 23, 59))
        val r = resolver(mapOf(q to list("\$day" to ItemMark.Variables, "14" to null)), clock)
        val p = popup(r.resolve(q, KeyState(minute = clock.at / 60_000)))
        // drawn on the 14th: the plain 14 left out as the same
        assertEquals(listOf(Item("14", Commit.AsIs)), p.items)
        clock.at = at(15, 0, 0)
        assertEquals("15", r.output(p.items[0], p.templates[0]))
        // made anew the next minute: both, each typing its own
        val next = popup(r.resolve(q, KeyState(minute = clock.at / 60_000)))
        assertEquals(listOf("15", "14"), next.items.map { it.text })
        assertEquals("15", r.output(next.items[0], next.templates[0]))
        assertEquals("14", r.output(next.items[1], next.templates.getOrNull(1)))
        val first = r.resolve(q, KeyState(minute = clock.at / 60_000))
        clock.at = at(16, 0, 0)
        assertEquals("16", r.output(first.first!!, first.firstTemplate))
    }

    @Test
    fun aNewMinuteIsMadeAnewTheSameOneKept() {
        val clock = Clock(at(14, 9, 5))
        val r = resolver(mapOf(q to list("\$fullhour:\$minute" to ItemMark.Variables)), clock)
        assertTrue(r.fillsIn)
        val before = r.marks(KeyState(minute = 1))
        clock.at = at(14, 9, 6)
        // the same minute asked: kept as made
        assertTrue(before === r.marks(KeyState(minute = 1)))
        assertEquals("09:05", r.resolve(q, KeyState(minute = 1)).firstShown)
        assertEquals("09:06", r.resolve(q, KeyState(minute = 2)).firstShown)
        assertEquals(MarkContent.Symbol("09…"), r.marks(KeyState(minute = 2)).shortcuts["full/q:up"])
        // no variables: nothing to make again by the minute
        assertFalse(resolver(mapOf(q to list("a" to null)), clock).fillsIn)
        assertFalse(resolver(emptyMap(), clock).fillsIn)
        assertTrue(resolver(emptyMap(), clock, SwipeSlots.Default.with("full/q", false, SwipeSlot.Text("\$day", variables = true))).fillsIn)
    }

    @Test
    fun aSlotOfVariablesIsDrawnFilledIn() {
        val clock = Clock(at(14, 9, 5))
        val slots = SwipeSlots.Default.with("full/q", false, SwipeSlot.Text("\${fullhour}:\$minute", variables = true))
            .with("full/w", false, SwipeSlot.Text("\$minute", variables = false))
            .with("full/e", false, SwipeSlot.Text("\$yaer", variables = true))
        val r = resolver(emptyMap(), clock, slots)
        assertEquals(MarkContent.Text("09…", "0…"), r.resolve(q, KeyState()).downMark)
        assertEquals(MarkContent.Text("\$m…", "\$…"), r.resolve(KeyId("full/w"), KeyState()).downMark)
        assertEquals(MarkContent.Empty, r.resolve(KeyId("full/e"), KeyState()).downMark)
    }

    // as it is, whatever the default's commit
    @Test
    fun anItemAsIsIsTypedAsItIs() {
        val clock = Clock(at(14, 9, 5))
        val plain = resolver(emptyMap(), clock).resolve(comma, zh)
        assertEquals(Item("."), popup(plain).items.first())
        // the default's `.` marked: as it is, not as the default's
        val r = resolver(mapOf(comma to list("." to ItemMark.AsIs, "," to null, first = First.Literal("."))), clock)
        val k = r.resolve(comma, zh)
        assertEquals(listOf(Item(".", Commit.AsIs), Item(",")), popup(k).items)
        // drawn as typed: the one as it is never as the punctuation
        assertEquals(listOf(".", "，"), popup(k).labels)
        assertEquals(Item(".", Commit.AsIs), k.first)
        assertEquals(".", k.firstShown)
        assertNull(k.firstTemplate)
        assertEquals(Item(".", Commit.AsIs), r.resolve(comma, zh.copy(caps = true)).first)
    }

    // where the engine converts no punctuation the mark changes nothing: typed as the key, English's space after `.` as ever
    @Test
    fun anItemAsIsIsTheKeyWhereNothingIsConverted() {
        val clock = Clock(at(14, 9, 5))
        val unmarked = resolver(mapOf(comma to list("." to null, "," to null, first = First.Literal("."))), clock).resolve(comma, KeyState())
        val r = resolver(mapOf(comma to list("." to ItemMark.AsIs, "," to null, first = First.Literal("."))), clock)
        val k = r.resolve(comma, KeyState())
        assertEquals(popup(unmarked).items, popup(k).items)
        assertEquals(unmarked.first, k.first)
        // and again as it is where it is converted
        assertEquals(Item(".", Commit.AsIs), r.resolve(comma, zh).first)
        // a mark the table leaves alone is the key too
        assertEquals(unmarked.first, r.resolve(comma, KeyState(punctuation = mapOf("," to "，"))).first)
    }
}
