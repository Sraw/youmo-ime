/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.ListedItem
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/** What may follow (联想) in a table and in handwriting, as [Engines] wires it to the one lexicon and the tables. */
class EnginesTableTest {
    // a disk that refuses, where a test needs one
    @get:Rule
    val folder = TemporaryFolder()

    // 你好 follows 好: offered after it
    private val pinyin = pinyinData(nin = false)

    // 你 also read nin, likelier, so 你好 gets a reading of its own
    private fun pinyinData(nin: Boolean): ByteArray = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("你", -2f, 0f)
        .unigram("好", -2.5f, 0f)
        .unigram("你好", -3f, 0f)
        .bigram("好", "你好", -0.5f, 0f)
        .entry("你", syl("ni"), weight = if (nin) -1f else 0f)
        .apply { if (nin) entry("你", syl("nin")) }
        .entry("好", syl("hao"))
        .entry("你好", syl("ni", "hao"))
        .build()
        .toByteArray()

    private val wubi = CodeTable.Builder()
        .header("键码", "abcdefghijklmnopqrstuvwxy")
        .header("码长", "4")
        .rule("e2", "p11+p12+p21+p22")
        .entry("wqiy", "你")
        .entry("vbg", "好")
        .entry("vbgf", "妤")
        .build()
        .toByteArray()

    private fun load(path: String): ByteBuffer = load(path, pinyin)

    private fun load(path: String, pinyin: ByteArray): ByteBuffer = ByteBuffer.wrap(
        when (path) {
            InputMethodCatalog.PINYIN_DATA -> pinyin
            "${InputMethodCatalog.TABLE_DIR}/wbx.data" -> wubi
            else -> throw FileNotFoundException(path)
        },
    )

    @Test
    fun aTablesPredictionIsBlockedInTheLexicon() {
        Engines(::load, MemoryFiles()).use { engines ->
            val after = engines.type("engine-wubi", "vbg ")
            assertEquals("好", after.commit)
            val i = after.candidates.indexOf("你好")
            assertTrue(i >= 0)
            assertTrue(Offer.BLOCK in engines.offers("engine-wubi", i))
            engines.onEvent("engine-wubi", EngineEvent.BLOCK, i)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertEquals(listOf(UserWord.Kind.BLOCKED), engines.userWords().filter { it.text == "你好" }.map { it.kind })
        }
    }

