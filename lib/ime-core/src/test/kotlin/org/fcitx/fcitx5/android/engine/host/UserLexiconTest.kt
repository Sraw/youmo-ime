/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.data.CodeTableReader
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.engine.user.UserLog
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.WordListFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A word taken off the added words: forgotten if no dictionary has it, so the log's picks do not
 * make it the user's own again, whatever the log does next; kept, in pinyin and the tables, if
 * one does; while a dictionary, or what the user added, does not read, nothing is forgotten. A
 * table's pick of a word from outside it makes no phrase of the table's. A word whose first
 * character reads in it as it never does alone is imported and learned from a table's phrases as the
 * dictionary reads it, not as its pieces read. A phrase an older build of a table saved is learned in
 * pinyin, as its pieces read if no dictionary reads it; a table's picks leave pinyin's order.
 */
class UserLexiconTest : EnginesFixture() {

    @get:Rule
    val folder = TemporaryFolder()

    private fun Engines.pick(keys: String, text: String) {
        type(Engines.PINYIN, keys)
        val at = candidates(Engines.PINYIN, 0, 20).indexOfFirst { it.text == text }
        assertEquals(text, onEvent(Engines.PINYIN, EngineEvent.PICK, at).commit)
        onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
    }

    private fun Engines.pickUntilFirst(keys: String, text: String) {
        repeat(MAX_PICKS) {
            val first = type(Engines.PINYIN, keys).candidates.first()
            onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            if (first != text) pick(keys, text)
        }
        assertEquals(text, type(Engines.PINYIN, keys).candidates.first())
        onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
    }

    // pinyin's data the fixture's, or [pinyin]; 五笔's [table]
    private fun withTable(dir: File, additions: Engines.Additions?, pinyin: ByteArray? = null, table: String = TABLE) = Engines(
        {
            when {
                pinyin != null && it == Engines.PINYIN_DATA -> ByteBuffer.wrap(pinyin)
                it == "${Engines.TABLE_DIR}/wbx.data" -> ByteBuffer.wrap(CodeTableReader().apply { read(table.reader().buffered(), "table") }.builder.build().toByteArray())
                else -> load(it)
            }
        },
        dir,
        // the user's words coded for the table at once, not in the background
        warmUps = { it.run() },
        additions = { additions },
        // a pick, not a code's one candidate committing itself
    ).apply { settings = settings.copy(tables = mapOf("Wubi" to TableSettings(autoSelect = false))) }

    private fun Engines.firstInTable(): String {
        val first = type(WUBI, "abcd").candidates.first()
        onEvent(WUBI, EngineEvent.RESET, 0)
        return first
    }

    private fun Engines.pickInTable(text: String, code: String = "abcd") {
        val at = type(WUBI, code).candidates.indexOf(text)
        assertEquals(text, onEvent(WUBI, EngineEvent.PICK, at).commit)
        onEvent(WUBI, EngineEvent.RESET, 0)
    }

    @Test
    fun aWordOfTheDictionaryTakenOffWhileBlockedKeepsWhatWasLearnedOfIt() {
        val dir = folder.newFolder("engine")
        Engines(::load, dir).use { engines ->
            assertTrue(engines.addWord("拟", "ni"))
            engines.pickUntilFirst("ni", "拟")
            // added and blocked at once: blocking leaves the added words be
            assertTrue(engines.blockWord("拟", "ni"))
            engines.removeWord(Engines.UserWord("拟", "ni", Engines.UserWord.Kind.ADDED))
            engines.removeWord(Engines.UserWord("拟", "ni", Engines.UserWord.Kind.BLOCKED))
            assertEquals("拟", engines.type(Engines.PINYIN, "ni").candidates.first())
        }
        Engines(::load, dir).use { engines ->
            assertEquals("拟", engines.type(Engines.PINYIN, "ni").candidates.first())
        }
    }

