/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EngineMigrationTest {

    // a config home is never folder.root: the migration deletes in data/ beside it, outside the folder then
    @get:Rule
    val folder = TemporaryFolder()

    private fun File.put(path: String, text: String) = resolve(path).apply { parentFile!!.mkdirs() }.writeText(text)

    @Test
    fun libimesSettingsBecomeTheEngines() {
        val home = folder.newFolder("config")
        home.put("conf/pinyin.conf", "PageSize=5\n")
        // where fcitx's table addon keeps them
        home.put("table/wbx.conf", "[Table]\nAutoSelect=False\n")
        EngineMigration.run(home, null)
        assertEquals("PageSize=5\n\n[Wubi]\nAutoSelect=False\n", home.resolve("conf/androidengine.conf").readText())
    }

    @Test
    fun aConfigMigratedBeforeTablesHadSettingsGetsThem() {
        val home = folder.newFolder("config")
        home.put("conf/androidengine.conf", "PageSize=5\n")
        home.put("table/zrm.conf", "[Table]\nOrderPolicy=No\n")
        EngineMigration.run(home, null)
        val once = home.resolve("conf/androidengine.conf").readText()
        assertEquals("PageSize=5\n\n[Ziranma]\nOrderPolicy=No\n", once)
        EngineMigration.run(home, null)
        assertEquals(once, home.resolve("conf/androidengine.conf").readText())
        assertFalse(home.resolve("conf/androidengine.conf.new").exists())
    }

    @Test
    fun whatTheMigrationRewritesIsKeptAsItWasOnce() {
        val config = folder.newFolder("config")
        config.put("conf/androidengine.conf", "PageSize=5\n")
        config.put("table/zrm.conf", "[Table]\nOrderPolicy=No\n")
        EngineMigration.run(config, null)
        assertEquals("PageSize=5\n", config.resolve(".libime/conf/androidengine.conf").readText())
        assertFalse(config.resolve(".libime/conf/androidengine.conf.new").exists())
        // rewritten again: what it was before the first time is what is kept
        config.put("table/wbx.conf", "[Table]\nAutoSelect=False\n")
        EngineMigration.run(config, null)
        assertEquals("PageSize=5\n", config.resolve(".libime/conf/androidengine.conf").readText())
    }

    @Test
    fun aFileThatCannotBeKeptIsNotRewrittenNorIsWhatFollowsIt() {
        val config = folder.newFolder("config")
        val profile = "[Groups/0]\nName=Default\nDefaultIM=pinyin\n\n[Groups/0/Items/0]\nName=pinyin\n"
        config.put("profile", profile)
        config.put("conf/androidengine.conf", "PageSize=5\n")
        config.put("table/zrm.conf", "[Table]\nOrderPolicy=No\n")
        // where the copies go: no directory can be made there, so no copy
        config.put(".libime", "")
        EngineMigration.run(config, null)
        assertArrayEquals(profile.toByteArray(), config.resolve("profile").readBytes())
        assertArrayEquals("PageSize=5\n".toByteArray(), config.resolve("conf/androidengine.conf").readBytes())
        assertFalse(config.resolve("profile.new").exists())
        assertFalse(config.resolve("conf/androidengine.conf.new").exists())
    }

    @Test
    fun aFileTheMigrationMakesHasNothingToKeep() {
        val home = folder.newFolder("config")
        home.put("conf/pinyin.conf", "PageSize=5\n")
        EngineMigration.run(home, null)
        assertTrue(home.resolve("conf/androidengine.conf").exists())
        assertFalse(home.resolve(".libime/conf/androidengine.conf").exists())
        // only read, never rewritten
        assertFalse(home.resolve(".libime/conf/pinyin.conf").exists())
    }

    @Test
    fun androidKeyboardsWordHintsOffTurnEnglishSuggestionsOffOnce() {
        val home = folder.newFolder("config")
        home.put("conf/androidkeyboard.conf", "EnableWordHint=False\n")
        EngineMigration.run(home, null)
        assertEquals("EnglishSuggest=False\n", home.resolve("conf/androidengine.conf").readText())
        // turned back on in the English page: left so
        home.put("conf/androidengine.conf", "EnglishSuggest=True\n")
        EngineMigration.run(home, null)
        assertEquals("EnglishSuggest=True\n", home.resolve("conf/androidengine.conf").readText())
    }

    @Test
    fun whatAnImportedTableLeftIsGoneAndTheAppsOwnTablesAreKept() {
        val config = File(folder.root, "config")
        val data = File(folder.root, "data")
        val engine = File(folder.root, "engine")
        data.put("inputmethod/my.conf", "[InputMethod]\nName=My\nAddon=androidengine\n")
        data.put("inputmethod/other.conf", "[InputMethod]\nName=Other\nAddon=keyboard\n")
        // what an older build's migration kept of my.conf before it rewrote it
        data.put(".libime/inputmethod/my.conf", "[InputMethod]\nName=My\nAddon=table\n")
        data.put("table/my.main.dict", "x")
        config.put("table/my.conf", "[Table]\n")
        config.put("table/wbx.conf", "[Table]\nAutoSelect=False\n")
        engine.put("tables/my.user", "x")
        engine.put("wubi.user", "x")
        EngineMigration.run(config, engine)
        assertEquals(listOf("other.conf"), data.resolve("inputmethod").list()!!.toList())
        assertFalse(data.resolve(".libime/inputmethod").exists())
        assertFalse(data.resolve("table").exists())
        assertEquals(listOf("wbx.conf"), config.resolve("table").list()!!.toList())
        assertFalse(engine.resolve("tables").exists())
        assertTrue(engine.resolve("wubi.user").exists())
    }

    @Test
    fun aDirectoryNamedAsAConfIsNotReadNorInTheWayOfTheRest() {
        val data = File(folder.root, "data")
        data.resolve("inputmethod/dir.conf").mkdirs()
        data.put("inputmethod/my.conf", "[InputMethod]\nName=My\nAddon=androidengine\n")
        EngineMigration.run(folder.newFolder("config"), null)
        assertTrue(data.resolve("inputmethod/dir.conf").isDirectory)
        assertFalse(data.resolve("inputmethod/my.conf").exists())
    }

    @Test
    fun aTableTypedWithBeforeKeepsOrderingByUseOnce() {
        val config = folder.newFolder("config")
        val engine = folder.newFolder("engine")
        // 五笔 typed with, its 调频 never set; 自然码 set off by the user; 仓颉 never typed
        engine.put("wubi.user", "x")
        engine.put("ziranma.user", "x")
        config.put("conf/androidengine.conf", "PageSize=5\n\n[Ziranma]\nOrderPolicy=No\n")
        // locked: nothing to read, looked at again at the next start
        EngineMigration.run(config, null)
        assertFalse(config.resolve("conf/androidengine.conf").readText().contains("Freq"))
        EngineMigration.run(config, engine)
        val once = "PageSize=5\n\n[Ziranma]\nOrderPolicy=No\n\n[Wubi]\nOrderPolicy=Freq\n"
        assertEquals(once, config.resolve("conf/androidengine.conf").readText())
        assertTrue(engine.resolve(EngineMigration.ORDER_KEPT).exists())
        // once: set off since, it stays off; a table typed with first since is a new install's
        config.put("conf/androidengine.conf", "[Wubi]\nOrderPolicy=No\n")
        engine.put("cangjie.user", "x")
        EngineMigration.run(config, engine)
        assertEquals("[Wubi]\nOrderPolicy=No\n", config.resolve("conf/androidengine.conf").readText())
    }

    @Test
    fun aLockedStartLeavesTheOrderingToTheFirstUnlockedOne() {
        val config = folder.newFolder("config")
        val engine = folder.newFolder("engine")
        engine.put("wubi.user", "x")
        config.put("conf/androidengine.conf", "[Ziranma]\nOrderPolicy=No\n")
        EngineMigration.run(config, null)
        assertEquals("[Ziranma]\nOrderPolicy=No\n", config.resolve("conf/androidengine.conf").readText())
        // 五笔's No written while still locked (not as fcitx saves a default, which is commented out): taken as unset all the same
        val saved = "[Ziranma]\nOrderPolicy=No\n\n[Wubi]\nOrderPolicy=No\n"
        config.put("conf/androidengine.conf", saved)
        // a second locked start keeps what the first saw
        EngineMigration.run(config, null)
        EngineMigration.run(config, engine)
        assertEquals("[Ziranma]\nOrderPolicy=No\n\n[Wubi]\nOrderPolicy=Freq\n", config.resolve("conf/androidengine.conf").readText())
        assertFalse(config.resolve(EngineMigration.ORDER_UNSET).exists())
        // decided: a locked start after it changes nothing the user sets since
        config.put("conf/androidengine.conf", "[Ziranma]\nOrderPolicy=No\n")
        EngineMigration.run(config, null)
        config.put("conf/androidengine.conf", saved)
        EngineMigration.run(config, engine)
        assertEquals(saved, config.resolve("conf/androidengine.conf").readText())
    }

    // as fcitx saved it on a device after one change (中英混输 off): defaults written commented out,
    // a group all at its defaults too (configuration.cpp:137-138, iniparser.cpp:127-142)
    @Test
    fun aDefaultFcitxSavedIsNotWrittenSoATableTypedWithStillKeepsOrderingByUse() {
        val config = folder.newFolder("config")
        val engine = folder.newFolder("engine")
        engine.put("wubi.user", "x")
        val saved = "# 中英混输\nLatinWords=False\n# 提示英文单词\n# EnglishSuggest=True\n\n" +
            "# [Wubi]\n# 自动选择候选词\n# AutoSelect=True\n# 排序规则\n# OrderPolicy=No\n\n"
        // locked, before and after the save: 五笔's order is unset in both
        EngineMigration.run(config, null)
        config.put("conf/androidengine.conf", saved)
        EngineMigration.run(config, null)
        EngineMigration.run(config, engine)
        val settings = config.resolve("conf/androidengine.conf").readText()
        assertTrue(settings, settings.contains("[Wubi]\nOrderPolicy=Freq\n"))
        assertTrue(settings.contains("LatinWords=False"))
        // and with no locked start at all
        val other = folder.newFolder("other")
        val otherEngine = folder.newFolder("other-engine")
        otherEngine.put("wubi.user", "x")
        other.put("conf/androidengine.conf", saved)
        EngineMigration.run(other, otherEngine)
        assertTrue(other.resolve("conf/androidengine.conf").readText().contains("[Wubi]\nOrderPolicy=Freq\n"))
    }

    // review C M1: no engine directory at a new install's first start, a table typed with before the second
    @Test
    fun aNewInstallStaysOffPastItsFirstTableUse() {
        val config = folder.newFolder("config")
        val engine = File(folder.root, "engine")
        EngineMigration.run(config, engine)
        assertTrue(engine.resolve(EngineMigration.ORDER_KEPT).exists())
        engine.put("wubi.user", "x")
        EngineMigration.run(config, engine)
        assertFalse(config.resolve("conf/androidengine.conf").exists())
    }

    // an import killed between its renames: the engine's files in engine.old, put back before deciding
    @Test
    fun anOlderBuildsTablesLeftAsideStillKeepOrderingByUse() {
        val config = folder.newFolder("config")
        val aside = folder.newFolder("engine.old")
        aside.put("wubi.user", "x")
        val engine = File(folder.root, "engine")
        EngineMigration.run(config, engine)
        assertEquals("[Wubi]\nOrderPolicy=Freq\n", config.resolve("conf/androidengine.conf").readText())
        assertTrue(engine.resolve("wubi.user").exists())
    }

    @Test
    fun aNewInstallStartedLockedOrdersNoTableByUse() {
        val config = folder.newFolder("config")
        val engine = folder.newFolder("engine")
        EngineMigration.run(config, null)
        config.put("conf/androidengine.conf", "[Wubi]\nOrderPolicy=No\n")
        EngineMigration.run(config, engine)
        assertEquals("[Wubi]\nOrderPolicy=No\n", config.resolve("conf/androidengine.conf").readText())
    }

    @Test
    fun aNewInstallOrdersNoTableByUse() {
        val config = folder.newFolder("config")
        val engine = folder.newFolder("engine")
        EngineMigration.run(config, engine)
        assertFalse(config.resolve("conf/androidengine.conf").exists())
        assertTrue(engine.resolve(EngineMigration.ORDER_KEPT).exists())
    }

    @Test
    fun thePunctuationDefaultIsSetWhereLibimesConfigCannotBeMigrated() {
        val config = folder.newFolder("config")
        config.put("profile", "[Groups/0]\nName=Default\nDefaultIM=pinyin\n\n[Groups/0/Items/0]\nName=pinyin\n")
        // no copy of the profile can be made, so the profile and what follows it are not migrated
        config.put(".libime", "")
        EngineMigration.run(config, null)
        assertEquals("HalfWidthPuncAfterLetterOrNumber=False\n", config.resolve("conf/punctuation.conf").readText())
    }

    @Test
    fun punctuationAfterALetterOrDigitIsChineseFromTheFirstStart() {
        val home = folder.newFolder("config")
        EngineMigration.run(home, null)
        assertEquals("HalfWidthPuncAfterLetterOrNumber=False\n", home.resolve("conf/punctuation.conf").readText())
        home.put("conf/punctuation.conf", "HalfWidthPuncAfterLetterOrNumber=True\nTypePairedPunctuationsTogether=True\n")
        EngineMigration.run(home, null)
        assertEquals("HalfWidthPuncAfterLetterOrNumber=False\nTypePairedPunctuationsTogether=True\n", home.resolve("conf/punctuation.conf").readText())
        assertFalse(home.resolve(".libime/conf/punctuation.conf").exists())
    }
}
