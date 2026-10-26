/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.host.IniFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `a = b` in the files `IniText`'s readers take. `IniText` trims around `=`; fcitx does not
 * (`iniparser.cpp:83-89`), so to fcitx that line is the key `a ` and is ignored. Every file they
 * read is one fcitx wrote (no spaces) or the addon's own flat settings, where both read alike; the
 * tests pin each reader's behaviour on the one line where they differ.
 */
class IniSpacesTest {

    @Test
    fun `LibimeMigration reads a spaced key, which fcitx-written files never have`() {
        // libime's pinyin.conf, written by fcitx: what the migration carries over
        val spaced = LibimeMigration.settings("PageSize = 5\n")
        val written = LibimeMigration.settings("PageSize=5\n")
        assertEquals("PageSize = 5\n", spaced)
        assertEquals("PageSize=5\n", written)
        // what the androidengine addon (fcitx) then reads of each
        assertNull(IniFile.read(spaced).find("PageSize"))
        assertEquals("5", IniFile.read(written).find("PageSize")?.value)
    }

    @Test
    fun `AddonDefaults takes a spaced key for the one it sets, and writes it unspaced`() {
        // punctuation.conf, written by fcitx
        assertEquals("HalfWidthPuncAfterLetterOrNumber=False\n", AddonDefaults.punctuation("HalfWidthPuncAfterLetterOrNumber = True\n"))
        assertNull(AddonDefaults.punctuation("HalfWidthPuncAfterLetterOrNumber=False\n"))
    }

    @Test
    fun `EngineSettings reads the flat settings, which never have spaces`() {
        // the addon's pushSettings (and EngineOptions.flat) write key=value with nothing around it
        assertEquals(5, EngineSettings.parse("PageSize = 5\n").pageSize)
        assertEquals(5, EngineSettings.parse("PageSize=5\n").pageSize)
    }
}
