/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.CodeTableReader
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.session.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A word taken off the added words: forgotten if no dictionary has it, so the log's picks do not
 * make it the user's own again, whatever the log does next; kept, in pinyin and the tables, if
 * one does. A table, which has no readings, keeps it too if a dictionary has its text in another
 * reading, or the added words still do; and while a dictionary, or what the user added, does not
 * read, nothing is forgotten. A word whose first character reads in it as it never does alone is
 * imported, blocked from a table and learned from one as the dictionary reads it, not as its pieces
 * read: told a table's history, any word of the dictionary's; saved as it is typed, one of the model's
 * or one the user's words have. A word a table saves is learned in pinyin, as its pieces read if no
 * dictionary reads it.
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

    // pinyin's data the fixture's, or [pinyin]; 五笔's [table], saving a phrase after [saveAfter] (as WUBI if null)
    private fun withTable(dir: File, additions: Engines.Additions?, pinyin: ByteArray? = null, table: String = TABLE, saveAfter: Int? = null) = Engines(
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
    ).apply { settings = settings.copy(tables = mapOf("Wubi" to TableSettings(autoSelect = false, saveAutoPhraseAfter = saveAfter))) }

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
    fun aSelfMadeWordTakenOffIsForgottenThoughTheLogDoesNotOpenAgainAtOnce() {
        val dir = folder.newFolder("engine")
        val log = dir.resolve(Engines.USER_PINYIN)
        val errors = ArrayList<IOException>()
        Engines(::load, dir, onError = { errors += it }).use { engines ->
            assertTrue(engines.addWord("妮浩", "nihao"))
            engines.pick("nihao", "妮浩")
            // the log open still takes appends, but read-only it does not open again
            log.setWritable(false)
            assumeFalse("a user who may write any file", log.canWrite())
            engines.removeWord(Engines.UserWord("妮浩", "ni hao", Engines.UserWord.Kind.ADDED))
            assertEquals(emptyList<Engines.UserWord>(), engines.userWords())
            assertTrue("opened again", errors.isNotEmpty())
        }
        log.setWritable(true)
        Engines(::load, dir).use { engines ->
            assertEquals(emptyList<Engines.UserWord>(), engines.userWords())
        }
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
    fun aWordOfADictionaryTakenOffKeepsWhatATableLearnedOfIt() {
        val dir = folder.newFolder("engine")
        val additions = Engines.Additions("", "d") { sequenceOf("你好 ni'hao 0") }
        withTable(dir, additions).use { engines ->
            assertTrue(engines.addWord("你好", "ni hao"))
            // the table's own entry, then the dictionary's word, coded by the table's rule: first once picked here
            assertEquals(listOf("妤", "你好"), engines.type(WUBI, "abcd").candidates)
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            engines.pickInTable("你好")
            assertEquals("你好", engines.firstInTable())
            engines.removeWord(Engines.UserWord("你好", "ni hao", Engines.UserWord.Kind.ADDED))
            assertEquals("你好", engines.firstInTable())
        }
        withTable(dir, additions).use { engines ->
            assertEquals("你好", engines.firstInTable())
        }
    }

    @Test
    fun aReadingOfTheUsersOwnTakenOffLeavesATableWhatItLearnedOfADictionarysText() {
        val dir = folder.newFolder("engine")
        val additions = Engines.Additions("", "d") { sequenceOf("你好 ni'hao 0") }
        withTable(dir, additions).use { engines ->
            // no dictionary reads it so: the user's own word, forgotten in pinyin
            assertTrue(engines.addWord("你好", "ni hou"))
            engines.pickInTable("你好")
            engines.removeWord(Engines.UserWord("你好", "ni hou", Engines.UserWord.Kind.ADDED))
            assertEquals("你好", engines.firstInTable())
        }
        withTable(dir, additions).use { engines ->
            assertEquals("你好", engines.firstInTable())
        }
    }

    @Test
    fun aReadingOfTheUsersOwnTakenOffLeavesATableWhatItLearnedOfTheBaseDictionarysText() {
        val dir = folder.newFolder("engine")
        // 你好 a word of the base dictionary, read ni hao
        val pinyin = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("你", -2f, 0f)
            .unigram("好", -2.5f, 0f)
            .unigram("你好", -3f, 0f)
            .entry("你", syl("ni"))
            .entry("好", syl("hao"))
            .entry("你好", syl("ni", "hao"))
            .build()
            .toByteArray()
        withTable(dir, null, pinyin).use { engines ->
            assertTrue(engines.addWord("你好", "ni hou"))
            engines.pickInTable("你好")
            engines.removeWord(Engines.UserWord("你好", "ni hou", Engines.UserWord.Kind.ADDED))
        }
        // not shared now, a base word never typed: the pick read back as the table's phrase
        withTable(dir, null, pinyin).use { engines ->
            assertEquals("你好", engines.firstInTable())
        }
    }

    @Test
    fun aReadingOfTheUsersOwnTakenOffLeavesATableWhatItLearnedOfATextAddedInAnother() {
        val dir = folder.newFolder("engine")
        withTable(dir, null).use { engines ->
            // no dictionary has 你好: the user's own in both readings
            assertTrue(engines.addWord("你好", "ni hou"))
            assertTrue(engines.addWord("你好", "ni heng"))
            assertEquals("妤", engines.firstInTable())
            engines.pickInTable("你好")
            engines.removeWord(Engines.UserWord("你好", "ni hou", Engines.UserWord.Kind.ADDED))
            assertEquals("你好", engines.firstInTable())
        }
        withTable(dir, null).use { engines ->
            assertEquals("你好", engines.firstInTable())
        }
    }

    @Test
    fun aReadingOfTheUsersOwnTakenOffLeavesATableWhatItLearnedOfABaseWordReadAsItsFirstCharacterNeverIsAlone() {
        val dir = folder.newFolder("engine")
        withTable(dir, null, LIAO_JIE_PINYIN, LIAO_JIE_TABLE).use { engines ->
            assertTrue(engines.addWord("了解", "le jie"))
            engines.pickInTable("了解")
            engines.removeWord(Engines.UserWord("了解", "le jie", Engines.UserWord.Kind.ADDED))
        }
        // not shared now, a base word never typed: the pick read back as the table's phrase
        withTable(dir, null, LIAO_JIE_PINYIN, LIAO_JIE_TABLE).use { engines ->
            assertEquals("了解", engines.firstInTable())
        }
    }

    @Test
    fun aWordImportedWithNoPinyinIsReadAsItsFirstCharacterNeverIsAlone() {
        withTable(folder.newFolder("engine"), null, LIAO_JIE_PINYIN).use { engines ->
            assertEquals(Engines.Imported(added = 1, blocked = 0, unread = 0), engines.importWords(sequenceOf("了解")))
            assertEquals(listOf("liao jie"), engines.userWords(Engines.UserWord.Kind.ADDED).map { it.pinyin })
        }
    }

    // ab then cd typed one by one: their phrase (了解 in LIAO_JIE_TABLE, 你好 in TABLE) is offered under abcd, seen, not saved yet
    private fun Engines.seePhrase() {
        type(WUBI, "ab ")
        type(WUBI, "cd ")
    }

    @Test
    fun aWordBlockedFromATableIsBlockedAsItsFirstCharacterNeverIsAlone() {
        withTable(folder.newFolder("engine"), null, LIAO_JIE_PINYIN, LIAO_JIE_TABLE).use { engines ->
            engines.seePhrase()
            val at = engines.type(WUBI, "abcd").candidates.indexOf("了解")
            assertTrue(Offer.BLOCK in engines.offers(WUBI, at))
            engines.onEvent(WUBI, EngineEvent.BLOCK, at)
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            // as the dictionary reads it: blocked as its pieces read (le jie), pinyin would still offer it for liaojie
            assertEquals(listOf("liao jie"), engines.userWords(Engines.UserWord.Kind.BLOCKED).map { it.pinyin })
        }
    }

    @Test
    fun aBaseWordATableSavesIsNotLearnedAsItsPiecesRead() {
        withTable(folder.newFolder("engine"), null, LIAO_JIE_PINYIN, LIAO_JIE_TABLE).use { engines ->
            engines.seePhrase()
            engines.pickInTable("了解")
            // learned as the dictionary reads it: never le jie, a reading it has not
            assertEquals(emptyList<String>(), engines.userWords().filter { it.text == "了解" && it.pinyin != "liao jie" }.map { it.pinyin })
        }
    }

    @Test
    fun aDictionaryWordOutsideTheModelTypedInPinyinIsNotLearnedAsItsPiecesReadAsATableSavesIt() {
        // saved as typed in one by one: a pick would take the word pinyin shares with the table, not the phrase
        withTable(folder.newFolder("engine"), null, LIAO_JIE_UNSCORED, LIAO_JIE_TABLE, saveAfter = 1).use { engines ->
            engines.pick("liaojie", "了解")
            engines.seePhrase()
            // saved: before the table's own 妤, as the word pinyin shares is not
            assertEquals("了解", engines.firstInTable())
            assertEquals(emptyList<String>(), engines.userWords().filter { it.text == "了解" && it.pinyin != "liao jie" }.map { it.pinyin })
        }
    }

    @Test
    fun aWordTheUserMadeInPinyinIsNotLearnedAsItsPiecesReadAsATableSavesIt() {
        // as LIAO_JIE_TABLE, with a rule to code three characters: 了解了 saved as typed in one by one
        val table = "键码=abcd\n码长=4\n[组词规则]\ne2=p11+p12+p21+p22\ne3=p11+p21+p31+p32\n[数据]\nab 了\nabcd 妤\ncd 解\n"
        withTable(folder.newFolder("engine"), null, LIAO_JIE_PINYIN, table, saveAfter = 1).use { engines ->
            // 了解 then 了: the user's own word 了解了, read liao jie le, which no dictionary has
            engines.type(Engines.PINYIN, "liaojiele")
            engines.onEvent(Engines.PINYIN, EngineEvent.PICK, engines.candidates(Engines.PINYIN, 0, 20).indexOfFirst { it.text == "了解" })
            val at = engines.candidates(Engines.PINYIN, 0, 20).indexOfFirst { it.text == "了" }
            assertEquals("了解了", engines.onEvent(Engines.PINYIN, EngineEvent.PICK, at).commit)
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            assertEquals(listOf("liao jie le" to 1f), engines.userWords().filter { it.text == "了解了" }.map { it.pinyin to it.count })
            engines.seePhrase()
            engines.type(WUBI, "ab ")
            // read le jie le as typed in, then with a stray first as the user made it: learned once more so, not made anew as its pieces read
            assertEquals(listOf("liao jie le" to 2f), engines.userWords().filter { it.text == "了解了" }.map { it.pinyin to it.count })
        }
    }

    // 了解 and 解了 saved in LIAO_JIE_TABLE, then told to pinyin afresh, as at a start after pinyin's log was lost: what pinyin then has of 了解
    private fun toldOnce(pinyin: ByteArray): List<Engines.UserWord> {
        val dir = folder.newFolder()
        withTable(dir, null, pinyin, LIAO_JIE_TABLE).use { engines ->
            engines.seePhrase()
            engines.pickInTable("了解")
            engines.type(WUBI, "cd ")
            engines.type(WUBI, "ab ")
            engines.pickInTable("解了", "cdab")
        }
        assertTrue(dir.resolve(Engines.USER_PINYIN).delete())
        val told = dir.resolve(Engines.userTable(WUBI) + Engines.TOLD)
        assertTrue(told.delete())
        return withTable(dir, null, pinyin, LIAO_JIE_TABLE).use { engines ->
            // its picked phrase first: the table read its log, and told pinyin of it
            assertEquals("了解", engines.firstInTable())
            assertTrue("told", told.exists())
            // read again in the same walk, no word of the dictionary's: learned still, as its pieces read
            assertEquals(listOf("jie le"), engines.userWords().filter { it.text == "解了" }.map { it.pinyin })
            engines.userWords().filter { it.text == "了解" }
        }
    }

    @Test
    fun aBaseWordATableSavedIsNotToldToPinyinAsItsPiecesRead() {
        // the table's history read again with a stray first, as each phrase it saves is: never le jie
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
    fun aBaseWordATableSavesReadWholeIsLearnedInPinyin() {
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
        withTable(folder.newFolder("engine"), null, pinyin).use { engines ->
            // the decoder's scores alone: the sentence model would weigh the readings again
            engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
            assertEquals("拟好", engines.firstInPinyin("nihao"))
            engines.seePhrase()
            engines.pickInTable("你好")
            // the dictionary's word, not listed as the user's own (userWords): learned, it leads
            assertEquals("你好", engines.firstInPinyin("nihao"))
        }
    }

    @Test
    fun aBaseWordATableSavesIsLearnedAsItsFirstCharacterReadsInIt() {
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
        withTable(folder.newFolder("engine"), null, pinyin, LIAO_JIE_TABLE).use { engines ->
            engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
            assertEquals("辽姐", engines.firstInPinyin("liaojie"))
            engines.seePhrase()
            engines.pickInTable("了解")
            // read le jie as it is typed in, read again with a stray first: learned as liao jie, not left out
            assertEquals("了解", engines.firstInPinyin("liaojie"))
        }
    }

    @Test
    fun aWordOfTheModelsNoDictionaryReadsIsLearnedFromATableAsItsPiecesRead() {
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
        withTable(folder.newFolder("engine"), null, pinyin).use { engines ->
            engines.seePhrase()
            engines.pickInTable("你好")
            // the only reading pinyin can offer it by
            assertEquals(listOf("你好" to "ni hao"), engines.userWords(Engines.UserWord.Kind.LEARNED).map { it.text to it.pinyin })
        }
    }

    @Test
    fun aWordTakenOffWhileADictionaryDoesNotReadKeepsWhatWasLearnedOfIt() {
        val dir = folder.newFolder("engine")
        var readable = true
        val additions = Engines.Additions("", "d") {
            if (!readable) throw IOException("unreadable")
            sequenceOf("妮浩 ni'hao 0")
        }
        val errors = ArrayList<IOException>()
        Engines(::load, dir, onError = { errors += it }, additions = { additions }).use { engines ->
            // the decoder's scores alone: the sentence model would weigh the readings again
            engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
            assertTrue(engines.addWord("妮浩", "nihao"))
            engines.pickUntilFirst("nihao", "妮浩")
            readable = false
            engines.removeWord(Engines.UserWord("妮浩", "ni hao", Engines.UserWord.Kind.ADDED))
            assertTrue(errors.any { it.message == "unreadable" })
            readable = true
            // the dictionary's word, its picks not forgotten as the user's own
            assertEquals("妮浩", engines.type(Engines.PINYIN, "nihao").candidates.first())
        }
    }

    @Test
    fun aWordTakenOffWhileTheAdditionsDoNotReadKeepsWhatWasLearnedOfIt() {
        val dir = folder.newFolder("engine")
        val additions = Engines.Additions("", "d") { sequenceOf("妮浩 ni'hao 0") }
        Engines(::load, dir, additions = { additions }).use { engines ->
            // the decoder's scores alone: the sentence model would weigh the readings again
            engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
            assertTrue(engines.addWord("妮浩", "nihao"))
            engines.pickUntilFirst("nihao", "妮浩")
        }
        val errors = ArrayList<IOException>()
        // which dictionaries the user has is not known: 妮浩 may be one's word
        Engines(::load, dir, onError = { errors += it }, additions = { throw IOException("unreadable") }).use { engines ->
            engines.removeWord(Engines.UserWord("妮浩", "ni hao", Engines.UserWord.Kind.ADDED))
            assertTrue(errors.any { it.message == "unreadable" })
        }
        Engines(::load, dir, additions = { additions }).use { engines ->
            engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
            // the dictionary's word, its picks not forgotten as the user's own
            assertEquals("妮浩", engines.type(Engines.PINYIN, "nihao").candidates.first())
        }
    }

    @Test
    fun aWordTakenOffBeforeTheModelIsMadeIsForgottenInTheOneMadeThen() {
        val dir = folder.newFolder("engine")
        var reads = 0
        val additions = Engines.Additions("", "d") {
            reads++
            sequenceOf("你好 ni'hao 0")
        }
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

        // the code abcd is 妤's and, by the rule, 你好's: ordered by use (as 五笔 is), so a pick of 你好 puts it first
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
}
