/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.table.TableUser.TableLog
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

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aWordAddedInAnotherReadingOfOneListedIsNotCodedTwice() {
        val additions = Engines.Additions("", "d") { sequenceOf("你好 ni'hao 0") }
        val now = Executor { it.run() }
        val assets = EngineAssets(::load, {}, null)
        val lexicon = UserLexicon(null, {}, { null }, { additions }, assets, now, forgetInTables = {}, dropSessions = {})
        val registry = TableRegistry(::load, null, {}, now, now, assets, lexicon)
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

    @Test
    fun theListedWordsAreCodedInTheBackgroundAndTillThenATableHasAddedOnesOnly() {
        // F2443: the pending state, on a queued executor rather than in place
        val additions = Engines.Additions("", "d") { sequenceOf("你好 ni'hao 0") }
        val now = Executor { it.run() }
        val queued = ArrayList<Runnable>()
        val assets = EngineAssets(::load, {}, null)
        val lexicon = UserLexicon(null, {}, { null }, { additions }, assets, now, forgetInTables = {}, dropSessions = {})
        val registry = TableRegistry(::load, null, {}, now, { queued += it }, assets, lexicon)
        val shared = registry.shared(registry.table(WUBI), WUBI)
        assertEquals(emptyList<Pair<String, String>>(), shared.words("wqvb"))
        assertTrue(lexicon.addWord("好你", "hao ni"))
        assertEquals(listOf("vbwq" to "好你"), shared.words("vbwq"))
        assertEquals(1, queued.size)
        queued.removeAt(0).run()
        assertEquals(listOf("wqvb" to "你好"), shared.words("wqvb"))
        assertEquals(listOf("vbwq" to "好你"), shared.words("vbwq"))
        assertTrue(queued.isEmpty())
    }

    @Test
    fun theListedWordsStartCodingAsATableComesUpNotAtItsFirstKey() {
        // UAT TBL-12a: coded at the first key, an imported word came too late for the first code
        val additions = Engines.Additions("", "d") { sequenceOf("你好 ni'hao 0") }
        val now = Executor { it.run() }
        val queued = ArrayList<Runnable>()
        val assets = EngineAssets(::load, {}, null)
        val lexicon = UserLexicon(null, {}, { null }, { additions }, assets, now, forgetInTables = {}, dropSessions = {})
        val registry = TableRegistry(::load, null, {}, now, { queued += it }, assets, lexicon)
        registry.prepare(WUBI)   // no session yet: nothing loaded, nothing coded
        assertTrue(queued.isEmpty())
        val shared = registry.shared(registry.table(WUBI), WUBI)
        registry.prepare(WUBI)
        assertEquals(1, queued.size)
        queued.removeAt(0).run()
        // the first key finds them coded
        assertEquals(listOf("wqvb" to "你好"), shared.words("wqvb"))
        assertTrue(queued.isEmpty())
    }

    @Test
    fun aTableHasTheModelsWordsIndexedInTheBackgroundAsItOpens() {
        // what learnSaved looks a saved phrase up in: not made on the input thread at the first save
        val later = ArrayList<Runnable>()
        val background = Executor { later += it }
        val assets = EngineAssets(::load, {}, null)
        val lexicon = UserLexicon(null, {}, { null }, { null }, assets, background, forgetInTables = {}, dropSessions = {})
        val registry = TableRegistry(::load, null, {}, background, Executor { it.run() }, assets, lexicon)
        val table = registry.table(WUBI)
        assertEquals(0, later.size)
        registry.shared(table, WUBI)
        assertEquals(1, later.size)
        // once, as the table opens
        registry.shared(table, WUBI)
        assertEquals(1, later.size)
        later.single().run()
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
        assertTrue(dir.resolve(Engines.USER_PINYIN).delete())
        Engines(::load, dir).use { assertFalse("你好" in it.wubi("wqvb")) }
    }

    @Test
    fun aWordAddedPickedInATableAndRemovedIsNotBackThereAtTheNextStart() {
        val dir = folder.newFolder("engine")
        Engines(::load, dir).use { engines ->
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
        val lexicon = UserLexicon(null, {}, { null }, { null }, assets, now, forgetInTables = {}, dropSessions = {})
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
        Engines(::load, null, additions = { Engines.Additions("", name) { lines } }, warmUps = { queued += it }).use { engines ->
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
}
