/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.user.UserModel
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
        val shared = assets.decoderExtensions(null)
        assertEquals(assets.pinyinData.dictionary.nodeCount, shared.size)
        assertSame(shared, assets.decoderExtensions(null))
        shared[0] = intArrayOf(0)
        // its picks were scored by what the user had learned, which may be gone
        assets.dropDecoderExtensions()
        val fresh = assets.decoderExtensions(null)
        assertNotSame(shared, fresh)
        assertTrue(fresh.all { it == null })
    }

    // kept across a setting while the one user model's counts are as they were when it was made:
    // a node filled since would be filled alike now (S15 N2)
    @Test
    fun theLookAheadIsKeptTillTheCountsItReadMoveOrTwoModelsShareIt() {
        val data = assets.pinyinData
        val user = UserModel(data.dictionary, data.vocabulary)
        val held = assets.decoderExtensions(user)
        assets.dropDecoderExtensionsIfLearned()
        assertSame(held, assets.decoderExtensions(user))
        // a word counted: what a node filled before read is not what it would read now
        user.setCount(0, 1f)
        assets.dropDecoderExtensionsIfLearned()
        val again = assets.decoderExtensions(user)
        assertNotSame(held, again)
        // a pair, the counts made again, each moves them too
        user.setPair(0, 1, 1f)
        assets.dropDecoderExtensionsIfLearned()
        val third = assets.decoderExtensions(user)
        assertNotSame(again, third)
        user.clearCounts()
        assets.dropDecoderExtensionsIfLearned()
        val fourth = assets.decoderExtensions(user)
        assertNotSame(third, fourth)
        // a count read in (a pair of the record) and a word forgotten: the other two ways counts move
        user.add(0, 1, 1f)
        assets.dropDecoderExtensionsIfLearned()
        val read = assets.decoderExtensions(user)
        assertNotSame(fourth, read)
        user.forgetCounts(setOf(0))
        assets.dropDecoderExtensionsIfLearned()
        val forgotten = assets.decoderExtensions(user)
        assertNotSame(read, forgotten)
        // nothing learned since it was made: kept
        assets.dropDecoderExtensionsIfLearned()
        assertSame(forgotten, assets.decoderExtensions(user))
        // asked for by another model's sessions too: whose counts it read is not known
        assets.decoderExtensions(UserModel(data.dictionary, data.vocabulary))
        assets.dropDecoderExtensionsIfLearned()
        assertNotSame(forgotten, assets.decoderExtensions(user))
        assertEquals(7, assets.extensionsMade)
    }

    /**
     * The look-ahead's picks are the model's alone, whatever the user typed in full (D32: a short
     * edge scores no counts, look-ahead arcs included), in the sessions made after a change to the
     * user's words too.
     */
    @Test
    fun theLookAheadIsTheModelsThoughTheWordAheadWasTypedInFull() {
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
        val engines = Engines({ if (it == InputMethodCatalog.PINYIN_DATA) ByteBuffer.wrap(data) else load(it) }, null)
        // the decoder's reading alone
        engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
        assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "n").candidates.take(2))
        repeat(PICKS) {
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            engines.type(InputMethodCatalog.PINYIN, "nihao")
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, engines.candidates(InputMethodCatalog.PINYIN, 0, 20).indexOfFirst { it.text == "拟好" })
        }
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
        assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "n").candidates.take(2))
        assertTrue(engines.addWord("号", "hao"))
        // made again: 拟 as the model scores 拟好, not as learned
        assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "n").candidates.take(2))
    }

    // 拟 goes on to 拟定 as the model has them, to 拟好 once that is learned
    private val niDing: ByteArray by lazy {
        PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("你", -2.2f, 0f)
            .unigram("拟", -2.6f, 0f)
            .unigram("好", -2.5f, 0f)
            .unigram("定", -2.5f, 0f)
            .unigram("拟好", -2.5f, 0f)
            .unigram("拟定", -2.4f, 0f)
            .entry("你", syl("ni"))
            .entry("拟", syl("ni"))
            .entry("好", syl("hao"))
            .entry("定", syl("ding"))
            .entry("拟好", syl("ni", "hao"))
            .entry("拟定", syl("ni", "ding"))
            .build()
            .toByteArray()
    }

    /**
     * A setting keeps the decoders' look-ahead while nothing was learned since it was made, and
     * drops it once something was: a node filled before the learning picks then as a fresh one
     * would (the user's counts in its scores), as it did when every setting dropped it (S15 N2).
     */
    @Test
    fun aSettingKeepsTheLookAheadTillSomethingWasLearned() {
        Engines({ if (it == InputMethodCatalog.PINYIN_DATA) ByteBuffer.wrap(niDing) else load(it) }, null).use { engines ->
            engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
            engines.type(InputMethodCatalog.PINYIN, "n")
            assertEquals(1, engines.extensionsMade)
            assertTrue(engines.lookAheadPicks().contains("拟定"))
            // nothing learned: kept, its picks as they were
            engines.settings = engines.settings.copy(pageSize = 5)
            engines.type(InputMethodCatalog.PINYIN, "n")
            assertEquals(1, engines.extensionsMade)
            assertTrue(engines.lookAheadPicks().contains("拟定"))
            repeat(PICKS) {
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
                engines.type(InputMethodCatalog.PINYIN, "nihao")
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, engines.candidates(InputMethodCatalog.PINYIN, 0, 20).indexOfFirst { it.text == "拟好" })
            }
            // learned, then a setting: made anew, 拟 now going on to 拟好
            engines.settings = engines.settings.copy(pageSize = 6)
            engines.type(InputMethodCatalog.PINYIN, "n")
            assertEquals(2, engines.extensionsMade)
            val picks = engines.lookAheadPicks()
            assertTrue(picks.toString(), picks.contains("拟好") && !picks.contains("拟定"))
        }
    }

    // what keeping it rests on: no setting changes how a node's look-ahead is scored, so the array kept
    // across one picks as an array made under the new setting does
    @Test
    fun noSettingChangesWhatTheLookAheadPicks() {
        val base = EngineSettings(modelLevel = ModelLevel.LOW)
        fun engines() = Engines({ if (it == InputMethodCatalog.PINYIN_DATA) ByteBuffer.wrap(niDing) else load(it) }, null)
        assertRows(
            listOf(
                "fuzzy" to base.copy(fuzzy = setOf(Fuzzy.L_N)),
                "model level" to base.copy(modelLevel = ModelLevel.MEDIUM),
                "prediction" to base.copy(prediction = false),
                "latin words" to base.copy(latinWords = false),
                "typos" to base.copy(typos = false),
            ),
            { it.first },
        ) { (_, changed) ->
            val kept = engines().use { engines ->
                engines.settings = base
                engines.type(InputMethodCatalog.PINYIN, "n")
                engines.settings = changed
                engines.type(InputMethodCatalog.PINYIN, "n")
                assertEquals("kept across the setting", 1, engines.extensionsMade)
                engines.lookAheadPicks()
            }
            val fresh = engines().use { engines ->
                engines.settings = changed
                engines.type(InputMethodCatalog.PINYIN, "n")
                engines.lookAheadPicks()
            }
            assertEquals(fresh, kept)
        }
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
        val pinyin: (String) -> ByteBuffer = { if (it == InputMethodCatalog.PINYIN_DATA) ByteBuffer.wrap(data) else load(it) }
        val liao = EngineAssets(pinyin, { throw it }, null)
        assertEquals("liao jie", liao.pinyinOf("了解", strayFirst = true))
        assertEquals("le liao jie", liao.pinyinOf("了了解", strayFirst = true))
        // a phrase a table saves, what a long press offers: walked with no stray first, the pieces'
        // reading, not the word's (a word of the model's so read is read again, with a stray first).
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
        val stale: (String) -> ByteBuffer = { if (it == InputMethodCatalog.ENGLISH_DATA) ByteBuffer.wrap(byteArrayOf(1, 2, 3)) else load(it) }
        assertNull(EngineAssets(stale, { errors += it }, null).englishData)
        val missing: (String) -> ByteBuffer = { if (it == InputMethodCatalog.ENGLISH_DATA) throw FileNotFoundException(it) else load(it) }
        assertNull(EngineAssets(missing, { errors += it }, null).englishData)
        assertEquals(2, errors.count { InputMethodCatalog.ENGLISH_DATA in it.message.orEmpty() })
        val engines = Engines(stale, null, onError = { errors += it })
        assertEquals(listOf("th"), engines.type(InputMethodCatalog.ENGLISH, "th").candidates)
        assertEquals(3, errors.count { InputMethodCatalog.ENGLISH_DATA in it.message.orEmpty() })
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
        val expected = alone.refined(InputMethodCatalog.PINYIN, alone.type(InputMethodCatalog.PINYIN, "nihao"))
        val engines = Engines(::load, null)
        var interrupted = false
        val refined = engines.refined(InputMethodCatalog.PINYIN, engines.type(InputMethodCatalog.PINYIN, "nihao")) {
            val typed = engines.type(InputMethodCatalog.SHUANGPIN, "ni")
            // through the refiner the pinyin's refine is half way through
            assertTrue("shuangpin refines", typed.refines)
            engines.refined(InputMethodCatalog.SHUANGPIN, typed)
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
