/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

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
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.table.TableUser.TableLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/** What may follow (联想) in a table and in handwriting, as [Engines] wires it to the one lexicon and the tables. */
class EnginesTableTest {

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
            Engines.PINYIN_DATA -> pinyin
            "${Engines.TABLE_DIR}/wbx.data" -> wubi
            else -> throw FileNotFoundException(path)
        },
    )

    // a prediction blocked in 五笔 is blocked in the lexicon, for pinyin too
    @Test
    fun aTablesPredictionIsBlockedInTheLexicon() {
        Engines(::load, folder.newFolder()).use { engines ->
            val after = engines.type("engine-wubi", "vbg ")
            assertEquals("好", after.commit)
            val i = after.candidates.indexOf("你好")
            assertTrue(i >= 0)
            assertTrue(Offer.BLOCK in engines.offers("engine-wubi", i))
            engines.onEvent("engine-wubi", EngineEvent.BLOCK, i)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertEquals(listOf(Engines.UserWord.Kind.BLOCKED), engines.userWords().filter { it.text == "你好" }.map { it.kind })
        }
    }

    // a table's own prediction forgotten is forgotten by the tables too: 五笔's phrase 你好 (an older build's) goes
    @Test
    fun aTablesPredictionForgottenGoesFromTheTables() {
        val dir = folder.newFolder()
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
        Engines(::load, folder.newFolder()).use { engines ->
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
        val dir = folder.newFolder()
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
        val dir = folder.newFolder()
        olderPhrases(dir, "wqvb" to "你好")
        Engines(::load, dir).use { engines ->
            assertTrue("你好" in engines.type("engine-wubi", "wqv").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            // the record cannot be written anew where its folder takes no new file
            assertTrue(dir.setWritable(false))
            try {
                // root writes there all the same (a container's build)
                assumeFalse("a user who may write any file", dir.canWrite())
                assertEquals(LearningResult.Failed, engines.learning(null, LearningOp.ClearLearned(all = true)))
            } finally {
                dir.setWritable(true)
            }
            assertTrue("你好" in engines.type("engine-wubi", "wqv").candidates)
        }
    }

    // a table's blocks under a code are listed on the lexicon page, and shown again from there
    @Test
    fun aTablesBlocksAreListedAndShownAgain() {
        Engines(::load, folder.newFolder()).use { engines ->
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
        val dir = folder.newFolder()
        Engines(::load, dir).use { engines ->
            engines.type("engine-wubi", "zni")
            val at = engines.candidates("engine-wubi", 0, 9).indexOfFirst { it.text == "你" }
            assertEquals("你", engines.onEvent("engine-wubi", EngineEvent.PICK, at).commit)
        }
        val codes = records(dir).filterIsInstance<Commit>().flatMap { commit -> commit.rows.map { it.code } }
        assertTrue(codes.toString(), Code(Kinds.FULL, "ni") in codes)
        assertTrue(codes.toString(), codes.none { it.kind == Kinds.table("engine-wubi") })
    }

    // U-93 (review-int M3): 不用我的输入习惯 holds in a table's lookup by pinyin too: nothing learned, nor 删除 offered of what was
    @Test
    fun aLookUpByPinyinWithHabitsOffLearnsNothingAndForgetsNothing() {
        val dir = folder.newFolder()
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

    // UAT TBL-33: an older build's picks (雨 twice, then 寸 twice) converted, and 寸 picked twice
    // since put first by use, as on a new install
    @Test
    fun picksConvertedFromAnOlderTableLogCountWithThoseSince() {
        val table = CodeTable.Builder()
            .header("键码", "abcdefghijklmnopqrstuvwxy")
            .header("码长", "4")
            .rule("e2", "p11+p12+p21+p22")
            .entry("fghy", "雨")
            .entry("fghy", "寸")
            .entry("fghy", "圷")
            .build()
            .toByteArray()
        val dir = folder.newFolder()
        dir.resolve(Engines.userTable("engine-wubi")).writeBytes(
            TableLog.FORMAT.header() + TableLog.picked("fghy", "雨", 2) + TableLog.picked("fghy", "寸", 2),
        )
        val load = { path: String -> if (path == "${Engines.TABLE_DIR}/wbx.data") ByteBuffer.wrap(table) else load(path) }
        // made off the typing thread, put in place after the first pick (a field started)
        val queued = ArrayList<Runnable>()
        Engines(load, dir, warmUps = { queued += it }).use { engines ->
            repeat(2) {
                val shown = engines.type("engine-wubi", "fghy").candidates
                assertEquals("寸", engines.onEvent("engine-wubi", EngineEvent.PICK, shown.indexOf("寸")).commit)
                engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
                while (queued.isNotEmpty()) queued.removeAt(0).run()
                engines.paused()
            }
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertEquals("寸", engines.type("engine-wubi", "fghy").candidates.first())
        }
    }

    // the texts the learning record in [dir] has forgotten
    private fun forgotten(dir: File) = records(dir).filterIsInstance<LearnRecord.Forget>().map { it.text }

    // a prediction forgotten in handwriting is forgotten by the tables too: 五笔's phrase 你好 goes
    @Test
    fun handwritingForgetsAPredictionInTheTables() {
        val dir = folder.newFolder()
        olderPhrases(dir, "wqvb" to "你好")
        Engines(::load, dir).use { engines ->
            assertTrue("你好" in engines.type("engine-wubi", "wqv").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            fun afterHao(): List<String> {
                engines.offerInk(InkReading(listOf("好"), doubleArrayOf(0.0)), engines.inkEnded + 1)
                engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.INK)
                return engines.onEvent(Engines.HANDWRITING, EngineEvent.PICK, 0).candidates
            }
            // picked: learned, to be forgotten
            assertEquals("你好", engines.onEvent(Engines.HANDWRITING, EngineEvent.PICK, afterHao().indexOf("你好")).commit)
            val i = afterHao().indexOf("你好")
            assertTrue(Offer.FORGET in engines.offers(Engines.HANDWRITING, i))
            engines.onEvent(Engines.HANDWRITING, EngineEvent.FORGET, i)
            engines.onEvent(Engines.HANDWRITING, EngineEvent.RESET, 0)
            assertFalse("你好" in engines.type("engine-wubi", "wqv").candidates)
        }
        assertEquals(listOf("你好"), forgotten(dir))
    }

    // a text forgotten is forgotten as the learning record has it, however read (U-84): the 你好 the
    // user put together at nin hao too. Apart from the test above, as a word of the user's the tables code too
    @Test
    fun handwritingForgetsAPredictionAsItsTextEverywhere() {
        val ninPinyin = pinyinData(nin = true)
        Engines({ load(it, ninPinyin) }, folder.newFolder()).use { engines ->
            // twice: the user's word (U-06)
            repeat(2) {
                engines.type(Engines.PINYIN, "ninhao")
                engines.onEvent(Engines.PINYIN, EngineEvent.PICK, engines.candidates(Engines.PINYIN, 0, 20).indexOfFirst { it.text == "你" })
                engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 0)
                engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            }
            fun ninHao() = engines.userWords(Engines.UserWord.Kind.LEARNED).any { it.text == "你好" && it.pinyin == "nin hao" }
            assertTrue(ninHao())
            fun afterHao(): List<String> {
                engines.offerInk(InkReading(listOf("好"), doubleArrayOf(0.0)), engines.inkEnded + 1)
                engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.INK)
                return engines.onEvent(Engines.HANDWRITING, EngineEvent.PICK, 0).candidates
            }
            assertEquals("你好", engines.onEvent(Engines.HANDWRITING, EngineEvent.PICK, afterHao().indexOf("你好")).commit)
            val i = afterHao().indexOf("你好")
            assertTrue(Offer.FORGET in engines.offers(Engines.HANDWRITING, i))
            engines.onEvent(Engines.HANDWRITING, EngineEvent.FORGET, i)
            engines.onEvent(Engines.HANDWRITING, EngineEvent.RESET, 0)
            assertFalse(ninHao())
        }
    }

    // U-91, D19: a search field shows no 联想, in a table as in pinyin
    @Test
    fun aSearchFieldShowsNoPredictionInATable() {
        Engines(::load, folder.newFolder()).use { engines ->
            engines.context("engine-wubi", "", learnFlags = Commit.NO_PREDICT)
            assertEquals(emptyList<String>(), engines.type("engine-wubi", "vbg ").candidates)
            engines.context("engine-wubi", "", learnFlags = 0)
            assertTrue("你好" in engines.type("engine-wubi", "vbg ").candidates)
        }
    }
}
