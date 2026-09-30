/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.rerank.TinyModel
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.user.LibimeImport
import org.fcitx.fcitx5.android.engine.user.UserLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.ByteBuffer

class EnginesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    private val pinyin = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("你", -2f, 0f)
        .unigram("拟", -3f, 0f)
        .unigram("好", -2.5f, 0f)
        .entry("你", syl("ni"))
        .entry("拟", syl("ni"))
        .entry("好", syl("hao"))
        .build()
        .toByteArray()

    private val wubi = CodeTable.Builder()
        .header("键码", "abcdefghijklmnopqrstuvwxy")
        .header("码长", "4")
        .entry("wqiy", "你")
        .entry("vbg", "好")
        .entry("vbgf", "妤")
        .apply { "一二三四五六七".forEachIndexed { i, c -> entry("va" + ('a' + i) + "a", c.toString()) } }
        .build()
        .toByteArray()

    private var model = TinyModel().bytes()

    private val loaded = ArrayList<String>()

    private fun load(path: String): ByteBuffer {
        loaded += path
        return ByteBuffer.wrap(
            when (path) {
                Engines.PINYIN_DATA -> pinyin
                "${Engines.TABLE_DIR}/wbx.data" -> wubi
                Engines.SENTENCE_MODEL -> model
                else -> throw IllegalArgumentException(path)
            },
        )
    }

    private fun Engines.type(im: String, keys: String) = keys.map { onEvent(im, EngineEvent.CHAR, it.code) }.last()

    @Test
    fun pinyinIsTypedAndPicked() {
        val engines = Engines(::load, null)
        assertEquals(listOf("你", "拟"), engines.type(Engines.PINYIN, "ni").candidates)
        assertEquals("你", engines.onEvent(Engines.PINYIN, EngineEvent.CHAR, ' '.code).commit)
    }

    @Test
    fun theRestOfTheCandidatesAreFetchedFromTheSession() {
        val engines = Engines(::load, null)
        assertTrue(engines.candidates(Engines.PINYIN, 0, 5).isEmpty())
        val shown = engines.type(Engines.PINYIN, "ni")
        assertEquals(2, shown.total)
        assertEquals(listOf(Choice("拟")), engines.candidates(Engines.PINYIN, 1, 5))
        assertEquals("拟", engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 1).commit)
    }

    @Test
    fun aTableIsLoadedOnlyWhenItsInputMethodIsUsed() {
        val engines = Engines(::load, null)
        engines.type(Engines.PINYIN, "ni")
        assertEquals(listOf(Engines.PINYIN_DATA, Engines.SENTENCE_MODEL), loaded)
        loaded.clear()
        val s = engines.type("engine-wubi", "vbg")
        assertEquals(listOf("好", "妤"), s.candidates)
        assertEquals(listOf("", "f"), s.hints)
        // wubi's pinyin lookup shares the pinyin data
        assertEquals(listOf("${Engines.TABLE_DIR}/wbx.data"), loaded)
    }

    @Test
    fun eachInputMethodKeepsItsOwnInput() {
        val engines = Engines(::load, null)
        engines.type(Engines.PINYIN, "ni")
        engines.type("engine-wubi", "vb")
        assertEquals("ni", engines.type(Engines.PINYIN, "h").preedit.replace(" ", "").take(2))
    }

    @Test
    fun anUnknownInputMethodIsAnError() {
        assertThrows(IllegalArgumentException::class.java) { Engines(::load, null).onEvent("nope", EngineEvent.CHAR, 'a'.code) }
    }

    @Test
    fun whatIsPickedIsKeptUnderTheUserDirectory() {
        val dir = folder.newFolder("engine")
        Engines(::load, dir).use { engines ->
            // picked until it leads
            repeat(MAX_PICKS) {
                if (engines.type(Engines.PINYIN, "ni").candidates.first() == "拟") return@repeat
                engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 1)
            }
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
        }
        assertTrue(dir.resolve(Engines.USER_PINYIN).length() > 0)
        // and read back: 拟 still comes first
        Engines(::load, dir).use { engines ->
            assertEquals("拟", engines.type(Engines.PINYIN, "ni").candidates.first())
        }
    }

    @Test
    fun afterAPageTurnAPickIsOfTheCandidateShown() {
        val engines = Engines(::load, null)
        engines.settings = EngineSettings(pageSize = 3)
        engines.type("engine-wubi", "va")
        val page = engines.onEvent("engine-wubi", EngineEvent.PAGE_DOWN, 0)
        assertEquals(3, page.first)
        assertEquals(page.candidates, engines.candidates("engine-wubi", page.first, 3).map { it.text })
        assertEquals("五", engines.onEvent("engine-wubi", EngineEvent.PICK, page.first + 1).commit)
    }

    @Test
    fun nothingIsLearnedWhereTheAppSaysNotTo() {
        val dir = folder.newFolder("engine")
        Engines(::load, dir).use { engines ->
            repeat(MAX_PICKS) {
                engines.type(Engines.PINYIN, "ni")
                engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 1, learning = false)
            }
            assertEquals("你", engines.type(Engines.PINYIN, "ni").candidates.first())
        }
    }

    @Test
    fun theSentenceModelIsLoadedOnceForBothPinyinsUnlessTurnedOff() {
        val engines = Engines(::load, null)
        engines.type(Engines.PINYIN, "ni")
        engines.type(Engines.SHUANGPIN, "ni")
        assertEquals(1, loaded.count { it == Engines.SENTENCE_MODEL })
        loaded.clear()
        val off = Engines(::load, null)
        off.settings = EngineSettings(sentenceModel = false)
        assertEquals(listOf("你", "拟"), off.type(Engines.PINYIN, "ni").candidates)
        assertFalse(Engines.SENTENCE_MODEL in loaded)
        // dropped when turned off, and read again when turned back on
        engines.settings = EngineSettings(sentenceModel = false)
        engines.settings = EngineSettings()
        engines.type(Engines.PINYIN, "ni")
        assertEquals(1, loaded.count { it == Engines.SENTENCE_MODEL })
    }

    @Test
    fun aSentenceModelThatCannotBeReadLeavesPinyinWorking() {
        model = model.copyOf(100)
        val errors = ArrayList<IOException>()
        val engines = Engines(::load, null, onError = { errors += it })
        assertEquals(listOf("你", "拟"), engines.type(Engines.PINYIN, "ni").candidates)
        assertEquals("拟", engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 1).commit)
        assertEquals(1, errors.size)
        assertTrue(errors[0].cause is IllegalArgumentException)
    }

    @Test
    fun aUserLogThatCannotBeReadLeavesPinyinWorking() {
        val dir = folder.newFolder("engine")
        // a directory where the log should be
        dir.resolve(Engines.USER_PINYIN).mkdir()
        val errors = ArrayList<IOException>()
        Engines(::load, dir, onError = { errors += it }).use { engines ->
            assertEquals(listOf("你", "拟"), engines.type(Engines.PINYIN, "ni").candidates)
            assertEquals("拟", engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 1).commit)
        }
        assertEquals(1, errors.size)
    }

    @Test
    fun aKeySlippedIsReadAsMeantUnlessSlipsAreOff() {
        val engines = Engines(::load, null)
        // p is next to o
        assertEquals("好", engines.type(Engines.PINYIN, "hap").candidates.first())
        engines.settings = EngineSettings(typos = false)
        assertEquals(listOf("hap"), engines.type(Engines.PINYIN, "hap").candidates)
    }

    @Test
    fun settingsShapeTheSessionsMadeAfterThem() {
        val engines = Engines(::load, null)
        assertEquals(EngineSettings.DEFAULT_PAGE_SIZE, engines.type("engine-wubi", "va").candidates.size)
        // only the letters as typed
        assertEquals(listOf("li"), engines.type(Engines.PINYIN, "li").candidates)
        engines.settings = EngineSettings(pageSize = 3, fuzzy = setOf(Fuzzy.L_N))
        // made again: what was typed is gone
        assertEquals(listOf("一", "二", "三"), engines.type("engine-wubi", "va").candidates)
        assertEquals(listOf("你", "拟"), engines.type(Engines.PINYIN, "li").candidates)
        // the same settings again leave the input as it is
        engines.settings = EngineSettings(pageSize = 3, fuzzy = setOf(Fuzzy.L_N))
        assertEquals("你", engines.onEvent(Engines.PINYIN, EngineEvent.CHAR, ' '.code).commit)
    }

    @Test
    fun whatLibimeLearnedIsReadIntoAFreshLogOnce() {
        val dir = folder.newFolder("engine")
        var asked = 0
        val legacy = {
            asked++
            LibimeImport.Legacy(listOf("拟 ni 0"), List(MAX_PICKS) { "拟\tni" })
        }
        Engines(::load, dir, legacy = legacy).use { engines ->
            assertEquals("拟", engines.type(Engines.PINYIN, "ni").candidates.first())
        }
        Engines(::load, dir, legacy = legacy).use { engines ->
            assertEquals("拟", engines.type(Engines.PINYIN, "ni").candidates.first())
        }
        assertEquals(1, asked)
    }

    @Test
    fun aLogKilledBeforeAnythingWasLearnedIsStillFilled() {
        val dir = folder.newFolder("engine")
        dir.resolve(Engines.USER_PINYIN).writeBytes(UserLog.header())
        val legacy = { LibimeImport.Legacy(emptyList(), List(MAX_PICKS) { "拟\tni" }) }
        Engines(::load, dir, legacy = legacy).use { engines ->
            assertEquals("拟", engines.type(Engines.PINYIN, "ni").candidates.first())
        }
    }

    @Test
    fun libimesFilesThatCannotBeReadAreTriedAgainNextTime() {
        val dir = folder.newFolder("engine")
        val errors = ArrayList<IOException>()
        val failures = ArrayDeque(listOf<Throwable>(IOException("unreadable"), IllegalStateException("native"), UnsatisfiedLinkError("gone")))
        val legacy: () -> LibimeImport.Legacy? = {
            failures.removeFirstOrNull()?.let { throw it }
            LibimeImport.Legacy(emptyList(), List(MAX_PICKS) { "拟\tni" })
        }
        repeat(3) {
            Engines(::load, dir, onError = { errors += it }, legacy = legacy).use { engines ->
                assertEquals(listOf("你", "拟"), engines.type(Engines.PINYIN, "ni").candidates)
            }
        }
        assertEquals(listOf("unreadable", "native", "gone"), errors.map { it.cause?.message ?: it.message })
        Engines(::load, dir, legacy = legacy).use { engines ->
            assertEquals("拟", engines.type(Engines.PINYIN, "ni").candidates.first())
        }
        // with none, nothing is asked of the log
        Engines(::load, folder.newFolder("other"), legacy = { null }).use { engines ->
            assertFalse(engines.type(Engines.PINYIN, "ni").candidates.isEmpty())
        }
    }

    private companion object {
        const val MAX_PICKS = 10
    }
}
