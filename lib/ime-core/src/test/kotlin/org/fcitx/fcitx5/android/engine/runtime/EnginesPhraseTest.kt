/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/** A whole phrase deleted by long press: its count alone goes, never a word of the same text; undo brings it back. */
class EnginesPhraseTest {
    private val pinyin = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("张", -2f, 0f)
        .unigram("张小明", -3f, 0f)
        .unigram("小明", -2.5f, 0f)
        .unigram("同学", -3f, 0f)
        .entry("张", syl("zhang"))
        .entry("张小明", syl("zhang", "xiao", "ming"))
        .entry("小明", syl("xiao", "ming"))
        .entry("同学", syl("tong", "xue"))
        .build()
        .toByteArray()

    private val wubi = CodeTable.Builder()
        .header("键码", "abcdefghijklmnopqrstuvwxy")
        .header("码长", "4")
        .rule("e2", "p11+p12+p21+p22")
        .entry("xtay", "张")
        .build()
        .toByteArray()

    private fun load(path: String): ByteBuffer = ByteBuffer.wrap(
        when (path) {
            InputMethodCatalog.PINYIN_DATA -> pinyin
            "${InputMethodCatalog.TABLE_DIR}/wbx.data" -> wubi
            else -> throw FileNotFoundException(path)
        },
    )

    private val pinyinIm = InputMethodCatalog.PINYIN
    private val wubiIm = "engine-wubi"

    // the phrase put in whole, the word added by hand
    private fun Engines.typedWhole() {
        context(pinyinIm, "")
        type(pinyinIm, "zhangxiaomingtongxue")
        val at = candidates(pinyinIm, 0, 20).indexOfFirst { it.text == PHRASE }
        assertTrue("${candidates(pinyinIm, 0, 20).map { it.text }}", at >= 0)
        assertEquals(PHRASE, onEvent(pinyinIm, EngineEvent.PICK, at).commit)
        onEvent(pinyinIm, EngineEvent.RESET, 0)
    }

    // 张小明 picked in a field of nothing: what may follow it
    private fun Engines.afterZhangXiaoming(): List<String> {
        context(pinyinIm, "")
        type(pinyinIm, "zhangxiaoming")
        val at = candidates(pinyinIm, 0, 20).indexOfFirst { it.text == "张小明" }
        return onEvent(pinyinIm, EngineEvent.PICK, at).candidates
    }

    // 张 put in by 五笔 in a field of nothing: what may follow it there
    private fun Engines.afterZhangInWubi(): List<String> {
        context(wubiIm, "")
        return type(wubiIm, "xtay ").candidates
    }

    // whether what is offered at [index] is the rest of a whole phrase
    private fun Engines.phraseAt(im: String, index: Int): Boolean {
        val text = candidates(im, index, 1).single().text
        return (learning(im, LearningOp.Menu(index, text)) as LearningResult.Menu).why?.whole.orEmpty().isNotEmpty()
    }

    private fun Engines.inWubi(): Boolean {
        onEvent(wubiIm, EngineEvent.RESET, 0)
        context(wubiIm, "")
        return PHRASE in type(wubiIm, TABLE_CODE.dropLast(1)).candidates.also { onEvent(wubiIm, EngineEvent.RESET, 0) }
    }

    // where the user has the phrase's text as a word: their lists (added by hand, learned), imported, a table's saved
    private fun Engines.words(): Set<String> = buildSet {
        userWords().filter { it.text == PHRASE }.forEach { add(it.kind.name) }
        if (importedWords().any { it.text == PHRASE }) add("imported")
        if (inWubi()) add("table")
    }

    // [byHand] added else imported, plus a table's saved phrase; then put in whole three times
    private fun prepared(engines: Engines, byHand: Boolean): Set<String> {
        if (byHand) {
            assertTrue(engines.addWord(PHRASE, "zhang xiao ming tong xue"))
        } else {
            assertEquals(1, engines.importWords(sequenceOf("$PHRASE zhang'xiao'ming'tong'xue 5"), "a.txt").imported)
        }
        repeat(3) { engines.typedWhole() }
        return engines.words().also { assertEquals(setOf(if (byHand) "ADDED" else "LEARNED", "table"), it) }
    }