    @Test
    fun withNoLogWhatWasLearnedOfASelfMadeWordTakenOffIsForgotten() {
        Engines(::load, null).use { engines ->
            // the decoder's scores alone: the sentence model would weigh the readings again
            engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
            assertTrue(engines.addWord("妮浩", "nihao"))
            engines.pickUntilFirst("nihao", "妮浩")
            engines.removeWord(Engines.UserWord("妮浩", "ni hao", Engines.UserWord.Kind.ADDED))
            // typed till the next start, with no log to make the words again from, but as never picked
            assertNotEquals("妮浩", engines.type(Engines.PINYIN, "nihao").candidates.first())
        }
    }

    @Test
    fun aWordFromOutsideTheTablePickedThereIsNotMadeTheTablesOwn() {
        val dir = folder.newFolder("engine")
        withTable(dir, null).use { engines ->
            // no dictionary has 你好: the user's own, coded by the table's rule, after its own entry (U-28)
            assertTrue(engines.addWord("你好", "ni hou"))
            assertEquals(listOf("妤", "你好"), engines.type(WUBI, "abcd").candidates)
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            engines.pickInTable("你好")
            engines.removeWord(Engines.UserWord("你好", "ni hou", Engines.UserWord.Kind.ADDED))
            // taken off the user's words: the table's pick of it no phrase of its own (自动造词 is gone)
            assertEquals(listOf("妤"), engines.type(WUBI, "abcd").candidates)
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
        }
        withTable(dir, null).use { engines ->
            assertEquals(listOf("妤"), engines.type(WUBI, "abcd").candidates)
        }
    }

    @Test
    fun aWordImportedWithNoPinyinIsReadAsItsFirstCharacterNeverIsAlone() {
        withTable(folder.newFolder("engine"), null, LIAO_JIE_PINYIN).use { engines ->
            // the user's own export: added as it was, not imported (U-101)
            assertEquals(Engines.Imported(added = 1, blocked = 0, unread = 0), engines.importWords(sequenceOf(WordListFiles.EXPORT_MARK, "了解")))
            assertEquals(listOf("liao jie"), engines.userWords(Engines.UserWord.Kind.ADDED).map { it.pinyin })
        }
    }

    @Test
    fun aWordBlockedFromATableIsBlockedUnderItsCodeAlone() {
        val dir = folder.newFolder("engine")
        olderPhrases(dir, "abcd" to "了解")
        withTable(dir, null, LIAO_JIE_PINYIN, LIAO_JIE_TABLE).use { engines ->
            val at = engines.type(WUBI, "abcd").candidates.indexOf("了解")
            assertTrue(Offer.BLOCK in engines.offers(WUBI, at))
            engines.onEvent(WUBI, EngineEvent.BLOCK, at)
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            assertEquals(listOf("妤"), engines.type(WUBI, "abcd").candidates)
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            // not blocked in pinyin, nor anywhere else (U-28)
            assertEquals(emptyList<String>(), engines.userWords(Engines.UserWord.Kind.BLOCKED).map { it.text })
        }
        withTable(dir, null, LIAO_JIE_PINYIN, LIAO_JIE_TABLE).use { engines ->
            assertEquals(listOf("妤"), engines.type(WUBI, "abcd").candidates)
        }
    }

    // 了解 and 解了 saved by an older build in LIAO_JIE_TABLE, told to pinyin at the first start: what pinyin then has of 了解
    private fun toldOnce(pinyin: ByteArray): List<Engines.UserWord> {
        val dir = folder.newFolder()
        olderPhrases(dir, "abcd" to "了解", "cdab" to "解了")
        val told = dir.resolve(Engines.userTable(WUBI) + Engines.TOLD)
        return withTable(dir, null, pinyin, LIAO_JIE_TABLE).use { engines ->
            // its phrase offered after the table's own: the table read its log, and told pinyin of it
            assertEquals(listOf("妤", "了解"), engines.type(WUBI, "abcd").candidates)
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            assertTrue("told", told.exists())
            // read again in the same walk, no word of the dictionary's: learned still, as its pieces read
            assertEquals(listOf("jie le"), engines.userWords().filter { it.text == "解了" }.map { it.pinyin })
            engines.userWords().filter { it.text == "了解" }
        }
    }

