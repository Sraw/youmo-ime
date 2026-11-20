/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.data.CharLevels.Level
import org.fcitx.fcitx5.android.engine.data.TestCharLevels
import org.fcitx.fcitx5.android.engine.data.TestCharLevels.AT_6501
import org.fcitx.fcitx5.android.engine.data.TestCharLevels.AT_8104
import org.fcitx.fcitx5.android.engine.data.TestCharLevels.AT_8105
import org.fcitx.fcitx5.android.engine.user.RareChoice.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 生僻字屏蔽's file: read, decided, changed, written. */
class RareChoiceTest {

    private val levels = TestCharLevels.levels
    private val ext = TestCharLevels.EXT
    private val out = TestCharLevels.OUT
    private val trad = TestCharLevels.TRAD

    private fun cps(s: String) = s.codePoints().toArray().toSet()
    private fun str(cp: Int) = String(Character.toChars(cp))
    private fun file(vararg lines: String) = (listOf("# youmo-rare-chars 1") + lines).joinToString("\n", postfix = "\n")
    private fun held(choice: RareChoice, cp: Int) = choice.gate(levels).holds(cp)

    @Test
    fun whatAFileSays() = assertRows(
        "no file is off" to { assertSame(RareChoice.NONE, RareChoice.parse("")) },
        "off, the groups of a first turn-on: 扩展 B on alone" to { assertEquals(setOf(Level.EXT), RareChoice.NONE.groups) },
        "the header alone is off" to { assertFalse(RareChoice.parse(file()).on) },
        "on 0 is off" to { assertFalse(RareChoice.parse(file("on\t0")).on) },
        "on 1 with no groups line is EXT alone" to {
            val c = RareChoice.parse(file("on\t1"))
            assertTrue(c.on)
            assertEquals(setOf(Level.EXT), c.groups)
        },
        "an empty groups line is none held whole" to { assertEquals(emptySet<Level>(), RareChoice.parse(file("on\t1", "groups\t")).groups) },
        "groups, allow and block read" to {
            val c = RareChoice.parse(file("on\t1", "groups\tL3 OUT", "allow\t${str(AT_8105)}犇", "block\t${str(ext)}"))
            assertEquals(setOf(Level.L3, Level.OUT), c.groups)
            assertEquals(setOf(AT_8105, AT_8104), c.allow)
            assertEquals(setOf(ext), c.block)
        },
        "a later version is off, marked newer" to {
            val c = RareChoice.parse("# youmo-rare-chars 2\non\t1\n")
            assertTrue(c.newer)
            assertFalse(c.on)
            assertTrue(c.gate(levels).none)
        },
        "a version it cannot read is as a later one: not written over" to { assertTrue(RareChoice.parse("# youmo-rare-chars x\non\t1\n").newer) },
    )

    @Test
    fun whatIsHeldBack() {
        val choice = RareChoice.parse(file("on\t1", "groups\tEXT TRAD", "allow\t${str(ext)}", "block\t${str(out)}${str(AT_8104)}"))
        assertRows(
            "EXT's 𠀀 held with its group" to { assertTrue(held(choice, 0x20000)) },
            "EXT's 𤝰 let through by allow" to { assertFalse(held(choice, ext)) },
            "TRAD's 髮 held with its group" to { assertTrue(held(choice, trad)) },
            "OUT's 龘 held by block, its group let through" to { assertTrue(held(choice, out)) },
            "OUT's other characters let through" to { assertFalse(held(choice, 0x9F97)) },
            "L3's 犇 held by block" to { assertTrue(held(choice, AT_8104)) },
            "L3's other characters let through, 扩展 A's among them" to { assertFalse(held(choice, AT_6501)) },
            "one in block and allow both is held: block wins" to {
                val both = RareChoice.parse(file("on\t1", "groups\t", "allow\t犇", "block\t犇"))
                assertTrue(held(both, AT_8104))
            },
            "一级 in block is never held" to { assertFalse(held(RareChoice.parse(file("on\t1", "block\t的")), '的'.code)) },
            "a group named L1 holds nothing" to { assertFalse(held(RareChoice.parse(file("on\t1", "groups\tL1 L2")), '的'.code)) },
            "a character not ours in block (〇) is never held" to { assertFalse(held(RareChoice.parse(file("on\t1", "block\t〇")), 0x3007)) },
            "off, nothing is held whatever the file says" to { assertTrue(RareChoice.parse(file("on\t0", "block\t犇")).gate(levels).none) },
            "without the levels nothing is held: no range-only fallback" to { assertTrue(RareChoice.parse(file("on\t1")).gate(null).none) },
            "OUT held, the listed characters in its ranges are their own group's" to {
                val outOnly = RareChoice.parse(file("on\t1", "groups\tOUT"))
                assertTrue(held(outOnly, out))
                assertFalse(held(outOnly, AT_6501))
                assertFalse(held(outOnly, trad))
                assertFalse(held(outOnly, '的'.code))
            },
            "L3 held, 扩展 B's 三级 character with it, 扩展 B's others not" to {
                val l3 = RareChoice.parse(file("on\t1", "groups\tL3"))
                assertTrue(held(l3, AT_8105))
                assertTrue(held(l3, AT_6501))
                assertFalse(held(l3, ext))
            },
        )
    }

