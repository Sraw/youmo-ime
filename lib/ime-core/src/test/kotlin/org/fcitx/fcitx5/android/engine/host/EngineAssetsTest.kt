/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer

/** What [EngineAssets] keeps for the pinyins' sessions to share, and when it makes it anew. */
class EngineAssetsTest : EnginesFixture() {

    private val assets = EngineAssets(::load, { throw it }, null)

    /** What a pause after [typed] ends with, refined slice by slice; [between] after the first, if there are more. */
    private fun Engines.refined(im: String, typed: Snapshot, between: () -> Unit = {}): List<String> {
        var s = typed
        var slices = 0
        while (s.refines) {
            s = onEvent(im, EngineEvent.REFINE, 0)
            if (++slices == 1 && s.refines) between()
            assertTrue("never done", slices < MAX_SLICES)
        }
        return s.candidates
    }

    @Test
    fun theLookAheadIsOneArrayTillDroppedThenANewOne() {
        val shared = assets.decoderExtensions
        assertEquals(assets.pinyinData.dictionary.nodeCount, shared.size)
        assertSame(shared, assets.decoderExtensions)
        shared[0] = intArrayOf(0)
        // its picks were scored by what the user had learned, which may be gone
        assets.dropDecoderExtensions()
        val fresh = assets.decoderExtensions
        assertNotSame(shared, fresh)
        assertTrue(fresh.all { it == null })
    }

    /** The look-ahead's picks, by what the user learned, are kept while a session lives: the sessions made after a change to the user's words pick anew. */
    @Test
    fun theSessionsMadeAfterTheUserWordsChangeLookAheadByWhatWasLearned() {
        // 拟 may go on to 拟好, under 拟 alone till learned
        val data = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("你", -2.2f, 0f)
            .unigram("拟", -2.4f, 0f)
            .unigram("好", -2.5f, 0f)
            .unigram("拟好", -2.6f, 0f)
            .entry("你", syl("ni"))
            .entry("拟", syl("ni"))
            .entry("好", syl("hao"))
            .entry("拟好", syl("ni", "hao"))
            .build()
            .toByteArray()
        val engines = Engines({ if (it == Engines.PINYIN_DATA) ByteBuffer.wrap(data) else load(it) }, null)
        // the decoder's reading alone
        engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
        assertEquals(listOf("你", "拟"), engines.type(Engines.PINYIN, "n").candidates.take(2))
        repeat(PICKS) {
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            engines.type(Engines.PINYIN, "nihao")
            engines.onEvent(Engines.PINYIN, EngineEvent.PICK, engines.candidates(Engines.PINYIN, 0, 20).indexOfFirst { it.text == "拟好" })
        }
        engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
        // the session's look-ahead, picked before
        assertEquals(listOf("你", "拟"), engines.type(Engines.PINYIN, "n").candidates.take(2))
        assertTrue(engines.addWord("号", "hao"))
        // 拟 scored as 拟好, learned
        assertEquals(listOf("拟", "你"), engines.type(Engines.PINYIN, "n").candidates.take(2))
    }

    /**
     * A word whose first character reads in it as it never does alone is read as the word, whole or inside a text not read
     * whole, for the settings and an import; piecewise for what a table asks as it is typed in, which the walk for it would hold up.
     */
    @Test
    fun aWordIsReadAsItsFirstCharacterNeverIsAlone() {
        // 了 alone le, liao in 了解
        val data = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("了", -2f, 0f)
            .unigram("解", -2.5f, 0f)
            .unigram("了解", -3f, 0f)
            .entry("了", syl("le"))
            .entry("解", syl("jie"))
            .entry("了解", syl("liao", "jie"))
            .build()
            .toByteArray()
        val pinyin: (String) -> ByteBuffer = { if (it == Engines.PINYIN_DATA) ByteBuffer.wrap(data) else load(it) }
        val liao = EngineAssets(pinyin, { throw it }, null)
        assertEquals("liao jie", liao.pinyinOf("了解", strayFirst = true))
        assertEquals("le liao jie", liao.pinyinOf("了了解", strayFirst = true))
        // a phrase a table saves, what a long press offers: walked with no stray first, the pieces'
        // reading, not the word's (learnSaved reads a word of the model's so read again, with a stray first).
        // This pins the walk's cost, not a reading wanted: a walk as cheap that reads it liao jie should change it
        assertEquals("le jie", liao.pinyinOf("了解"))
        // what the settings guess
        assertEquals("liao jie", Engines(pinyin, null).pinyinOf("了解"))
    }

