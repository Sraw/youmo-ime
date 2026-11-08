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

    // 你 read nin too, likelier, for 你好 to be put together at a reading of its own (of equal
    // weight, 你 picked from ninhao would read ni, the arc found first)
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

    // a prediction blocked in 五笔 is blocked in the lexicon, for pinyin too
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

    // a table's own prediction forgotten is forgotten by the tables too: 五笔's phrase 你好 (an older build's) goes
    @Test
    fun aTablesPredictionForgottenGoesFromTheTables() {
        val dir = MemoryFiles()
        olderPhrases(dir, "wqvb" to "你好")
        Engines(::load, dir).use { engines ->
            assertTrue("你好" in engines.type("engine-wubi", "wqv").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            // picked: learned, to be forgotten
            val picked = engines.type("engine-wubi", "vbg ").candidates.indexOf("你好")
            assertEquals("你好", engines.onEvent("engine-wubi", EngineEvent.PICK, picked).commit)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            val i = engines.type("engine-wubi", "vbg ").candidates.indexOf("你好")
            assertTrue(Offer.FORGET in engines.offers("engine-wubi", i))
            engines.onEvent("engine-wubi", EngineEvent.FORGET, i)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertFalse("你好" in engines.type("engine-wubi", "wqv").candidates)
        }
        // review-1a-app S3: and from the learning record
        assertEquals(listOf("你好"), forgotten(dir))
    }

    // U-72 in a table (review-E-engine S10): 你好 after 好 turned off there, as pinyin's, and taken back
    @Test
    fun aTablesPredictionIsTurnedOffAfterItsWord() {
        Engines(::load, MemoryFiles()).use { engines ->
            val i = engines.type("engine-wubi", "vbg ").candidates.indexOf("你好")
            val menu = engines.learning("engine-wubi", LearningOp.Menu(i, "你好")) as LearningResult.Menu
            assertTrue(MenuItem.NO_PREDICT in menu.items)
            // what may follow is blocked as pinyin's: everywhere
            assertEquals(BlockScope.EVERYWHERE, menu.scope)
            val done = engines.learning("engine-wubi", LearningOp.NoPredict(i, "你好")) as LearningResult.Done
            // one by-text kind for every IM (no code): what pinyin and handwriting block after 好 too
            val off = (engines.learning(null, LearningOp.ListItems(LearningOp.Listed.NO_PREDICT)) as LearningResult.Listed).items
            assertEquals(listOf(ListedItem(null, "好", "你好")), off)
            assertFalse("你好" in engines.onEvent("engine-wubi", EngineEvent.CHAR, Keyboard.AGAIN).candidates)
            assertEquals(LearningResult.Done(), engines.learning("engine-wubi", LearningOp.Undo(done.undo)))
            assertTrue("你好" in engines.onEvent("engine-wubi", EngineEvent.CHAR, Keyboard.AGAIN).candidates)
            // turned off again, it stays off for the next time
            val again = engines.candidates("engine-wubi", 0, 10).map { it.text }.indexOf("你好")
            engines.learning("engine-wubi", LearningOp.NoPredict(again, "你好"))
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertFalse("你好" in engines.type("engine-wubi", "vbg ").candidates)
        }
    }

    // U-28 屏蔽 in a table: under the code typed alone, kept by 清空学到的, gone with 全部清空 as the phrases an older build saved
    @Test
    fun aTablesBlockAndPhrasesGoOnlyWithEverything() {
        val dir = MemoryFiles()
        olderPhrases(dir, "wqvb" to "你好")
        Engines(::load, dir).use { engines ->
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
            assertTrue("你好" in engines.type("engine-wubi", "wqv").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertTrue(engines.learning(null, LearningOp.ClearLearned(all = true)) is LearningResult.Cleared)
            assertTrue("好" in engines.type("engine-wubi", "vbg").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertFalse("你好" in engines.type("engine-wubi", "wqv").candidates)
        }
    }

    // verify-int-E: 全部清空 that failed on disk forgets none of the phrases an older build saved
    @Test
    fun aFailedClearOfEverythingKeepsATablesPhrases() {
        val disk = folder.newFolder()
        val dir = JvmFiles(disk)
        olderPhrases(dir, "wqvb" to "你好")
        Engines(::load, dir).use { engines ->
            assertTrue("你好" in engines.type("engine-wubi", "wqv").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            // the record cannot be written anew where its folder takes no new file
            assertTrue(disk.setWritable(false))
            try {
                // root writes there all the same (a container's build)
                assumeFalse("a user who may write any file", disk.canWrite())
                assertEquals(LearningResult.Failed, engines.learning(null, LearningOp.ClearLearned(all = true)))
            } finally {
                disk.setWritable(true)
            }
            assertTrue("你好" in engines.type("engine-wubi", "wqv").candidates)
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

    // U-28: a character looked up by pinyin in 五笔 is learned as full pinyin learns it, the table's codes untouched
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

    // a character looked up by pinyin in 五笔 is blocked as full pinyin blocks it, and comes back unblocked
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

    // review H3: the menu says what a block of it does: pinyin's word, blocked everywhere, as the list of blocked words shows it
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

    // and what the table's code typed has, under that code alone
    @Test
    fun whatIsTypedByTheCodeSaysItIsBlockedUnderIt() {
        Engines(::load, MemoryFiles()).use { engines ->
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            engines.type("engine-wubi", "wqv")
            val text = engines.candidates("engine-wubi", 0, 1).first().text
            val menu = engines.learning("engine-wubi", LearningOp.Menu(0, text)) as LearningResult.Menu
            assertEquals(BlockScope.UNDER_CODE, menu.scope)
        }
    }

    // U-93 (review-int M3): 不用我的输入习惯 holds in a table's lookup by pinyin too: nothing learned, nor 删除 offered of what was
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

    // a prediction forgotten in handwriting is forgotten by the tables too: 五笔's phrase 你好 goes
    @Test
    fun handwritingForgetsAPredictionInTheTables() {
        val dir = MemoryFiles()
        olderPhrases(dir, "wqvb" to "你好")
        Engines(::load, dir).use { engines ->
            assertTrue("你好" in engines.type("engine-wubi", "wqv").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            fun afterHao(): List<String> {
                engines.onEvent(InputMethodCatalog.HANDWRITING, EngineEvent.CHAR, Keyboard.INK, payload = Action.Ink(engines.inkEnded + 1, InkReading(listOf("好"), doubleArrayOf(0.0))))
                return engines.onEvent(InputMethodCatalog.HANDWRITING, EngineEvent.PICK, 0).candidates
            }
            // picked: learned, to be forgotten
            assertEquals("你好", engines.onEvent(InputMethodCatalog.HANDWRITING, EngineEvent.PICK, afterHao().indexOf("你好")).commit)
            val i = afterHao().indexOf("你好")
            assertTrue(Offer.FORGET in engines.offers(InputMethodCatalog.HANDWRITING, i))
            engines.onEvent(InputMethodCatalog.HANDWRITING, EngineEvent.FORGET, i)
            engines.onEvent(InputMethodCatalog.HANDWRITING, EngineEvent.RESET, 0)
            assertFalse("你好" in engines.type("engine-wubi", "wqv").candidates)
        }
        assertEquals(listOf("你好"), forgotten(dir))
    }

    // a text forgotten is forgotten as the learning record has it, however read (U-84): the 你好 the
    // user put together at nin hao too. Apart from the test above, as a word of the user's the tables code too
    @Test
    fun handwritingForgetsAPredictionAsItsTextEverywhere() {
        val ninPinyin = pinyinData(nin = true)
        Engines({ load(it, ninPinyin) }, MemoryFiles()).use { engines ->
            // twice: the user's word (U-06)
            repeat(2) {
                engines.type(InputMethodCatalog.PINYIN, "ninhao")
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, engines.candidates(InputMethodCatalog.PINYIN, 0, 20).indexOfFirst { it.text == "你" })
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0)
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            }
            fun ninHao() = engines.userWords(UserWord.Kind.LEARNED).any { it.text == "你好" && it.pinyin == "nin hao" }
            assertTrue(ninHao())
            fun afterHao(): List<String> {
                engines.onEvent(InputMethodCatalog.HANDWRITING, EngineEvent.CHAR, Keyboard.INK, payload = Action.Ink(engines.inkEnded + 1, InkReading(listOf("好"), doubleArrayOf(0.0))))
                return engines.onEvent(InputMethodCatalog.HANDWRITING, EngineEvent.PICK, 0).candidates
            }
            assertEquals("你好", engines.onEvent(InputMethodCatalog.HANDWRITING, EngineEvent.PICK, afterHao().indexOf("你好")).commit)
            val i = afterHao().indexOf("你好")
            assertTrue(Offer.FORGET in engines.offers(InputMethodCatalog.HANDWRITING, i))
            engines.onEvent(InputMethodCatalog.HANDWRITING, EngineEvent.FORGET, i)
            engines.onEvent(InputMethodCatalog.HANDWRITING, EngineEvent.RESET, 0)
            assertFalse(ninHao())
        }
    }

    // U-91, D19: a search field shows no 联想, in a table as in pinyin
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
