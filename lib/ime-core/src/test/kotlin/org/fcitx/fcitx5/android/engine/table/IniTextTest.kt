/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IniTextTest {

    private val profile = "Version=1\n# a comment\n\n[Groups/0]\nName=Default\n  #Name=old\n[ Groups/1 ]\nName=\"a \\\"b\\\"\"\n"

    @Test
    fun sectionsKeepTheirLinesAsWrittenToBeWrittenBack() {
        assertEquals(
            listOf(
                "" to listOf("Version=1", "# a comment"),
                "Groups/0" to listOf("Name=Default", "  #Name=old"),
                " Groups/1 " to listOf("Name=\"a \\\"b\\\"\""),
            ),
            IniText.sections(profile),
        )
    }

    @Test
    fun aCommentHasNoKeyAndAValueReadsUnescaped() {
        assertEquals("Name", IniText.key(" Name = x"))
        assertNull(IniText.key("  #Name=old"))
        assertNull(IniText.key("=x"))
        assertNull(IniText.key("Name"))
        assertEquals("a \"b\"", IniText.value("Name=\"a \\\"b\\\"\""))
    }
}
