/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
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
        assertEquals(
            SvgSubset.Icon(48.0, 32.0, listOf(SvgSubset.Path("M0 0h1v1z", true, 0.25), SvgSubset.Path("M4 4h1v1z", false, 1.0))),
            read.value,
        )
        assertEquals(24.0, (SvgSubset.read("""<svg><path d="M0 0"/></svg>""") as Parsed.Ok).value.width)
        assertEquals(10.0, (SvgSubset.read("""<svg width="10px" height="12"><path d="M0 0"/></svg>""") as Parsed.Ok).value.width)
        assertIs<Parsed.Failed>(SvgSubset.read("""<svg><path d="M0 0" transform="scale(2)"/></svg>"""))
    }

    @Test
    fun whatReachesOutOrRunsIsRefused() {
        fun refused(svg: String) = assertTrue(problems(svg).isNotEmpty(), svg)
        refused("""<svg><script>alert(1)</script></svg>""")
        refused("""<svg><image href="http://x/y.png"/></svg>""")
        refused("""<svg><use href="#a"/></svg>""")
        refused("""<svg><path d="M0 0" onclick="x()"/></svg>""")
        refused("""<svg><path d="M0 0" style="fill:red"/></svg>""")
        refused("""<svg><path d="M0 0" stroke="#000"/></svg>""")
        refused("""<svg><path d="M0 0" transform="scale(2)"/></svg>""")
        refused("""<svg><path d="M0 0" fill="url(#g)"/></svg>""")
        refused("""<svg><svg/></svg>""")
        refused("""<svg><text>hi</text></svg>""")
        refused("""<svg>words</svg>""")
        refused("""<svg xmlns="http://example.com/x"/>""")
        refused("""<html/>""")
        // a DTD's entities: the billion laughs and XXE never get past the reader
        refused("""<!DOCTYPE svg [<!ENTITY a "aaaa">]><svg>&a;</svg>""")
        refused("""<!DOCTYPE svg SYSTEM "file:///etc/passwd"><svg/>""")
        refused("""<svg><path d="M0 0" xlink:href="x"/></svg>""")
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
        assertEquals(emptyList<Problem>(), SvgSubset.check("""<svg width="0.001" height="1e6"><path d="M0 0"/></svg>"""))
    }

    @Test
    fun theViewBoxsOriginIsKeptForTheDrawer() {
        // Material Symbols draw above y=0: without the origin the preview put the icon a whole key higher
        val icon = SvgSubset.read("""<svg viewBox="0 -960 960 960"><path d="M0 -480 h960"/></svg>""").ok()
        assertEquals(listOf(0.0, -960.0, 960.0, 960.0), listOf(icon.x, icon.y, icon.width, icon.height))
        assertEquals(listOf(0.0, 0.0), SvgSubset.read("""<svg width="10"><path d="M0 0"/></svg>""").ok().let { listOf(it.x, it.y) })
    }

    @Test
    fun aGroupsFillOpacityIsInheritedLikeItsFill() {
        // fill-opacity is inherited in SVG: the nearest one's counts, opacity multiplies on top
        val icon = SvgSubset.read(
            """<svg><g fill-opacity="0.5" opacity="0.5"><path d="M0 0"/><path d="M1 1" fill-opacity="1"/></g></svg>""",
        ).ok()
        assertEquals(listOf(0.25, 0.5), icon.paths.map { it.alpha })
    }
}
