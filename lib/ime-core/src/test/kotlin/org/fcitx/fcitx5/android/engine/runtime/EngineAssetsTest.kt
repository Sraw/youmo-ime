/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.syl
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.rerank.SentenceModel
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.TinyModel
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
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What [EngineAssets] keeps for the pinyins' sessions to share, and when it makes it anew. */
class EngineAssetsTest : EnginesFixture() {

    private val assets = EngineAssets(::load, { throw it }, null)

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
        assets.dropDecoderExtensionsIfLearned()
        assertSame(forgotten, assets.decoderExtensions(user))
        // asked for by another model's sessions too: whose counts it read is not known
        assets.decoderExtensions(UserModel(data.dictionary, data.vocabulary))
        assets.dropDecoderExtensionsIfLearned()
        assertNotSame(forgotten, assets.decoderExtensions(user))
        assertEquals(7, assets.extensionsMade)
    }

    /** The look-ahead's picks are the model's alone, whatever the user typed. */
    @Test
    fun theLookAheadIsTheModelsThoughTheWordAheadWasTypedInFull() {
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

    @Test
    fun aSettingKeepsTheLookAheadTillSomethingWasLearned() {
        Engines({ if (it == InputMethodCatalog.PINYIN_DATA) ByteBuffer.wrap(niDing) else load(it) }, null).use { engines ->
            engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
            engines.type(InputMethodCatalog.PINYIN, "n")
            assertEquals(1, engines.extensionsMade)
            assertTrue(engines.lookAheadPicks().contains("拟定"))
            engines.settings = engines.settings.copy(pageSize = 5)
            engines.type(InputMethodCatalog.PINYIN, "n")
            assertEquals(1, engines.extensionsMade)
            assertTrue(engines.lookAheadPicks().contains("拟定"))
            repeat(PICKS) {
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
                engines.type(InputMethodCatalog.PINYIN, "nihao")
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, engines.candidates(InputMethodCatalog.PINYIN, 0, 20).indexOfFirst { it.text == "拟好" })
            }
            engines.settings = engines.settings.copy(pageSize = 6)
            engines.type(InputMethodCatalog.PINYIN, "n")
            assertEquals(2, engines.extensionsMade)
            val picks = engines.lookAheadPicks()
            assertTrue(picks.toString(), picks.contains("拟好") && !picks.contains("拟定"))
        }
    }

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

    @Test
    fun aWordIsReadAsItsFirstCharacterNeverIsAlone() {
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
        // pins the walk's cost, not a wanted reading: a walk as cheap reading liao jie may change it
        assertEquals("le jie", liao.pinyinOf("了解"))
        assertEquals("liao jie", Engines(pinyin, null).pinyinOf("了解"))
    }

    /** Walked for again with a stray first, some hundred times the walk: only the pieces of two characters or more the texts not read whole missed. */
    @Test
    fun onlyWhatATextNotReadWholeMissedIsWalkedForAgain() {
        val found = mapOf("了" to syl("le"), "解" to syl("jie"), "了解" to syl("liao", "jie"), "了解了" to syl("liao", "jie", "le"))
        // 了解了 read whole though its 解了 was not found; 了解x not, its x no word; "" no text to read
        val offsets = mapOf("了解了" to intArrayOf(0, 1, 2, 3), "了解x" to intArrayOf(0, 1, 2, 3), "" to null)
        assertEquals(setOf("解x", "了解x"), assets.strayPieces(offsets, found))
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
        assertThrows(IllegalStateException::class.java) { model.reranker(2) }
        // dropped, as when turned off, it is made again for whichever limit comes first
        model.drop()
        assertNotSame(reranker, model.reranker(2))
    }

    // models wide enough to read a context, the large unlike the small
    private val small = TinyModel(window = 32).bytes()
    private val large = TinyModel(window = 32, seed = 7).bytes()

    /** Assets whose large model [read] gives, the reads of it counted in [reads]. */
    private fun models(reads: AtomicInteger = AtomicInteger(), errors: MutableList<IOException> = ArrayList(), read: () -> ByteArray = { large }) =
        EngineAssets({
            when (it) {
                InputMethodCatalog.SENTENCE_MODEL -> ByteBuffer.wrap(small)
                InputMethodCatalog.LARGE_MODEL -> ByteBuffer.wrap(read()).also { reads.incrementAndGet() }
                else -> load(it)
            }
        }, { errors += it }, null)

    /** Random keys: each a few readings a key longer than the last, after one of a few contexts, with the decoder's scores. */
    private fun keys(seed: Long): List<Triple<String, List<String>, List<Float>>> {
        val random = Random(seed)
        val texts = listOf("我", "你", "好", "的", "天")
        val word = { (1..1 + random.nextInt(4)).joinToString("") { texts[random.nextInt(texts.size)] } }
        return (0 until PICKS).flatMap {
            val set = List(5) { word() }
            val context = listOf("", "的", "我你好", "天天的我好你我的你好")[random.nextInt(4)]
            (1..set.maxOf { it.length }).map { k ->
                Triple(context, set.map { it.take(k) }, List(5) { -5f - random.nextInt(4) * 0.1f })
            }
        }
    }

    private fun picks(picker: SentencePicker?, keys: List<Triple<String, List<String>, List<Float>>>) =
        keys.map { (context, readings, scores) -> picker?.pick(context, readings, scores) }

    @Test
    fun eachLevelPicksWithItsModels() {
        val keys = keys(1)
        val reads = AtomicInteger()
        val assets = models(reads)
        assertNull(assets.picker(ModelLevel.LOW, nineKeys = false))
        val medium = picks(Reranker(SentenceModel.load(ByteBuffer.wrap(small))), keys)
        assertEquals(medium, picks(assets.picker(ModelLevel.MEDIUM, nineKeys = false), keys))
        assertEquals(0, reads.get())
        // the large model where the small is unsure: some picks change
        val high = picks(assets.picker(ModelLevel.HIGH, nineKeys = false), keys)
        assertEquals(1, reads.get())
        assertTrue(high != medium)
        // no small model, no picker
        val none = EngineAssets({ if (it == InputMethodCatalog.SENTENCE_MODEL) throw FileNotFoundException(it) else load(it) }, {}, null)
        assertNull(none.picker(ModelLevel.HIGH, nineKeys = false))
    }

    /** Read ahead at high only where the small model reads (the large one is asked only through it); then each model reads a commit's context there, the picks the same. */
    @Test
    fun aLargeModelIsReadAheadOnlyWhereTheSmallReads() {
        val ran = AtomicInteger()
        val background = Executor {
            ran.incrementAndGet()
            it.run()
        }
        val reads = AtomicInteger()
        val noSmall = EngineAssets({
            when (it) {
                InputMethodCatalog.SENTENCE_MODEL -> throw FileNotFoundException(it)
                InputMethodCatalog.LARGE_MODEL -> ByteBuffer.wrap(large).also { reads.incrementAndGet() }
                else -> load(it)
            }
        }, {}, null)
        assertNull(noSmall.picker(ModelLevel.HIGH, nineKeys = false, background))
        assertEquals(0, ran.get())
        assertEquals(0, reads.get())
        val picker = models(reads).picker(ModelLevel.HIGH, nineKeys = false, background)!!
        assertEquals("read ahead before any key", 1, reads.get())
        val keys = keys(5)
        val expected = picks(models().picker(ModelLevel.HIGH, nineKeys = false), keys)
        val before = ran.get()
        assertEquals(expected, keys.map { (context, readings, scores) -> picker.prewarm(context).let { picker.pick(context, readings, scores) } })
        assertTrue(ran.get() > before)
    }

    /** Each model reads a commit's context on the executor for reads, the large model itself on the background one. */
    @Test
    fun aCommitsContextGoesToTheReadsAndTheLargeModelToTheBackground() {
        fun counting(n: AtomicInteger) = Executor {
            n.incrementAndGet()
            it.run()
        }
        val background = AtomicInteger()
        val reads = AtomicInteger()
        val picker = models().picker(ModelLevel.HIGH, nineKeys = false, counting(background), counting(reads))!!
        assertEquals("the large model read ahead", 1, background.get())
        assertEquals(0, reads.get())
        picker.prewarm("天天的我好你")
        assertEquals("one read per model", 2, reads.get())
        assertEquals(1, background.get())
    }

    /** Each high-level session makes a picker: the large model handed to the background once. */
    @Test
    fun theLargeModelGoesToTheBackgroundOnceHoweverManyPickers() {
        val background = AtomicInteger()
        val ran = Executor {
            background.incrementAndGet()
            it.run()
        }
        val assets = models()
        repeat(3) { assertNotNull(assets.picker(ModelLevel.HIGH, nineKeys = it == 1, ran)) }
        assertEquals(1, background.get())
    }

    /** Lowered from high, the large model read ahead is let go, so lowering the level gives back its memory. */
    @Test
    @Suppress("ExplicitGarbageCollectionCall") // whether the model is let go shows only once the GC has run
    fun aLargeModelReadAheadIsLetGoWhenDropped() {
        val assets = models()
        val read = readAhead(assets)
        assets.largeModel.drop()
        var tries = 0
        while (read.get() != null && tries++ < 100) {
            System.gc()
            Thread.sleep(10)
        }
        assertNull("held after the drop", read.get())
        // kept reachable till here: the model is let go by the assets, not with them
        assertNotNull(assets.picker(ModelLevel.MEDIUM, nineKeys = false))
    }

    // the large model read ahead at high, nothing but [assets] holding it
    private fun readAhead(assets: EngineAssets): WeakReference<SentencePicker> {
        assertNotNull(assets.picker(ModelLevel.HIGH, nineKeys = false, inPlace))
        return WeakReference(assets.largeModel.handle(Reranker.LIMIT).loaded()!!)
    }

    /** A large model that does not read, a file missing or memory short, leaves every pick the medium level's, to the bit; told once. */
    @Test
    fun aLargeModelThatCannotBeReadPicksAsMedium() {
        val keys = keys(2)
        val medium = picks(models().picker(ModelLevel.MEDIUM, nineKeys = false), keys)
        for (failure in listOf<() -> ByteArray>({ throw FileNotFoundException("gone") }, { throw OutOfMemoryError("25M") }, { byteArrayOf(1, 2, 3) })) {
            val errors = ArrayList<IOException>()
            val assets = models(errors = errors, read = failure)
            for (nineKeys in listOf(false, true)) assertEquals(medium, picks(assets.picker(ModelLevel.HIGH, nineKeys), keys))
            assertEquals(errors.toString(), 1, errors.size)
            assertTrue(InputMethodCatalog.LARGE_MODEL in errors[0].message.orEmpty())
        }
    }

    /** Read ahead on another thread while a key needs it: read once, the key waiting for that read. */
    @Test
    fun aModelReadAheadAndNeededAtOnceIsReadOnce() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val assets = models(reads) {
            started.countDown()
            assertTrue(release.await(1, TimeUnit.MINUTES))
            large
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            assets.largeModel.preload(Reranker.LIMIT, pool)
            assertTrue(started.await(1, TimeUnit.MINUTES))
            val handle = assets.largeModel.handle(Reranker.LIMIT)
            assertNull("not read yet", handle.loaded())
            release.countDown()
            val read = handle.get()
            assertNotNull(read)
            assertSame(read, handle.loaded())
            assertSame(read, assets.largeModel.reranker(Reranker.LIMIT))
            // a read ahead after it finds it read
            assets.largeModel.preload(Reranker.LIMIT, pool)
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(1, TimeUnit.MINUTES))
        }
        assertEquals(1, reads.get())
    }

    /** Dropped while still reading, as when the level is lowered, and raised again: the old read comes to nothing, a new one is made. */
    @Test
    fun aModelDroppedWhileReadingIsReadAnewWhenRaised() {
        val keys = keys(3)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        var first = true
        val assets = models(reads) {
            if (first) {
                first = false
                started.countDown()
                assertTrue(release.await(1, TimeUnit.MINUTES))
            }
            large
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val before = assets.picker(ModelLevel.HIGH, nineKeys = false)
            assets.largeModel.preload(Reranker.LIMIT, pool)
            assertTrue(started.await(1, TimeUnit.MINUTES))
            assets.largeModel.drop()
            release.countDown()
            // the dropped one's picker is the medium level's
            assertEquals(picks(assets.picker(ModelLevel.MEDIUM, nineKeys = false), keys), picks(before, keys))
            val again = picks(assets.picker(ModelLevel.HIGH, nineKeys = false), keys)
            assertEquals(picks(models().picker(ModelLevel.HIGH, nineKeys = false), keys), again)
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(1, TimeUnit.MINUTES))
        }
        assertEquals(2, reads.get())
    }

    /** Full pinyin and the nine keys in turn share the models, each picking as it would alone. */
    @Test
    fun fullPinyinAndTheNineKeysInTurnPickAsEachAlone() {
        val keys = keys(4)
        val reads = AtomicInteger()
        val assets = models(reads)
        val full = assets.picker(ModelLevel.HIGH, nineKeys = false)!!
        val nine = assets.picker(ModelLevel.HIGH, nineKeys = true)!!
        val mixed = keys.mapIndexed { i, (context, readings, scores) -> (if (i % 2 == 0) full else nine).pick(context, readings, scores) }
        val fullAlone = picks(models().picker(ModelLevel.HIGH, nineKeys = false), keys)
        val nineAlone = picks(models().picker(ModelLevel.HIGH, nineKeys = true), keys)
        assertEquals(keys.indices.map { if (it % 2 == 0) fullAlone[it] else nineAlone[it] }, mixed)
        assertTrue(fullAlone != nineAlone)
        assertEquals(1, reads.get())
    }

    // told once, never thrown
    @Test
    fun anOptionalFileThatDoesNotReadFallsBackOneWay() {
        val errors = ArrayList<IOException>()
        val bad = EngineAssets({ if (it == InputMethodCatalog.STROKE_DATA) throw IllegalArgumentException("garbled") else load(it) }, { errors += it }, null)
        assertNull(bad.strokes())
        assertNull(bad.strokes())
        assertEquals(1, errors.size)
    }

    private companion object {
        const val PICKS = 20
    }
}
