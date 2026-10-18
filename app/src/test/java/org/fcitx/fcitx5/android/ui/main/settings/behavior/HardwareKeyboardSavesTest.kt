/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

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
 * The 实体键盘 page saves its page size alone, through pinyin: the shared options read with it
 * may have been changed on 通用输入 since, and writing them back would undo that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class HardwareKeyboardSavesTest {

    @Test
    fun theHardwareKeyboardPageSavesThePageSizeAlone(): Unit = runBlocking {
        val fcitx = FakeFcitxAPI()
        val cfg = RawConfig().apply {
            getOrCreate(EngineSettings.PAGE_SIZE).value = "7"
            getOrCreate(EngineSettings.EMOJI).value = "False"
            getOrCreate(EngineSettings.CHAIZI).value = "True"
        }
        HardwareKeyboardFragment.save(fcitx, cfg)
        val saved = fcitx.getImConfig(Engines.PINYIN)
        assertEquals("7", saved.findByName(EngineSettings.PAGE_SIZE)?.value)
        assertNull(saved.findByName(EngineSettings.EMOJI))
        assertNull(saved.findByName(EngineSettings.CHAIZI))
    }
}
