/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.LibimeMigration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine is loaded again on a save only for an imported table, whose options it reads as it loads the table. */
class InputMethodConfigFragmentTest {

    private fun entry(uniqueName: String, addon: String = LibimeMigration.ENGINE_ADDON) =
        InputMethodEntry(uniqueName, uniqueName, "", "", "", "zh_CN", addon, true)

    @Test
    fun onlyATableTheUserImportedIsLoadedAgain() {
        assertTrue(isImportedTable(entry("cangjie5")))
        // libime's wbx imported, named apart from the engine's own
        assertTrue(isImportedTable(entry(LibimeMigration.importedTableName("wbx"))))
        (listOf(Engines.PINYIN, Engines.SHUANGPIN, Engines.T9) + Engines.TABLES.keys).forEach {
            assertFalse(it, isImportedTable(entry(it)))
        }
        assertFalse(isImportedTable(entry("keyboard-us", addon = "keyboard")))
    }
}
