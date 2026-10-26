/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.fcitx.fcitx5.android.engine.learn.backup.BackupHead
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.BackupResult
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.user.DictionaryCompiler
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * uat/15-privacy-backup.md, the engine's half: a field that asks not to learn, 不用我的输入习惯
 * with an imported base dictionary, 清掉最近, and the learning backups (restore merged, 退回恢复前,
 * this install's automatic ones, blocks first). The pages' buttons are FcitxAPI's `learning(op)`;
 * here they are those ops.
 */
class PrivacyUatTest {

    private fun EngineReplay.backups(op: BackupOp) = engines.learning(null, op) as BackupResult

    @Test
    @Uat("PR-07")
    fun aFieldThatAsksNotToLearnLearnsNothing() = with(EngineReplay(field = EngineReplay.Field.NO_LEARNING)) {
        learnWangXiaoming()
        assertEquals("王小明", wangXiaoming().first())
        assertFalse("汪小明" in engines.userWords().map { it.text })
    }

    @Test
    @Uat("PR-17a")
    fun notUsingHabitsTheBaseDictionaryStillTypes() = with(EngineReplay()) {
        // dict-words.txt merged into the base, as the app compiles its merged file
        val merged = Files.createTempFile("uat-merged", ".ymd").toFile().apply { deleteOnExit() }
        val lines = UatEngines.fixture("dict-words.txt").readLines().map { line ->
            val parts = line.split(" ")
            "${parts[0]} ${parts.drop(1).joinToString("'")} 0.0"
        }
        DictionaryCompiler.compile(listOf(lines.asSequence()), merged, names = listOf("dict-words"))
        UatEngines.dictionary = { DictionaryFile.open(merged) }
        UatEngines.indexes = UatEngines.tempDir("uat-indexes")
        engines.reload()
        assertTrue(engines.addWord("鲸园", "jing yuan"))
        engines.learning(im, LearningOp.UseHabits(false))
        assertTrue("量子文化村" in typed("liangziwenhuacun"))
        assertFalse("鲸园" in typed("jingyuan"))
        focus("")
        ime(Engines.WUBI)
        UatEngines.settle()
        type("jbys")
        expectAmong("量子计算机", "量子文化村")
        assertTrue(engines.learning(im, LearningOp.UseHabits(true)) is LearningResult.Done)
    }

    @Test
    @Uat("PR-21")
    fun clearingTheLastHourForgetsItsPicksNotThePins() = with(EngineReplay()) {
        repeat(2) { taDl("点了") }
        focus("")
        type("shi")
        val c = candidates[4]
        engines.learning(im, LearningOp.Pin(4, c))
        assertTrue(engines.learning(null, LearningOp.ClearRecent(LearningOp.Span.HOUR)) is LearningResult.Cleared)
        val after = taDl(null)
        assertEquals("带来", after.first())
        assertTrue("$after", after.getOrNull(1) != "点了")
        assertEquals(c, typed("shi").first())
    }

    @Test
    @Uat("PR-25")
    fun aRestoreMergesAndIsTakenBack() = with(EngineReplay()) {
        learnWangXiaoming()
        focus("")
        type("shi")
        val shi = candidates
        val c = shi[4]
        engines.learning(im, LearningOp.Pin(4, c))
        val saved = backups(BackupOp.Copy).bytes!!
        // C's pin off, D pinned, 鲸喵屿 added: the phone's since
        val pins = engines.learning(null, LearningOp.ListItems(LearningOp.Listed.PINS)) as LearningResult.Listed
        engines.learning(null, LearningOp.RemoveItems(pins.items))
        focus("")
        type("shi")
        val d = candidates[5].takeIf { it != c } ?: candidates[6]
        engines.learning(im, LearningOp.Pin(candidates.indexOf(d), d))
        assertTrue(engines.addWord("鲸喵屿", "jing miao yu"))
        repeat(2) {
            assertTrue(backups(BackupOp.Restore(saved)).ok)
            // the pins at odds: the phone's
            assertEquals(d, typed("shi").first())
            assertTrue("鲸喵屿" in typed("jingmiaoyu"))
            assertEquals("汪小明", wangXiaoming().first())
        }
        assertTrue(backups(BackupOp.Kept).canRevert)
        assertTrue(backups(BackupOp.Revert).ok)
        assertEquals(d, typed("shi").first())
        assertTrue("鲸喵屿" in typed("jingmiaoyu"))
    }

    @Test
    @Uat("PR-26")
    fun clearedThenRestoredFromThisInstallsBackupBringsNothingBack() = with(EngineReplay()) {
        repeat(2) { taDl("点了") }
        UatEngines.settle()
        val before = backups(BackupOp.Kept).backups.filter { it.own && it.head.kind == BackupHead.AUTO }
        assertEquals("$before", 1, before.size)
        engines.learning(null, LearningOp.ClearLearned(all = false))
        UatEngines.settle()
        val after = backups(BackupOp.Kept).backups.filter { it.own && it.head.kind == BackupHead.AUTO }
        assertEquals("$after", 1, after.size)
        assertTrue(after.single().name != before.single().name)
        assertTrue(backups(BackupOp.RestoreKept(after.single().name)).ok)
        assertEquals("带来", taDl(null).first())
    }

    @Test
    @Uat("PR-27")
    fun anAutomaticBackupOnTheFirstTypingDayDeleted() = with(EngineReplay()) {
        type("nihao")
        space()
        UatEngines.settle()
        val kept = backups(BackupOp.Kept).backups
        val auto = kept.single()
        assertTrue("$auto", auto.own && auto.head.kind == BackupHead.AUTO)
        assertTrue(backups(BackupOp.Delete(auto.name)).ok)
        assertTrue(backups(BackupOp.Kept).backups.isEmpty())
    }

    @Test
    @Uat("PR-32")
    fun aBlockInTheBackupWinsOverThePhonesPin() {
        val saved = with(EngineReplay()) {
            type("shi")
            engines.learning(im, LearningOp.Block(candidates.indexOf("是"), "是"))
            backups(BackupOp.Copy).bytes!!
        }
        with(EngineReplay()) {
            type("shi")
            engines.learning(im, LearningOp.Pin(candidates.indexOf("是"), "是"))
            assertTrue(backups(BackupOp.Restore(saved)).ok)
            assertEquals(listOf("是"), engines.userWords(UserWord.Kind.BLOCKED).map { it.text })
            val pins = engines.learning(null, LearningOp.ListItems(LearningOp.Listed.PINS)) as LearningResult.Listed
            assertTrue("$pins", pins.items.none { it.text == "是" })
            assertFalse("是" in typed("shi"))
        }
    }
}