    @Test
    fun aBaseWordATableSavedIsNotToldToPinyinAsItsPiecesRead() {
        // the table's history read again with a stray first: never le jie
        assertEquals(emptyList<String>(), toldOnce(LIAO_JIE_PINYIN).map { it.pinyin }.filter { it != "liao jie" })
    }

    @Test
    fun aDictionaryWordOutsideTheModelATableSavedIsNotToldToPinyinAsItsPiecesRead() {
        assertEquals(emptyList<String>(), toldOnce(LIAO_JIE_UNSCORED).map { it.pinyin }.filter { it != "liao jie" })
    }

    private fun Engines.firstInPinyin(keys: String): String {
        val first = type(Engines.PINYIN, keys).candidates.first()
        onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
        return first
    }

    @Test
    fun aBaseWordATableSavedReadWholeLeavesPinyinsOrder() {
        // 你好 a word of the base dictionary, read whole; 拟好 likelier under ni hao
        val pinyin = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("你", -2f, 0f)
            .unigram("好", -2.5f, 0f)
            .unigram("拟好", -3f, 0f)
            .unigram("你好", -4f, 0f)
            .entry("你", syl("ni"))
            .entry("好", syl("hao"))
            .entry("拟好", syl("ni", "hao"))
            .entry("你好", syl("ni", "hao"))
            .build()
            .toByteArray()
        val dir = folder.newFolder("engine")
        olderPhrases(dir, "abcd" to "你好")
        withTable(dir, null, pinyin).use { engines ->
            // the decoder's scores alone: the sentence model would weigh the readings again
            engines.settings = engines.settings.copy(modelLevel = ModelLevel.LOW)
            assertEquals("拟好", engines.firstInPinyin("nihao"))
            engines.pickInTable("你好")
            // the dictionary's word, not listed as the user's own (userWords); the table's picks are its own, not pinyin's (U-28)
            assertEquals("拟好", engines.firstInPinyin("nihao"))
            assertTrue(engines.userWords().none { it.text == "你好" })
        }
    }

    @Test
    fun aBaseWordATableSavedReadAsItsFirstCharacterNeverIsAloneLeavesPinyinsOrder() {
        // as LIAO_JIE_PINYIN, with 辽姐 likelier under liao jie
        val pinyin = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("了", -2f, 0f)
            .unigram("解", -2.5f, 0f)
            .unigram("辽姐", -3f, 0f)
            .unigram("了解", -4f, 0f)
            .entry("了", syl("le"))
            .entry("解", syl("jie"))
            .entry("辽姐", syl("liao", "jie"))
            .entry("了解", syl("liao", "jie"))
            .build()
            .toByteArray()
        val dir = folder.newFolder("engine")
        olderPhrases(dir, "abcd" to "了解")
        withTable(dir, null, pinyin, LIAO_JIE_TABLE).use { engines ->
            engines.settings = engines.settings.copy(modelLevel = ModelLevel.LOW)
            assertEquals("辽姐", engines.firstInPinyin("liaojie"))
            engines.pickInTable("了解")
            // read again with a stray first: the dictionary's word, no word of the user's at le jie; the
            // table's picks its own, not pinyin's (U-28)
            assertEquals("辽姐", engines.firstInPinyin("liaojie"))
            assertTrue(engines.userWords().none { it.text == "了解" })
        }
    }

    @Test
    fun aWordOfTheModelsNoDictionaryReadsIsLearnedFromATablesPhraseAsItsPiecesRead() {
        // 你好 the model's word alone, only ever a context: no dictionary reads it
        val pinyin = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("你", -2f, 0f)
            .unigram("好", -2.5f, 0f)
            .unigram("你好", -3f, 0f)
            .entry("你", syl("ni"))
            .entry("好", syl("hao"))
            .build()
            .toByteArray()
        val dir = folder.newFolder("engine")
        olderPhrases(dir, "abcd" to "你好")
        withTable(dir, null, pinyin).use { engines ->
            // told as the table comes up: the only reading pinyin can offer it by
            engines.firstInTable()
            assertEquals(listOf("你好" to "ni hao"), engines.userWords(Engines.UserWord.Kind.LEARNED).map { it.text to it.pinyin })
        }
    }

