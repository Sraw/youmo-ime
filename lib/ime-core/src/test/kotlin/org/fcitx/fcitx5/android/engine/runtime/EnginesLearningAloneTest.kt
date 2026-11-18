/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.learn.HabitRanker
import org.fcitx.fcitx5.android.engine.learn.control.LearningMenu
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Engines.learning]'s menu on a 双拼 word read by its keys alone (061-sp-abbrev §3.3), apart from EnginesLearningTest for its size. */
class EnginesLearningAloneTest : EnginesFixture() {

    private val root = MemoryFiles()

    private fun engines() = Engines(::load, root, warmUps = inPlace)

    // 双拼: a word typed, read by its keys alone, says so (「你打过的词，按声母读出」; R27 F8568). 自然码 js is jiong
    // whole, and j and s each alone: 角色, typed in full as jtse
    @Test
    @Uat("SP-30")
    fun theMenuSaysAWordTypedIsReadByItsKeysAlone() {
        val engines = engines()
        val sp = InputMethodCatalog.SHUANGPIN
        fun shown() = engines.candidates(sp, 0, 10).map { it.text }
        engines.type(sp, "jtse")
        engines.onEvent(sp, EngineEvent.PICK, shown().indexOf("角色"))
        engines.onEvent(sp, EngineEvent.RESET, 0)
        engines.context(sp, "")
        engines.type(sp, "js")
        val at = shown().indexOf("角色")
        assertTrue("${shown()}", at >= 0)
        val menu = engines.learning(sp, LearningOp.Menu(at, "角色")) as LearningResult.Menu
        assertEquals(HabitRanker.Tier.ALONE, menu.why!!.tier)
        assertEquals(LearningMenu.Reason.ALONE, LearningMenu.reason(menu.why))
        engines.close()
    }
}
