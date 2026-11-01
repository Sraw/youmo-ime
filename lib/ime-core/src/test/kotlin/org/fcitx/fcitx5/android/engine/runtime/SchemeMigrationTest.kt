/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.host.EngineOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双拼 down to 小鹤, 微软 and 自然码, 小鹤 the default (the user's 2026-11-01): a config as stored, read as 0.5 has them.
 *
 * contract: the configs as 0.4.0 stores them (its schemes' names), which mutation cannot see.
 */
class SchemeMigrationTest {

    private fun read(text: String, upgrade: Boolean = false) = EngineOptions().apply { load(text, upgrade) }

    @Test
    fun theDefaultIsXiaoheForANewInstallAndAReset() {
        // nothing read (a new install, direct boot's defaults) and none stored on a 0.5 install
        for (text in listOf("", "# ShuangpinProfile=Xiaohe\nPageSize=5\n", "PageSize=5\n")) {
            val options = read(text)
            assertNull(text, options.schemeMigrated)
            assertEquals(text, EngineSettings.XIAOHE, options.toSettings().shuangpin)
        }
        // what resets it is the option's default: a page's value it cannot read (setPage), an upgrade's
        // pinned 自然码 too; written, the default is left out (fcitx's way)
        val pinned = read("", upgrade = true)
        val page = pinned.page(InputMethodCatalog.SHUANGPIN)!!.apply { this[EngineSettings.SHUANGPIN_PROFILE].value = "" }
        assertTrue(pinned.setPage(InputMethodCatalog.SHUANGPIN, page))
        assertEquals(EngineSettings.XIAOHE, pinned.toSettings().shuangpin)
        assertTrue(pinned.page(InputMethodCatalog.SHUANGPIN)!![EngineSettings.SHUANGPIN_PROFILE].implicit)
        assertTrue(EngineOptions().text().lines().contains("# ShuangpinProfile=Xiaohe"))
    }

    @Test
    fun anUpgradeWithNoneIsPinnedToZiranma() {
        for (text in listOf("", "# ShuangpinProfile=Ziranma\nPageSize=5\n", "PageSize=5\n")) {
            val options = read(text, upgrade = true)
            assertEquals(text, SchemeMigration.Outcome(EngineSettings.ZIRANMA), options.schemeMigrated)
            assertEquals(text, EngineSettings.ZIRANMA, options.toSettings().shuangpin)
            // written explicitly (not the default now), so a later read, settled, says 自然码 too
            assertTrue(text, options.text().lines().contains("ShuangpinProfile=Ziranma"))
            val again = read(options.text())
            assertNull(text, again.schemeMigrated)
            assertEquals(text, EngineSettings.ZIRANMA, again.toSettings().shuangpin)
            assertNull(text, read(options.text(), upgrade = true).schemeMigrated)
        }
    }

    @Test
    fun theKeptSchemesAreLeftAsTheyAre() {
        for (name in listOf(EngineSettings.ZIRANMA, EngineSettings.XIAOHE, "MS")) {
            for (upgrade in listOf(false, true)) {
                val options = read("ShuangpinProfile=$name\n", upgrade)
                assertNull(name, options.schemeMigrated)
                assertEquals(name, name, options.toSettings().shuangpin)
            }
        }
        assertEquals(listOf(EngineSettings.ZIRANMA, "MS", EngineSettings.XIAOHE), EngineSettings.SCHEMES.keys.toList())
    }

    @Test
    fun eachDroppedSchemeIsXiaoheAndSaidWhich() {
        val dropped = listOf("Ziguang", "ABC", "Zhongwenzhixing", "PinyinJiajia", "GB Standard")
        assertEquals(dropped.toSet(), SchemeMigration.RETIRED)
        for (name in dropped) {
            val quoted = if (' ' in name) "\"$name\"" else name
            for (upgrade in listOf(false, true)) {
                val options = read("ShuangpinProfile=$quoted\nPageSize=5\n", upgrade)
                assertEquals(name, SchemeMigration.Outcome(EngineSettings.XIAOHE, retired = name), options.schemeMigrated)
                assertEquals(name, EngineSettings.XIAOHE, options.toSettings().shuangpin)
                // the rest as it was
                assertEquals(name, 5, options.toSettings().pageSize)
                // written so (the default: left out), read again: nothing more to do
                val again = read(options.text())
                assertNull(name, again.schemeMigrated)
                assertEquals(name, EngineSettings.XIAOHE, again.toSettings().shuangpin)
            }
        }
    }

    @Test
    fun anUpgradeWithAnEmptyOrUnknownSchemeIsPinnedToZiranmaAsWithNone() {
        assertRows(listOf("" to "empty", "Foo" to "no scheme, current or retired"), { "${it.first}: ${it.second}" }) { (stored, _) ->
            // 0.4 read either as its default, 自然码
            val options = read("ShuangpinProfile=$stored\n", upgrade = true)
            assertEquals(SchemeMigration.Outcome(EngineSettings.ZIRANMA), options.schemeMigrated)
            assertEquals(EngineSettings.ZIRANMA, options.toSettings().shuangpin)
            // settled (no upgrade): a bad value, the option's default
            val settled = read("ShuangpinProfile=$stored\n")
            assertNull(settled.schemeMigrated)
            assertEquals(EngineSettings.XIAOHE, settled.toSettings().shuangpin)
        }
    }
}