    // what the user's dictionaries have not known when a word added by hand is taken off: none to
    // know them by, so the removal forgets nothing of what was learned of it (review M4, F3287)
    @Test
    fun aWordTakenOffWhileTheDictionariesAreNotKnownKeepsWhatWasLearnedOfIt() {
        // 拟好 beside it: a word added by hand alone over its keys would be first at once (U-65), with no picks to keep
        val made = compiled(::load) { sequenceOf("妮浩 ni'hao 0", "拟好 ni'hao 0") }
        val unreadable: () -> DictionaryFile? = { throw IOException("unreadable") }
        // why the dictionaries are not known, the additions read then, and how many "unreadable" are told
        // (a failure told once, however often the model is made again)
        class Row(val why: String, val told: IntRange, val unchecked: Boolean = false, val failing: () -> Engines.Additions?)
        val rows = listOf(
            Row("the merged file does not open", 1..1) { Engines.Additions("", "d", indexes = indexDir(), dictionary = unreadable) },
            // not made yet (an upgrade's minute) or failed
            Row("the merged file is pending", 0..0) { Engines.Additions("", "pending", dictionary = { null }, dictionaryPending = { true }) },
            // which dictionaries the user has is not known: 妮浩 may be one's word
            Row("the additions do not read", 1..Int.MAX_VALUE) { throw IOException("unreadable") },
            // review F3403/F3411: an unchecked throw from the open, not remembered as "no dictionaries"
            Row("the open throws unchecked", 0..0, unchecked = true) {
                Engines.Additions("", "d", indexes = indexDir(), dictionary = throwing(IllegalStateException("bug")))
            },
        )
        assertRows(rows, { it.why }) { row ->
            val why = row.why
            val dir = folder.newFolder()
            val additions = { Engines.Additions("", "d", indexes = indexDir(), dictionary = made) }
            Engines(::load, dir, additions = additions).use { engines ->
                // the decoder's scores alone: the sentence model would weigh the readings again
                engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
                assertTrue(why, engines.addWord("妮浩", "nihao"))
                engines.pickUntilFirst("nihao", "妮浩")
            }
            val errors = ArrayList<IOException>()
            Engines(::load, dir, onError = { errors += it }, additions = row.failing).use { engines ->
                fun guarded(step: () -> Unit) = if (row.unchecked) runCatching(step).let {} else step()
                // typed first: the removal asks again what that failed open left
                guarded { engines.type(Engines.PINYIN, "nihao") }
                guarded { engines.removeWord(Engines.UserWord("妮浩", "ni hao", Engines.UserWord.Kind.ADDED)) }
                guarded { engines.type(Engines.PINYIN, "nihao") }
            }
            assertTrue("$why: $errors", errors.count { it.message == "unreadable" } in row.told)
            Engines(::load, dir, additions = additions).use { engines ->
                engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
                // the dictionary's word, its picks not forgotten as the user's own
                assertEquals(why, "妮浩", engines.type(Engines.PINYIN, "nihao").candidates.first())
            }
        }
    }

    private fun throwing(e: RuntimeException): () -> DictionaryFile? = { throw e }

    // review F3304: a model whose making failed half-way is not kept; the next need makes it whole
    @Test
    fun aUserModelWhoseMakingFailedIsMadeWholeAtTheNextNeed() {
        val assets = EngineAssets(::load, {}, null)
        var fail = true
        val attached = ArrayList<UserModel>()
        val lexicon = UserLexicon(
            null, {}, { null }, { null }, assets, forgetInTables = {}, dropSessions = {},
            attach = { model ->
                attached += model
                if (fail) throw IllegalStateException("attach")
            },
        )
        assertEquals("attach", runCatching { lexicon.user() }.exceptionOrNull()?.message)
        fail = false
        val model = lexicon.user()
        // made again and attached, not the half-made one handed on
        assertEquals(2, attached.size)
        assertTrue(model === attached[1] && model !== attached[0])
        assertTrue(model === lexicon.user())
    }

