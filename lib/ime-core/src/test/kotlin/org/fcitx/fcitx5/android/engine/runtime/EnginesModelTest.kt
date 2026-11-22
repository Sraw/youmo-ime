/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.rerank.MatrixKernel
import org.fcitx.fcitx5.android.engine.rerank.TinyModel
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/** The sentence models as [Engines] reads them: when, which for which input method, and without them. */
class EnginesModelTest {

    private val pinyin = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("你", -2f, 0f)
        .unigram("拟", -3f, 0f)
        .unigram("好", -2.5f, 0f)
        .bigram("好", "拟", -0.5f, 0f)
        .entry("你", syl("ni"))
        .entry("拟", syl("ni"))
        .entry("好", syl("hao"))
        .build()
        .toByteArray()

    private var model: ByteArray? = TinyModel().bytes()
    private var large: ByteArray? = TinyModel().bytes()

    private val loaded = ArrayList<String>()

    // the large model read ahead here at once, so what was read is known at each step
    private val inPlace = Executor { it.run() }

    private fun load(path: String): ByteBuffer {
        loaded += path
        val bytes = when (path) {
            InputMethodCatalog.PINYIN_DATA -> pinyin
            InputMethodCatalog.SENTENCE_MODEL -> model
            InputMethodCatalog.LARGE_MODEL -> large
            InputMethodCatalog.EMOJI_DATA -> ByteArray(0)
            else -> null
        }
        return ByteBuffer.wrap(bytes ?: throw FileNotFoundException(path))
    }

    @Test
    fun aKernelGivenMultipliesTheSentenceModelsAsTheKotlinOneDoes() {
        var calls = 0
        val kernel = object : MatrixKernel {
            override fun times(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, y: FloatArray) = error("floats")

            override fun timesManyQ8(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, m: Int, y: FloatArray) {
                calls++
                MatrixKernel.JVM.timesManyQ8(q, scales, rows, columns, x, m, y)
            }
        }
        val expected = Engines(::load, null).type(InputMethodCatalog.PINYIN, "nihao").candidates
        assertEquals(0, calls)
        assertEquals(expected, Engines(::load, null, kernel = kernel).type(InputMethodCatalog.PINYIN, "nihao").candidates)
        assertTrue(calls > 0)
    }

    @Test
    fun theSentenceModelsAreLoadedOnceForBothPinyinsUnlessTurnedOff() {
        val models = listOf(InputMethodCatalog.SENTENCE_MODEL, InputMethodCatalog.LARGE_MODEL)
        val engines = Engines(::load, null, warmUps = inPlace)
        engines.type(InputMethodCatalog.PINYIN, "ni")
        engines.type(InputMethodCatalog.SHUANGPIN, "ni")
        assertEquals(models, loaded.filter { it.endsWith(".safetensors") })
        loaded.clear()
        val off = Engines(::load, null, warmUps = inPlace)
        off.settings = EngineSettings(modelLevel = ModelLevel.LOW)
        assertEquals(listOf("你", "拟"), off.type(InputMethodCatalog.PINYIN, "ni").candidates)
        assertTrue(loaded.none { it.endsWith(".safetensors") })
        engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
        engines.settings = EngineSettings()
        engines.type(InputMethodCatalog.PINYIN, "ni")
        assertEquals(models, loaded.filter { it.endsWith(".safetensors") })
    }

    /** 中档 runs the small model at every key, 高档 the large one too where the small is unsure; 低档 none; for every pinyin alike. */
    @Test
    fun eachLevelRunsItsModelsAndDropsThoseItNoLongerRuns() {
        val engines = Engines(::load, null, warmUps = inPlace)
        engines.settings = EngineSettings(modelLevel = ModelLevel.MEDIUM)
        for ((im, typed) in listOf(InputMethodCatalog.PINYIN to "ni", InputMethodCatalog.SHUANGPIN to "ni", InputMethodCatalog.T9 to "64")) {
            engines.type(im, typed)
            engines.onEvent(im, EngineEvent.RESET, 0)
        }
        assertEquals(listOf(InputMethodCatalog.SENTENCE_MODEL), loaded.filter { it.endsWith(".safetensors") })
        engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
        loaded.clear()
        for ((im, typed) in listOf(InputMethodCatalog.PINYIN to "ni", InputMethodCatalog.SHUANGPIN to "ni", InputMethodCatalog.T9 to "64")) {
            engines.type(im, typed)
            engines.onEvent(im, EngineEvent.RESET, 0)
        }
        assertTrue(loaded.none { it.endsWith(".safetensors") })
        // up again: read anew, dropped on the way down
        engines.settings = EngineSettings()
        engines.type(InputMethodCatalog.PINYIN, "ni")
        assertEquals(listOf(InputMethodCatalog.SENTENCE_MODEL, InputMethodCatalog.LARGE_MODEL), loaded.filter { it.endsWith(".safetensors") })
    }

