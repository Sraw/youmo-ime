/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.WUBI
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.data.CodeTableReader
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.WordListFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A word taken off the added words: forgotten if no dictionary has it, so the log's picks do not
 * make it the user's own again, whatever the log does next; kept, in pinyin and the tables, if
 * one does; while a dictionary, or what the user added, does not read, nothing is forgotten. A
 * table's pick of a word from outside it makes no phrase of the table's. A word whose first
 * character reads in it as it never does alone is imported and learned from a table's phrases as the
 * dictionary reads it, not as its pieces read. A phrase an older build of a table saved, in the
 * record as 0.4 converted it, is pinyin's word too; a table's picks leave pinyin's order.
 */
class UserLexiconTest : EnginesFixture() {

    private fun Engines.pick(keys: String, text: String) {
        type(InputMethodCatalog.PINYIN, keys)
        val at = candidates(InputMethodCatalog.PINYIN, 0, 20).indexOfFirst { it.text == text }
        assertEquals(text, onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, at).commit)
        onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
    }

    private fun Engines.pickUntilFirst(keys: String, text: String) {
        repeat(MAX_PICKS) {
            val first = type(InputMethodCatalog.PINYIN, keys).candidates.first()
            onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            if (first != text) pick(keys, text)
        }
        assertEquals(text, type(InputMethodCatalog.PINYIN, keys).candidates.first())
        onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
    }

    // pinyin's data the fixture's, or [pinyin]; 五笔's [table]
    private fun withTable(dir: UserFiles, additions: Engines.Additions?, pinyin: ByteArray? = null, table: String = TABLE) = Engines(
        {
            when {
                pinyin != null && it == InputMethodCatalog.PINYIN_DATA -> ByteBuffer.wrap(pinyin)
                it == "${InputMethodCatalog.TABLE_DIR}/wbx.data" -> ByteBuffer.wrap(CodeTableReader().apply { read(table.reader().buffered(), "table") }.builder.build().toByteArray())
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
        val dir = MemoryFiles()
        Engines(::load, dir).use { engines ->
            assertTrue(engines.addWord("拟", "ni"))
            engines.pickUntilFirst("ni", "拟")
            // added and blocked at once: blocking leaves the added words be
            assertTrue(engines.blockWord("拟", "ni"))
            engines.removeWord(UserWord("拟", "ni", UserWord.Kind.ADDED))
            engines.removeWord(UserWord("拟", "ni", UserWord.Kind.BLOCKED))
            assertEquals("拟", engines.type(InputMethodCatalog.PINYIN, "ni").candidates.first())
        }
        Engines(::load, dir).use { engines ->
            assertEquals("拟", engines.type(InputMethodCatalog.PINYIN, "ni").candidates.first())
        }
    }

    @Test
    fun withNoLogWhatWasLearnedOfASelfMadeWordTakenOffIsForgotten() {
        Engines(::load, null).use { engines ->
            // the decoder's scores alone: the sentence model would weigh the readings again
            engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
            assertTrue(engines.addWord("妮浩", "nihao"))
            engines.pickUntilFirst("nihao", "妮浩")
            engines.removeWord(UserWord("妮浩", "ni hao", UserWord.Kind.ADDED))
            // typed till the next start, with no log to make the words again from, but as never picked
            assertNotEquals("妮浩", engines.type(InputMethodCatalog.PINYIN, "nihao").candidates.first())
        }
    }

    @Test
    fun aWordFromOutsideTheTablePickedThereIsNotMadeTheTablesOwn() {
        val dir = MemoryFiles()
        withTable(dir, null).use { engines ->
            // no dictionary has 你好: the user's own, coded by the table's rule, after its own entry (U-28)
            assertTrue(engines.addWord("你好", "ni hou"))
            assertEquals(listOf("妤", "你好"), engines.type(WUBI, "abcd").candidates)
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            engines.pickInTable("你好")
            engines.removeWord(UserWord("你好", "ni hou", UserWord.Kind.ADDED))
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
        withTable(MemoryFiles(), null, LIAO_JIE_PINYIN).use { engines ->
            // the user's own export: added as it was, not imported (U-101)
            assertEquals(Engines.Imported(added = 1, blocked = 0, unread = 0), engines.importWords(sequenceOf(WordListFiles.EXPORT_MARK, "了解")))
            assertEquals(listOf("liao jie"), engines.userWords(UserWord.Kind.ADDED).map { it.pinyin })
        }
    }

    @Test
    fun aWordBlockedFromATableIsBlockedUnderItsCodeAlone() {
        val dir = MemoryFiles()
        olderPhrases(dir, "abcd" to "了解")
        withTable(dir, null, LIAO_JIE_PINYIN, LIAO_JIE_TABLE).use { engines ->
            val at = engines.type(WUBI, "abcd").candidates.indexOf("了解")
            assertTrue(Offer.BLOCK in engines.offers(WUBI, at))
            engines.onEvent(WUBI, EngineEvent.BLOCK, at)
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            assertEquals(listOf("妤"), engines.type(WUBI, "abcd").candidates)
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            // not blocked in pinyin, nor anywhere else (U-28)
            assertEquals(emptyList<String>(), engines.userWords(UserWord.Kind.BLOCKED).map { it.text })
        }
        withTable(dir, null, LIAO_JIE_PINYIN, LIAO_JIE_TABLE).use { engines ->
            assertEquals(listOf("妤"), engines.type(WUBI, "abcd").candidates)
        }
    }

    private fun Engines.firstInPinyin(keys: String): String {
        val first = type(InputMethodCatalog.PINYIN, keys).candidates.first()
        onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
        return first
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
            val dir = MemoryFiles()
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
                guarded { engines.type(InputMethodCatalog.PINYIN, "nihao") }
                guarded { engines.removeWord(UserWord("妮浩", "ni hao", UserWord.Kind.ADDED)) }
                guarded { engines.type(InputMethodCatalog.PINYIN, "nihao") }
            }
            assertTrue("$why: $errors", errors.count { it.message == "unreadable" } in row.told)
            Engines(::load, dir, additions = additions).use { engines ->
                engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
                // the dictionary's word, its picks not forgotten as the user's own
                assertEquals(why, "妮浩", engines.type(InputMethodCatalog.PINYIN, "nihao").candidates.first())
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
            null, {}, { null }, assets, forgetInTables = {}, dropSessions = {},
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
        val dir = MemoryFiles()
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
            engines.removeWord(UserWord("妮浩", "ni hao", UserWord.Kind.ADDED))
            assertEquals(emptyList<UserWord>(), engines.userWords())
            engines.type(InputMethodCatalog.PINYIN, "nihao")
            assertTrue("not typeable", engines.candidates(InputMethodCatalog.PINYIN, 0, 20).none { it.text == "妮浩" })
            // once to tell it is no dictionary's, once to make the model it is forgotten in, at most:
            // made after it was taken off, not made again
            assertTrue(reads.toString(), reads <= 2)
        }
        Engines(::load, dir, additions = { additions }).use { engines ->
            assertEquals(emptyList<UserWord>(), engines.userWords())
        }
    }

    private companion object {
        const val MAX_PICKS = 10

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

}