    @Test
    fun aWordTakenOffBeforeTheModelIsMadeIsForgottenInTheOneMadeThen() {
        val dir = folder.newFolder("engine")
        var reads = 0
        val additions = Engines.Additions(
            "", "d",
            indexes = indexDir(), dictionary = compiled(::load) {
                reads++
                sequenceOf("你好 ni'hao 0")
            },
        )
        Engines(::load, dir, additions = { additions }).use { engines ->
            assertTrue(engines.addWord("妮浩", "nihao"))
            engines.pick("nihao", "妮浩")
        }
        reads = 0
        Engines(::load, dir, additions = { additions }).use { engines ->
            engines.removeWord(Engines.UserWord("妮浩", "ni hao", Engines.UserWord.Kind.ADDED))
            assertEquals(emptyList<Engines.UserWord>(), engines.userWords())
            engines.type(Engines.PINYIN, "nihao")
            assertTrue("not typeable", engines.candidates(Engines.PINYIN, 0, 20).none { it.text == "妮浩" })
            // once to tell it is no dictionary's, once to make the model it is forgotten in, at most:
            // made after it was taken off, not made again
            assertTrue(reads.toString(), reads <= 2)
        }
        Engines(::load, dir, additions = { additions }).use { engines ->
            assertEquals(emptyList<Engines.UserWord>(), engines.userWords())
        }
    }

    private companion object {
        const val MAX_PICKS = 10

        const val WUBI = "engine-wubi"

        // the code abcd is 妤's and, by the rule, 你好's
        const val TABLE = "键码=abcd\n码长=4\n[组词规则]\ne2=p11+p12+p21+p22\n[数据]\nab 你\nabcd 妤\ncd 好\n"

        // as TABLE, abcd 了解's by the rule
        const val LIAO_JIE_TABLE = "键码=abcd\n码长=4\n[组词规则]\ne2=p11+p12+p21+p22\n[数据]\nab 了\nabcd 妤\ncd 解\n"

        // 了解 a word of the base dictionary, read liao jie; 了 alone le
        val LIAO_JIE_PINYIN: ByteArray = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("了", -2f, 0f)
            .unigram("解", -2.5f, 0f)
            .unigram("了解", -3f, 0f)
            .entry("了", syl("le"))
            .entry("解", syl("jie"))
            .entry("了解", syl("liao", "jie"))
            .build()
            .toByteArray()

        // as LIAO_JIE_PINYIN, 了解 no word of the model's: the dictionary's alone, its id after the model's words
        val LIAO_JIE_UNSCORED: ByteArray = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("了", -2f, 0f)
            .unigram("解", -2.5f, 0f)
            .entry("了", syl("le"))
            .entry("解", syl("jie"))
            .entry("了解", syl("liao", "jie"))
            .build()
            .toByteArray()
    }

    // review S4: an older build's log converted at a fresh start, before the merged file's open (queued)
    // came: opened now, its words are the dictionaries', not the user's made words for good
    @Test
    fun aLegacyLogConvertedBeforeTheMergedFileOpensKnowsItsWords() {
        val dir = folder.newFolder("user")
        val niHao = UserModel.Entry("妮好", intArrayOf(Syllables.id("ni"), Syllables.id("hao")))
        val log = File(dir, Engines.USER_PINYIN).apply { writeBytes(UserLog.header() + UserLog.sentence(null, listOf(niHao))) }
        fun lexicon(now: () -> DictionaryFile?) = UserLexicon(
            dir, { throw it }, { null },
            { Engines.Additions("", "d", dictionary = { null }, dictionaryNow = now) }, EngineAssets(::load, {}, null),
            forgetInTables = {}, dropSessions = {},
        )
        assertEquals(listOf("妮好"), lexicon { null }.legacyModel(log)!!.madeWords().map { it.first.text })
        val model = lexicon(compiled(::load) { sequenceOf("妮好 ni'hao 0.5") }).legacyModel(log)!!
        assertEquals(emptyList<Pair<UserModel.Entry, Float>>(), model.madeWords())
        assertTrue(model.isMerged(model.known(niHao)))
    }
}