    @Test
    fun theNineKeysReadTheModelFullPinyinReads() {
        val errors = ArrayList<IOException>()
        val engines = Engines(::load, null, onError = { errors += it }, warmUps = inPlace)
        engines.type(InputMethodCatalog.T9, "64426")
        assertEquals(listOf(InputMethodCatalog.SENTENCE_MODEL, InputMethodCatalog.LARGE_MODEL), loaded.filter { it.endsWith(".safetensors") })
        // the one in memory, whichever of the two the user types with
        loaded.clear()
        engines.type(InputMethodCatalog.PINYIN, "nihao")
        assertTrue(loaded.none { it.endsWith(".safetensors") })
        assertTrue(errors.isEmpty())
    }

    @Test
    fun withNoSentenceModelPinyinWorksAndTheMissingOneIsReportedOnce() {
        model = null
        val errors = ArrayList<IOException>()
        val engines = Engines(::load, null, onError = { errors += it }, warmUps = inPlace)
        val typed = engines.type(InputMethodCatalog.PINYIN, "ni")
        assertEquals(listOf("你", "拟"), typed.candidates)
        assertEquals("拟", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 1).commit)
        // the large one is asked only through the small: not read ahead for nothing
        assertEquals(listOf(InputMethodCatalog.SENTENCE_MODEL), loaded.filter { it.endsWith(".safetensors") })
        // a build that lost it (stored compressed, say) says why pinyin reads worse
        assertEquals(1, errors.size)
        assertTrue(InputMethodCatalog.SENTENCE_MODEL in errors[0].message.orEmpty())
        assertTrue(errors.all { (it.cause ?: it) is FileNotFoundException })
    }

    @Test
    fun aSentenceModelThatCannotBeReadLeavesPinyinWorking() {
        model = model!!.copyOf(100)
        val errors = ArrayList<IOException>()
        val engines = Engines(::load, null, onError = { errors += it })
        assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
        assertEquals("拟", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 1).commit)
        assertEquals(1, errors.size)
        assertTrue(errors[0].cause is IllegalArgumentException)
    }

    @Test
    fun atHighThePinyinSessionHasTheLargeModelReadOnTheWarmUpThread() {
        val queued = ArrayList<Runnable>()
        val engines = Engines(::load, null, warmUps = { queued += it })
        engines.settings = EngineSettings(modelLevel = ModelLevel.MEDIUM)
        engines.type(InputMethodCatalog.PINYIN, "ni")
        queued.toList().forEach { it.run() }
        assertTrue(InputMethodCatalog.LARGE_MODEL !in loaded)
        engines.settings = EngineSettings()
        engines.type(InputMethodCatalog.T9, "64")
        queued.toList().forEach { it.run() }
        assertEquals(1, loaded.count { it == InputMethodCatalog.LARGE_MODEL })
    }

    @Test
    fun aLargeModelThatCannotBeReadPicksAsMedium() {
        val inputs = listOf(InputMethodCatalog.PINYIN to "nihaoni", InputMethodCatalog.T9 to "6442664", InputMethodCatalog.SHUANGPIN to "nihk")
        val medium = Engines(::load, null, warmUps = inPlace).apply { settings = EngineSettings(modelLevel = ModelLevel.MEDIUM) }
        val expected = inputs.map { (im, typed) -> medium.type(im, typed).candidates }
        val errors = ArrayList<IOException>()
        large = null
        val high = Engines(::load, null, onError = { errors += it }, warmUps = inPlace)
        assertEquals(expected, inputs.map { (im, typed) -> high.type(im, typed).candidates })
        assertTrue(errors.size <= 1)
        assertTrue(errors.all { InputMethodCatalog.LARGE_MODEL in it.message.orEmpty() })
    }
}
