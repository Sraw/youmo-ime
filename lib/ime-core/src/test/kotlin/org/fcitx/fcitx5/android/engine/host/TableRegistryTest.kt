/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.fcitx.fcitx5.android.engine.table.TableUser.TableLog
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

/**
 * The user's words as [TableRegistry] codes them for a table, below what a session makes of them,
 * and what a table forgets of what is forgotten elsewhere ([TableRegistry.forgetInTables]).
 */
class TableRegistryTest : EnginesFixture() {

    // wired as Engines wires it: the merged dictionaries coded from their file, into an index of the table's
    private fun wired(warmUps: Executor, assets: EngineAssets, lexicon: UserLexicon): TableRegistry {
        val indexes = indexDir()
        return TableRegistry(::load, null, {}, { it.run() }, warmUps, assets, lexicon, indexes = { indexes })
    }

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aWordAddedInAnotherReadingOfOneListedIsNotCodedTwice() {
        val additions = Engines.Additions("", "d", indexes = indexDir(), dictionary = compiled(::load) { sequenceOf("你好 ni'hao 0") })
        val now = Executor { it.run() }
        val assets = EngineAssets(::load, {}, null)
        val lexicon = UserLexicon(null, {}, { null }, { additions }, assets, forgetInTables = {}, dropSessions = {})
        val registry = wired(now, assets, lexicon)
        val wubi = "engine-wubi"
        val shared = registry.shared(registry.table(wubi), wubi)
        assertEquals(listOf("wqvb" to "你好"), shared.words("wqvb"))
        assertTrue(lexicon.addWord("好你", "hao ni"))
        assertTrue(lexicon.addWord("你好", "li hao"))
        assertEquals(listOf("vbwq" to "好你"), shared.words("vbwq"))
        // once is the registry's own doing, nothing the user sees: a session shows a text once however often
        // it is offered (EnginesTest's ...OfferedOnce). Twice would be the word coded again and ranked twice on each key
        assertEquals(listOf("wqvb" to "你好"), shared.words("wqvb"))
    }

    // D8 / TBL-12: 一二's vava is 七上八下's alone, which commits itself; 你好's wqvb the table has nothing under
    @Test
    fun aWordWhoseCodeATableCommitsAloneIsToldWithThatWord() {
        val now = Executor { it.run() }
        val assets = EngineAssets(::load, {}, null)
        val lexicon = UserLexicon(null, {}, { null }, { null }, assets, forgetInTables = {}, dropSessions = {})
        val registry = TableRegistry(::load, null, {}, now, now, assets, lexicon)
        val wubi = "engine-wubi"
        val words = listOf("一二", "你好", "一")
        assertEquals(mapOf("一二" to "七上八下"), registry.sameCode(wubi, TableOptions.WUBI, words))
        // nothing commits itself: every word is offered
        assertEquals(emptyMap<String, String>(), registry.sameCode(wubi, TableOptions.WUBI.copy(autoSelect = false), words))
    }

    // review C S5: the learning record opened before a table's older log is, which a large one's compaction would cut
    @Test
    fun theLearningRecordIsOpenedBeforeATablesLog() {
        val now = Executor { it.run() }
        val assets = EngineAssets(::load, {}, null)
        val lexicon = UserLexicon(null, {}, { null }, { null }, assets, forgetInTables = {}, dropSessions = {})
        val dir = folder.newFolder("engine")
        val table = File(dir, Engines.userTable("engine-wubi"))
        // whether the table's log was there yet when the record opened: not, if the record came first
        val order = ArrayList<Boolean>()
        val registry = TableRegistry(::load, dir, {}, now, now, assets, lexicon, { false }, beforeOpen = { order += table.exists() })
        registry.table("engine-wubi")
        registry.table("engine-wubi")
        assertEquals(listOf(false), order)
        assertTrue(table.exists())
        registry.stores().forEach { it.close() }
    }

    // review R11 F3201: 全部清空 forgets an older build's pick of a table's own entry, which no saved phrase stands for
    @Test
    fun clearingSavedForgetsAnOlderBuildsPicks() {
        val now = Executor { it.run() }
        val assets = EngineAssets(::load, {}, null)
        val lexicon = UserLexicon(null, {}, { null }, { null }, assets, forgetInTables = {}, dropSessions = {})
        val dir = folder.newFolder("engine")
        // one of the table's own entries: a pick of an entry is kept as no phrase, so clearing the saved ones misses it
        val entries = TableRegistry(::load, null, {}, now, now, assets, lexicon).table("engine-wubi").dictionary
        val code = entries.code(0)
        val text = entries.text(0)
        val log = File(dir, Engines.userTable("engine-wubi"))
        log.writeBytes(TableLog.FORMAT.header() + TableLog.picked(code, text, 3))
        val registry = TableRegistry(::load, dir, {}, now, now, assets, lexicon, { false })
        registry.clearSaved()
        registry.stores().forEach { it.close() }
        val forgot = TableLog.forgot(code, text)
        val bytes = log.readBytes()
        assertTrue((0..bytes.size - forgot.size).any { i -> forgot.indices.all { bytes[i + it] == forgot[it] } })
    }