    /** Walked for again with a stray first, some hundred times the walk: only the pieces of two characters or more the texts not read whole missed. */
    @Test
    fun onlyWhatATextNotReadWholeMissedIsWalkedForAgain() {
        val found = mapOf("了" to syl("le"), "解" to syl("jie"), "了解" to syl("liao", "jie"), "了解了" to syl("liao", "jie", "le"))
        // 了解了 read whole though its 解了 was not found; 了解x not, its x no word; "" no text to read
        val offsets = mapOf("了解了" to intArrayOf(0, 1, 2, 3), "了解x" to intArrayOf(0, 1, 2, 3), "" to null)
        assertEquals(setOf("解x", "了解x"), assets.strayPieces(offsets, found))
        // each read whole: no walk again
        assertEquals(emptySet<String>(), assets.strayPieces(offsets - "了解x", found))
    }

    /** English data that cannot be read (a stale VERSION, say) or is not there is reported: English then offers what is typed alone. */
    @Test
    fun englishDataThatCannotBeReadIsReported() {
        val errors = ArrayList<IOException>()
        val stale: (String) -> ByteBuffer = { if (it == Engines.ENGLISH_DATA) ByteBuffer.wrap(byteArrayOf(1, 2, 3)) else load(it) }
        assertNull(EngineAssets(stale, { errors += it }, null).englishData)
        val missing: (String) -> ByteBuffer = { if (it == Engines.ENGLISH_DATA) throw FileNotFoundException(it) else load(it) }
        assertNull(EngineAssets(missing, { errors += it }, null).englishData)
        assertEquals(2, errors.count { Engines.ENGLISH_DATA in it.message.orEmpty() })
        val engines = Engines(stale, null, onError = { errors += it })
        assertEquals(listOf("th"), engines.type(Engines.ENGLISH, "th").candidates)
        assertEquals(3, errors.count { Engines.ENGLISH_DATA in it.message.orEmpty() })
    }

    @Test
    fun aModelsRerankerIsMadeOnceForTheOneLimitItIsAskedWith() {
        val model = assets.sentenceModel
        val reranker = model.reranker(Reranker.LIMIT)
        assertNotNull(reranker)
        assertSame(reranker, model.reranker(Reranker.LIMIT))
        // the one made would compare as many readings as the first caller asked for
        assertThrows(IllegalStateException::class.java) { model.reranker(Reranker.REFINE_LIMIT) }
        // dropped, as when turned off, it is made again for whichever limit comes first
        model.drop()
        assertNotNull(model.reranker(Reranker.REFINE_LIMIT))
    }

    /** The pinyins' sessions share one reranker and one refiner: typing in another halfway through a refine changes nothing of its end. */
    @Test
    fun aRefineHalfDoneWhileAnotherPinyinIsTypedEndsAsItWouldAlone() {
        val alone = Engines(::load, null)
        val expected = alone.refined(Engines.PINYIN, alone.type(Engines.PINYIN, "nihao"))
        val engines = Engines(::load, null)
        var interrupted = false
        val refined = engines.refined(Engines.PINYIN, engines.type(Engines.PINYIN, "nihao")) {
            val typed = engines.type(Engines.SHUANGPIN, "ni")
            // through the refiner the pinyin's refine is half way through
            assertTrue("shuangpin refines", typed.refines)
            engines.refined(Engines.SHUANGPIN, typed)
            interrupted = true
        }
        assertTrue("refined in one slice", interrupted)
        assertEquals(expected, refined)
    }

    private companion object {
        const val MAX_SLICES = 10_000
        const val PICKS = 20
    }
}
