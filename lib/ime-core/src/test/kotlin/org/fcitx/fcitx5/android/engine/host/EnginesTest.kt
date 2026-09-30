/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.session.Choice
import org.junit.Assert.assertEquals
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

    private val loaded = ArrayList<String>()

    private fun load(path: String): ByteBuffer {
        loaded += path
        return ByteBuffer.wrap(
            when (path) {
                Engines.PINYIN_DATA -> pinyin
                "${Engines.TABLE_DIR}/wbx.data" -> wubi
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
        assertEquals(listOf(Engines.PINYIN_DATA), loaded)
        val s = engines.type("engine-wubi", "vbg")
        assertEquals(listOf("好", "妤"), s.candidates)
        assertEquals(listOf("", "f"), s.hints)
        // wubi's pinyin lookup shares the pinyin data
        assertEquals(listOf(Engines.PINYIN_DATA, "${Engines.TABLE_DIR}/wbx.data"), loaded)
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
        engines.type("engine-wubi", "va")
        val page = engines.onEvent("engine-wubi", EngineEvent.PAGE_DOWN, 0)
        assertEquals(5, page.first)
        assertEquals(page.candidates, engines.candidates("engine-wubi", page.first, 5).map { it.text })
        assertEquals("七", engines.onEvent("engine-wubi", EngineEvent.PICK, page.first + 1).commit)
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

    private companion object {
        const val MAX_PICKS = 10
    }
}
