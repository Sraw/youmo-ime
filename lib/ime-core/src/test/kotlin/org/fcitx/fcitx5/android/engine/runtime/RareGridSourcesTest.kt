/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.TABLE_DIR
import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.ReadingSource
import org.fcitx.fcitx5.android.engine.data.TestCharLevels
import org.fcitx.fcitx5.android.engine.phrase.Chaizi
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer

/**
 * 生僻字屏蔽's grid as [Engines] makes it of the pack's data (design §6.2): of 扩展 B on, only what
 * something types, the dictionary's words, a table or the parts (DECISIONS N2); made once (review S7).
 */
class RareGridSourcesTest : EnginesFixture() {

    private fun s(cp: Int) = String(Character.toChars(cp))

    private val inWord = 0x20000 // in a word of two, never alone
    private val inTable = 0x20001
    private val inParts = 0x20002
    private val readOnly = 0x20003 // a reading, nothing that types it
    // a reading, typed by the user's words alone
    private val added = 0x20004
    private val imported = 0x20005
    private val merged = 0x20006
    private val learned = 0x20007 // imported, then picked: the user's own

    init {
        pinyin = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("大${s(inWord)}", -3f, 0f).entry("大${s(inWord)}", syl("da", "a"))
            .apply {
                for (cp in listOf(inWord, inTable, inParts, readOnly, added, imported, merged, learned, TestCharLevels.OUT)) {
                    charReading(ReadingSource.MANDARIN, cp, Syllables.id("a"), 1)
                }
            }
            .build().toByteArray()
        wubiData = CodeTable.Builder()
            .header("键码", "abcdefghijklmnopqrstuvwxy")
            .header("码长", "4")
            .entry("aaaa", s(inTable))
            .build().toByteArray()
        chaizi = Chaizi.format(mapOf("aa" to listOf(s(inParts)))).toByteArray()
        charLevels = TestCharLevels.bytes
    }

    // the tables but 五笔 not in the pack, as the app's assets have not a file: passed over, told
    private val told = ArrayList<IOException>()
    private val engines = Engines(
        { path -> if (path.startsWith(TABLE_DIR) && !path.endsWith("/wbx.data")) throw FileNotFoundException(path) else load(path) },
        MemoryFiles(), onError = { told += it }, warmUps = inPlace,
    )

    @Test
    fun ofExtensionBOnlyWhatSomethingTypesHasACell() {
        val grid = checkNotNull(engines.rareGrid())
        assertRows(
            "扩展 B in a word of the dictionary's" to { assertNotEquals(-1, grid.indexOf(inWord)) },
            "扩展 B in a table" to { assertNotEquals(-1, grid.indexOf(inTable)) },
            "扩展 B found by its parts" to { assertNotEquals(-1, grid.indexOf(inParts)) },
            "扩展 B with a reading, nothing typing it: none" to { assertEquals(-1, grid.indexOf(readOnly)) },
            "其它表外字 with a reading alone keeps its cell: the rule is 扩展 B's" to { assertNotEquals(-1, grid.indexOf(TestCharLevels.OUT)) },
            "made once: the same grid asked again" to { assertSame(grid, engines.rareGrid()) },
            "a table not to be read passed over and told" to { assertTrue(told.toString(), told.any { "cj.data" in it.message.orEmpty() }) },
        )
    }

    // review F8110: a table this build cannot read (another kind or version) threw the engine's
    // DataFormatException past a catch of java.util.zip's, and the whole grid failed to be made
    @Test
    fun aTableOfAnotherKindIsPassedOverAndTold() {
        val told = ArrayList<IOException>()
        val loadOther = { path: String ->
            when {
                path.endsWith("/cj.data") -> ByteBuffer.wrap(pinyin) // a file of another kind
                path.startsWith(TABLE_DIR) && !path.endsWith("/wbx.data") -> throw FileNotFoundException(path)
                else -> load(path)
            }
        }
        Engines(loadOther, MemoryFiles(), onError = { told += it }, warmUps = inPlace).use { engines ->
            val grid = checkNotNull(engines.rareGrid())
            assertRows(
                "the tables read still give their characters" to { assertNotEquals(-1, grid.indexOf(inTable)) },
                "the table of another kind told" to { assertTrue(told.toString(), told.any { "cj.data" in it.message.orEmpty() }) },
            )
        }
    }

