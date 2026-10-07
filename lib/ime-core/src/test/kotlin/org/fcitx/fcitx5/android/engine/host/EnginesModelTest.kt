/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
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

/** The sentence models as [Engines] reads them: when, which for which input method, and without them. */
class EnginesModelTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

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
    private var refining: ByteArray? = TinyModel().bytes()
    // the nine keys' own pair; none unless a test gives them, as in a build without them
    private var t9Model: ByteArray? = null
    private var t9Refining: ByteArray? = null

    private val loaded = ArrayList<String>()

    private fun load(path: String): ByteBuffer {
        loaded += path
        val bytes = when (path) {
            Engines.PINYIN_DATA -> pinyin
            Engines.SENTENCE_MODEL -> model
            Engines.REFINING_MODEL -> refining
            Engines.T9_SENTENCE_MODEL -> t9Model
            Engines.T9_REFINING_MODEL -> t9Refining
            else -> null
        }
        // as the app's assets do of a file it has not got
        return ByteBuffer.wrap(bytes ?: throw FileNotFoundException(path))
    }

    private fun Engines.type(im: String, keys: String) = keys.map { onEvent(im, EngineEvent.CHAR, it.code) }.last()

    /** What the addon does while the user pauses after [typed]: refines till there is no more to do. */
    private fun Engines.pause(im: String, typed: Snapshot): List<Snapshot> {
        val slices = ArrayList<Snapshot>()
        var s = typed
        while (s.refines) {
            s = onEvent(im, EngineEvent.REFINE, 0)
            slices += s
            assertTrue("never done", slices.size < MAX_SLICES)
        }
        return slices
    }

    @Test
    fun aKernelGivenMultipliesTheSentenceModelsAsTheKotlinOneDoes() {
        var calls = 0
        val kernel = MatrixKernel { q, scales, rows, columns, x, y -> calls++; MatrixKernel.JVM.times(q, scales, rows, columns, x, y) }
        val expected = Engines(::load, null).type(Engines.PINYIN, "nihao").candidates
        assertEquals(0, calls)
        assertEquals(expected, Engines(::load, null, kernel = kernel).type(Engines.PINYIN, "nihao").candidates)
        assertTrue(calls > 0)
    }

    @Test
    fun theSentenceModelsAreLoadedOnceForBothPinyinsUnlessTurnedOff() {
        val models = listOf(Engines.SENTENCE_MODEL, Engines.REFINING_MODEL)
        val engines = Engines(::load, null)
        engines.pause(Engines.PINYIN, engines.type(Engines.PINYIN, "ni"))
        engines.pause(Engines.SHUANGPIN, engines.type(Engines.SHUANGPIN, "ni"))
        assertEquals(models, loaded.filter { it in models })
        loaded.clear()
        val off = Engines(::load, null)
        off.settings = EngineSettings(sentenceModel = false)
        assertEquals(listOf("你", "拟"), off.type(Engines.PINYIN, "ni").candidates)
        assertTrue(loaded.none { it in models })
        // dropped when turned off, and read again when turned back on
        engines.settings = EngineSettings(sentenceModel = false)
        engines.settings = EngineSettings()
        engines.pause(Engines.PINYIN, engines.type(Engines.PINYIN, "ni"))
        assertEquals(models, loaded.filter { it in models })
    }

    @Test
    fun theNineKeysReadTheirOwnModelsAndFullPinyinNeverDoes() {
        t9Model = TinyModel().bytes()
        t9Refining = TinyModel().bytes()
        val engines = Engines(::load, null)
        engines.pause(Engines.T9, engines.type(Engines.T9, "64426"))
        assertEquals(listOf(Engines.T9_SENTENCE_MODEL, Engines.T9_REFINING_MODEL), loaded.filter { it.endsWith(".safetensors") })
        loaded.clear()
        engines.pause(Engines.PINYIN, engines.type(Engines.PINYIN, "nihao"))
        assertEquals(listOf(Engines.SENTENCE_MODEL, Engines.REFINING_MODEL), loaded.filter { it.endsWith(".safetensors") })
    }

    @Test
    fun withoutTheirOwnTheNineKeysHaveFullPinyinsModels() {
        val errors = ArrayList<IOException>()
        val engines = Engines(::load, null, onError = { errors += it })
        engines.pause(Engines.T9, engines.type(Engines.T9, "64426"))
        assertEquals(
            listOf(Engines.T9_SENTENCE_MODEL, Engines.SENTENCE_MODEL, Engines.T9_REFINING_MODEL, Engines.REFINING_MODEL),
            loaded.filter { it.endsWith(".safetensors") },
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun whileTheUserPausesTheLargerModelWeighsTheReadingsTillItHasNoMoreToDo() {
        val engines = Engines(::load, null)
        val typed = engines.type(Engines.PINYIN, "nihao")
        assertTrue(typed.refines)
        val slices = engines.pause(Engines.PINYIN, typed)
        assertTrue(slices.isNotEmpty())
        assertTrue(slices.all { it.commit.isEmpty() })
        // shown only if it changed the order, and then once
        assertTrue(slices.count { it.handled } <= 1)
        assertEquals(typed.candidates.toSet(), slices.last().candidates.toSet())
        assertFalse(engines.onEvent(Engines.PINYIN, EngineEvent.REFINE, 0).handled)
    }

    @Test
    fun withNoSentenceModelsPinyinWorksAndNothingIsReported() {
        model = null
        refining = null
        val errors = ArrayList<IOException>()
        val engines = Engines(::load, null, onError = { errors += it })
        val typed = engines.type(Engines.PINYIN, "ni")
        assertEquals(listOf("你", "拟"), typed.candidates)
        assertTrue(engines.pause(Engines.PINYIN, typed).none { it.handled })
        assertEquals("拟", engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 1).commit)
        assertEquals(listOf(Engines.SENTENCE_MODEL, Engines.REFINING_MODEL), loaded.filter { it.endsWith(".safetensors") })
        assertTrue(errors.isEmpty())
    }

    @Test
    fun aSentenceModelThatCannotBeReadLeavesPinyinWorking() {
        val (small, large) = model!! to refining!!
        for (broken in listOf(Engines.SENTENCE_MODEL, Engines.REFINING_MODEL)) {
            model = if (broken == Engines.SENTENCE_MODEL) small.copyOf(100) else small
            refining = if (broken == Engines.REFINING_MODEL) large.copyOf(100) else large
            val errors = ArrayList<IOException>()
            val engines = Engines(::load, null, onError = { errors += it })
            val typed = engines.type(Engines.PINYIN, "ni")
            assertEquals(broken, listOf("你", "拟"), typed.candidates)
            assertTrue(engines.pause(Engines.PINYIN, typed).none { it.handled })
            assertEquals("拟", engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 1).commit)
            assertEquals(broken, 1, errors.size)
            assertTrue(errors[0].cause is IllegalArgumentException)
        }
    }

    private companion object {
        const val MAX_SLICES = 10_000
    }
}
