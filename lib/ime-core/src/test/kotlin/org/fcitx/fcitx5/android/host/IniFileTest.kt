/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** fcitx's INI rules, each pinned to `fcitx/../fcitx-config/iniparser.cpp`. */
class IniFileTest {

    @Test
    fun `a commented line, a default, reads as not there`() {
        // iniparser.cpp:67-69: trimmed, then # skips the whole line
        val tree = IniFile.read("# PageSize=7\n   # [Fuzzy]\n#L_N=True\n")
        assertTrue(tree.leaves().isEmpty())
    }

    @Test
    fun `nothing is trimmed around the equals sign`() {
        // iniparser.cpp:83-89: the name is lineBuf.substr(0, equalPos), the value what follows it
        val tree = IniFile.read("PageSize = 5\n  Emoji=False  \n")
        assertEquals(mapOf("PageSize " to " 5", "Emoji" to "False"), tree.leaves())
    }

    @Test
    fun `a group header names the group and makes it, even with nothing under it`() {
        // iniparser.cpp:71-79: visitItemsOnPath makes each node on the path
        val tree = IniFile.read("[Cangjie]\n[a/b]\nc=1\n")
        assertNotNull(tree.find("Cangjie"))
        assertEquals("1", tree.find("a/b/c")?.value)
    }

    @Test
    fun `a value quoted at both ends is unescaped, one whose quote closes early is skipped`() {
        // iniparser.cpp:90-95 and stringutils.cpp:390-401
        val tree = IniFile.read("A=\"x y\\n\"\nB=\"x\"y\"\nC=\"open\nD=a\"b\n")
        assertEquals("x y\n", tree.find("A")?.value)
        assertNull(tree.find("B"))
        assertEquals("\"open", tree.find("C")?.value)
        assertEquals("a\"b", tree.find("D")?.value)
    }

    @Test
    fun `a key may hold a path`() {
        assertEquals("False", IniFile.read("Cangjie/AutoSelect=False\n").find("Cangjie/AutoSelect")?.value)
    }

    @Test
    fun `carriage returns go with the trim`() {
        assertEquals(mapOf("A" to "1", "G/B" to "2"), IniFile.read("A=1\r\n[G]\r\nB=2\r\n").leaves())
    }

    @Test
    fun `writes leaves then groups, implicit ones commented, no descriptions`() {
        // iniparser.cpp:106-148
        val tree = ConfigTree()
        tree["A"].apply { value = "x y"; implicit = false }
        tree["B"].apply { value = "1"; implicit = true }
        tree["G"].apply { implicit = true }
        tree["G/C"].apply { value = "True"; implicit = true }
        tree["H/D"].value = "\"q\""
        assertEquals(
            "A=\"x y\"\n# B=1\n\n# [G]\n# C=True\n\n[H]\nD=\"\\\"q\\\"\"\n\n",
            IniFile.write(tree),
        )
    }

    @Test
    fun `what is written reads back as the same values`() {
        val tree = ConfigTree()
        tree["Tab"].value = "a\tb\\c"
        tree["G/Sub/K"].value = "v"
        assertEquals(tree.leaves(), IniFile.read(IniFile.write(tree)).leaves())
    }
}
