/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

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
    private var refining: ByteArray? = TinyModel().bytes()

    private val loaded = ArrayList<String>()

    private fun load(path: String): ByteBuffer {
        loaded += path
        val bytes = when (path) {
            Engines.PINYIN_DATA -> pinyin
            Engines.SENTENCE_MODEL -> model
            Engines.REFINING_MODEL -> refining
            // none of a word
            Engines.EMOJI_DATA -> ByteArray(0)
            else -> null
        }
        // as the app's assets do of a file it has not got
        return ByteBuffer.wrap(bytes ?: throw FileNotFoundException(path))
    }

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
        off.settings = EngineSettings(modelLevel = ModelLevel.LOW)
        assertEquals(listOf("你", "拟"), off.type(Engines.PINYIN, "ni").candidates)
        assertTrue(loaded.none { it in models })
        // dropped when turned off, and read again when turned back on
        engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
        engines.settings = EngineSettings()
        engines.pause(Engines.PINYIN, engines.type(Engines.PINYIN, "ni"))
        assertEquals(models, loaded.filter { it in models })
    }

    /** 中档 runs the small model at every key and never refines; 低档 neither; for every pinyin alike. */
    @Test
    fun eachLevelRunsItsModelsAndDropsThoseItNoLongerRuns() {
        val engines = Engines(::load, null)
        // what 高档 refines (two sentences, ending on a syllable)
        val high = Engines(::load, null)
        for ((im, typed) in listOf(Engines.PINYIN to "ni", Engines.SHUANGPIN to "ni", Engines.T9 to "64")) assertTrue(im, high.type(im, typed).refines)
        loaded.clear()
        engines.settings = EngineSettings(modelLevel = ModelLevel.MEDIUM)
        for ((im, typed) in listOf(Engines.PINYIN to "ni", Engines.SHUANGPIN to "ni", Engines.T9 to "64")) {
            assertFalse(im, engines.type(im, typed).refines)
            engines.onEvent(im, EngineEvent.REFINE, 0)
            engines.onEvent(im, EngineEvent.RESET, 0)
        }
        assertEquals(listOf(Engines.SENTENCE_MODEL), loaded.filter { it.endsWith(".safetensors") })
        engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
        loaded.clear()
        for ((im, typed) in listOf(Engines.PINYIN to "ni", Engines.SHUANGPIN to "ni", Engines.T9 to "64")) {
            assertFalse(im, engines.type(im, typed).refines)
            engines.onEvent(im, EngineEvent.RESET, 0)
        }
        assertTrue(loaded.none { it.endsWith(".safetensors") })
        // up to 高档 again: both read anew, the first dropped on the way down
        engines.settings = EngineSettings()
        engines.pause(Engines.PINYIN, engines.type(Engines.PINYIN, "ni"))
        assertEquals(listOf(Engines.SENTENCE_MODEL, Engines.REFINING_MODEL), loaded.filter { it.endsWith(".safetensors") })
        // down to 中档: the large one dropped, the small one kept
        loaded.clear()
        engines.settings = EngineSettings(modelLevel = ModelLevel.MEDIUM)
        engines.settings = EngineSettings()
        engines.pause(Engines.PINYIN, engines.type(Engines.PINYIN, "ni"))
        assertEquals(listOf(Engines.REFINING_MODEL), loaded.filter { it.endsWith(".safetensors") })
    }

    @Test
    fun theNineKeysReadTheModelsFullPinyinReads() {
        val errors = ArrayList<IOException>()
        val engines = Engines(::load, null, onError = { errors += it })
        engines.pause(Engines.T9, engines.type(Engines.T9, "64426"))
        assertEquals(listOf(Engines.SENTENCE_MODEL, Engines.REFINING_MODEL), loaded.filter { it.endsWith(".safetensors") })
        // the one pair in memory, whichever of the two the user types with
        loaded.clear()
        engines.pause(Engines.PINYIN, engines.type(Engines.PINYIN, "nihao"))
        assertTrue(loaded.none { it.endsWith(".safetensors") })
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
    fun withNoSentenceModelsPinyinWorksAndEachMissingOneIsReportedOnce() {
        model = null
        refining = null
        val errors = ArrayList<IOException>()
        val engines = Engines(::load, null, onError = { errors += it })
        val typed = engines.type(Engines.PINYIN, "ni")
        assertEquals(listOf("你", "拟"), typed.candidates)
        assertTrue(engines.pause(Engines.PINYIN, typed).none { it.handled })
        assertEquals("拟", engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 1).commit)
        assertEquals(listOf(Engines.SENTENCE_MODEL, Engines.REFINING_MODEL), loaded.filter { it.endsWith(".safetensors") })
        // a build that lost them (stored compressed, say) says why pinyin reads worse
        assertEquals(2, errors.size)
        assertTrue(Engines.SENTENCE_MODEL in errors[0].message.orEmpty())
        assertTrue(Engines.REFINING_MODEL in errors[1].message.orEmpty())
        assertTrue(errors.all { (it.cause ?: it) is FileNotFoundException })
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