    @Test
    fun deletedInPinyinItsCountGoesAndTheWordsOfItsTextStay() {
        deletedInPinyin(byHand = true)
        deletedInPinyin(byHand = false)
    }

    private fun deletedInPinyin(byHand: Boolean) {
        val dir = MemoryFiles()
        olderPhrases(dir, TABLE_CODE to PHRASE)
        Engines(::load, dir).use { engines ->
            val words = prepared(engines, byHand)
            assertEquals(REST, engines.afterZhangXiaoming().first())
            val done = engines.learning(pinyinIm, LearningOp.Delete(0, REST)) as LearningResult.Done
            assertNotEquals(LearningResult.NO_UNDO, done.undo)
            assertEquals(words, engines.words())
            assertEquals(LearningResult.Done(), engines.learning(pinyinIm, LearningOp.Undo(done.undo)))
            assertEquals(REST, engines.afterZhangXiaoming().first())
            val again = engines.learning(pinyinIm, LearningOp.Delete(0, REST))
            assertTrue("$byHand $again", again is LearningResult.Done)
            // as a phrase: imported, its pieces were put in apart at first, 同学 after 张小明 a pair learned
            val after = engines.afterZhangXiaoming()
            assertFalse("$byHand $after", after.indexOf(REST).let { it >= 0 && engines.phraseAt(pinyinIm, it) })
            assertEquals(words, engines.words())
        }
        val records = records(dir)
        assertTrue("$records", records.none { it is LearnRecord.Forget })
    }

    // a table's 联想 (Predictions): the same
    @Test
    fun deletedInATableItsCountGoesAndTheWordsOfItsTextStay() {
        deletedInATable(byHand = true)
        deletedInATable(byHand = false)
    }

    private fun deletedInATable(byHand: Boolean) {
        val dir = MemoryFiles()
        olderPhrases(dir, TABLE_CODE to PHRASE)
        Engines(::load, dir).use { engines ->
            val words = prepared(engines, byHand)
            val shown = engines.afterZhangInWubi()
            val at = shown.indexOf("小明同学")
            assertEquals("$shown", 0, at)
            val done = engines.learning(wubiIm, LearningOp.Delete(at, "小明同学")) as LearningResult.Done
            assertNotEquals(LearningResult.NO_UNDO, done.undo)
            assertEquals(words, engines.words())
            assertEquals(LearningResult.Done(), engines.learning(wubiIm, LearningOp.Undo(done.undo)))
            assertEquals("小明同学", engines.afterZhangInWubi().first())
            assertTrue(engines.learning(wubiIm, LearningOp.Delete(0, "小明同学")) is LearningResult.Done)
            assertFalse("小明同学" in engines.afterZhangInWubi())
            assertEquals(words, engines.words())
        }
        assertTrue(records(dir).none { it is LearnRecord.Forget })
    }

    @Test
    fun deletedItComesBackAfterThreeMore() {
        Engines(::load, MemoryFiles()).use { engines ->
            assertTrue(engines.addWord(PHRASE, "zhang xiao ming tong xue"))
            repeat(3) { engines.typedWhole() }
            engines.afterZhangXiaoming()
            assertTrue(engines.learning(pinyinIm, LearningOp.Delete(0, REST)) is LearningResult.Done)
            repeat(2) { engines.typedWhole() }
            assertFalse(REST == engines.afterZhangXiaoming().firstOrNull())
            onReset(engines)
            engines.typedWhole()
            assertEquals(REST, engines.afterZhangXiaoming().first())
        }
    }

    private fun onReset(engines: Engines) {
        engines.onEvent(pinyinIm, EngineEvent.RESET, 0)
    }

    private companion object {
        const val PHRASE = "张小明同学"
        const val REST = "同学"
        const val TABLE_CODE = "xtiy"
    }
}
