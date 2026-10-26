/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * uat/13-words.md and 15-privacy-backup.md: what the user's words, phrases and habits do to typing.
 * The settings pages are where the user does it; here it is done as the pages do it (FcitxAPI's
 * calls, the custom phrases file) and the page's list is what the engine lists.
 */
class WordsUatTest {

    private fun EngineReplay.added() = engines.userWords(UserWord.Kind.ADDED).map { it.text }

    @Test
    @Uat("WD-03")
    fun aWordAddedByHandTypedAtOnceInSentencesEditedDeleted() = with(EngineReplay()) {
        assertEquals(emptyList<String>(), added())
        assertTrue(engines.addWord("澜境花园", "lan jing hua yuan"))
        assertEquals(listOf("澜境花园"), added())
        assertTrue(engines.addWord("鲸喵屿", "jing miao yu"))
        type("lanjinghuayuan")
        expectFirst("澜境花园")
        focus("")
        type("jingmiaoyu")
        expectFirst("鲸喵屿")
        focus("")
        type("wozhuzaijingmiaoyu")
        expectFirst("我住在鲸喵屿")
        focus("")
        // edited as the page edits: the new one added, the old removed
        val old = engines.userWords(UserWord.Kind.ADDED).single { it.text == "澜境花园" }
        assertTrue(engines.addWord("澜境公园", "lan jing gong yuan"))
        engines.removeWords(listOf(old))
        assertTrue("list ${added()}", "澜境公园" in added() && "澜境花园" !in added())
        type("lanjinggongyuan")
        expectFirst("澜境公园")
        focus("")
        engines.removeWords(engines.userWords(UserWord.Kind.ADDED).filter { it.text == "澜境公园" })
        assertFalse("list ${added()}", "澜境公园" in added())
        type("lanjinggongyuan")
        expectNotAmong("澜境公园")
    }

    @Test
    @Uat("WD-18")
    fun aCustomPhraseAddedNotMovedByLearningEditedDeleted() = with(EngineReplay()) {
        UatEngines.phrases = "dz,1=北京市海淀区\n"
        engines.reload()
        type("dz")
        expectFirst("北京市海淀区")
        val w = candidates[1]
        repeat(2) {
            pick(w)
            // W took the whole code: the 。 below is the app's, which on a device goes through the engine
            expectPreedit("")
            commit("。")
            type("dz")
        }
        expectStart("北京市海淀区", w)
        focus("")
        UatEngines.phrases = "dz,1=上海市\n"
        engines.reload()
        type("dz")
        expectFirst("上海市")
        focus("")
        UatEngines.phrases = ""
        engines.reload()
        type("dz")
        expectNotAmong("上海市", "北京市海淀区")
    }

    @Test
    @Uat("PR-17")
    fun notUsingMyHabitsAsFreshlyInstalledButForWhatIsBlocked() = with(EngineReplay()) {
        learnWangXiaoming()
        assertEquals("汪小明", wangXiaoming().first())
        focus("")
        type("shi")
        val before = candidates
        // 固定首位 on the fifth (C), then 是 blocked, as their long presses' menus do (FcitxAPI.learning)
        val c = before[4]
        // with habits off the first is the first of before that is not 是, wherever 是 was; 是 being C would make it unmeetable
        assertTrue("是 is the fifth of shi $before: the case blocks it and pins another", before.indexOf("是") != 4)
        assertTrue(engines.learning(im, LearningOp.Pin(4, c)) is LearningResult.Done)
        focus("")
        type("shi")
        expectFirst(c)
        val shi = candidates.indexOf("是")
        assertTrue(engines.learning(im, LearningOp.Block(shi, "是")) is LearningResult.Done)
        focus("")
        UatEngines.phrases = "dz,1=北京市海淀区\n"
        engines.reload()
        // the phrase loaded by this reload, so that its absence below is the habits' doing
        type("dz")
        expectFirst("北京市海淀区")
        focus("")

        assertTrue(engines.learning(im, LearningOp.UseHabits(false)) is LearningResult.Done)
        val name = wangXiaoming()
        assertEquals("王小明", name.first())
        assertFalse("汪小明 in $name", "汪小明" in name)
        focus("")
        type("shi")
        val after = candidates
        assertEquals("shi $after (before: $before)", before.first { it != "是" }, after.first())
        assertNotEquals(c, after.first())
        expectNotAmong("是")
        focus("")
        type("dz")
        expectNotAmong("北京市海淀区")
        focus("")

        assertTrue(engines.learning(im, LearningOp.UseHabits(true)) is LearningResult.Done)
        assertEquals("汪小明", wangXiaoming().first())
        // the rest of the habits back too, not only what was learned: the pin, the block, the phrase
        focus("")
        type("shi")
        expectFirst(c)
        expectNotAmong("是")
        focus("")
        type("dz")
        expectFirst("北京市海淀区")
    }
}
