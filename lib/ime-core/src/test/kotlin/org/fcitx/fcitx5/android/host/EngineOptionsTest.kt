/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.ModelLevel
import org.fcitx.fcitx5.android.engine.runtime.TableSettings
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings the engine reads of a config file's [text], as the host reads it at start. */
private fun settingsOf(text: String) = EngineOptions().apply { load(text) }.toSettings()

/** What the golden files don't say outright: the settings the engine reads, the default rule, pages. */
class EngineOptionsTest {

    @Test
    fun `the settings are what the engine reads`() {
        val options = EngineOptions().apply {
            load("PageSize=5\nModelLevel=Low\n[Fuzzy]\nL_N=True\nNG_GN=False\n[Wubi]\nOrderPolicy=Freq\nHint=False\n")
        }
        val settings = options.toSettings()
        assertEquals(5, settings.pageSize)
        assertEquals(ModelLevel.LOW, settings.modelLevel)
        assertEquals(setOf(Fuzzy.L_N), settings.fuzzy)
        assertFalse(settings.typos)
        assertEquals(TableSettings(autoSelect = true, hint = false, orderByUse = true, keyLabels = KeyLabels.NONE), settings.tables["Wubi"])
        // every table's settings are pushed, defaults too (native-last:app/src/main/cpp/androidengine/androidengine.cpp:473-491)
        assertEquals(InputMethodCatalog.TABLES.values.map { it.group }.toSet(), settings.tables.keys)
        assertEquals(TableSettings(true, false, false, KeyLabels.NAMES), settings.tables["Cangjie"])
        // a table with no roots has nothing of them to set
        assertNull(settings.tables["Erbi"]?.keyLabels)
        assertEquals(true, settingsOf("[Wubi]\nOrderPolicy=Fast\n").tables["Wubi"]?.orderByUse)
        assertEquals(false, settingsOf("[Wubi]\nOrderPolicy=Sometimes\n").tables["Wubi"]?.orderByUse)
    }

    @Test
    fun `settings missing or unreadable are the defaults`() {
        val defaults = settingsOf("")
        // 双拼's the app's default, 小鹤, not the engine's own (SchemeMigration)
        assertEquals(EngineSettings().copy(tables = defaults.tables, shuangpin = EngineSettings.XIAOHE), defaults)
        assertEquals(defaults, settingsOf("ShuangpinProfile=Custom\nPageSize=99\nPrediction=1\n=x\n[Fuzzy]\nL_N=yes\n"))
        assertEquals(ShuangpinScheme.MICROSOFT, settingsOf("ShuangpinProfile=MS\n").scheme)
        for (level in ModelLevel.entries) assertEquals(defaults.copy(modelLevel = level), settingsOf("ModelLevel=${level.key}\n"))
        assertEquals(ModelLevel.HIGH, settingsOf("ModelLevel=Max\n").modelLevel)
        // the switch before the levels is not carried over: no one used it but us
        assertEquals(ModelLevel.HIGH, settingsOf("SentenceModel=False\n").modelLevel)
        // read for the keyboard: 九键英文 spells a letter at a time where English suggests nothing
        assertFalse(settingsOf("EnglishSuggest=False\n").englishSuggest)
        assertTrue(settingsOf("EnglishSuggest=maybe\n").englishSuggest)
        assertFalse(settingsOf("Chaizi=False\n").chaizi)
        assertFalse(settingsOf("BuiltInPhrases=False\n").builtInPhrases)
        assertFalse(settingsOf("Emoji=False\n").emoji)
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
    fun `what a table's keys show defaults to its preset's, in an older config's group too`() {
        // v0.4's config: a 仓颉 group of its own (its AutoSelect changed), no KeyLabels in it
        for (text in listOf("", "[Cangjie]\nAutoSelect=False\n", "[Wubi]\nHint=False\n")) {
            val settings = settingsOf(text)
            assertEquals(text, KeyLabels.NAMES, settings.tables["Cangjie"]?.keyLabels)
            assertEquals(text, KeyLabels.NONE, settings.tables["Wubi"]?.keyLabels)
        }
        assertEquals(KeyLabels.ROOTS, settingsOf("[Wubi]\nKeyLabels=Roots\n").tables["Wubi"]?.keyLabels)
        assertEquals(KeyLabels.NONE, settingsOf("[Cangjie]\nKeyLabels=None\n").tables["Cangjie"]?.keyLabels)
        // a table's page sets it in its group
        val options = EngineOptions()
        assertTrue(options.setPage("engine-wubi", IniFile.read("KeyLabels=Names\n")))
        assertEquals("Names", options["Wubi/KeyLabels"])
        assertEquals("None", options["WubiPinyin/KeyLabels"])
        assertNull(options["Erbi/KeyLabels"])
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

    @Test
    fun candidateReadingIsOffTillSetAndAnUnknownStyleIsOff() {
        assertEquals(ReadingStyle.OFF, EngineOptions().toSettings().candidateReading)
        assertEquals(ReadingStyle.ZHUYIN, EngineOptions().apply { load("CandidateReading=Zhuyin\n") }.toSettings().candidateReading)
        assertEquals("Off", EngineOptions().apply { load("CandidateReading=Ruby\n") }["CandidateReading"])
    }

}
