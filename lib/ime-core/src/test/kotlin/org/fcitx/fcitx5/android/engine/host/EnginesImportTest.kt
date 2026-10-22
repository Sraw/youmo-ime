/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.learn.FakeClock
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.backup.BackupHead
import org.fcitx.fcitx5.android.engine.learn.backup.LearnBackup
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.BackupResult
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.learn.control.WordKind
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.user.WordListFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/**
 * Another input method's list imported (U-101, U-102, D24, C19): its words typed after the
 * dictionary's of the same keys, never in a sentence, the user's once typed twice; undone by batch.
 */
class EnginesImportTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val pinyin = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("王", -2f, 0f)
        .unigram("汪", -3f, 0f)
        .unigram("小明", -2.5f, 0f)
        .unigram("小", -2.5f, 0f)
        .unigram("明", -3f, 0f)
        .unigram("吃饭", -3f, 0f)
        .entry("王", syl("wang"))
        .entry("汪", syl("wang"))
        .entry("小明", syl("xiao", "ming"))
        .entry("小", syl("xiao"))
        .entry("明", syl("ming"))
        .entry("吃饭", syl("chi", "fan"))
        // zi read exactly, and zhi that z⇄zh reaches it by (review R11 F2741)
        .unigram("资", -1.5f, 0f)
        .unigram("支", -3f, 0f)
        .entry("资", syl("zi"))
        .entry("支", syl("zhi"))
        .build()
        .toByteArray()

    private fun load(path: String): ByteBuffer =
        if (path == Engines.PINYIN_DATA) ByteBuffer.wrap(pinyin) else throw FileNotFoundException(path)

    private val list = sequenceOf(
        "# a list of another input method's",
        "汪晓明 wang'xiao'ming 5",
        // a higher count: first of the imported
        "'wang'xiao'ming 王晓鸣 9",
        // the dictionary's: left as it is
        "小明 xiao'ming 3",
        "!明 ming",
    )

    private fun Engines.all(keys: String) = type(Engines.PINYIN, keys).let { candidates(Engines.PINYIN, 0, 100).map { it.text } }

    private fun Engines.reset() = onEvent(Engines.PINYIN, EngineEvent.RESET, 0)

    private fun Engines.pick(keys: String, text: String) {
        val shown = all(keys)
        val at = shown.indexOf(text)
        assertTrue("$text in $shown", at >= 0)
        onEvent(Engines.PINYIN, EngineEvent.PICK, at)
        reset()
    }

    // review R11 F2741 (PLAN §2.3): an imported word goes after the dictionary's read exactly, but before
    // one reached only through a fuzzy reading, not after them all
    @Test
    fun anImportedWordGoesBeforeADictionaryWordReachedOnlyByAFuzzyReading() {
        Engines(::load, folder.newFolder()).use { engines ->
            engines.settings = EngineSettings(fuzzy = setOf(Fuzzy.Z_ZH))
            assertEquals(1, engines.importWords(sequenceOf("子 zi 5"), "a.txt").imported)
            val shown = engines.all("zi")
            assertTrue("$shown", shown.indexOf("资") in 0 until shown.indexOf("子"))
            assertTrue("$shown", shown.indexOf("子") < shown.indexOf("支"))
        }
    }

    @Test
    fun importedWordsGoAfterTheDictionarysByTheirCountAndNeverInASentence() {
        Engines(::load, folder.newFolder()).use { engines ->
            assertEquals(Engines.Imported(added = 0, blocked = 1, unread = 0, imported = 2), engines.importWords(list, "a.txt"))
            val shown = engines.all("wangxiaoming")
            engines.reset()
            assertEquals("王小明", shown.first())
            assertTrue(shown.indexOf("汪小明") < shown.indexOf("王晓鸣"))
            assertTrue(shown.indexOf("王晓鸣") < shown.indexOf("汪晓明"))
            // as a word the input starts with, never inside a sentence nor after another word
            val longer = engines.all("wangxiaomingchifan")
            engines.reset()
            assertTrue("汪晓明" in longer)
            assertTrue(longer.filter { "晓" in it }.all { it == "汪晓明" || it == "王晓鸣" })
            // 简拼 too, after the dictionary's
            val short = engines.all("wxm")
            engines.reset()
            assertTrue("$short", short.indexOf("王小明") < short.indexOf("汪晓明"))
            // none on the user's lists, so none a voice hotword; not in their export
            assertEquals(listOf("明"), engines.userWords().map { it.text })
            assertEquals(listOf("!明 ming"), engines.exportWords())
            assertEquals(listOf("王晓鸣", "汪晓明"), engines.importedWords().map { it.text })
        }
    }

    @Test
    fun anImportedWordPickedTwiceIsTheUsersAndStaysWhenTheBatchIsUndone() {
        val dir = folder.newFolder()
        Engines(::load, dir).use { engines ->
            engines.importWords(list, "a.txt")
            engines.pick("wangxiaoming", "汪晓明")
            engines.pick("wangxiaoming", "汪晓明")
            assertEquals("汪晓明", engines.all("wangxiaoming").first())
            engines.reset()
            assertTrue("汪晓明" in engines.userWords(Engines.UserWord.Kind.LEARNED).map { it.text })
            assertEquals(listOf("王晓鸣"), engines.importedWords().map { it.text })
            val batch = engines.importBatches().single()
            assertEquals("a.txt" to 2, batch.file to batch.count)
            assertEquals(1, batch.left)
            // undone: the word only imported goes, the user's stays
            assertEquals(1, engines.dropImport(batch.id))
            val after = engines.all("wangxiaoming")
            engines.reset()
            assertFalse("王晓鸣" in after)
            assertEquals("汪晓明", after.first())
            assertEquals(emptyList<ImportLayer.Batch>(), engines.importBatches())
            // 撤销
            assertTrue(engines.restoreImport(batch.id))
            assertEquals(listOf("王晓鸣"), engines.importedWords().map { it.text })
            assertTrue("王晓鸣" in engines.all("wangxiaoming"))
            engines.reset()
        }
        // kept in the record
        Engines(::load, dir).use { again ->
            assertEquals(listOf("王晓鸣"), again.importedWords().map { it.text })
            assertTrue("王晓鸣" in again.all("wangxiaoming"))
        }
    }

    // review-G S4 and NIT: a word the dictionary has in another reading, and a line of no Chinese, are no words to import
    @Test
    fun aDictionaryWordMisreadOrALatinLineIsNotImported() {
        Engines(::load, folder.newFolder()).use { engines ->
            val imported = engines.importWords(sequenceOf("小明 xiao'meng 3", "hello", "汪晓明 wang'xiao'ming"), "a.txt")
            assertEquals(1, imported.imported)
            assertEquals(1, imported.unread)
            assertEquals(listOf("汪晓明"), engines.importedWords().map { it.text })
        }
    }

    // review-G NIT: picked once, an imported word is filed as read where it was typed
    @Test
    fun anImportedWordPickedIsFiledAsTyped() {
        val dir = folder.newFolder()
        Engines(::load, dir).use { engines ->
            engines.importWords(list, "a.txt")
            engines.pick("wangxiaoming", "汪晓明")
        }
        val state = LearnLog.replay(File(dir, LearnLog.FILE).readBytes())
        assertEquals(setOf("wang xiao ming"), state.wordUses["汪晓明"]?.keys)
    }

    @Test
    fun theUsersOwnExportComesBackAddedNotImported() {
        Engines(::load, folder.newFolder()).use { engines ->
            val export = sequenceOf(WordListFiles.EXPORT_MARK, "# header", "汪晓明 wang'xiao'ming")
            assertEquals(Engines.Imported(added = 1, blocked = 0, unread = 0), engines.importWords(export, "export.txt"))
            assertEquals(listOf("汪晓明"), engines.userWords(Engines.UserWord.Kind.ADDED).map { it.text })
            assertEquals(emptyList<ImportLayer.Word>(), engines.importedWords())
            // a list then: 王晓鸣 imported, 汪晓明 added already
            assertEquals(1, engines.importWords(list, "a.txt").imported)
            assertEquals(listOf("王晓鸣"), engines.importedWords().map { it.text })
        }
    }

    // review-G S1: as after a restart, a word typed once and its list undone is no word: the list imports it again
    @Test
    fun aWordTypedOnceIsImportedAgainAfterItsListIsUndone() {
        Engines(::load, folder.newFolder()).use { engines ->
            engines.importWords(list, "a.txt")
            engines.pick("wangxiaoming", "汪晓明")
            val batch = engines.importBatches().single().id
            assertEquals(2, engines.dropImport(batch))
            assertEquals(2, engines.importWords(list, "a.txt").imported)
            assertTrue("汪晓明" in engines.all("wangxiaoming"))
        }
    }

    // review-G S6: 导入的词 lists only those not yet the user's: no word added by hand or blocked since
    @Test
    fun theWordsImportedLeaveOutThoseAddedByHandOrBlocked() {
        Engines(::load, folder.newFolder()).use { engines ->
            engines.importWords(list, "a.txt")
            assertTrue(engines.addWord("汪晓明", "wang xiao ming"))
            assertEquals(listOf("王晓鸣"), engines.importedWords().map { it.text })
            assertTrue(engines.blockWord("王晓鸣", "wang xiao ming"))
            assertEquals(emptyList<String>(), engines.importedWords().map { it.text })
            assertEquals(0, engines.importBatches().single().left)
        }
    }

    // review-G S9: a page of them put in at a code, by their order; the rest where the decoder has them, after
    @Test
    fun aPageOfImportedWordsAtACodeGoesByTheirOrder() {
        val many = listOf("汪晓明", "王晓鸣", "望小名", "忘晓铭", "网晓敏", "旺小鸣", "往晓明", "枉小铭", "罔晓名", "亡小敏", "惘晓鸣", "尪小明")
            .mapIndexed { i, text -> "$text wang'xiao'ming ${100 - i}" }
        Engines(::load, folder.newFolder()).use { engines ->
            assertEquals(12, engines.importWords(many.asSequence(), "a.txt").imported)
            val texts = many.map { it.substringBefore(' ') }
            val shown = engines.all("wangxiaoming").filter { it in texts }
            assertEquals(texts.take(10), shown.take(10))
            assertEquals(texts.toSet(), shown.toSet())
        }
    }

    // review-G M1: a rewrite (U-88's, a restore's) makes the record's imported words anew, their
    // places numbered again: the model is made again with them, so a batch dropped after it is gone,
    // and a list imported then is typed as its own words, no old place standing for another's
    @Test
    fun aRewrittenRecordsImportsAreTheModelsToo() {
        val other = LearnBackup.bytes(BackupHead(0, 1, 7L, BackupHead.SAVED), LearnLog(null, FakeClock()).apply { open() }.state, emptyList(), emptyList())
        val rewrites = listOf<(Engines) -> Unit>(
            { assertTrue(it.learning(Engines.PINYIN, LearningOp.ClearRecent(LearningOp.Span.TODAY)) is LearningResult.Cleared) },
            { assertTrue((it.learning(null, BackupOp.Restore(other)) as BackupResult).ok) },
        )
        for ((n, rewrite) in rewrites.withIndex()) Engines(::load, folder.newFolder()).use { engines ->
            engines.importWords(list, "a.txt")
            engines.pick("wangxiaoming", "汪晓明")
            rewrite(engines)
            assertEquals(listOf("王晓鸣" to "wang xiao ming", "汪晓明" to "wang xiao ming"), engines.importedWords().map { it.text to it.pinyin })
            assertTrue(engines.dropImport(engines.importBatches().single().id) != null)
            val shown = engines.all("wangxiaoming")
            engines.reset()
            assertFalse("$n $shown", "汪晓明" in shown || "王晓鸣" in shown)
            engines.importWords(sequenceOf("望小名 wang'xiao'ming 3"), "b.txt")
            val again = engines.all("wangxiaoming")
            engines.reset()
            assertTrue("$again", "望小名" in again)
            assertFalse("$again", "汪晓明" in again || "王晓鸣" in again)
        }
    }

    // review-G S8: U-86's 全部清空 takes every batch with it
    @Test
    fun clearingAllTakesTheImportsToo() {
        Engines(::load, folder.newFolder()).use { engines ->
            engines.importWords(list, "a.txt")
            assertTrue(engines.learning(null, LearningOp.ClearLearned(all = true)) is LearningResult.Cleared)
            assertTrue(engines.importBatches().isEmpty())
            val shown = engines.all("wangxiaoming")
            assertFalse("$shown", "汪晓明" in shown || "王晓鸣" in shown)
        }
    }

    // an undo while typing, words imported: the session and its input stay, the record's places
    // the model's still (review-G M1), the list read anew by AGAIN
    @Test
    fun anUndoWhileTypingKeepsTheInput() {
        Engines(::load, folder.newFolder()).use { engines ->
            engines.importWords(list, "a.txt")
            repeat(2) { engines.pick("wangxiaoming", "汪晓明") }
            val shown = engines.all("wangxiaoming")
            val done = engines.learning(Engines.PINYIN, LearningOp.Delete(shown.indexOf("汪晓明"), "汪晓明")) as LearningResult.Done
            assertTrue(done.undo != LearningResult.NO_UNDO)
            engines.onEvent(Engines.PINYIN, EngineEvent.CHAR, Keyboard.AGAIN)
            assertFalse("汪晓明" in engines.candidates(Engines.PINYIN, 0, 100).map { it.text })
            assertEquals(LearningResult.Done(), engines.learning(Engines.PINYIN, LearningOp.Undo(done.undo)))
            val again = engines.onEvent(Engines.PINYIN, EngineEvent.CHAR, Keyboard.AGAIN)
            assertEquals("wangxiaoming", again.preedit.replace(" ", ""))
            val back = engines.candidates(Engines.PINYIN, 0, 100).map { it.text }
            assertTrue("$back", "汪晓明" in back && "王晓鸣" in back)
        }
    }

    // review-int S3 (U-82, D13): an imported word not yet typed is the user's to 删除, said imported
    @Test
    fun anImportedWordIsDeletedAsTheUsers() {
        Engines(::load, folder.newFolder()).use { engines ->
            engines.importWords(list, "a.txt")
            val shown = engines.all("wangxiaoming")
            val at = shown.indexOf("汪晓明")
            val menu = engines.learning(Engines.PINYIN, LearningOp.Menu(at, "汪晓明")) as LearningResult.Menu
            assertEquals(WordKind.IMPORTED, menu.kind)
            assertTrue("${menu.items}", MenuItem.DELETE in menu.items)
            assertTrue(engines.learning(Engines.PINYIN, LearningOp.Delete(at, "汪晓明")) is LearningResult.Done)
            assertFalse("汪晓明" in engines.importedWords().map { it.text })
        }
    }
}