    // review-int-records N8: pinyin told of a table's phrases in memory only (the record did not open) is told again next time
    @Test
    fun aTableTellsPinyinOnceOnlyWhereTheRecordIsKept() {
        val now = Executor { it.run() }
        val assets = EngineAssets(::load, {}, null)
        for (kept in listOf(false, true)) {
            // each its own lexicon, so the second is told afresh; what it makes the user's own goes to
            // the learning record (Engines wires [owned] to it), so it is seen there
            val owned = ArrayList<String>()
            val lexicon = UserLexicon(
                null, {}, { null }, { null }, assets, forgetInTables = {}, dropSessions = {},
                owned = { words -> words.keys.mapTo(owned) { it.text }.size },
            )
            val dir = folder.newFolder("engine-$kept")
            olderPhrases(dir, "vbwq" to "好你")
            val registry = TableRegistry(::load, dir, {}, now, now, assets, lexicon, { false }, { kept })
            registry.shared(registry.table("engine-wubi"), "engine-wubi")
            assertEquals(listOf("好你"), owned)
            assertEquals(kept, File(dir, Engines.userTable("engine-wubi") + Engines.TOLD).exists())
            registry.stores().forEach { it.close() }
        }
    }

    @Test
    fun theListedWordsAreCodedInTheBackgroundAndTillThenATableHasAddedOnesOnly() {
        // F2443: the pending state, on a queued executor rather than in place
        val additions = Engines.Additions("", "d", indexes = indexDir(), dictionary = compiled(::load) { sequenceOf("你好 ni'hao 0") })
        val now = Executor { it.run() }
        val queued = ArrayList<Runnable>()
        val assets = EngineAssets(::load, {}, null)
        val lexicon = UserLexicon(null, {}, { null }, { additions }, assets, forgetInTables = {}, dropSessions = {})
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
        val lexicon = UserLexicon(null, {}, { null }, { additions }, assets, forgetInTables = {}, dropSessions = {})
        val registry = wired({ queued += it }, assets, lexicon)
        registry.prepare(WUBI)   // no session yet: nothing loaded, nothing coded
        assertTrue(queued.isEmpty())
        val shared = registry.shared(registry.table(WUBI), WUBI)
        registry.prepare(WUBI)
        while (queued.isNotEmpty()) queued.removeAt(0).run()
        // the first key finds them coded
        assertEquals(listOf("wqvb" to "你好"), shared.words("wqvb"))
        assertTrue(queued.isEmpty())
    }

    /** A start whose 五笔 has 你好 saved at wqvb; pinyin learns it as 五笔 opens ([TableRegistry.shared]). */
    private fun savedNihao(): File = folder.newFolder("engine").apply {
        resolve(Engines.userTable(WUBI)).writeBytes(TableLog.FORMAT.header() + TableLog.saved("wqvb", "你好"))
    }

    private fun Engines.wubi(keys: String): List<String> {
        settings = wubiShown
        return type(WUBI, keys).candidates
    }

    @Test
    fun aPhraseForgottenElsewhereIsForgottenByATableNotTypedWithSinceTheStart() {
        val dir = savedNihao()
        Engines(::load, dir).use { assertTrue("你好" in it.wubi("wqvb")) }
        Engines(::load, dir).use { engines ->
            val learned = engines.userWords().filter { it.text == "你好" }
            assertEquals(1, learned.size)
            engines.removeWords(learned)
        }
        Engines(::load, dir).use { engines ->
            assertFalse("你好" in engines.wubi("wqvb"))
            assertTrue(engines.userWords().none { it.text == "你好" })
        }
    }

    @Test
    fun aPhraseATableSavedForgottenInPinyinIsForgottenByTheTableToo() {
        val dir = savedNihao()
        Engines(::load, dir).use { engines ->
            assertTrue("你好" in engines.wubi("wqvb"))
            engines.type(Engines.PINYIN, "nihao")
            val at = engines.candidates(Engines.PINYIN, 0, 20).indexOfFirst { it.text == "你好" }
            assertTrue(Offer.FORGET in engines.offers(Engines.PINYIN, at))
            engines.onEvent(Engines.PINYIN, EngineEvent.FORGET, at)
        }
        // pinyin's words gone, the table offers only what it saved itself
        assertTrue(dir.resolve(LearnLog.FILE).delete())
        Engines(::load, dir).use { assertFalse("你好" in it.wubi("wqvb")) }
    }

    @Test
    fun aWordAddedPickedInATableAndRemovedIsNotBackThereAtTheNextStart() {
        val dir = folder.newFolder("engine")
        // the listed words coded at once: added before pinyin's model was made, the word is one of
        // them, and in the background a slow machine typed before they were coded
        Engines(::load, dir, warmUps = { it.run() }).use { engines ->
            assertTrue(engines.addWord("你好", "ni hao"))
            // the user's word, coded by the table's rule: its pick is the table's, read back as a phrase saved
            val offered = engines.wubi("wqvb")
            assertTrue("你好" in offered)
            assertEquals("你好", engines.onEvent(WUBI, EngineEvent.PICK, offered.indexOf("你好")).commit)
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            engines.removeWord(Engines.UserWord("你好", "ni hao", Engines.UserWord.Kind.ADDED))
        }
        Engines(::load, dir).use { engines ->
            assertFalse("你好" in engines.wubi("wqvb"))
            assertEquals(emptyList<Engines.UserWord>(), engines.userWords())
        }
    }

