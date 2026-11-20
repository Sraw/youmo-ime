/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.WUBI
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.control.LearningMenu
import org.fcitx.fcitx5.android.engine.learn.control.LearningMenu.Row
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFile
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.fcitx.fcitx5.android.engine.user.DictionaryCompiler
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

/** The user's words as [TableRegistry] codes them for a table, and the words a table remembers. */
class TableRegistryTest : EnginesFixture() {

    // wired as Engines wires it: the merged dictionaries coded from their file, into an index of the table's
    private fun wired(warmUps: Executor, assets: EngineAssets, lexicon: UserLexicon): TableRegistry {
        val indexes = indexDir()
        return TableRegistry(blobs = ::load, onError = {}, coding = warmUps, assets = assets, lexicon = lexicon, indexes = { indexes })
    }

    // the merged file and the tables' indexes of it on the disk: the index mapped, its time read
    @get:Rule
    val folder = TemporaryFolder()

    private val root get() = JvmFiles(folder.root)

    @Test
    fun aWordAddedInAnotherReadingOfOneListedIsNotCodedTwice() {
        val additions = Engines.Additions("", "d", indexes = indexDir(), dictionary = compiled(::load) { sequenceOf("你好 ni'hao 0") })
        val now = Executor { it.run() }
        val assets = EngineAssets(::load, {}, null)
        val lexicon = UserLexicon(userDir = null, onError = {}, additions = { additions }, assets = assets, dropSessions = {})
        val registry = wired(now, assets, lexicon)
        val wubi = WUBI
        val shared = registry.shared(registry.table(wubi), wubi)
        assertEquals(listOf("wqvb" to "你好"), shared.words("wqvb"))
        assertTrue(lexicon.addWord("好你", "hao ni"))
        assertTrue(lexicon.addWord("你好", "li hao"))
        assertEquals(listOf("vbwq" to "好你"), shared.words("vbwq"))
        assertEquals(listOf("wqvb" to "你好"), shared.words("wqvb"))
    }

    @Test
    fun anEmojiBlockedInPinyinIsNoWordATableLeavesOut() {
        val now = Executor { it.run() }
        val assets = EngineAssets(::load, {}, null)
        val lexicon = UserLexicon(userDir = null, onError = {}, additions = { null }, assets = assets, dropSessions = {})
        val registry = wired(now, assets, lexicon)
        val shared = registry.shared(registry.table(WUBI), WUBI)
        lexicon.addListed(emptyList(), listOf("👍🏻" to "hao", "你好" to "ni hao", "ao" to "a o"))
        assertTrue(lexicon.isBlocked("👍🏻"))
        assertFalse(shared.blocked("👍🏻"))
        assertTrue(shared.blocked("你好"))
        assertTrue(shared.blocked("ao"))
    }

    // 一二's vava is 七上八下's alone, which commits itself; 你好's wqvb the table has nothing under
    @Test
    fun aWordWhoseCodeATableCommitsAloneIsToldWithThatWord() {
        val now = Executor { it.run() }
        val assets = EngineAssets(::load, {}, null)
        val lexicon = UserLexicon(userDir = null, onError = {}, additions = { null }, assets = assets, dropSessions = {})
        val registry = TableRegistry(blobs = ::load, onError = {}, coding = now, assets = assets, lexicon = lexicon)
        val wubi = WUBI
        val words = listOf("一二", "你好", "一")
        assertEquals(mapOf("一二" to "七上八下"), registry.sameCode(wubi, TableOptions.WUBI, words))
        assertEquals(emptyMap<String, String>(), registry.sameCode(wubi, TableOptions.WUBI.copy(autoSelect = false), words))
    }