    // a table's prediction forgotten is forgotten by text, as pinyin's: the word 五笔 remembered under its code stays
    @Test
    fun aTablesPredictionForgottenLeavesTheWordTheTableRemembered() {
        val dir = MemoryFiles()
        olderPhrases(dir, "wqvb" to "你好")
        Engines(::load, dir).use { engines ->
            // shown, not committed: the one word under its code commits itself as typed
            engines.settings = wubiShown
            assertTrue("你好" in engines.type("engine-wubi", "wqvb").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            val picked = engines.type("engine-wubi", "vbg ").candidates.indexOf("你好")
            assertEquals("你好", engines.onEvent("engine-wubi", EngineEvent.PICK, picked).commit)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            val i = engines.type("engine-wubi", "vbg ").candidates.indexOf("你好")
            assertTrue(Offer.FORGET in engines.offers("engine-wubi", i))
            engines.onEvent("engine-wubi", EngineEvent.FORGET, i)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertTrue("你好" in engines.type("engine-wubi", "wqvb").candidates)
        }
        assertEquals(listOf("你好"), forgotten(dir))
    }

    // 你好 after 好 turned off there in a table, as pinyin's, and taken back
    @Test
    fun aTablesPredictionIsTurnedOffAfterItsWord() {
        Engines(::load, MemoryFiles()).use { engines ->
            val i = engines.type("engine-wubi", "vbg ").candidates.indexOf("你好")
            val menu = engines.learning("engine-wubi", LearningOp.Menu(i, "你好")) as LearningResult.Menu
            assertTrue(MenuItem.NO_PREDICT in menu.items)
            assertEquals(BlockScope.EVERYWHERE, menu.scope)
            val done = engines.learning("engine-wubi", LearningOp.NoPredict(i, "你好")) as LearningResult.Done
            // one by-text kind for every IM (no code): what pinyin and handwriting block after 好 too
            val off = (engines.learning(null, LearningOp.ListItems(LearningOp.Listed.NO_PREDICT)) as LearningResult.Listed).items
            assertEquals(listOf(ListedItem(null, "好", "你好")), off)
            assertFalse("你好" in engines.onEvent("engine-wubi", EngineEvent.CHAR, Keyboard.AGAIN).candidates)
            assertEquals(LearningResult.Done(), engines.learning("engine-wubi", LearningOp.Undo(done.undo)))
            assertTrue("你好" in engines.onEvent("engine-wubi", EngineEvent.CHAR, Keyboard.AGAIN).candidates)
            val again = engines.candidates("engine-wubi", 0, 10).map { it.text }.indexOf("你好")
            engines.learning("engine-wubi", LearningOp.NoPredict(again, "你好"))
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertFalse("你好" in engines.type("engine-wubi", "vbg ").candidates)
        }
    }

    // 屏蔽 in a table: under the code typed alone, kept by 清空学到的, gone with 全部清空 as the phrases an older build saved
    @Test
    fun aTablesBlockAndPhrasesGoOnlyWithEverything() {
        val dir = MemoryFiles()
        olderPhrases(dir, "wqvb" to "你好")
        Engines(::load, dir).use { engines ->
            engines.settings = wubiShown
            val at = engines.type("engine-wubi", "vbg").candidates.indexOf("好")
            val menu = engines.learning("engine-wubi", LearningOp.Menu(at, "好")) as LearningResult.Menu
            assertEquals(BlockScope.UNDER_CODE, menu.scope)
            assertTrue(engines.learning("engine-wubi", LearningOp.Block(at, "好")) is LearningResult.Done)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertFalse("好" in engines.type("engine-wubi", "vbg").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertTrue(engines.learning(null, LearningOp.ClearLearned(all = false)) is LearningResult.Cleared)
            assertFalse("好" in engines.type("engine-wubi", "vbg").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertTrue("你好" in engines.type("engine-wubi", "wqvb").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertTrue(engines.learning(null, LearningOp.ClearLearned(all = true)) is LearningResult.Cleared)
            assertTrue("好" in engines.type("engine-wubi", "vbg").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertFalse("你好" in engines.type("engine-wubi", "wqvb").candidates)
        }
    }

    // 全部清空 that failed on disk forgets none of the phrases an older build saved
    @Test
    fun aFailedClearOfEverythingKeepsATablesPhrases() {
        val disk = folder.newFolder()
        val dir = JvmFiles(disk)
        olderPhrases(dir, "wqvb" to "你好")
        Engines(::load, dir).use { engines ->
            // shown, not committed: the one word under its code commits itself as typed
            engines.settings = wubiShown
            assertTrue("你好" in engines.type("engine-wubi", "wqvb").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            // the record cannot be written anew where its folder takes no new file
            assertTrue(disk.setWritable(false))
            try {
                assumeFalse("a user who may write any file", disk.canWrite())
                assertEquals(LearningResult.Failed, engines.learning(null, LearningOp.ClearLearned(all = true)))
            } finally {
                disk.setWritable(true)
            }
            assertTrue("你好" in engines.type("engine-wubi", "wqvb").candidates)
        }
    }

    // a table's blocks under a code are listed on the lexicon page, and shown again from there
    @Test
    fun aTablesBlocksAreListedAndShownAgain() {
        Engines(::load, MemoryFiles()).use { engines ->
            val at = engines.type("engine-wubi", "vbg").candidates.indexOf("好")
            engines.learning("engine-wubi", LearningOp.Block(at, "好"))
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            val listed = (engines.learning(null, LearningOp.ListItems(LearningOp.Listed.TABLE_BLOCKS)) as LearningResult.Listed).items
            assertEquals(listOf(ListedItem(Code(Kinds.table("engine-wubi"), "vbg"), "", "好", blocked = true)), listed)
            engines.learning(null, LearningOp.RemoveItems(listed))
            assertTrue((engines.learning(null, LearningOp.ListItems(LearningOp.Listed.TABLE_BLOCKS)) as LearningResult.Listed).items.isEmpty())
            assertTrue("好" in engines.type("engine-wubi", "vbg").candidates)
        }
    }

    @Test
    fun whatIsLookedUpByPinyinIsLearnedAsFullPinyin() {
        val dir = MemoryFiles()
        Engines(::load, dir).use { engines ->
            engines.type("engine-wubi", "zni")
            val at = engines.candidates("engine-wubi", 0, 9).indexOfFirst { it.text == "你" }
            assertEquals("你", engines.onEvent("engine-wubi", EngineEvent.PICK, at).commit)
        }
        val codes = records(dir).filterIsInstance<Commit>().flatMap { commit -> commit.rows.map { it.code } }
        assertTrue(codes.toString(), Code(Kinds.FULL, "ni") in codes)
        assertTrue(codes.toString(), codes.none { it.kind == Kinds.table("engine-wubi") })
    }

    @Test
    fun whatIsLookedUpByPinyinIsBlockedAsFullPinyinBlocksIt() {
        Engines(::load, MemoryFiles()).use { engines ->
            fun lookedUp(): List<String> {
                engines.type("engine-wubi", "zni")
                return engines.candidates("engine-wubi", 0, 9).map { it.text }
            }
            val at = lookedUp().indexOf("你")
            assertTrue(Offer.BLOCK in engines.offers("engine-wubi", at))
            assertFalse(Offer.PIN in engines.offers("engine-wubi", at))
            engines.onEvent("engine-wubi", EngineEvent.BLOCK, at)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertFalse("你" in lookedUp())
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            val blocked = engines.userWords(UserWord.Kind.BLOCKED).single()
            assertEquals("你", blocked.text)
            engines.removeWord(blocked)
            assertTrue("你" in lookedUp())
        }
    }

    // the menu says what a block of it does: pinyin's word, blocked everywhere, as the list of blocked words shows it
    @Test
    fun whatIsLookedUpByPinyinSaysItIsBlockedEverywhere() {
        Engines(::load, MemoryFiles()).use { engines ->
            fun lookedUp(): List<String> {
                engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
                engines.type("engine-wubi", "zni")
                return engines.candidates("engine-wubi", 0, 9).map { it.text }
            }
            val at = lookedUp().indexOf("你")
            val menu = engines.learning("engine-wubi", LearningOp.Menu(at, "你")) as LearningResult.Menu
            assertEquals(BlockScope.EVERYWHERE, menu.scope)
            val done = engines.learning("engine-wubi", LearningOp.Block(at, "你")) as LearningResult.Done
            // a pinyin word blocked (its reading), none under the table's code
            assertEquals(listOf(UserWord("你", "ni", UserWord.Kind.BLOCKED)), engines.userWords(UserWord.Kind.BLOCKED))
            val tableBlocks = engines.learning(null, LearningOp.ListItems(LearningOp.Listed.TABLE_BLOCKS)) as LearningResult.Listed
            assertEquals(emptyList<Any>(), tableBlocks.items)
            engines.learning("engine-wubi", LearningOp.Undo(done.undo))
            assertTrue("你" in lookedUp())
        }
    }

    @Test
    fun whatIsTypedByTheCodeSaysItIsBlockedUnderIt() {
        Engines(::load, MemoryFiles()).use { engines ->
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            engines.type("engine-wubi", "wqvb")
            val text = engines.candidates("engine-wubi", 0, 1).first().text
            val menu = engines.learning("engine-wubi", LearningOp.Menu(0, text)) as LearningResult.Menu
            assertEquals(BlockScope.UNDER_CODE, menu.scope)
        }
    }

    // 不用我的输入习惯 holds in a table's lookup by pinyin too: nothing learned, nor 删除 offered of what was
    @Test
    fun aLookUpByPinyinWithHabitsOffLearnsNothingAndForgetsNothing() {
        val dir = MemoryFiles()
        fun commits() = records(dir).count { it is Commit }
        Engines(::load, dir).use { engines ->
            fun lookUpNi(): Int {
                engines.type("engine-wubi", "zni")
                return engines.candidates("engine-wubi", 0, 9).indexOfFirst { it.text == "你" }
            }
            engines.onEvent("engine-wubi", EngineEvent.PICK, lookUpNi())
            assertEquals(1, commits())
            engines.useHabits = false
            val at = lookUpNi()
            assertFalse(Offer.FORGET in engines.offers("engine-wubi", at))
            assertEquals("你", engines.onEvent("engine-wubi", EngineEvent.PICK, at).commit)
            assertEquals(1, commits())
        }
    }

    // the texts the learning record in [dir] has forgotten
    private fun forgotten(dir: UserFiles) = records(dir).filterIsInstance<LearnRecord.Forget>().map { it.text }

    // a prediction forgotten in handwriting is forgotten by text: the word 五笔 remembered stays
    @Test
    fun handwritingForgetsAPredictionAndLeavesTheTablesWord() {
        val dir = MemoryFiles()
        olderPhrases(dir, "wqvb" to "你好")
        Engines(::load, dir).use { engines ->
            // shown, not committed: the one word under its code commits itself as typed
            engines.settings = wubiShown
            assertTrue("你好" in engines.type("engine-wubi", "wqvb").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            fun afterHao(): List<String> {
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, Keyboard.INK, payload = Action.Ink(engines.inkEnded + 1, InkReading(listOf("好"), doubleArrayOf(0.0))))
                return engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0).candidates
            }
            assertEquals("你好", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, afterHao().indexOf("你好")).commit)
            val i = afterHao().indexOf("你好")
            assertTrue(Offer.FORGET in engines.offers(InputMethodCatalog.PINYIN, i))
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.FORGET, i)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertTrue("你好" in engines.type("engine-wubi", "wqvb").candidates)
        }
        assertEquals(listOf("你好"), forgotten(dir))
    }

    // however read: the 你好 put together at nin hao too
    @Test
    fun handwritingForgetsAPredictionAsItsTextEverywhere() {
        val ninPinyin = pinyinData(nin = true)
        Engines({ load(it, ninPinyin) }, MemoryFiles()).use { engines ->
            repeat(2) {
                engines.type(InputMethodCatalog.PINYIN, "ninhao")
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, engines.candidates(InputMethodCatalog.PINYIN, 0, 20).indexOfFirst { it.text == "你" })
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0)
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            }
            fun ninHao() = engines.userWords(UserWord.Kind.LEARNED).any { it.text == "你好" && it.pinyin == "nin hao" }
            assertTrue(ninHao())
            fun afterHao(): List<String> {
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, Keyboard.INK, payload = Action.Ink(engines.inkEnded + 1, InkReading(listOf("好"), doubleArrayOf(0.0))))
                return engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0).candidates
            }
            assertEquals("你好", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, afterHao().indexOf("你好")).commit)
            val i = afterHao().indexOf("你好")
            assertTrue(Offer.FORGET in engines.offers(InputMethodCatalog.PINYIN, i))
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.FORGET, i)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertFalse(ninHao())
        }
    }

    @Test
    fun aSearchFieldShowsNoPredictionInATable() {
        Engines(::load, MemoryFiles()).use { engines ->
            engines.context("engine-wubi", "", learnFlags = Commit.NO_PREDICT)
            assertEquals(emptyList<String>(), engines.type("engine-wubi", "vbg ").candidates)
            engines.context("engine-wubi", "", learnFlags = 0)
            assertTrue("你好" in engines.type("engine-wubi", "vbg ").candidates)
        }
    }
}