    @Test
    fun changesLaidOverTheFile() {
        val base = RareChoice.parse(file("on\t1", "groups\tEXT", "allow\t${str(ext)}", "block\t犇"))
        assertRows(
            "a group held clears its characters' exceptions" to {
                val c = base.with(listOf(Change.Group(Level.EXT, held = true)), levels)
                assertEquals(emptySet<Int>(), c.allow)
                assertEquals(setOf(AT_8104), c.block)
            },
            "a group let through clears its exceptions too" to {
                val c = base.with(listOf(Change.Group(Level.L3, held = false)), levels)
                assertEquals(emptySet<Int>(), c.block)
                assertEquals(setOf(Level.EXT), c.groups)
            },
            "a character as its group says is no exception" to {
                val c = base.with(listOf(Change.One(ext, held = true), Change.One(AT_8104, held = false)), levels)
                assertEquals(emptySet<Int>(), c.allow)
                assertEquals(emptySet<Int>(), c.block)
            },
            "a character against its group is one" to {
                val c = base.with(listOf(Change.One(0x20000, held = false), Change.One(out, held = true)), levels)
                assertEquals(setOf(ext, 0x20000), c.allow)
                assertEquals(setOf(AT_8104, out), c.block)
            },
            "一级 cannot be held" to { assertEquals(setOf(AT_8104), base.with(listOf(Change.One('的'.code, held = true)), levels).block) },
            "turning off keeps the choices" to {
                val c = base.with(listOf(Change.On(false)), levels)
                assertFalse(c.on)
                assertEquals(base.allow, c.allow)
                assertEquals(base.block, c.block)
            },
            "恢复默认: EXT alone, no exceptions, the switch as it was" to {
                val c = RareChoice.parse(file("on\t1", "groups\tL3 FUTURE", "allow\t${str(ext)}", "later\tx")).with(listOf(Change.Reset), levels)
                assertTrue(c.on)
                assertEquals(setOf(Level.EXT), c.groups)
                assertEquals(emptySet<Int>(), c.allow)
                assertEquals(emptyList<String>(), c.otherGroups)
                assertEquals(emptyList<String>(), c.otherLines)
            },
            "a later version's file is not written over" to {
                assertThrows(IllegalStateException::class.java) { RareChoice.parse("# youmo-rare-chars 9\n").with(listOf(Change.On(true)), levels) }
            },
        )
    }

    @Test
    fun writtenAsRead() = assertRows(
        "a group and a line this build knows not are kept, the known groups in order" to {
            val text = file("on\t1", "groups\tFUTURE EXT L3", "allow\t", "block\t", "colours\tred")
            assertEquals(file("on\t1", "groups\tL3 EXT FUTURE", "allow\t", "block\t", "colours\tred"), RareChoice.parse(text).format())
        },
        "the characters written by code point, beyond the basic plane as one" to {
            val c = RareChoice.NONE.with(listOf(Change.On(true), Change.One(ext, held = false), Change.One(out, held = true)), levels)
            assertEquals(file("on\t1", "groups\tEXT", "allow\t${str(ext)}", "block\t${str(out)}"), c.format())
            assertEquals(c, RareChoice.parse(c.format()))
        },
        "the first turn-on writes on and EXT" to {
            assertEquals(file("on\t1", "groups\tEXT", "allow\t", "block\t"), RareChoice.NONE.with(listOf(Change.On(true)), levels).format())
        },
    )

    @Test
    fun aCharacterBeyondTheBasicPlaneIsOne() = assertEquals(cps(str(ext)), RareChoice.parse(file("on\t1", "allow\t${str(ext)}")).allow)

    // a user word's row names the characters held back in it
    @Test
    @Uat("WD-56")
    fun theCharactersHeldInAWord() {
        val gate = RareChoice.parse(file("on\t1", "groups\tEXT", "block\t${str(out)}")).gate(levels)
        assertRows(
            "each held once, in the word's order, one beyond the basic plane whole" to {
                assertEquals(listOf(str(out), str(ext)), gate.heldIn("大${str(out)}${str(ext)}${str(out)}"))
            },
            "none held: none" to { assertEquals(emptyList<String>(), gate.heldIn("大家${str(trad)}")) },
            "off: none" to { assertEquals(emptyList<String>(), CharGate.NONE.heldIn(str(ext))) },
        )
    }
}
