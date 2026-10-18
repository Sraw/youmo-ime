/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.user.LibimeImport
import org.fcitx.fcitx5.android.engine.user.UserLog
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/** The words the user adds, makes and blocks, as the settings list them ([Engines.userWords]). */
class EnginesUserWordsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val pinyin = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("你", -2f, 0f)
        .unigram("拟", -3f, 0f)
        .unigram("好", -2.5f, 0f)
        .bigram("好", "拟", -0.5f, 0f)
        .entry("你", syl("ni"))
        .entry("拟", syl("ni"))
        .entry("好", syl("hao"))
        // a character of two readings
        .unigram("行", -2f, 0f)
        .unigram("航", -3f, 0f)
        .unigram("星", -3f, 0f)
        .entry("行", syl("xing"))
        .entry("行", syl("hang"))
        .entry("航", syl("hang"))
        .entry("星", syl("xing"))
        .build()
        .toByteArray()

    // no sentence models: the decoder's readings alone
    private fun load(path: String): ByteBuffer =
        if (path == Engines.PINYIN_DATA) ByteBuffer.wrap(pinyin) else throw FileNotFoundException(path)

    @Test
    fun aWordBlockedFromTheKeyboardIsNeverOfferedAgainTillUnblocked() {
        val dir = folder.newFolder()
        val blocked = Engines.UserWord("拟", "ni", Engines.UserWord.Kind.BLOCKED)
        Engines(::load, dir).use { engines ->
            val shown = engines.type(Engines.PINYIN, "ni").candidates
            assertEquals(listOf("你", "拟"), shown)
            assertTrue(Offer.BLOCK in engines.offers(Engines.PINYIN, 1))
            assertEquals(listOf("你"), engines.onEvent(Engines.PINYIN, EngineEvent.BLOCK, 1).candidates)
            // the session goes on: its candidates are still there to fetch
            assertEquals(listOf("你"), engines.candidates(Engines.PINYIN, 0, 10).map { it.text })
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            // nor predicted after 好, the model's word for it
            engines.type(Engines.PINYIN, "hao")
            assertEquals(emptyList<String>(), engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 0).candidates)
            assertEquals(listOf(blocked), engines.userWords())
        }
        // kept in the user directory
        Engines(::load, dir).use { again ->
            assertEquals(listOf("你"), again.type(Engines.PINYIN, "ni").candidates)
            again.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            again.removeWord(blocked)
            assertEquals(listOf("你", "拟"), again.type(Engines.PINYIN, "ni").candidates)
            assertEquals(emptyList<Engines.UserWord>(), again.userWords())
        }
    }

    @Test
    fun aWordAddedInTheSettingsIsTypedTillRemoved() {
        val dir = folder.newFolder()
        val added = Engines.UserWord("妮浩", "ni hao", Engines.UserWord.Kind.ADDED)
        Engines(::load, dir).use { engines ->
            assertFalse(engines.addWord("妮浩", "ni"))
            assertFalse(engines.addWord("妮浩", "nizz"))
            assertTrue(engines.addWord("妮浩", "nihao"))
            assertTrue("妮浩" in engines.type(Engines.PINYIN, "nihao").candidates)
            assertEquals(listOf(added), engines.userWords())
        }
        Engines(::load, dir).use { again ->
            assertTrue("妮浩" in again.type(Engines.PINYIN, "nihao").candidates)
            again.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            again.removeWord(added)
            assertFalse("妮浩" in again.type(Engines.PINYIN, "nihao").candidates)
            assertEquals(emptyList<Engines.UserWord>(), again.userWords())
        }
    }

    @Test
    fun aWordAddedByHandAtANewReadingIsTheModelsWordAndOneImportedIsNot() {
        // at nin, which the dictionary lacks: 拟 added by hand, the model's 拟 (-3); 你, merged into
        // the base, the unknown word's (-7) till picked, though the model's 你 (-2) would come first
        val additions = Engines.Additions("", "d") { sequenceOf("你 nin 0") }
        // 拟 is offered too, as ni with n begun, after it; sentences of ni n may come first
        val order = listOf("拟", "你")
        fun Engines.atNin() = type(Engines.PINYIN, "nin").candidates.filter { it in order }.also { onEvent(Engines.PINYIN, EngineEvent.RESET, 0) }
        val dir = folder.newFolder()
        Engines(::load, dir, additions = { additions }).use { engines ->
            assertEquals(listOf("你", "拟"), engines.atNin())
            assertTrue(engines.addWord("拟", "nin"))
            assertEquals(order, engines.atNin())
        }
        // listed by hand again on a start
        Engines(::load, dir, additions = { additions }).use { assertEquals(order, it.atNin()) }
        // removed with no log to make the model again from: still typed till the next start, but the unknown word's
        Engines(::load, null, additions = { additions }).use { engines ->
            assertEquals(listOf("你", "拟"), engines.atNin())
            assertTrue(engines.addWord("拟", "nin"))
            assertEquals(order, engines.atNin())
            engines.removeWord(Engines.UserWord("拟", "nin", Engines.UserWord.Kind.ADDED))
            assertEquals("你", engines.atNin().first())
        }
    }

    @Test
    fun aWordTheUserPutTogetherIsListedTillRemoved() {
        Engines(::load, folder.newFolder()).use { engines ->
            // 拟 then 好, over the decoder's 你好: learned as the user's word 拟好
            engines.type(Engines.PINYIN, "nihao")
            val pieces = engines.onEvent(Engines.PINYIN, EngineEvent.PICK, engines.candidates(Engines.PINYIN, 0, 20).indexOfFirst { it.text == "拟" })
            assertEquals("拟", pieces.preedit.substringBefore("hao").trim())
            engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 0)
            val learned = engines.userWords().single()
            assertEquals("拟好" to Engines.UserWord.Kind.LEARNED, learned.text to learned.kind)
            assertEquals("ni hao", learned.pinyin)
            engines.removeWord(learned)
            assertEquals(emptyList<Engines.UserWord>(), engines.userWords())
        }
    }

    @Test
    fun wordsTheUserPutTogetherAreRemovedAtOnceForGood() {
        val dir = folder.newFolder()
        Engines(::load, dir).use { engines ->
            fun putTogether(keys: String, vararg pieces: String) {
                engines.type(Engines.PINYIN, keys)
                for (p in pieces) engines.onEvent(Engines.PINYIN, EngineEvent.PICK, engines.candidates(Engines.PINYIN, 0, 20).indexOfFirst { it.text == p })
                engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            }
            putTogether("nihao", "拟", "好")
            putTogether("haoxing", "好", "星")
            val learned = engines.userWords()
            assertEquals(setOf("拟好", "好星"), learned.map { it.text }.toSet())
            engines.removeWords(learned)
            assertEquals(emptyList<Engines.UserWord>(), engines.userWords())
        }
        assertEquals(emptyList<Engines.UserWord>(), Engines(::load, dir).use { it.userWords() })
        // compacted: a start does not make them words again only to forget them
        val replayed = PinyinData.load(ByteBuffer.wrap(pinyin)).let { UserModel(it.dictionary, it.vocabulary) }
        UserLog.read(File(dir, Engines.USER_PINYIN).readBytes(), replayed)
        assertEquals(0, replayed.size)
    }

    @Test
    fun wordsLibimeLearnedStayRemovedThoughItsFilesStay() {
        val dir = folder.newFolder()
        var asked = 0
        // its user dictionary's own words, nothing typed since: once they go, the log has nothing left
        val legacy = {
            asked++
            LibimeImport.Legacy(listOf("拟好 ni'hao 0", "好星 hao'xing 0"), emptyList())
        }
        Engines(::load, dir, legacy = legacy).use { engines ->
            val added = Engines.UserWord("妮浩", "ni hao", Engines.UserWord.Kind.ADDED)
            assertTrue(engines.addWord("妮浩", "nihao"))
            val learned = engines.userWords() - added
            assertEquals(setOf("拟好", "好星"), learned.map { it.text }.toSet())
            engines.removeWords(learned)
            // the log read again without a restart
            engines.removeWord(added)
            assertEquals(emptyList<Engines.UserWord>(), engines.userWords())
        }
        assertEquals(emptyList<Engines.UserWord>(), Engines(::load, dir, legacy = legacy).use { it.userWords() })
        assertEquals(1, asked)
    }

    @Test
    fun eachKindIsListedAndCountedAlone() {
        Engines(::load, folder.newFolder()).use { engines ->
            // 拟 then 好, over the decoder's 你好: learned as the user's word 拟好
            engines.type(Engines.PINYIN, "nihao")
            engines.onEvent(Engines.PINYIN, EngineEvent.PICK, engines.candidates(Engines.PINYIN, 0, 20).indexOfFirst { it.text == "拟" })
            engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 0)
            // as many of each kind as no other: a count or list of the wrong kind tells
            assertTrue(engines.addWord("妮浩", "nihao"))
            assertTrue(engines.addWord("星航", "xing hang"))
            for ((text, pinyin) in listOf("航" to "hang", "星" to "xing", "你" to "ni")) assertTrue(engines.blockWord(text, pinyin))
            assertEquals(listOf("妮浩", "星航"), engines.userWords(Engines.UserWord.Kind.ADDED).map { it.text })
            assertEquals(listOf("拟好"), engines.userWords(Engines.UserWord.Kind.LEARNED).map { it.text })
            assertEquals(listOf("航", "星", "你"), engines.userWords(Engines.UserWord.Kind.BLOCKED).map { it.text })
            // all of them, kind by kind
            assertEquals(listOf("妮浩", "星航", "拟好", "航", "星", "你"), engines.userWords().map { it.text })
            assertEquals(
                mapOf(Engines.UserWord.Kind.ADDED to 2, Engines.UserWord.Kind.LEARNED to 1, Engines.UserWord.Kind.BLOCKED to 3),
                engines.userWordCounts(),
            )
        }
    }

    @Test
    fun aWordIsReadAsTheDictionaryReadsItsCharacters() {
        val engines = Engines(::load, null)
        assertEquals("ni hao", engines.pinyinOf("你好"))
        assertEquals("ni ni", engines.pinyinOf("拟你"))
        assertEquals(null, engines.pinyinOf("你x"))
        assertEquals(null, engines.pinyinOf(""))
    }

    @Test
    fun aWordIsBlockedInTheReadingItWasBlockedInOnly() {
        Engines(::load, folder.newFolder()).use { engines ->
            assertEquals(listOf("行", "航"), engines.type(Engines.PINYIN, "hang").candidates)
            engines.onEvent(Engines.PINYIN, EngineEvent.BLOCK, 0)
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            assertEquals(listOf("航"), engines.type(Engines.PINYIN, "hang").candidates)
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            assertEquals(listOf("行", "星"), engines.type(Engines.PINYIN, "xing").candidates)
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            // added as hang: offered so again
            assertTrue(engines.addWord("行", "hang"))
            assertTrue("行" in engines.type(Engines.PINYIN, "hang").candidates)
        }
    }

    @Test
    fun aListImportedIsAddedAndBlockedAndExportsAsItWasImported() {
        Engines(::load, folder.newFolder()).use { engines ->
            val imported = engines.importWords(
                sequenceOf(
                    "# a comment",
                    "妮浩 ni'hao",
                    // the other way round, a count after it, and no pinyin to read as the dictionary does
                    "hang xing 航星 12",
                    "好你",
                    "!拟 ni",
                    // pinyin it does not read as, a character the dictionary has not
                    "妮浩 nizz",
                    "妮x",
                    "妮 c=3",
                    // both ways: blocked
                    "你好 ni'hao",
                    "!你好 ni'hao",
                    "",
                ),
            )
            assertEquals(Engines.Imported(added = 3, blocked = 2, unread = 3), imported)
            assertEquals(listOf("妮浩 ni'hao", "航星 hang'xing", "好你 hao'ni", "!拟 ni", "!你好 ni'hao"), engines.exportWords())
            assertTrue("妮浩" in engines.type(Engines.PINYIN, "nihao").candidates)
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            assertFalse("拟" in engines.type(Engines.PINYIN, "ni").candidates)
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            // read back: nothing new
            assertEquals(Engines.Imported(added = 0, blocked = 0, unread = 0), engines.importWords(engines.exportWords().asSequence()))
            assertEquals(5, engines.userWords().size)
        }
    }

    @Test
    fun aThousandWordsAreAddedAndRemovedAtOnce() {
        val dir = folder.newFolder()
        val kept = Engines(::load, dir).use { engines ->
            val chars = listOf("你" to "ni", "拟" to "ni", "好" to "hao", "行" to "xing", "航" to "hang", "星" to "xing")
            // every word of four of the characters: 1296, each its own
            val words = chars.flatMap { a -> chars.flatMap { b -> chars.flatMap { c -> chars.map { d -> listOf(a, b, c, d) } } } }
                .map { w -> "${w.joinToString("") { it.first }} ${w.joinToString("'") { it.second }}" }
            assertEquals(Engines.Imported(added = words.size, blocked = 0, unread = 0), engines.importWords(words.asSequence()))
            val listed = engines.userWords()
            assertEquals(words.size, listed.size)
            engines.removeWords(listed.drop(1))
            assertEquals(listOf(listed.first()), engines.userWords())
            listed.first()
        }
        assertEquals(listOf(kept), Engines(::load, dir).use { it.userWords() })
    }
}
