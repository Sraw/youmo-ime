/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.FakeFcitxAPI
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.host.Engines
import org.junit.Assert.assertEquals
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
        val fcitx = FakeFcitxAPI()
        val cfg = RawConfig().apply {
            getOrCreate(EngineSettings.EMOJI).value = "False"
            getOrCreate(EngineSettings.CHAIZI).value = "True"
        }
        SharedInputFragment().saveConfig(fcitx, cfg)
        val saved = fcitx.getImConfig(Engines.PINYIN)
        assertEquals("False", saved.findByName(EngineSettings.EMOJI)?.value)
        assertNull(saved.findByName(EngineSettings.CHAIZI))
    }
}
