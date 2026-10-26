/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.BackupResult
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.libime.LibimeFiles
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * uat/13-words.md, the user lexicon's half: blocked, learned and imported words, export and
 * learning backups, custom phrases, clearing what was learned. The pages do these through
 * FcitxAPI's calls (the words, the imports, `learning(op)`); here they are those calls, and a
 * page's list is what the engine lists. An import's file is read as the page reads it: a word
 * list's lines as they are, libime's `.dict` through LibimeFiles.
 */
class LexiconUatTest {

    private fun EngineReplay.listed(kind: UserWord.Kind) = engines.userWords(kind).map { it.text }

    private fun lines(name: String) = UatEngines.fixture(name).readLines().asSequence()

    @Test
    @Uat("WD-06")
    fun aWordBlockedInTheSettingsIsNoCandidate() = with(EngineReplay()) {
        assertTrue(engines.blockWord("时间", "shi jian"))
        assertTrue(listed(UserWord.Kind.BLOCKED).toString(), "时间" in listed(UserWord.Kind.BLOCKED))
        assertFalse("时间" in typed("shijian"))
    }

    @Test
    @Uat("WD-07")
    fun aBlockedWordRestoredIsBack() = with(EngineReplay()) {
        type("shijian")
        engines.learning(im, LearningOp.Block(candidates.indexOf("时间"), "时间"))
        val blocked = engines.userWords(UserWord.Kind.BLOCKED)
        assertEquals(listOf("时间"), blocked.map { it.text })
        assertFalse("时间" in typed("shijian"))
        // 恢复显示 on the list: the blocked word removed
        engines.removeWords(blocked)
        assertTrue(listed(UserWord.Kind.BLOCKED).isEmpty())
        assertTrue("时间" in typed("shijian"))
    }

    @Test
    @Uat("WD-08")
    fun aLearnedWordDeletedIsGone() = with(EngineReplay()) {
        learnWangXiaoming()
        val learned = engines.userWords(UserWord.Kind.LEARNED).filter { it.text == "汪小明" }
        assertEquals(1, learned.size)
        engines.removeWords(learned)
        assertFalse("汪小明" in listed(UserWord.Kind.LEARNED))
        val name = wangXiaoming()
        assertEquals("王小明", name.first())
        assertFalse("汪小明" in name)
        assertFalse("汪小明" in typed("wxm"))
    }

    @Test
    @Uat("WD-09")
    fun aWordListImportedIntoTheUserDictionary() = with(EngineReplay()) {
        val done = engines.importWords(lines("userwords.txt"), "userwords.txt")
        assertEquals("$done", Engines.Imported(added = 0, blocked = 1, unread = 0, imported = 2), done.copy(learned = 0))
        assertEquals(listOf("马上就好"), listed(UserWord.Kind.BLOCKED))
        val batch = engines.importBatches().single()
        assertEquals("userwords.txt", batch.file)
        assertEquals(setOf("幽默测试", "蓝鲸花园"), engines.importedWords(batch.id).map { it.text }.toSet())
    }

    @Test
    @Uat("WD-09b")
    fun aThousandWordsImportedIntoTheUserDictionary() = with(EngineReplay()) {
        val done = engines.importWords(lines("big-words.txt"), "big-words.txt")
        assertEquals("$done", 1001, done.imported)
        val batch = engines.importBatches().single()
        assertEquals("big-words.txt", batch.file)
        assertEquals("$batch", 1001, batch.left)
    }

    @Test
    @Uat("WD-10")
    fun anExportImportedElsewhereHasTheAddedBlockedAndLearned() {
        val copy = with(EngineReplay()) {
            learnWangXiaoming()
            assertTrue(engines.addWord("蓝鲸花园", "lan jing hua yuan"))
            assertTrue(engines.blockWord("时间", "shi jian"))
            // 导出: the learning as a backup's bytes (the .fxlb the page saves)
            (engines.learning(null, BackupOp.Copy) as BackupResult).bytes!!
        }
        with(EngineReplay()) {
            // picked, it is a backup: its head (source, date) for the confirmation, not 并入哪个词库
            assertNotNull((engines.learning(null, BackupOp.Peek(copy)) as BackupResult).head)
            assertTrue((engines.learning(null, BackupOp.Restore(copy)) as BackupResult).ok)
            assertEquals(listOf("蓝鲸花园"), listed(UserWord.Kind.ADDED))
            assertEquals(listOf("时间"), listed(UserWord.Kind.BLOCKED))
            assertTrue("汪小明" in listed(UserWord.Kind.LEARNED))
            assertEquals(1, engines.userWordCounts()[UserWord.Kind.ADDED])
            assertEquals("汪小明", wangXiaoming().first())
        }
    }

    @Test
    @Uat("WD-10a")
    fun aBackupKeepsTheImportedBatch() {
        val copy = with(EngineReplay()) {
            assertEquals(1001, engines.importWords(lines("big-words.txt"), "big-words.txt").imported)
            (engines.learning(null, BackupOp.Copy) as BackupResult).bytes!!
        }
        with(EngineReplay()) {
            assertTrue((engines.learning(null, BackupOp.Restore(copy)) as BackupResult).ok)
            val batch = engines.importBatches().single()
            assertEquals("big-words.txt", batch.file)
            assertEquals(1001, batch.left)
            // 撤掉这批 still works on it
            assertEquals(1001, engines.dropImport(batch.id))
            assertTrue(engines.importBatches().isEmpty())
        }
    }

