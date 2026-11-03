/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.stroke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeShapeTest {

    @Test
    fun eachShapeItsKindAndItsCharacter() {
        // 行's 竖钩 a 竖; the turning ones each their own: 口 己 乙 九 马
        assertEquals("丿丿丨一一亅", StrokeShape.shown(StrokeShape.parse("P P S H H SG")))
        assertEquals("ppshhs", StrokeShape.keys(StrokeShape.parse("P P S H H SG")))
        assertEquals("丨㇕一", StrokeShape.shown(StrokeShape.parse("S HZ H")))
        assertEquals("㇕一乚", StrokeShape.shown(StrokeShape.parse("HZ H SWG")))
        assertEquals("㇈", StrokeShape.shown(StrokeShape.parse("HZWG")))
        assertEquals("丿㇈", StrokeShape.shown(StrokeShape.parse("P HZWG")))
        assertEquals("㇕㇉一", StrokeShape.shown(StrokeShape.parse("HZ SZWG H")))
        // 提 a 横, 捺 a 点 (江 氵, 人)
        assertEquals("nnhhsh", StrokeShape.keys(StrokeShape.parse("D D T H S H")))
        assertEquals("pn", StrokeShape.keys(StrokeShape.parse("P N")))
    }

    @Test
    fun theCodesAreUnicodesNamesAndTheShownCharactersItsStrokesWhereFontsHaveThem() {
        val stand = mapOf(StrokeShape.HXG to '⺄', StrokeShape.SZP to 'ㄣ', StrokeShape.ZHE to '乛')
        for (s in StrokeShape.entries) {
            if (s == StrokeShape.ZHE) {
                assertNull(StrokeShape.of(s.code))
                continue
            }
            assertEquals(s, StrokeShape.of(s.code))
            val c = s.shown
            val name = Character.getName(c.code).orEmpty()
            when {
                s in stand -> assertEquals(stand[s], c)
                c.code in 0x31C0..0x31E3 -> assertEquals("CJK STROKE ${s.code}", name)
                // the CJK characters of the commonest five, as the preedit draws them, and 亅 乚
                else -> assertTrue("$s $c", c in "一丨丿丶亅乚")
            }
        }
        assertEquals(33, StrokeShape.entries.size)
    }

    @Test
    fun aKindAloneAndNoShape() {
        assertEquals(listOf(StrokeShape.H, StrokeShape.S, StrokeShape.P, StrokeShape.D, StrokeShape.ZHE), "hspnz".map(StrokeShape::ofKey))
        assertEquals("hspnz", StrokeShape.keys("hspnz".map(StrokeShape::ofKey)))
        assertThrows(IllegalArgumentException::class.java) { StrokeShape.ofKey('x') }
        assertNull(StrokeShape.of("Z"))
        assertNull(StrokeShape.of(""))
        assertThrows(IllegalArgumentException::class.java) { StrokeShape.parse("P Q") }
        assertEquals(emptyList<StrokeShape>(), StrokeShape.parse(" "))
    }
}
