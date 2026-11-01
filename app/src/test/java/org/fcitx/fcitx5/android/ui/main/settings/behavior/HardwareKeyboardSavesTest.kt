/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.FakeHostApi
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
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
        val fcitx = FakeHostApi()
        val cfg = mutableMapOf<String, String>().apply {
            this[EngineSettings.PAGE_SIZE] = "7"
            this[EngineSettings.EMOJI] = "False"
            this[EngineSettings.CHAIZI] = "True"
        }
        HardwareKeyboardFragment.save(fcitx, cfg)
        val saved = fcitx.imOptions(InputMethodCatalog.PINYIN)!!
        assertEquals("7", saved[EngineSettings.PAGE_SIZE])
        assertNull(saved[EngineSettings.EMOJI])
        assertNull(saved[EngineSettings.CHAIZI])
    }
}
