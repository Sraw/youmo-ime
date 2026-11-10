/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.user.WordListFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/** The words the user adds, makes and blocks, as the settings list them ([Engines.userWords]). */
class EnginesUserWordsTest {

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
        // a Latin word of the dictionary, read as its letters in 全拼's 中英混输
        .unigram("iPhone", -5f, 0f)
        .entry("iPhone", syl("I", "P", "H", "O", "N", "E"))
        .build()
        .toByteArray()

    // no sentence models: the decoder's readings alone
    private fun load(path: String): ByteBuffer =
        if (path == InputMethodCatalog.PINYIN_DATA) ByteBuffer.wrap(pinyin) else throw FileNotFoundException(path)

    @Test
    fun aWordBlockedFromTheKeyboardIsNeverOfferedAgainTillUnblocked() {
        val dir = MemoryFiles()
        val blocked = UserWord("拟", "ni", UserWord.Kind.BLOCKED)
        Engines(::load, dir).use { engines ->
            val shown = engines.type(InputMethodCatalog.PINYIN, "ni").candidates
            assertEquals(listOf("你", "拟"), shown)
            assertTrue(Offer.BLOCK in engines.offers(InputMethodCatalog.PINYIN, 1))
            assertEquals(listOf("你"), engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.BLOCK, 1).candidates)
            // the session goes on: its candidates are still there to fetch
            assertEquals(listOf("你"), engines.candidates(InputMethodCatalog.PINYIN, 0, 10).map { it.text })
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            // nor predicted after 好, the model's word for it
            engines.type(InputMethodCatalog.PINYIN, "hao")
            assertEquals(emptyList<String>(), engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0).candidates)
            assertEquals(listOf(blocked), engines.userWords())
        }
        // kept in the user directory
        Engines(::load, dir).use { again ->
            assertEquals(listOf("你"), again.type(InputMethodCatalog.PINYIN, "ni").candidates)
            again.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            again.removeWord(blocked)
            assertEquals(listOf("你", "拟"), again.type(InputMethodCatalog.PINYIN, "ni").candidates)
            assertEquals(emptyList<UserWord>(), again.userWords())
        }
    }

    // a Latin candidate blocked in pinyin: written so it reads back, not offered after a restart, till restored
    @Test
    @Uat("PY-117")
    fun aLatinWordBlockedInPinyinStaysBlockedAfterARestartTillRestored() {
        val dir = MemoryFiles()
        Engines(::load, dir).use { engines ->
            val shown = engines.type(InputMethodCatalog.PINYIN, "iphone").candidates
            val at = shown.indexOf("iPhone")
            assertTrue("$shown", at >= 0)
            assertTrue(Offer.BLOCK in engines.offers(InputMethodCatalog.PINYIN, at))
            assertFalse("iPhone" in engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.BLOCK, at).candidates)
            assertEquals(listOf("iPhone"), engines.userWords().filter { it.kind == UserWord.Kind.BLOCKED }.map { it.text })
        }
        Engines(::load, dir).use { again ->
            val blocked = again.userWords().single { it.text == "iPhone" }
            assertEquals(UserWord.Kind.BLOCKED, blocked.kind)
            assertFalse("iPhone" in again.type(InputMethodCatalog.PINYIN, "iphone").candidates)
            again.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            again.removeWord(blocked)
            assertTrue("iPhone" in again.type(InputMethodCatalog.PINYIN, "iphone").candidates)
        }
    }

    // 啊之 (a zhi) the user reported: a sentence of words, no word itself, blocked as the whole of it
    @Test
    fun aSentenceBlockedFromTheKeyboardIsNeverOfferedAgainTillUnblocked() {
        val dir = MemoryFiles()
        val blocked = UserWord("你好", "ni hao", UserWord.Kind.BLOCKED)
        Engines(::load, dir).use { engines ->
            val shown = engines.type(InputMethodCatalog.PINYIN, "nihao").candidates
            assertEquals("你好", shown.first())
            val menu = engines.learning(InputMethodCatalog.PINYIN, LearningOp.Menu(0, "你好")) as LearningResult.Menu
            assertEquals(listOf(MenuItem.BLOCK, MenuItem.PIN, MenuItem.WHY), menu.items)
            assertEquals(BlockScope.EVERYWHERE, menu.scope)
            // blocked, and the block taken back by its undo, then blocked again
            val done = engines.learning(InputMethodCatalog.PINYIN, LearningOp.Block(0, "你好")) as LearningResult.Done
            assertFalse("你好" in engines.candidates(InputMethodCatalog.PINYIN, 0, 10).map { it.text })
            engines.learning(InputMethodCatalog.PINYIN, LearningOp.Undo(done.undo))
            assertEquals(emptyList<UserWord>(), engines.userWords())
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertEquals("你好", engines.type(InputMethodCatalog.PINYIN, "nihao").candidates.first())
            val after = engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.BLOCK, 0).candidates
            assertFalse(after.toString(), "你好" in after)
            // its words are not: each still offered alone
            assertTrue(after.toString(), "你" in after)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertEquals(listOf(blocked), engines.userWords())
            assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertEquals(listOf("好"), engines.type(InputMethodCatalog.PINYIN, "hao").candidates)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
        }
        // kept in the user directory
        Engines(::load, dir).use { again ->
            assertFalse("你好" in again.type(InputMethodCatalog.PINYIN, "nihao").candidates)
            again.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            again.removeWord(blocked)
            assertEquals("你好", again.type(InputMethodCatalog.PINYIN, "nihao").candidates.first())
            assertEquals(emptyList<UserWord>(), again.userWords())
        }
    }

    // the same in 双拼 and on the nine keys: the sentence as it reads, the keys' readings alike
    @Test
    fun aSentenceIsBlockedOnEveryPinyinKeyboard() {
        for ((im, keys) in listOf(InputMethodCatalog.SHUANGPIN to "nihk", InputMethodCatalog.T9 to "64426")) {
            Engines(::load, MemoryFiles()).use { engines ->
                assertEquals(im, "你好", engines.type(im, keys).candidates.first())
                assertTrue(im, Offer.BLOCK in engines.offers(im, 0))
                assertFalse(im, "你好" in engines.onEvent(im, EngineEvent.BLOCK, 0).candidates)
                engines.onEvent(im, EngineEvent.RESET, 0)
                assertFalse(im, "你好" in engines.type(im, keys).candidates)
                engines.onEvent(im, EngineEvent.RESET, 0)
                engines.removeWord(engines.userWords().single())
                assertEquals(im, "你好", engines.type(im, keys).candidates.first())
            }
        }
    }

    // blocked as it reads: 行好 blocked at xing hao is still offered at hang hao
    @Test
    fun aSentenceIsBlockedAtItsReadingAlone() {
        Engines(::load, MemoryFiles()).use { engines ->
            val at = engines.type(InputMethodCatalog.PINYIN, "xinghao").candidates.indexOf("行好")
            assertTrue(at >= 0)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.BLOCK, at)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertEquals(listOf(UserWord("行好", "xing hao", UserWord.Kind.BLOCKED)), engines.userWords())
            assertFalse("行好" in engines.type(InputMethodCatalog.PINYIN, "xinghao").candidates)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertTrue("行好" in engines.type(InputMethodCatalog.PINYIN, "hanghao").candidates)
        }
    }

    @Test
    fun aWordAddedInTheSettingsIsTypedTillRemoved() {
        val dir = MemoryFiles()
        val added = UserWord("妮浩", "ni hao", UserWord.Kind.ADDED)
        Engines(::load, dir).use { engines ->
            assertFalse(engines.addWord("妮浩", "ni"))
            assertFalse(engines.addWord("妮浩", "nizz"))
            assertTrue(engines.addWord("妮浩", "nihao"))
            assertTrue("妮浩" in engines.type(InputMethodCatalog.PINYIN, "nihao").candidates)
            assertEquals(listOf(added), engines.userWords())
        }
        Engines(::load, dir).use { again ->
            assertTrue("妮浩" in again.type(InputMethodCatalog.PINYIN, "nihao").candidates)
            again.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            again.removeWord(added)
            assertFalse("妮浩" in again.type(InputMethodCatalog.PINYIN, "nihao").candidates)
            assertEquals(emptyList<UserWord>(), again.userWords())
        }
    }

    @Test
    fun aWordAddedByHandAtANewReadingIsTheModelsWordAndOneImportedIsNot() {
        // at nin, which the dictionary lacks: 拟 added by hand, the model's 拟 (-3); 你, merged into
        // the base, the unknown word's (-7) till picked, though the model's 你 (-2) would come first
        val additions = Engines.Additions("", "d", indexes = indexDir(), dictionary = compiled(::load) { sequenceOf("你 nin 0") })
        // 拟 is offered too, as ni with n begun, after it; sentences of ni n may come first
        val order = listOf("拟", "你")
        fun Engines.atNin() = type(InputMethodCatalog.PINYIN, "nin").candidates.filter { it in order }.also { onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0) }
        val dir = MemoryFiles()
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
            engines.removeWord(UserWord("拟", "nin", UserWord.Kind.ADDED))
            assertEquals("你", engines.atNin().first())
        }
    }

    @Test
    fun aWordTheUserPutTogetherIsListedTillRemoved() {
        Engines(::load, MemoryFiles()).use { engines ->
            // 拟 then 好, over the decoder's 你好, twice: the user's word 拟好 (U-06); once may be a slip
            engines.type(InputMethodCatalog.PINYIN, "nihao")
            val pieces = engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, engines.candidates(InputMethodCatalog.PINYIN, 0, 20).indexOfFirst { it.text == "拟" })
            assertEquals("拟", pieces.preedit.substringBefore("hao").trim())
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertEquals(emptyList<UserWord>(), engines.userWords())
            engines.learnNiHao()
            val learned = engines.userWords().single()
            assertEquals("拟好" to UserWord.Kind.LEARNED, learned.text to learned.kind)
            assertEquals("ni hao", learned.pinyin)
            engines.removeWord(learned)
            assertEquals(emptyList<UserWord>(), engines.userWords())
        }
    }

    @Test
    fun wordsTheUserPutTogetherAreRemovedAtOnceForGood() {
        val dir = MemoryFiles()
        Engines(::load, dir).use { engines ->
            fun putTogether(keys: String, vararg pieces: String) {
                engines.type(InputMethodCatalog.PINYIN, keys)
                for (p in pieces) engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, engines.candidates(InputMethodCatalog.PINYIN, 0, 20).indexOfFirst { it.text == p })
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            }
            repeat(2) {
                putTogether("nihao", "拟", "好")
                putTogether("haoxing", "好", "星")
            }
            val learned = engines.userWords()
            assertEquals(setOf("拟好", "好星"), learned.map { it.text }.toSet())
            engines.removeWords(learned)
            assertEquals(emptyList<UserWord>(), engines.userWords())
        }
        assertEquals(emptyList<UserWord>(), Engines(::load, dir).use { it.userWords() })
    }

    @Test
    fun eachKindIsListedAndCountedAlone() {
        Engines(::load, MemoryFiles()).use { engines ->
            // 拟 then 好, over the decoder's 你好, twice: the user's word 拟好
            repeat(2) { engines.learnNiHao() }
            // as many of each kind as no other: a count or list of the wrong kind tells
            assertTrue(engines.addWord("妮浩", "nihao"))
            assertTrue(engines.addWord("星航", "xing hang"))
            for ((text, pinyin) in listOf("航" to "hang", "星" to "xing", "你" to "ni")) assertTrue(engines.blockWord(text, pinyin))
            assertEquals(listOf("妮浩", "星航"), engines.userWords(UserWord.Kind.ADDED).map { it.text })
            assertEquals(listOf("拟好"), engines.userWords(UserWord.Kind.LEARNED).map { it.text })
            assertEquals(listOf("航", "星", "你"), engines.userWords(UserWord.Kind.BLOCKED).map { it.text })
            // all of them, kind by kind
            assertEquals(listOf("妮浩", "星航", "拟好", "航", "星", "你"), engines.userWords().map { it.text })
            assertEquals(
                mapOf(UserWord.Kind.ADDED to 2, UserWord.Kind.LEARNED to 1, UserWord.Kind.BLOCKED to 3),
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
        Engines(::load, MemoryFiles()).use { engines ->
            assertEquals(listOf("行", "航"), engines.type(InputMethodCatalog.PINYIN, "hang").candidates)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.BLOCK, 0)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertEquals(listOf("航"), engines.type(InputMethodCatalog.PINYIN, "hang").candidates)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertEquals(listOf("行", "星"), engines.type(InputMethodCatalog.PINYIN, "xing").candidates)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            // added as hang: offered so again
            assertTrue(engines.addWord("行", "hang"))
            assertTrue("行" in engines.type(InputMethodCatalog.PINYIN, "hang").candidates)
        }
    }

    @Test
    fun voiceBlockingsBoundariesSplitAPhraseIntoItsWords() {
        Engines(::load, MemoryFiles()).use { engines ->
            assertEquals(listOf(0, 1, 2, 3), engines.wordBoundaries(listOf("你好拟")).getValue("你好拟").toList())
        }
    }

    @Test
    fun aListImportedIsBlockedAndImportedAndExportsOnlyWhatIsBlocked() {
        Engines(::load, MemoryFiles()).use { engines ->
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
            engines.type(InputMethodCatalog.PINYIN, "nihao")
            assertTrue("妮浩" in engines.candidates(InputMethodCatalog.PINYIN, 0, 20).map { it.text })
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertFalse("拟" in engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            // read back: nothing new
            assertEquals(Engines.Imported(added = 0, blocked = 0, unread = 0), engines.importWords(engines.exportWords().asSequence()))
            // imported again: imported already
            assertEquals(0, engines.importWords(sequenceOf("妮浩 ni'hao"), "again.txt").imported)
            assertEquals(2, engines.userWords().size)
        }
    }

    // 拟 then 好, over the decoder's 你好: twice, the user's word 拟好
    private fun Engines.learnNiHao() {
        type(InputMethodCatalog.PINYIN, "nihao")
        onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, candidates(InputMethodCatalog.PINYIN, 0, 20).indexOfFirst { it.text == "拟" })
        onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0)
        onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
    }

    private fun exported(lines: List<String>) = (listOf(WordListFiles.EXPORT_MARK, "# header") + lines).asSequence()

    @Test
    fun anExportBringsLearnedWordsBackLearnedWithTheirCounts() {
        val (export, count) = Engines(::load, MemoryFiles()).use { engines ->
            engines.learnNiHao()
            engines.learnNiHao()
            engines.addWord("妮浩", "nihao")
            engines.blockWord("航", "hang")
            val count = engines.userWords(UserWord.Kind.LEARNED).single().count
            assertTrue(count > 1f)
            engines.exportWords() to count
        }
        assertEquals(listOf("妮浩 ni'hao", "~ 拟好 ni'hao $count", "!航 hang"), export)
        val dir = MemoryFiles()
        Engines(::load, dir).use { engines ->
            assertEquals(Engines.Imported(added = 1, blocked = 1, unread = 0, learned = 1), engines.importWords(exported(export)))
            assertEquals(listOf("妮浩"), engines.userWords(UserWord.Kind.ADDED).map { it.text })
            assertEquals(listOf("航"), engines.userWords(UserWord.Kind.BLOCKED).map { it.text })
        }
        // kept across a start, and not counted twice
        Engines(::load, dir).use { engines ->
            assertEquals(listOf("拟好" to count), engines.userWords(UserWord.Kind.LEARNED).map { it.text to it.count })
            assertEquals(Engines.Imported(added = 0, blocked = 0, unread = 0, learned = 0), engines.importWords(exported(export)))
            assertEquals(listOf("拟好" to count), engines.userWords(UserWord.Kind.LEARNED).map { it.text to it.count })
        }
    }

    // an export is told by its first non-blank line, as the base's reading and the import hint tell it:
    // a byte order mark alone, then a blank line, before the mark
    @Test
    fun anExportStartingWithBlankLinesStillBringsLearnedWordsBack() {
        Engines(::load, MemoryFiles()).use { engines ->
            val lines = sequenceOf("\uFEFF", "", WordListFiles.EXPORT_MARK, "~ 拟好 ni'hao 2.0")
            assertEquals(1, engines.importWords(lines).learned)
            assertEquals(listOf("拟好" to UserWord.Kind.LEARNED), engines.userWords().map { it.text to it.kind })
        }
    }

    @Test
    fun anAddedWordMarkedLearnedStaysAdded() {
        Engines(::load, MemoryFiles()).use { engines ->
            engines.addWord("拟好", "ni hao")
            assertEquals(0, engines.importWords(exported(listOf("~ 拟好 ni'hao 2.0"))).learned)
            assertEquals(listOf("拟好" to UserWord.Kind.ADDED), engines.userWords().map { it.text to it.kind })
        }
    }

    @Test
    fun aBlockedWordMarkedLearnedStaysBlocked() {
        Engines(::load, MemoryFiles()).use { engines ->
            engines.blockWord("拟好", "ni hao")
            assertEquals(0, engines.importWords(exported(listOf("~ 拟好 ni'hao 2.0"))).learned)
            assertEquals(listOf("拟好" to UserWord.Kind.BLOCKED), engines.userWords().map { it.text to it.kind })
        }
    }

    @Test
    fun aLearnedCountUnderOneComesBackAsOneUse() {
        val dir = MemoryFiles()
        // the learning record counts commits: an older build's halved count is a use still
        Engines(::load, dir).use { engines ->
            engines.importWords(exported(listOf("~ 拟好 ni'hao 0.5")))
            assertEquals(listOf("拟好" to 1f), engines.userWords(UserWord.Kind.LEARNED).map { it.text to it.count })
        }
        // and so after a start
        Engines(::load, dir).use { engines ->
            assertEquals(listOf("拟好" to 1f), engines.userWords(UserWord.Kind.LEARNED).map { it.text to it.count })
        }
    }

    @Test
    fun aLearnedLineWithNoCountIsOnceAndOneWithNoWordIsUnread() {
        Engines(::load, MemoryFiles()).use { engines ->
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
            assertEquals(listOf("好拟" to 2f, "拟好" to 1f), engines.userWords(UserWord.Kind.LEARNED).map { it.text to it.count })
        }
    }

    @Test
    fun anOlderExportComesBackAdded() {
        Engines(::load, MemoryFiles()).use { engines ->
            // the header of an export from before EXPORT_MARK
            val old = sequenceOf("# 幽默输入法用户词库：一行一个词，词后面是拼音；! 开头的是屏蔽的词", "拟好 ni'hao")
            assertEquals(Engines.Imported(added = 1, blocked = 0, unread = 0), engines.importWords(old))
            assertEquals(listOf("拟好" to UserWord.Kind.ADDED), engines.userWords().map { it.text to it.kind })
        }
    }

    @Test
    fun aLearnedMarkOutsideAnExportIsNotRead() {
        Engines(::load, MemoryFiles()).use { engines ->
            assertEquals(Engines.Imported(added = 0, blocked = 0, unread = 1), engines.importWords(sequenceOf("~拟好 ni'hao 3")))
            assertEquals(emptyList<UserWord>(), engines.userWords())
        }
    }

    @Test
    fun aThousandWordsAreAddedAndRemovedAtOnce() {
        val dir = MemoryFiles()
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

    // a sentence picked twice is a habit: blocked whole, the habits do not put it back in (R20 F5914)
    @Test
    fun aSentenceUsedBeforeIsNotPutBackByTheHabitsOnceBlocked() {
        Engines(::load, MemoryFiles()).use { engines ->
            repeat(2) {
                assertEquals("你好", engines.type(InputMethodCatalog.PINYIN, "nihao").candidates.first())
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0)
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            }
            engines.type(InputMethodCatalog.PINYIN, "nihao")
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.BLOCK, 0)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            for (keys in listOf("nihao", "nihaoni")) {
                val shown = engines.type(InputMethodCatalog.PINYIN, keys).candidates
                assertFalse("$keys $shown", "你好" in shown)
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            }
        }
    }
}
