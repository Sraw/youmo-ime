/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.FakeHostApi
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.host.ConfigTree
import org.fcitx.fcitx5.android.host.EngineOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shared page saves what it shows alone: pinyin's own options, read with it from pinyin's
 * config, may have been changed on pinyin's page since, and writing them back would undo that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class SharedInputSavesTest {

    @Test
    fun theSharedPageSavesTheSharedOptionsAlone(): Unit = runBlocking {
        val fcitx = FakeHostApi()
        val cfg = mutableMapOf<String, String>().apply {
            this[EngineSettings.EMOJI] = "False"
            this[EngineSettings.CHAIZI] = "True"
        }
        SharedInputFragment().saveConfig(fcitx, cfg)
        val saved = fcitx.imOptions(InputMethodCatalog.PINYIN)!!
        assertEquals("False", saved[EngineSettings.EMOJI])
        assertNull(saved[EngineSettings.CHAIZI])
    }

    // pinyin's page open behind the shared one read the shared options before they changed there:
    // its save, of what it shows alone, leaves them as the shared page set them (the host's partial load)
    @Test
    fun aPageOpenBehindTheSharedOneDoesNotWriteBackWhatItRead() {
        val options = EngineOptions()
        val behind = options.page(InputMethodCatalog.PINYIN)!!.leaves().toMutableMap()
        val shared = options.page(InputMethodCatalog.PINYIN)!!.leaves().toMutableMap()
        shared[EngineSettings.EMOJI] = "False"
        options.setPage(InputMethodCatalog.PINYIN, ConfigTree.of(InputMethodSettings.only(InputMethodSettings.SHARED, shared)))
        behind[EngineSettings.CHAIZI] = "False"
        options.setPage(InputMethodCatalog.PINYIN, ConfigTree.of(InputMethodSettings.only(InputMethodSettings.of(InputMethodCatalog.PINYIN)!!, behind)))
        val read = options.toSettings()
        assertFalse(read.emoji)
        assertFalse(read.chaizi)
    }
}
