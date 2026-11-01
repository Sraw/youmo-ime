/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.core.HostInstance
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.TableSettings
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** The engine's options as the settings pages save them: each page sends only what it shows. */
class HostConfigTest {

    // as HostTest's: a broken engine interaction fails the run instead of hanging it
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private companion object {

        val harness = TestHost()
        val fcitx: HostInstance get() = harness.fcitx

        @BeforeClass
        @JvmStatic
        fun setup() = harness.start()

        @AfterClass
        @JvmStatic
        fun cleanup() = harness.stop()

        /** 五笔, the engine's own table (a key of [InputMethodCatalog.TABLES]). */
        const val WUBI = "engine-wubi"
    }

    /** A clean engine and event queue for each test: see [TestHost.beforeTest]. */
    @Before
    fun resetEngineAndDrainEvents() = harness.beforeTest()

    @After
    fun restoreEnabledIME() = harness.afterTest()

    /**
     * The addon keeps one page size for every input method: one saved through a table is pinyin's
     * too. A save of the page size alone, through pinyin or a table, leaves every option it does
     * not carry as it was: each settings page sends only what it shows.
     */
    @Test
    fun aTablesPageSharesThePageSizeWithPinyinAndKeepsItsOwnOptions(): Unit = runBlocking {
        suspend fun pinyin(key: String) = fcitx.imOptions(InputMethodCatalog.PINYIN)?.get(key).orEmpty()
        suspend fun wubi(key: String) = fcitx.imOptions(WUBI)?.get(key).orEmpty()
        val pageSize = pinyin(EngineSettings.PAGE_SIZE)
        val chaizi = pinyin(EngineSettings.CHAIZI)
        val autoSelect = wubi(TableSettings.AUTO_SELECT)
        // not the defaults, so a full load, which resets what it is not given, would show
        val chaiziSet = if (EngineSettings().chaizi) "False" else "True"
        val autoSelectSet = if (TableOptions.WUBI.autoSelect) "False" else "True"
        // a size unlike the stored one, so the save is seen whatever was there
        val first = if (pageSize == "4") "6" else "4"
        fun pageSizeAlone(v: String) = mapOf(EngineSettings.PAGE_SIZE to v)
        try {
            fcitx.setImOptions(InputMethodCatalog.PINYIN, mapOf(EngineSettings.CHAIZI to chaiziSet))
            fcitx.setImOptions(WUBI, mapOf(TableSettings.AUTO_SELECT to autoSelectSet))
            Assert.assertEquals(chaiziSet, pinyin(EngineSettings.CHAIZI))
            Assert.assertEquals(autoSelectSet, wubi(TableSettings.AUTO_SELECT))

            fcitx.setImOptions(WUBI, pageSizeAlone(first))
            Assert.assertEquals("pinyin's", first, pinyin(EngineSettings.PAGE_SIZE))
            Assert.assertEquals("the table's", first, wubi(EngineSettings.PAGE_SIZE))
            Assert.assertEquals("pinyin's own, through the table", chaiziSet, pinyin(EngineSettings.CHAIZI))
            Assert.assertEquals("the table's own, through the table", autoSelectSet, wubi(TableSettings.AUTO_SELECT))

            fcitx.setImOptions(InputMethodCatalog.PINYIN, pageSizeAlone("5"))
            Assert.assertEquals("pinyin's", "5", pinyin(EngineSettings.PAGE_SIZE))
            Assert.assertEquals("the table's", "5", wubi(EngineSettings.PAGE_SIZE))
            Assert.assertEquals("pinyin's own, through pinyin", chaiziSet, pinyin(EngineSettings.CHAIZI))
            Assert.assertEquals("the table's own, through pinyin", autoSelectSet, wubi(TableSettings.AUTO_SELECT))
        } finally {
            fcitx.setImOptions(WUBI, mapOf(TableSettings.AUTO_SELECT to autoSelect))
            fcitx.setImOptions(
                InputMethodCatalog.PINYIN,
                mapOf(EngineSettings.PAGE_SIZE to pageSize, EngineSettings.CHAIZI to chaizi)
            )
        }
    }
}