    @Test
    fun theListedWordsAreCodedInTheBackgroundAndTillThenATableHasAddedOnesOnly() {
        // the pending state, on a queued executor rather than in place
        val additions = Engines.Additions("", "d", indexes = indexDir(), dictionary = compiled(::load) { sequenceOf("你好 ni'hao 0") })
        val now = Executor { it.run() }
        val queued = ArrayList<Runnable>()
        val assets = EngineAssets(::load, {}, null)
        val lexicon = UserLexicon(userDir = null, onError = {}, additions = { additions }, assets = assets, dropSessions = {})
        val registry = wired({ queued += it }, assets, lexicon)
        val shared = registry.shared(registry.table(WUBI), WUBI)
        assertEquals(emptyList<Pair<String, String>>(), shared.words("wqvb"))
        assertTrue(lexicon.addWord("好你", "hao ni"))
        assertEquals(listOf("vbwq" to "好你"), shared.words("vbwq"))
        while (queued.isNotEmpty()) queued.removeAt(0).run()
        assertEquals(listOf("wqvb" to "你好"), shared.words("wqvb"))
        assertEquals(listOf("vbwq" to "好你"), shared.words("vbwq"))
        assertTrue(queued.isEmpty())
    }

    @Test
    fun theListedWordsStartCodingAsATableComesUpNotAtItsFirstKey() {
        // coded at the first key, an imported word came too late for the first code
        val additions = Engines.Additions("", "d", indexes = indexDir(), dictionary = compiled(::load) { sequenceOf("你好 ni'hao 0") })
        val now = Executor { it.run() }
        val queued = ArrayList<Runnable>()
        val assets = EngineAssets(::load, {}, null)
        val lexicon = UserLexicon(userDir = null, onError = {}, additions = { additions }, assets = assets, dropSessions = {})
        val registry = wired({ queued += it }, assets, lexicon)
        registry.prepare(WUBI)   // no session yet: nothing loaded, nothing coded
        assertTrue(queued.isEmpty())
        val shared = registry.shared(registry.table(WUBI), WUBI)
        registry.prepare(WUBI)
        while (queued.isNotEmpty()) queued.removeAt(0).run()
        assertEquals(listOf("wqvb" to "你好"), shared.words("wqvb"))
        assertTrue(queued.isEmpty())
    }

    /** A start whose 五笔 has 你好 saved at wqvb, pinyin's own word too, as an older build converted it. */
    private fun savedNihao(): UserFiles = MemoryFiles().apply { olderPhrases(this, "wqvb" to "你好", own = mapOf("你好" to "ni hao")) }

    private fun Engines.wubi(keys: String): List<String> {
        settings = wubiShown
        return type(WUBI, keys).candidates
    }

    // the user word list holds pinyin's word and 五笔's apart: each deleted alone, the other kept
    @Test
    @Uat("WD-66")
    fun theWordListDeletesATablesWordOrPinyinsAlone() {
        val dir = savedNihao()
        Engines(::load, dir).use { engines ->
            val listed = engines.userWords().filter { it.text == "你好" }
            assertEquals(listOf("" to "ni hao", WUBI to "wqvb"), listed.map { it.table to it.pinyin }.sortedBy { it.first })
            engines.removeWords(listed.filter { it.table.isEmpty() })
        }
        Engines(::load, dir).use { engines ->
            assertTrue("你好" in engines.wubi("wqvb"))
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            val left = engines.userWords().filter { it.text == "你好" }
            assertEquals(listOf(WUBI), left.map { it.table })
            engines.removeWords(left)
        }
        Engines(::load, dir).use { engines ->
            assertFalse("你好" in engines.wubi("wqvb"))
            assertTrue(engines.userWords().none { it.text == "你好" })
        }
    }

    // the long-press reads 删除 (and the notice 已删除) for a word the table remembered, as for pinyin's own; not 忘掉学到的
    @Test
    @Uat("TBL-11c")
    fun aRememberedWordIsDeletedNotForgotten() {
        val dir = MemoryFiles().apply { olderPhrases(this, "wqvb" to "你好") }
        Engines(::load, dir).use { engines ->
            val at = engines.wubi("wqvb").indexOf("你好")
            val menu = engines.learning(WUBI, LearningOp.Menu(at, "你好")) as LearningResult.Menu
            assertEquals(listOf(Row.DELETE), LearningMenu.rows(menu).filter { it == Row.DELETE || it == Row.FORGET })
        }
    }

