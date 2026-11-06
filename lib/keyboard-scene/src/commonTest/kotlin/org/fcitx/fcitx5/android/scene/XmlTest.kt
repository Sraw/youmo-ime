/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.xml.Xml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// 060-render-v2 §6 step 1: the engine's own reader of the XML the formats use, its errors with lines
class XmlTest {

    @Test
    fun elementsAttributesAndLines() {
        val root = Xml.parse(
            """
            <?xml version="1.0"?>
            <!-- a comment -->
            <a x="1" y='two'>
              <b/>
              <c z="&lt;&amp;&gt;&quot;&apos;&#65;&#x1F600;"> </c>
            </a>
            """.trimIndent(),
        ).ok()
        assertEquals("a", root.name)
        assertEquals(3, root.line)
        assertEquals("1", root["x"])
        assertEquals("two", root["y"])
        assertEquals(listOf("b", "c"), root.children.map { it.name })
        val c = root.all("c").single()
        assertEquals("<&>\"'A😀", c["z"])
        assertEquals(5, c.line)
    }

    @Test
    fun strayTextAndNonAsciiNamesAreProblems() {
        // nothing the formats read is text: a stray word is a slip (L7)
        val p = assertIs<Parsed.Failed>(Xml.parse("<a>\n<b/>\n  oops\n</a>"))
        assertEquals(3, p.problems.single().line)
        assertTrue(p.problems.single().message.contains("oops"), "${p.problems}")
        assertIs<Parsed.Failed>(Xml.parse("<键/>"))
        assertIs<Parsed.Failed>(Xml.parse("<a 名=\"1\"/>"))
        assertIs<Parsed.Failed>(Xml.parse("<a>&#99999999;</a>"))
    }

    @Test
    fun namespacePrefixesAreDropped() {
        assertEquals("keyboard", Xml.parse("<l:keyboard xmlns:l=\"x\"/>").ok().name)
    }

    @Test
    fun mismatchedTagIsAProblemOnItsLine() {
        val p = assertIs<Parsed.Failed>(Xml.parse("<a>\n<b>\n</a>"))
        assertEquals(3, p.problems.single().line)
    }

    @Test
    fun duplicateAttributeDoctypeCdataAndTrailingTextAreProblems() {
        assertIs<Parsed.Failed>(Xml.parse("<a x=\"1\" x=\"2\"/>"))
        assertIs<Parsed.Failed>(Xml.parse("<!DOCTYPE a><a/>"))
        assertIs<Parsed.Failed>(Xml.parse("<a><![CDATA[x]]></a>"))
        assertIs<Parsed.Failed>(Xml.parse("<a/><b/>"))
        assertIs<Parsed.Failed>(Xml.parse("<a>"))
        assertIs<Parsed.Failed>(Xml.parse("<a x=1/>"))
        assertIs<Parsed.Failed>(Xml.parse("<a>&nbsp;</a>"))
        assertIs<Parsed.Failed>(Xml.parse(""))
    }

    @Test
    fun tooDeepIsAProblem() {
        val deep = "<a>".repeat(Xml.MAX_DEPTH + 1) + "</a>".repeat(Xml.MAX_DEPTH + 1)
        assertTrue(Xml.parse(deep).problems().isNotEmpty())
    }
}