    private fun Engines.pick(keys: String, text: String) {
        type(PINYIN, keys)
        val shown = candidates(PINYIN, 0, 100).map { it.text }
        val at = shown.indexOf(text)
        assertTrue("$text in $shown", at >= 0)
        onEvent(PINYIN, EngineEvent.PICK, at)
        onEvent(PINYIN, EngineEvent.RESET, 0)
    }

    // an import of a rare character's words would leave the user nothing to show it by: theirs type it
    // too, the grid made again as they change
    @Test
    fun theUsersWordsTypeTheirCharactersTooAsTheyChange() {
        val additions = Engines.Additions("", "d", indexes = indexDir(), dictionary = compiled(::load) { sequenceOf("大${s(merged)} da'a 0.5") })
        Engines(::load, MemoryFiles(), { told += it }, additions = { additions }, warmUps = inPlace).use { engines ->
            fun has(cp: Int) = checkNotNull(engines.rareGrid()).indexOf(cp) != -1
            val before = checkNotNull(engines.rareGrid())
            assertTrue(engines.addWord("大${s(added)}", "da a"))
            assertEquals(2, engines.importWords(sequenceOf("大${s(imported)} da'a 3", "大${s(learned)} da'a 2"), "a.txt").imported)
            repeat(2) { engines.pick("da'a", "大${s(learned)}") }
            assertRows(
                "nothing of the user's yet: none" to { assertEquals(listOf(-1, -1), listOf(added, imported).map(before::indexOf)) },
                "in a dictionary of the user's" to { assertTrue(has(merged)) },
                "in a word added by hand" to { assertTrue(has(added)) },
                "in a word imported" to { assertTrue(has(imported)) },
                "the same grid while they stay" to { assertSame(engines.rareGrid(), engines.rareGrid()) },
            )
            engines.removeWords(engines.userWords(UserWord.Kind.ADDED))
            // the word picked stays the user's
            assertEquals(1, engines.dropImport(engines.importBatches().single().id))
            assertRows(
                "the word added taken off: gone" to { assertFalse(has(added)) },
                "the import undone: gone" to { assertFalse(has(imported)) },
                "the dictionary's stays" to { assertTrue(has(merged)) },
                "the word imported but learned stays" to { assertTrue(has(learned)) },
            )
        }
    }

    // a merged dictionary may have a million words: walked once a file, not at each grid made again
    // as the user learns
    @Test
    fun aMergedDictionaryIsWalkedOnceAFileNotAsTheUserLearns() {
        fun of(cp: Int, name: String) = Engines.Additions("", name, indexes = indexDir(), dictionary = compiled(::load) { sequenceOf("大${s(cp)} da'a 0.5") })
        var additions = of(merged, "d")
        Engines(::load, MemoryFiles(), { told += it }, additions = { additions }, warmUps = inPlace).use { engines ->
            val first = checkNotNull(engines.rareGrid())
            val walked = engines.rareMergedWalks
            engines.pick("da'a", "大${s(inWord)}")
            val learned = checkNotNull(engines.rareGrid())
            val walkedAgain = engines.rareMergedWalks
            additions = of(added, "e")
            engines.reload()
            val other = checkNotNull(engines.rareGrid())
            assertRows(
                "walked at the first grid" to { assertEquals(1, walked) },
                "made again as the user learned, the dictionary not walked again" to {
                    assertNotSame(first, learned)
                    assertEquals(1, walkedAgain)
                    assertNotEquals(-1, learned.indexOf(merged))
                },
                "another dictionary merged: walked, its character in, the first's out" to {
                    assertEquals(2, engines.rareMergedWalks)
                    assertNotEquals(-1, other.indexOf(added))
                    assertEquals(-1, other.indexOf(merged))
                },
            )
        }
    }
}
