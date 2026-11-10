/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.ShapePath.Segment.Close
import org.fcitx.fcitx5.android.scene.theme.ShapePath.Segment.Line
import org.fcitx.fcitx5.android.scene.theme.ShapePath.Segment.Move
import org.fcitx.fcitx5.android.scene.theme.SvgSubset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SvgSubsetTest {

    private fun problems(svg: String) = SvgSubset.check(svg).map { it.message }

    @Test
    fun filledPathsAreAnIcon() {
        val icon = """<?xml version="1.0" encoding="UTF-8"?>
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24px" height="24">
              <!-- a comment is fine -->
              <g fill="#333" fill-rule="evenodd" opacity="0.5">
                <path d="M12 2L2 22h20z m0 4 .5-1e1 A1,1 0 0 1 3 3 Z" fill="currentColor"/>
                <path id="b" d="m1 1 h2 v2 c1 1 2 2 3 3 s1 1 2 2 q1 1 2 2 t1 1 z" fill-opacity="1" fill="none"/>
              </g>
            </svg>"""
        assertEquals(emptyList<String>(), problems(icon))
    }

    @Test
    fun anIconIsReadAsItsFilledPaths() {
        val icon = """<svg viewBox="0 0 48 32"><g fill-rule="evenodd" opacity="0.5"><path d="M0 0h1v1z" fill-opacity="0.5"/>""" +
            """<path d="M2 2h1v1z" fill="none"/></g><path d="M4 4h1v1z"/></svg>"""
        val read = SvgSubset.read(icon)
        assertIs<Parsed.Ok<SvgSubset.Icon>>(read)
        fun square(x: Double) = listOf(Move(x, x), Line(x + 1, x), Line(x + 1, x + 1), Close)
        assertEquals(
            SvgSubset.Icon(48.0, 32.0, listOf(SvgSubset.Path(square(0.0), true, 0.25), SvgSubset.Path(square(4.0), false, 1.0))),
            read.value,
        )
        assertEquals(24.0, SvgSubset.read("""<svg><path d="M0 0h1"/></svg>""").ok().width)
        assertEquals(10.0, SvgSubset.read("""<svg width="10px" height="12"><path d="M0 0h1"/></svg>""").ok().width)
        assertIs<Parsed.Failed>(SvgSubset.read("""<svg><path d="M0 0h1" transform="scale(2)"/></svg>"""))
    }

    // CSS's byte order, alpha last: #f008 is red at 0x88, not the project's #aarrggbb (a transparent-ish blue-less nothing)
    @Test
    fun aPathsOwnColourIsReadAlphaLast() {
        fun color(fill: String) = SvgSubset.read("""<svg><path d="M0 0h1" fill="$fill"/></svg>""").ok().paths.single().color
        assertEquals(Argb.of(255, 0xff, 0, 0), color("#f00"))
        // on JS, Regex.matches once refused #f008 (four digits) though the JVM took it: this row keeps the web's reading
        assertEquals(Argb.of(0x88, 0xff, 0, 0), color("#f008"))
        assertEquals(Argb.of(255, 0x12, 0x34, 0x56), color("#123456"))
        assertEquals(Argb.of(0x80, 0x12, 0x34, 0x56), color("#12345680"))
        // no fill, or currentColor, inherited or not: the key's icon-color
        assertEquals(null, color("currentColor"))
        assertEquals(null, SvgSubset.read("""<svg><path d="M0 0h1"/></svg>""").ok().paths.single().color)
        assertEquals(Argb.of(255, 0, 0xff, 0), SvgSubset.read("""<svg fill="#0f0"><g><path d="M0 0h1"/></g></svg>""").ok().paths.single().color)
    }

    // a replaced required key would be blank: an icon must draw something
    @Test
    fun anIconThatDrawsNothingIsRefused() {
        val nothing = listOf(
            """<path d="M0 0h1" fill="none"/>""",
            """<g fill="none"><path d="M0 0h1"/></g>""",
            """<path d="M0 0h1" opacity="0"/>""",
            """<path d="M0 0h1" fill-opacity="0"/>""",
            """<path d="M0 0h1" fill="#f000"/>""",
            """<path d="M0 0 M5 5"/>""",
            """<path d="M0 0 z"/>""",
            """<path d="M0 0 a1 1 0 0 0 0 0"/>""",
            "",
        )
        // every row checked, the wrong ones said together
        val wrong = nothing.map { it to problems("<svg>$it</svg>") }.filter { (_, got) -> got != listOf(SvgSubset.NOTHING_DRAWN) }
        assertEquals(emptyList<Pair<String, List<String>>>(), wrong)
        // one that draws among them is enough; a zero-area one counts (its area is not worked out)
        assertEquals(emptyList<String>(), problems("""<svg><path d="M0 0" /><path d="M0 0L1 0Z"/></svg>"""))
        assertEquals(1, SvgSubset.read("""<svg><path d="M0 0" /><path d="M0 0L1 0Z"/></svg>""").ok().paths.size)
    }

    // xMidYMid meet: alike both ways, centred, the viewBox's origin at the box's corner
    @Test
    fun anIconFitsItsBoxCentredAndUnstretched() {
        val wide = SvgSubset.Icon(48.0, 24.0, emptyList())
        assertEquals(SvgSubset.Fit(0.5, 10.0, 26.0), wide.fit(Box(10.0, 20.0, 34.0, 44.0)))
        val tall = SvgSubset.Icon(10.0, 40.0, emptyList(), 5.0, -10.0)
        assertEquals(SvgSubset.Fit(1.0, 2.0, 10.0), tall.fit(Box(0.0, 0.0, 24.0, 40.0)))
        val square = SvgSubset.Icon(24.0, 24.0, emptyList())
        assertEquals(SvgSubset.Fit(2.0, 0.0, 0.0), square.fit(Box(0.0, 0.0, 48.0, 48.0)))
    }

    @Test
    fun whatReachesOutOrRunsIsRefused() {
        // each with a path that draws, and refused for something else than drawing nothing: the check it is about
        fun refused(svg: String) = assertTrue(problems(svg).any { it != SvgSubset.NOTHING_DRAWN }, "$svg: ${problems(svg)}")
        val draws = """<path d="M0 0h1"/>"""
        refused("""<svg>$draws<script>alert(1)</script></svg>""")
        refused("""<svg>$draws<image href="http://x/y.png"/></svg>""")
        refused("""<svg>$draws<use href="#a"/></svg>""")
        refused("""<svg><path d="M0 0h1" onclick="x()"/></svg>""")
        refused("""<svg><path d="M0 0h1" style="fill:red"/></svg>""")
        refused("""<svg><path d="M0 0h1" stroke="#000"/></svg>""")
        refused("""<svg><path d="M0 0h1" transform="scale(2)"/></svg>""")
        refused("""<svg><path d="M0 0h1" fill="url(#g)"/></svg>""")
        refused("""<svg>$draws<svg/></svg>""")
        refused("""<svg>$draws<text>hi</text></svg>""")
        refused("""<svg>${draws}words</svg>""")
        refused("""<svg xmlns="http://example.com/x">$draws</svg>""")
        refused("""<html>$draws</html>""")
        // a DTD's entities: the billion laughs and XXE never get past the reader
        refused("""<!DOCTYPE svg [<!ENTITY a "aaaa">]><svg>$draws&a;</svg>""")
        refused("""<!DOCTYPE svg SYSTEM "file:///etc/passwd"><svg>$draws</svg>""")
        refused("""<svg><path d="M0 0h1" xlink:href="x"/></svg>""")
    }

    @Test
    fun pathDataIsCommandsAndNumbersStartingWithAMove() {
        assertEquals(listOf("d=: a path starts with M"), problems("""<svg><path d="L0 0"/></svg>"""))
        assertEquals(listOf("d=: a path starts with M"), problems("""<svg><path d="0 0"/></svg>"""))
        assertEquals(listOf("d=: empty"), problems("""<svg><path d=" "/></svg>"""))
        assertEquals(listOf("<path> needs d="), problems("""<svg><path/></svg>"""))
        assertEquals(listOf("d=: 'x' at 4 is no command or number"), problems("""<svg><path d="M0 x"/></svg>"""))
        assertTrue(problems("""<svg viewBox="0 0 0 24"/>""").single().startsWith("viewBox="))
        assertTrue(problems("""<svg><path d="M0 0" opacity="2"/></svg>""").single().startsWith("opacity="))
        assertTrue(problems("""<svg><path d="M0 0" fill-rule="odd"/></svg>""").single().startsWith("fill-rule="))
        assertTrue(problems("""<svg width="1em"/>""").single().startsWith("width="))
        // written as a number, read as Infinity: past what a drawing can scale by
        assertTrue(problems("""<svg viewBox="0 0 1e999 24"/>""").single().startsWith("viewBox="))
        assertTrue(problems("""<svg width="1e999px"/>""").single().startsWith("width="))
        assertEquals(listOf("d=: 1e999 at 2 is past a number's range"), problems("""<svg><path d="M1e999 0"/></svg>"""))
        // a double's but a float's Infinity, or a side that scales by it: past it too
        assertEquals(listOf("d=: 1e300 at 2 is past a number's range"), problems("""<svg><path d="M1e300 0"/></svg>"""))
        assertEquals(listOf("d=: -2e6 at 4 is past a number's range"), problems("""<svg><path d="M0 -2e6"/></svg>"""))
        assertTrue(problems("""<svg viewBox="0 0 1e-300 24"/>""").single().startsWith("viewBox="))
        assertTrue(problems("""<svg width="4e38"/>""").single().startsWith("width="))
        // an icon's numbers as they come: none
        assertEquals(emptyList<String>(), problems("""<svg viewBox="0 0 1024 0.5"><path d="M-1e6 0 L1e6 1" opacity="0.5"/></svg>"""))
    }

    @Test
    fun aTracedPictureIsNoIcon() {
        val many = (0..SvgSubset.MAX_PATHS).joinToString("") { """<path d="M0 0"/>""" }
        assertEquals(listOf("over ${SvgSubset.MAX_PATHS} paths"), problems("<svg>$many</svg>"))
        val long = "M" + List(SvgSubset.MAX_NUMBERS + 1) { "1" }.joinToString(" ")
        assertEquals(listOf("over ${SvgSubset.MAX_NUMBERS} numbers in the paths"), problems("""<svg><path d="$long"/></svg>"""))
    }

    // a theme's own SVG icon as the maker's preview (and a later app) draws it: its sides, its origin, its opacity
    @Test
    fun aSideOfNothingOrLessIsRefusedAsAViewBoxsIs() {
        // with no viewBox width= and height= are the sides: 0 would scale by Infinity, -24 mirror the icon
        for (side in listOf("0", "-24", "1e-300", "0px")) {
            assertTrue(SvgSubset.check("""<svg width="$side"><path d="M0 0"/></svg>""").single().message.startsWith("width="), side)
            assertTrue(SvgSubset.check("""<svg height="$side"><path d="M0 0"/></svg>""").single().message.startsWith("height="), side)
        }
        assertEquals(emptyList<Problem>(), SvgSubset.check("""<svg width="0.001" height="1e6"><path d="M0 0h1"/></svg>"""))
    }

    @Test
    fun theViewBoxsOriginIsKeptForTheDrawer() {
        // Material Symbols draw above y=0: without the origin the preview put the icon a whole key higher
        val icon = SvgSubset.read("""<svg viewBox="0 -960 960 960"><path d="M0 -480 h960"/></svg>""").ok()
        assertEquals(listOf(0.0, -960.0, 960.0, 960.0), listOf(icon.x, icon.y, icon.width, icon.height))
        assertEquals(listOf(0.0, 0.0), SvgSubset.read("""<svg width="10"><path d="M0 0h1"/></svg>""").ok().let { listOf(it.x, it.y) })
    }

    @Test
    fun aGroupsFillOpacityIsInheritedLikeItsFill() {
        // fill-opacity is inherited in SVG: the nearest one's counts, opacity multiplies on top
        val icon = SvgSubset.read(
            """<svg><g fill-opacity="0.5" opacity="0.5"><path d="M0 0h1"/><path d="M1 1h1" fill-opacity="1"/></g></svg>""",
        ).ok()
        assertEquals(listOf(0.25, 0.5), icon.paths.map { it.alpha })
    }
}
