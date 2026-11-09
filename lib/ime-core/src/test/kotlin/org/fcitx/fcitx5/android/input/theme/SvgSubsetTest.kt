/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun whatReachesOutOrRunsIsRefused() {
        fun refused(svg: String) = assertTrue(svg, problems(svg).isNotEmpty())
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
    }

    @Test
    fun aTracedPictureIsNoIcon() {
        val many = (0..SvgSubset.MAX_PATHS).joinToString("") { """<path d="M0 0"/>""" }
        assertEquals(listOf("over ${SvgSubset.MAX_PATHS} paths"), problems("<svg>$many</svg>"))
        val long = "M" + List(SvgSubset.MAX_NUMBERS + 1) { "1" }.joinToString(" ")
        assertEquals(listOf("over ${SvgSubset.MAX_NUMBERS} numbers in the paths"), problems("""<svg><path d="$long"/></svg>"""))
    }
}
