/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Profile]: `fcitx/inputmethodmanager.cpp:82-162,427-454`, `fcitx-config/iniparser.cpp:58-148`. */
class ProfileTest {
    private val available = setOf("engine-pinyin", "engine-wubi", "keyboard-us")

    // as fcitx 5 writes it, descriptions and all
    private val fcitxWritten = """
        [Groups/0]
        # Group Name
        Name=默认
        # Layout
        Default Layout=us
        # Default Input Method
        DefaultIM=keyboard-us

        [Groups/0/Items/0]
        # Name
        Name=engine-pinyin
        # Layout
        # Layout=

        [Groups/0/Items/1]
        # Name
        Name=gone-im
        # Layout
        # Layout=

        [Groups/0/Items/2]
        # Name
        Name=keyboard-us
        # Layout
        # Layout=

        [GroupOrder]
        0=默认

    """.trimIndent()

    @Test
    fun `fcitx's file reads as fcitx reads it, unknown items dropped`() {
        val p = Profile.load(fcitxWritten, available, "Default")
        val g = p.currentGroup()
        assertEquals("默认", g.name)
        assertEquals(listOf("engine-pinyin", "keyboard-us"), g.inputMethods)
        assertEquals("keyboard-us", g.defaultInputMethod)
    }

    @Test
    fun `what it writes reads back the same`() {
        val p = Profile.load(fcitxWritten, available, "Default").with(ImGroup.of("默认", listOf("engine-wubi", "engine-pinyin", "keyboard-us"), "engine-pinyin"))
        val again = Profile.load(p.text(), available, "Default")
        assertEquals(p.text(), again.text())
        assertEquals(listOf("engine-wubi", "engine-pinyin", "keyboard-us"), again.currentGroup().inputMethods)
        assertEquals("engine-pinyin", again.currentGroup().defaultInputMethod)
    }

    @Test
    fun `no file, or no valid group, is the default group`() {
        // :142-162; a group without a name or a layout is skipped (:96-100), one left empty removed (:121-126)
        assertEquals(listOf("keyboard-us"), Profile.load(null, available, "Default").currentGroup().inputMethods)
        val bad = "[Groups/0]\nName=x\n[Groups/0/Items/0]\nName=engine-pinyin\n[Groups/1]\nDefault Layout=us\nName=y\n[Groups/1/Items/0]\nName=nope\n"
        assertEquals("D", Profile.load(bad, available, "D").currentGroup().name)
    }

    @Test
    fun `the order picks the current group, unknown names dropped, the rest after`() {
        // setGroupOrder (:365-381)
        val two = "[Groups/0]\nName=a\nDefault Layout=us\n[Groups/0/Items/0]\nName=keyboard-us\n" +
            "[Groups/1]\nName=b\nDefault Layout=us\n[Groups/1/Items/0]\nName=engine-pinyin\n[GroupOrder]\n0=zz\n1=b\n"
        val p = Profile.load(two, available, "D")
        assertEquals(listOf("b", "a"), p.order)
        assertEquals("b", p.currentGroup().name)
        // a change to the current group keeps the other
        assertEquals(listOf("keyboard-us"), Profile.load(p.with(ImGroup.of("b", listOf("engine-wubi"))).text(), available, "D").groups.first { it.name == "a" }.items.map { it.name })
    }

    @Test
    fun `values are read by fcitx's rules`() {
        // iniparser.cpp:63-69 (no trim around =), stringutils.cpp:390-425
        val values = Profile.read("  [S]  \nKey = v\n#Skip=1\nQ=\"a b\\\"c\"\nBad=\"a\"b\"\n")
        assertEquals(" v", values["S/Key "])
        assertEquals("a b\"c", values["S/Q"])
        assertNull(values["S/Bad"])
        assertNull(values["S/#Skip"])
        // written through IniFile: a value with a space quoted, read back the same
        val written = Profile.default("a b\\c").text()
        assertTrue(written.contains("Name=\"a b\\\\c\""))
        assertEquals("a b\\c", Profile.load(written, setOf(ImGroup.KEYBOARD_US), "x").current.name)
    }

    @Test
    fun `defaults are written as comments`() {
        // configuration.cpp:126-139 implicit, iniparser.cpp:124-127
        val text = Profile.default("Default").text()
        assertTrue(text.contains("# DefaultIM=\n"))
        assertTrue(text.contains("# Layout=\n"))
        assertTrue(text.contains("Name=keyboard-us\n"))
    }
}
