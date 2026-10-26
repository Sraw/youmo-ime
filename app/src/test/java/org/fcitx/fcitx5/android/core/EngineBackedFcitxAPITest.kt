/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.host.EngineEvent
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.concurrent.Executor

class EngineBackedFcitxAPITest {

    // 你 好 and 你好, all an engine needs to read and to take words
    private val pinyin = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("你", -2f, 0f)
        .unigram("好", -2.5f, 0f)
        .entry("你", intArrayOf(Syllables.id("ni")))
        .entry("好", intArrayOf(Syllables.id("hao")))
        .build()
        .toByteArray()

    private fun engines() = Engines(
        load = { path -> if (path == Engines.PINYIN_DATA) ByteBuffer.wrap(pinyin) else throw FileNotFoundException(path) },
        userDir = null,
        warmUps = Executor(Runnable::run),
    )

    @Test
    fun `readings and the user's words are the engine's`() = runTest {
        val api = EngineBackedFcitxAPI(engines())
        assertEquals("ni hao", api.pinyinOf("你好"))
        assertTrue(api.addUserWord("你好", "ni hao"))
        val words = api.userWords()
        assertEquals(listOf("你好"), words.map { it.text })
        assertEquals(1, api.userWordCounts()[Engines.UserWord.Kind.ADDED])
        api.removeUserWords(words)
        assertTrue(api.userWords().isEmpty())
    }

    @Test
    fun `a context is told the engine and recorded`() = runTest {
        val api = EngineBackedFcitxAPI(engines())
        api.engineContext("你", started = true)
        assertEquals(listOf("engineContext(你, true, started)"), api.fake.calls)
        // the engine typed after it, and the rest of the API still the fake's
        val shot = api.engines.onEvent(Engines.PINYIN, EngineEvent.CHAR, 'n'.code)
        assertTrue(shot.handled)
        api.reloadEngine()
        assertEquals("reloadEngine()", api.fake.calls.last())
    }

    @Test
    fun `a learning op reaches the engines`() = runTest {
        val api = EngineBackedFcitxAPI(engines())
        assertEquals(LearningResult.Done(), api.learning(LearningOp.UseHabits(false)))
        assertFalse(api.engines.useHabits)
    }
}
