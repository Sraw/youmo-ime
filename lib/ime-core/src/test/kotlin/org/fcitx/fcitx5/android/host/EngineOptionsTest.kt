/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.ModelLevel
import org.fcitx.fcitx5.android.engine.host.TableSettings
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the golden files don't say outright: the flat settings, the default rule, pages. */
class EngineOptionsTest {

    @Test
    fun `the flat settings are what the engine reads`() {
        val options = EngineOptions().apply {
            load("PageSize=5\nModelLevel=Low\n[Fuzzy]\nL_N=True\nNG_GN=False\n[Wubi]\nOrderPolicy=Freq\nHint=False\n")
        }
        val settings = EngineSettings.parse(options.flat())
        assertEquals(5, settings.pageSize)
        assertEquals(ModelLevel.LOW, settings.modelLevel)
        assertEquals(setOf(Fuzzy.L_N), settings.fuzzy)
        assertFalse(settings.typos)
        assertEquals(TableSettings(autoSelect = true, hint = false, orderByUse = true), settings.tables["Wubi"])
        // every table's settings are pushed, defaults too (native-last:app/src/main/cpp/androidengine/androidengine.cpp:473-491)
        assertEquals(Engines.TABLES.values.map { it.group }.toSet(), settings.tables.keys)
        assertEquals(TableSettings(true, false, false), settings.tables["Cangjie"])
    }

    @Test
    fun `a default not written follows a change of default`() {
        // design §2.1: a default is written commented, so the file says nothing of it
        val text = EngineOptions().apply { load("ModelLevel=Medium\n") }.text()
        assertTrue("# PageSize=7" in text.lines())
        assertEquals(listOf("ModelLevel=Medium"), text.lines().filter { it.isNotEmpty() && !it.startsWith("#") })
        assertTrue(IniFile.read(text).find("PageSize") == null)
    }

    @Test
    fun `a bad value falls back to the default, not to the nearest`() {
        // configuration.cpp:117-123: unmarshall fails → reset()
        val options = EngineOptions().apply { load("PageSize=11\n") }
        assertEquals("7", options["PageSize"])
    }

    @Test
    fun `a table's group missing takes the table's defaults, one there a fresh group's`() {
        // option.h:435-438: a full load starts a sub-configuration fresh; reset() is engineTable(hint)
        assertEquals("False", EngineOptions().apply { load("") }["Cangjie/Hint"])
        assertEquals("True", EngineOptions().apply { load("[Cangjie]\nAutoSelect=False\n") }["Cangjie/Hint"])
    }

    @Test
    fun `a page saves only what it has, a table's own keys into its group`() {
        val options = EngineOptions()
        val page = IniFile.read("Hint=False\nPageSize=4\n")
        assertTrue(options.setPage("engine-wubi", page))
        assertEquals("False", options["Wubi/Hint"])
        assertEquals("4", options["PageSize"])
        assertEquals("True", options["Prediction"])
        assertFalse(options.setPage("not-an-im", page))
        assertNull(options.page("not-an-im"))
    }

    @Test
    fun `an unknown key is gone at the next save`() {
        val text = EngineOptions().apply { load("Unknown=1\n[Wubi]\nAutoPhraseLength=4\n") }.text()
        assertFalse("Unknown" in text)
        assertFalse("AutoPhraseLength" in text)
    }
}