    @Test
    fun aCharacterForgottenElsewhereOpensNoTableWithALogNotOpenYet() {
        // a table saves and shares no single character: forgetting one has nothing to replay a log for
        val dir = savedNihao()
        val opened = ArrayList<String>()
        val now = Executor { it.run() }
        val assets = EngineAssets(::load, {}, null)
        val lexicon = UserLexicon(null, {}, { null }, { null }, assets, forgetInTables = {}, dropSessions = {})
        val registry = TableRegistry({ opened += it; load(it) }, dir, {}, now, now, assets, lexicon)
        registry.forgetInTables(setOf("好"))
        assertEquals(emptyList<String>(), opened.filter { it.startsWith(Engines.TABLE_DIR) })
        registry.forgetInTables(setOf("你好"))
        assertEquals(1, opened.count { it.startsWith(Engines.TABLE_DIR) })
    }

    private companion object {
        const val WUBI = "engine-wubi"
    }

    @Test
    fun aFieldStartingInATableStartsCodingTheWordsListedSinceBeforeItsFirstKey() {
        // Engines.context → TableRegistry.prepare, end to end: dictionaries changed by a reload are
        // coded as the next field starts in 五笔, not at its first key
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
        // design D4: coded once into the table's index, not listed as objects (0.3.1 ran out of heap so)
        val file = mergedFile("merged.ymd", "s", "好你 hao'ni 0.5", "门你 men'ni 0.2")
        val queued = ArrayList<Runnable>()
        val assets = EngineAssets(::load, {}, null)
        val lexicon = mergedLexicon(assets, file)
        fun registry() = TableRegistry(::load, null, {}, { it.run() }, { queued += it }, assets, lexicon, indexes = { folder.root })
        val first = registry()
        val shared = first.shared(first.table(WUBI), WUBI)
        assertEquals(emptyList<Pair<String, String>>(), shared.words("vbwq"))
        assertFalse(shared.leadsAnywhere("vbw"))
        while (queued.isNotEmpty()) queued.removeAt(0).run()
        assertEquals(listOf("vbwq" to "好你"), shared.words("vbwq"))
        assertTrue(shared.leadsAnywhere("vbw"))
        assertTrue(File(folder.root, "merged.$WUBI.ymc").exists())
        // added by hand once the index has it: offered once
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

    // review F3295: the file merged again (another word, another stamp) at the same index path: built again,
    // not the old index's places read into the new file
    @Test
    fun anIndexOfAnotherMergedFileIsBuiltAgain() {
        val queued = ArrayList<Runnable>()
        val assets = EngineAssets(::load, {}, null)
        fun typed(file: DictionaryFile, code: String): List<Pair<String, String>> {
            val registry = TableRegistry(::load, null, {}, { it.run() }, { queued += it }, assets, mergedLexicon(assets, file), indexes = { folder.root })
            val shared = registry.shared(registry.table(WUBI), WUBI)
            shared.prepare()
            while (queued.isNotEmpty()) queued.removeAt(0).run()
            return shared.words(code)
        }
        assertEquals(listOf("vbwq" to "好你"), typed(mergedFile("a.ymd", "a", "好你 hao'ni 0.5"), "vbwq"))
        // the same path compiled again: its stamp alone tells the index is another's (review F3432)
        val newer = mergedFile("a.ymd", "b", "你好 ni'hao 0.2")
        assertEquals(emptyList<Pair<String, String>>(), typed(newer, "vbwq"))
        assertEquals(listOf("wqvb" to "你好"), typed(newer, "wqvb"))
    }

    // review F3303: a word added by hand that the merged file has too, while no index is kept: offered still
    @Test
    fun aWordAddedByHandThatTheFileHasIsOfferedWithoutAnIndex() {
        val assets = EngineAssets(::load, {}, null)
        val lexicon = mergedLexicon(assets, mergedFile("merged.ymd", "s", "好你 hao'ni 0.5"))
        val registry = TableRegistry(::load, null, {}, { it.run() }, { it.run() }, assets, lexicon)
        val shared = registry.shared(registry.table(WUBI), WUBI)
        assertTrue(lexicon.addWord("好你", "hao ni"))
        assertEquals(listOf("vbwq" to "好你"), shared.words("vbwq"))
    }

    private fun mergedFile(name: String, stamp: String, vararg lines: String): DictionaryFile {
        val f = File(folder.root, name)
        DictionaryCompiler.compile(listOf(lines.asSequence()), f, stamp = stamp)
        return DictionaryFile.open(f)
    }

    private fun mergedLexicon(assets: EngineAssets, file: DictionaryFile) =
        UserLexicon(null, {}, { null }, { Engines.Additions("", "d", dictionary = { file }) }, assets, forgetInTables = {}, dropSessions = {})
}
