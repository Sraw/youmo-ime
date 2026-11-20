/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.junit.Assert.assertEquals
import org.junit.Test

/** Full pinyin's chain as [Engines] makes it: a key the app takes reaches pinyin, which drops what came before. */
class EnginesStrokeLookupChainTest : EnginesFixture() {

    // no prediction shown after a commit: Keyboard.passOn sends no Reset, passedOn alone has to drop the context
    private fun engines() = ready(PINYIN, settings = EngineSettings(prediction = false))

    private fun Engines.commitHao() = assertEquals("好", type(PINYIN, "hao").let { onEvent(PINYIN, EngineEvent.CHAR, ' '.code).commit })

    @Uat("PY-120")
    @Test
    fun withoutAKeyPassedOnWhatWasCommittedIsTheContext() {
        val engines = engines()
        engines.commitHao()
        assertEquals(listOf("拟", "你"), engines.type(PINYIN, "ni").candidates)
    }

    @Uat("PY-120")
    @Test
    fun enterPassedOnToTheAppDropsWhatWasCommittedBefore() {
        val engines = engines()
        engines.commitHao()
        engines.onEvent(PINYIN, EngineEvent.ENTER, 0)
        assertEquals(listOf("你", "拟"), engines.type(PINYIN, "ni").candidates)
    }
}
