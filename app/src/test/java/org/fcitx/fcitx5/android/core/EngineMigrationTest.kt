/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EngineMigrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun File.put(path: String, text: String) = resolve(path).apply { parentFile!!.mkdirs() }.writeText(text)

    @Test
    fun libimesSettingsBecomeTheEngines() {
        val home = folder.root
        home.put("conf/pinyin.conf", "PageSize=5\n")
        // where fcitx's table addon keeps them
        home.put("table/wbx.conf", "[Table]\nAutoSelect=False\n")
        EngineMigration.run(home)
        assertEquals("PageSize=5\n\n[Wubi]\nAutoSelect=False\n", home.resolve("conf/androidengine.conf").readText())
    }

    @Test
    fun aConfigMigratedBeforeTablesHadSettingsGetsThem() {
        val home = folder.root
        home.put("conf/androidengine.conf", "PageSize=5\n")
        home.put("table/zrm.conf", "[Table]\nOrderPolicy=No\n")
        EngineMigration.run(home)
        val once = home.resolve("conf/androidengine.conf").readText()
        assertEquals("PageSize=5\n\n[Ziranma]\nOrderPolicy=No\n", once)
        EngineMigration.run(home)
        assertEquals(once, home.resolve("conf/androidengine.conf").readText())
        assertFalse(home.resolve("conf/androidengine.conf.new").exists())
    }
}