    // everything learned cleared: what an older build's table saved (now the record's rows) goes with it, not back at the next start
    @Test
    fun clearingEverythingLearnedTakesThePhrasesATableSaved() {
        val dir = savedNihao()
        Engines(::load, dir).use { engines ->
            assertTrue("你好" in engines.wubi("wqvb"))
            assertTrue(engines.learning(null, LearningOp.ClearLearned(all = true)) is LearningResult.Cleared)
        }
        Engines(::load, dir).use { assertFalse("你好" in it.wubi("wqvb")) }
    }

    @Test
    fun aWordATableRememberedStaysThereForgottenInPinyin() {
        val dir = savedNihao()
        Engines(::load, dir).use { engines ->
            assertTrue("你好" in engines.wubi("wqvb"))
            engines.type(InputMethodCatalog.PINYIN, "nihao")
            val at = engines.candidates(InputMethodCatalog.PINYIN, 0, 20).indexOfFirst { it.text == "你好" }
            assertTrue(Offer.FORGET in engines.offers(InputMethodCatalog.PINYIN, at))
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.FORGET, at)
        }
        // the table's row is no pinyin word: still there at the next start
        Engines(::load, dir).use { assertTrue("你好" in it.wubi("wqvb")) }
    }

    @Test
    fun aWordAddedPickedInATableAndRemovedIsNotBackThereAtTheNextStart() {
        val dir = MemoryFiles()
        // the listed words coded at once, as a slow machine typed before they were
        Engines(::load, dir, warmUps = { it.run() }).use { engines ->
            assertTrue(engines.addWord("你好", "ni hao"))
            // the user's word, coded by the table's rule: its pick is the table's, read back as a phrase saved
            val offered = engines.wubi("wqvb")
            assertTrue("你好" in offered)
            assertEquals("你好", engines.onEvent(WUBI, EngineEvent.PICK, offered.indexOf("你好")).commit)
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            engines.removeWord(UserWord("你好", "ni hao", UserWord.Kind.ADDED))
        }
        Engines(::load, dir).use { engines ->
            assertFalse("你好" in engines.wubi("wqvb"))
            assertEquals(emptyList<UserWord>(), engines.userWords())
        }
    }

    @Test
    fun aWordForgottenElsewhereStaysWhereATableRemembersIt() {
        // pinyin's delete is pinyin's: a table deletes its own word, undoably, under its code alone
        val dir = savedNihao()
        val log = LearnLog(dir.file(LearnLog.FILE)).apply { open() }
        log.forget("你好")
        assertTrue(log.state.tableWords.keys.any { it.third == "你好" })
        log.close()
    }

    @Test
    fun aFieldStartingInATableStartsCodingTheWordsListedSinceBeforeItsFirstKey() {
        // dictionaries changed by a reload are coded as the next field starts, not at the first key
        var lines = sequenceOf<String>()
        var name = "a"
        val queued = ArrayList<Runnable>()
        fun runAll() { while (queued.isNotEmpty()) queued.removeAt(0).run() }
        Engines(::load, null, additions = { Engines.Additions("", name, indexes = indexDir(), dictionary = compiled(::load) { lines }) }, warmUps = { queued += it }).use { engines ->
            engines.settings = wubiShown
            engines.type(WUBI, "w")
            runAll()
            engines.context(WUBI, "", cleared = true)
            lines = sequenceOf("你好 ni'hao 0")
            name = "b"
            engines.reload()
            runAll()
            engines.context(WUBI, "")
            assertTrue(queued.isNotEmpty())
            runAll()
            assertTrue("你好" in engines.type(WUBI, "wqvb").candidates)
        }
    }

    @Test
    fun theMergedFilesWordsComeFromAnIndexOnDiskBuiltInTheBackgroundAndKept() {
        // coded once into the table's index, not listed as objects (which ran out of heap)
        val file = mergedFile("merged.ymd", "s", "好你 hao'ni 0.5", "门你 men'ni 0.2")
        val queued = ArrayList<Runnable>()
        val assets = EngineAssets(::load, {}, null)
        val lexicon = mergedLexicon(assets, file)
        fun registry() = TableRegistry(blobs = ::load, onError = {}, coding = { queued += it }, assets = assets, lexicon = lexicon, indexes = { root })
        val first = registry()
        val shared = first.shared(first.table(WUBI), WUBI)
        assertEquals(emptyList<Pair<String, String>>(), shared.words("vbwq"))
        assertFalse(shared.leadsAnywhere("vbw"))
        while (queued.isNotEmpty()) queued.removeAt(0).run()
        assertEquals(listOf("vbwq" to "好你"), shared.words("vbwq"))
        assertTrue(shared.leadsAnywhere("vbw"))
        assertTrue(root.file("merged.$WUBI.ymc").exists())
        assertTrue(lexicon.addWord("好你", "hao ni"))
        assertEquals(listOf("vbwq" to "好你"), shared.words("vbwq"))
        // the next start opens the index kept, built for the same table and file
        val modified = File(folder.root, "merged.$WUBI.ymc").lastModified()
        val again = registry()
        val shared2 = again.shared(again.table(WUBI), WUBI)
        shared2.prepare()
        while (queued.isNotEmpty()) queued.removeAt(0).run()
        assertEquals(listOf("vbwq" to "好你"), shared2.words("vbwq"))
        assertEquals(modified, File(folder.root, "merged.$WUBI.ymc").lastModified())
    }

    @Test
    fun anIndexOfAnotherMergedFileIsBuiltAgain() {
        val queued = ArrayList<Runnable>()
        val assets = EngineAssets(::load, {}, null)
        fun typed(file: DictionaryFile, code: String): List<Pair<String, String>> {
            val registry = TableRegistry(blobs = ::load, onError = {}, coding = { queued += it }, assets = assets, lexicon = mergedLexicon(assets, file), indexes = { root })
            val shared = registry.shared(registry.table(WUBI), WUBI)
            shared.prepare()
            while (queued.isNotEmpty()) queued.removeAt(0).run()
            return shared.words(code)
        }
        assertEquals(listOf("vbwq" to "好你"), typed(mergedFile("a.ymd", "a", "好你 hao'ni 0.5"), "vbwq"))
        // the same path compiled again: its stamp alone tells the index is another's
        val newer = mergedFile("a.ymd", "b", "你好 ni'hao 0.2")
        assertEquals(emptyList<Pair<String, String>>(), typed(newer, "vbwq"))
        assertEquals(listOf("wqvb" to "你好"), typed(newer, "wqvb"))
    }

    @Test
    fun aWordAddedByHandThatTheFileHasIsOfferedWithoutAnIndex() {
        val assets = EngineAssets(::load, {}, null)
        val lexicon = mergedLexicon(assets, mergedFile("merged.ymd", "s", "好你 hao'ni 0.5"))
        val registry = TableRegistry(blobs = ::load, onError = {}, coding = { it.run() }, assets = assets, lexicon = lexicon)
        val shared = registry.shared(registry.table(WUBI), WUBI)
        assertTrue(lexicon.addWord("好你", "hao ni"))
        assertEquals(listOf("vbwq" to "好你"), shared.words("vbwq"))
    }

    private fun mergedFile(name: String, stamp: String, vararg lines: String): DictionaryFile {
        val f = File(folder.root, name)
        DictionaryCompiler.compile(listOf(lines.asSequence()), f, stamp = stamp)
        return DictionaryFile.open(JvmFile(f))
    }

    private fun mergedLexicon(assets: EngineAssets, file: DictionaryFile) =
        UserLexicon(userDir = null, onError = {}, additions = { Engines.Additions("", "d", dictionary = { file }) }, assets = assets, dropSessions = {})
}