    @Test
    @Uat("WD-14b")
    fun aDictFileIntoTheUserDictionaryRanksBecomesYoursDroppedAndUndone() = with(EngineReplay()) {
        val dict = LibimeFiles.pinyinDictionary(UatEngines.fixture("extra.dict").readBytes())
        assertEquals(2, engines.importWords(dict.asSequence(), "extra.dict").imported)
        assertEquals(2, engines.importBatches().single().left)
        val c1 = typed("congjing")
        assertTrue("first $c1", c1.first() != "琮璟" && c1.indexOf("琮璟") > c1.indexOf("从警"))
        pick("琮璟")
        val c2 = typed("congjing")
        assertEquals("second $c2", "琮璟", c2[1])
        pick("琮璟")
        assertEquals("third", "琮璟", typed("congjing").first())
        assertTrue("琮璟" in listed(UserWord.Kind.LEARNED))
        val batch = engines.importBatches().single()
        assertEquals("1 个词还不是你的", 1, batch.left)
        assertEquals(1, engines.dropImport(batch.id))
        assertTrue(engines.importBatches().isEmpty())
        assertFalse("骁骎" in typed("xiaoqin"))
        assertTrue("琮璟" in typed("congjing"))
        focus("")
        assertEquals(1, engines.importWords(dict.asSequence(), "extra.dict").imported)
        val again = engines.importBatches().single()
        assertEquals(1, engines.dropImport(again.id))
        // 撤销 on the bar
        assertTrue(engines.restoreImport(again.id))
        assertEquals(listOf("extra.dict"), engines.importBatches().map { it.file })
        assertTrue("骁骎" in typed("xiaoqin"))
    }

    @Test
    @Uat("WD-16")
    fun aPhraseOfTwoLines() = with(EngineReplay()) {
        UatEngines.phrases = CustomPhrases.format(listOf(CustomPhrases.Phrase("qm", 1, "张三\n138 0000 0000")))
        engines.reload()
        type("qm")
        space()
        expectText("张三\n138 0000 0000")
    }

    @Test
    @Uat("WD-19")
    fun phrasesNotOnTheNineKeysButInShuangpin() = with(EngineReplay(im = Engines.T9)) {
        UatEngines.phrases = "dz,1=北京市海淀区\n"
        engines.reload()
        type("39")
        expectNotAmong("北京市海淀区")
        focus("")
        ime(Engines.SHUANGPIN)
        type("dz")
        expectAmong("北京市海淀区")
    }

    @Test
    @Uat("WD-22")
    fun aPinUndoneInOneStep() = with(EngineReplay()) {
        type("shi")
        val before = candidates
        val c = before[4]
        engines.learning(im, LearningOp.Pin(4, c))
        assertEquals(c, typed("shi").first())
        val pins = engines.learning(null, LearningOp.ListItems(LearningOp.Listed.PINS)) as LearningResult.Listed
        assertEquals(listOf(c), pins.items.map { it.text })
        // 取消固定 in its confirmation
        engines.learning(null, LearningOp.RemoveItems(pins.items))
        assertTrue((engines.learning(null, LearningOp.ListItems(LearningOp.Listed.PINS)) as LearningResult.Listed).items.isEmpty())
        assertEquals(before.first(), typed("shi").first())
    }

    @Test
    @Uat("WD-25")
    fun clearingTheLearnedKeepsYourWordsUnlessAll() = with(EngineReplay()) {
        repeat(2) { taDl("点了") }
        learnWangXiaoming()
        focus("")
        type("shi")
        val c = candidates[4]
        engines.learning(im, LearningOp.Pin(4, c))
        focus("")
        type("shi")
        engines.learning(im, LearningOp.Block(candidates.indexOf("是"), "是"))
        focus("")
        ime(Engines.WUBI)
        type("fghy")
        engines.learning(im, LearningOp.Block(candidates.indexOf("寸"), "寸"))
        focus("")
        ime(Engines.PINYIN)

        assertTrue(engines.learning(null, LearningOp.ClearLearned(all = false)) is LearningResult.Cleared)
        assertEquals("带来", taDl(null).first())
        focus("")
        commit("。")
        type("wxm")
        assertTrue("wxm $candidates", "汪小明" in candidates)
        val shi = typed("shi")
        assertEquals(c, shi.first())
        assertFalse("是" in shi)

        assertTrue(engines.learning(null, LearningOp.ClearLearned(all = true)) is LearningResult.Cleared)
        val name = wangXiaoming()
        assertEquals("王小明", name.first())
        assertFalse("汪小明" in name)
        val shi2 = typed("shi")
        // the block cleared: 是 back; the pin cleared: C no longer first, unless C is 是 itself
        assertTrue("是 not back: shi $shi2", "是" in shi2)
        assertTrue("$c still pinned: shi $shi2", c == "是" || shi2.first() != c)
        focus("")
        ime(Engines.WUBI)
        type("fghy")
        expectAmong("寸")
    }

    @Test
    @Uat("WD-33")
    fun aPhraseAlreadyBeforeDoesNotMoveTheNextUp() = with(EngineReplay()) {
        UatEngines.phrases = "nihao,2=你好\nnihao,3=您好呀\n"
        engines.reload()
        type("nihao")
        assertEquals("nihao $candidates", 1, candidates.count { it == "你好" })
        assertEquals("nihao $candidates", "您好呀", candidates[2])
    }
}
