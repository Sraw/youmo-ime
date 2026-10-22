/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.user.LibimeImport
import org.fcitx.fcitx5.android.engine.user.WordListFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
        // a phrase as one word: voice blocking splits it again
        .unigram("你好拟", -3f, 0f)
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
            // 拟 then 好, over the decoder's 你好, twice: the user's word 拟好 (U-06); once may be a slip
            engines.type(Engines.PINYIN, "nihao")
            val pieces = engines.onEvent(Engines.PINYIN, EngineEvent.PICK, engines.candidates(Engines.PINYIN, 0, 20).indexOfFirst { it.text == "拟" })
            assertEquals("拟", pieces.preedit.substringBefore("hao").trim())
            engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 0)
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            assertEquals(emptyList<Engines.UserWord>(), engines.userWords())
            engines.learnNiHao()
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
            repeat(2) {
                putTogether("nihao", "拟", "好")
                putTogether("haoxing", "好", "星")
            }
            val learned = engines.userWords()
            assertEquals(setOf("拟好", "好星"), learned.map { it.text }.toSet())
            engines.removeWords(learned)
            assertEquals(emptyList<Engines.UserWord>(), engines.userWords())
        }
        assertEquals(emptyList<Engines.UserWord>(), Engines(::load, dir).use { it.userWords() })
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
            // 拟 then 好, over the decoder's 你好, twice: the user's word 拟好
            repeat(2) { engines.learnNiHao() }
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
    fun voiceBlockingsBoundariesSplitAPhraseIntoItsWords() {
        Engines(::load, folder.newFolder()).use { engines ->
            assertEquals(listOf(0, 1, 2, 3), engines.wordBoundaries(listOf("你好拟")).getValue("你好拟").toList())
        }
    }

    @Test
    fun aListImportedIsBlockedAndImportedAndExportsOnlyWhatIsBlocked() {
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
                "list.txt",
            )
            // another input method's list: its words imported (U-101), none added by hand
            assertEquals(Engines.Imported(added = 0, blocked = 2, unread = 3, imported = 3), imported)
            assertEquals(listOf("航星 hang xing", "妮浩 ni hao", "好你 hao ni"), engines.importedWords().map { "${it.text} ${it.pinyin}" })
            assertEquals(listOf("!拟 ni", "!你好 ni'hao"), engines.exportWords())
            engines.type(Engines.PINYIN, "nihao")
            assertTrue("妮浩" in engines.candidates(Engines.PINYIN, 0, 20).map { it.text })
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            assertFalse("拟" in engines.type(Engines.PINYIN, "ni").candidates)
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            // read back: nothing new
            assertEquals(Engines.Imported(added = 0, blocked = 0, unread = 0), engines.importWords(engines.exportWords().asSequence()))
            // imported again: imported already
            assertEquals(0, engines.importWords(sequenceOf("妮浩 ni'hao"), "again.txt").imported)
            assertEquals(2, engines.userWords().size)
        }
    }

    // 拟 then 好, over the decoder's 你好: twice, the user's word 拟好
    private fun Engines.learnNiHao() {
        type(Engines.PINYIN, "nihao")
        onEvent(Engines.PINYIN, EngineEvent.PICK, candidates(Engines.PINYIN, 0, 20).indexOfFirst { it.text == "拟" })
        onEvent(Engines.PINYIN, EngineEvent.PICK, 0)
        onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
    }

    private fun exported(lines: List<String>) = (listOf(WordListFiles.EXPORT_MARK, "# header") + lines).asSequence()

    @Test
    fun anExportBringsLearnedWordsBackLearnedWithTheirCounts() {
        val (export, count) = Engines(::load, folder.newFolder()).use { engines ->
            engines.learnNiHao()
            engines.learnNiHao()
            engines.addWord("妮浩", "nihao")
            engines.blockWord("航", "hang")
            val count = engines.userWords(Engines.UserWord.Kind.LEARNED).single().count
            assertTrue(count > 1f)
            engines.exportWords() to count
        }
        assertEquals(listOf("妮浩 ni'hao", "~ 拟好 ni'hao $count", "!航 hang"), export)
        val dir = folder.newFolder()
        Engines(::load, dir).use { engines ->
            assertEquals(Engines.Imported(added = 1, blocked = 1, unread = 0, learned = 1), engines.importWords(exported(export)))
            assertEquals(listOf("妮浩"), engines.userWords(Engines.UserWord.Kind.ADDED).map { it.text })
            assertEquals(listOf("航"), engines.userWords(Engines.UserWord.Kind.BLOCKED).map { it.text })
        }
        // kept across a start, and not counted twice
        Engines(::load, dir).use { engines ->
            assertEquals(listOf("拟好" to count), engines.userWords(Engines.UserWord.Kind.LEARNED).map { it.text to it.count })
            assertEquals(Engines.Imported(added = 0, blocked = 0, unread = 0, learned = 0), engines.importWords(exported(export)))
            assertEquals(listOf("拟好" to count), engines.userWords(Engines.UserWord.Kind.LEARNED).map { it.text to it.count })
        }
    }

    // an export is told by its first non-blank line, as the base's reading and the import hint tell it:
    // a byte order mark alone, then a blank line, before the mark
    @Test
    fun anExportStartingWithBlankLinesStillBringsLearnedWordsBack() {
        Engines(::load, folder.newFolder()).use { engines ->
            val lines = sequenceOf("\uFEFF", "", WordListFiles.EXPORT_MARK, "~ 拟好 ni'hao 2.0")
            assertEquals(1, engines.importWords(lines).learned)
            assertEquals(listOf("拟好" to Engines.UserWord.Kind.LEARNED), engines.userWords().map { it.text to it.kind })
        }
    }

    @Test
    fun anAddedWordMarkedLearnedStaysAdded() {
        Engines(::load, folder.newFolder()).use { engines ->
            engines.addWord("拟好", "ni hao")
            assertEquals(0, engines.importWords(exported(listOf("~ 拟好 ni'hao 2.0"))).learned)
            assertEquals(listOf("拟好" to Engines.UserWord.Kind.ADDED), engines.userWords().map { it.text to it.kind })
        }
    }

    @Test
    fun aBlockedWordMarkedLearnedStaysBlocked() {
        Engines(::load, folder.newFolder()).use { engines ->
            engines.blockWord("拟好", "ni hao")
            assertEquals(0, engines.importWords(exported(listOf("~ 拟好 ni'hao 2.0"))).learned)
            assertEquals(listOf("拟好" to Engines.UserWord.Kind.BLOCKED), engines.userWords().map { it.text to it.kind })
        }
    }

    @Test
    fun aLearnedCountUnderOneComesBackAsOneUse() {
        val dir = folder.newFolder()
        // the learning record counts commits: an older build's halved count is a use still
        Engines(::load, dir).use { engines ->
            engines.importWords(exported(listOf("~ 拟好 ni'hao 0.5")))
            assertEquals(listOf("拟好" to 1f), engines.userWords(Engines.UserWord.Kind.LEARNED).map { it.text to it.count })
        }
        // and so after a start
        Engines(::load, dir).use { engines ->
            assertEquals(listOf("拟好" to 1f), engines.userWords(Engines.UserWord.Kind.LEARNED).map { it.text to it.count })
        }
    }

    @Test
    fun aLearnedLineWithNoCountIsOnceAndOneWithNoWordIsUnread() {
        Engines(::load, folder.newFolder()).use { engines ->
            val lines = listOf(
                // no count: once
                "~ 拟好 ni'hao",
                // blocked too, no pinyin, a pinyin it does not read as
                "~ !航 hang",
                "~ 航星",
                "~ 航星 zz",
                // the mark against the word, as well
                "~好拟 hao'ni 2.0",
            )
            assertEquals(Engines.Imported(added = 0, blocked = 0, unread = 3, learned = 2), engines.importWords(exported(lines)))
            assertEquals(listOf("好拟" to 2f, "拟好" to 1f), engines.userWords(Engines.UserWord.Kind.LEARNED).map { it.text to it.count })
        }
    }

    @Test
    fun anOlderExportComesBackAdded() {
        Engines(::load, folder.newFolder()).use { engines ->
            // the header of an export from before EXPORT_MARK
            val old = sequenceOf("# 幽默输入法用户词库：一行一个词，词后面是拼音；! 开头的是屏蔽的词", "拟好 ni'hao")
            assertEquals(Engines.Imported(added = 1, blocked = 0, unread = 0), engines.importWords(old))
            assertEquals(listOf("拟好" to Engines.UserWord.Kind.ADDED), engines.userWords().map { it.text to it.kind })
        }
    }

    @Test
    fun aLearnedMarkOutsideAnExportIsNotRead() {
        Engines(::load, folder.newFolder()).use { engines ->
            assertEquals(Engines.Imported(added = 0, blocked = 0, unread = 1), engines.importWords(sequenceOf("~拟好 ni'hao 3")))
            assertEquals(emptyList<Engines.UserWord>(), engines.userWords())
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
            // as the user's export brings them: added (another list's are imported, U-101)
            assertEquals(Engines.Imported(added = words.size, blocked = 0, unread = 0), engines.importWords(exported(words)))
            val listed = engines.userWords()
            assertEquals(words.size, listed.size)
            engines.removeWords(listed.drop(1))
            assertEquals(listOf(listed.first()), engines.userWords())
            listed.first()
        }
        assertEquals(listOf(kept), Engines(::load, dir).use { it.userWords() })
    }
}
