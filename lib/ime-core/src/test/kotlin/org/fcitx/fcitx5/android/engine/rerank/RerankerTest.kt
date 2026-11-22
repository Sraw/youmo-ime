/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.abs
import kotlin.math.ln

class RerankerTest {

    // 32 leaves room for the context
    private val model = TinyModel(window = 32).load()

    // the least fluent first, as though the decoder got it wrong
    private val readings = listOf("我好", "你好", "天的", "我你", "好天").let { r ->
        val s = model.Scorer().score("的", r)
        r.indices.sortedBy { s[it] }.map { r[it] }
    }
    private val lm = model.Scorer().score("的", readings)
    private val even = List(readings.size) { -5f }

    @Test
    fun whereTheDecoderIsUnsureTheModelDecides() {
        assertEquals(1, Reranker(model, limit = 2).pick("的", readings, even))
        assertEquals(readings.lastIndex, Reranker(model, limit = readings.size).pick("的", readings, even))
    }

    @Test
    fun whereItIsSureItsOrderHolds() {
        // what the model gains weighed against what the decoder loses, both in the decoder's log10
        val gain = Reranker.WEIGHT * (lm[1] - lm[0]) / ln(10f)
        assertEquals(0, Reranker(model, limit = 2).pick("的", readings, listOf(-5f, -5f - gain - 0.01f)))
        assertEquals(1, Reranker(model, limit = 2).pick("的", readings, listOf(-5f, -5f - gain + 0.01f)))
        assertEquals(0, Reranker(model, weight = 0f, limit = 2).pick("的", readings, listOf(-5f, -5.01f)))
    }

    /** Whether the model puts the second of [pair] first after [context]; null if the two are near even. */
    private fun prefersSecond(context: String, pair: List<String>): Boolean? {
        val s = model.Scorer().score(context, pair)
        return if (abs(s[1] - s[0]) < 0.01f) null else s[1] > s[0]
    }

    @Test
    fun whatWasWrittenBeforeCanChangeWhichGoesFirst() {
        // the weights are random: a pair of readings two contexts disagree on is looked for
        val texts = listOf("我", "你", "好", "的", "天")
        val contexts = listOf("") + texts + texts.flatMap { a -> texts.map { a + it } }
        val pairs = readings.indices.flatMap { i -> (i + 1 until readings.size).map { listOf(readings[i], readings[it]) } }
        val (pair, toSecond, toFirst) = pairs.firstNotNullOf { candidate ->
            val by = contexts.groupBy { prefersSecond(it, candidate) }
            by[true]?.first()?.let { second -> by[false]?.first()?.let { first -> Triple(candidate, second, first) } }
        }
        assertEquals(1, Reranker(model, limit = 2).pick(toSecond, pair, even.take(2)))
        assertEquals(0, Reranker(model, limit = 2).pick(toFirst, pair, even.take(2)))
    }

    /** Random readings a key at a time, each key's after one of a few contexts. */
    private fun keys(seed: Long): List<Pair<String, List<String>>> {
        val random = java.util.Random(seed)
        val texts = listOf("我", "你", "好", "的", "天")
        val word = { (1..1 + random.nextInt(4)).joinToString("") { texts[random.nextInt(texts.size)] } }
        return (0 until 20).flatMap {
            val stem = word()
            val set = List(5) { stem.take(random.nextInt(stem.length + 1)) + word() }
            val context = listOf("", "的", "我你好", "天天的我好你")[random.nextInt(4)]
            (1..set.maxOf { it.length }).map { k -> context to set.map { it.take(k) } }
        }
    }

    private fun handle(read: () -> SentencePicker?) = object : LargeHandle {
        override fun get() = read()

        override fun loaded() = null
    }

    private val priors = listOf(-5f, -5.2f, -5.1f, -5.4f, -5.3f)

    @Test
    fun aContextReadAheadPicksAsOneReadAtTheKey() {
        val warm = Reranker(model)
        val cold = Reranker(model)
        for ((context, readings) in keys(1)) {
            warm.prewarm(context)
            assertEquals("'$context' $readings", Reranker(model).pick(context, readings, priors), warm.pick(context, readings, priors))
            assertEquals("'$context' $readings", Reranker(model).pick(context, readings, priors), cold.pick(context, readings, priors))
        }
    }

    // the large model only where the small one is unsure, among the small one's first few
    @Test
    fun aGateRunsTheLargeModelOnlyWhereTheSmallIsUnsure() {
        val large = Reranker(TinyModel(window = 32, seed = 7).load())
        var asked = 0
        fun gate(margin: Float, sub: Int, disagreeing: Boolean = false) =
            GatedPicker(Reranker(model), handle { asked++; large }, margin, sub, disagreeing)
        val small = Reranker(model)
        val c = small.combined("的", readings, priors)
        val order = c.indices.sortedByDescending { c[it] }
        val sure = gate(0f, 2)
        assertEquals(small.pick("的", readings, priors), sure.pick("的", readings, priors))
        assertEquals(0L, sure.escalations)
        assertEquals(0, asked)
        // always: the large model among the small one's first two, kept in the decoder's order
        val firstTwo = order.take(2).sorted()
        val expected = firstTwo[Reranker(TinyModel(window = 32, seed = 7).load()).pick("的", firstTwo.map(readings::get), firstTwo.map(priors::get))]
        val always = gate(Float.POSITIVE_INFINITY, 2)
        assertEquals(expected, always.pick("的", readings, priors))
        assertEquals(1L, always.escalations)
        assertEquals(1, asked)
        // where the small one keeps the decoder's first, a disagreement gate leaves it
        val kept = listOf(0f, -3f, -3f, -3f, -3f)
        val disagreeing = gate(Float.POSITIVE_INFINITY, 5, disagreeing = true)
        assertEquals(0, disagreeing.pick("的", readings, kept))
        assertEquals(0L, disagreeing.escalations)
        // where the small one's first is not the decoder's, it asks the large model among the small one's first five
        assertTrue(Reranker.first(small.combined("的", readings, even)) != 0)
        assertEquals(large.pick("的", readings, even), disagreeing.pick("的", readings, even))
        assertEquals(1L, disagreeing.escalations)
        assertEquals(0, gate(Float.POSITIVE_INFINITY, 2).pick("的", readings.take(1), kept.take(1)))
        // no reading for the large model, or no margin to compare
        assertThrows(IllegalArgumentException::class.java) { gate(1f, 0) }
        assertThrows(IllegalArgumentException::class.java) { gate(Float.NaN, 2) }
    }

    /** A large model that cannot be read leaves every pick the small one's alone, as at the medium level. */
    @Test
    fun withNoLargeModelAGatePicksAsTheSmallOneAlone() {
        val medium = Reranker(model)
        val gates = listOf(
            GatedPicker(Reranker(model), handle { null }, GatedPicker.MARGIN, GatedPicker.SUB, disagreeing = false),
            GatedPicker(Reranker(model), handle { null }, GatedPicker.NINE_MARGIN, GatedPicker.NINE_SUB, disagreeing = true),
            GatedPicker(Reranker(model), handle { null }, Float.POSITIVE_INFINITY, 5, disagreeing = false),
        )
        for ((context, readings) in keys(2)) {
            val expected = medium.pick(context, readings, priors)
            for (g in gates) assertEquals("'$context' $readings", expected, g.pick(context, readings, priors))
        }
        assertTrue(gates.last().escalations > 0)
    }

    @Test
    fun theGatesThresholdsAreTheMeasuredOnes() {
        assertEquals(0x3f98fb50, GatedPicker.MARGIN.toRawBits())
        assertEquals(0x3dc54800, GatedPicker.NINE_MARGIN.toRawBits())
        assertEquals(Reranker.LIMIT, GatedPicker.NINE_SUB)
    }

    /** After a commit the context is read on another thread: none of it on the committing one, each pick as one read at the key. */
    @Test
    fun aCommitsContextIsReadAheadElsewhereAndPicksTheSame() {
        val working = java.util.Collections.synchronizedList(ArrayList<Thread>())
        val kernel = object : MatrixKernel {
            override fun times(q: java.nio.ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, y: FloatArray) =
                MatrixKernel.JVM.times(q, scales, rows, columns, x, y)

            @Suppress("LongParameterList")
            override fun timesManyQ8(q: java.nio.ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, m: Int, y: FloatArray) {
                working += Thread.currentThread()
                super.timesManyQ8(q, scales, rows, columns, x, m, y)
            }
        }
        val queued = ArrayList<Runnable>()
        val ahead = Reranker(TinyModel(window = 32).load(kernel = kernel), ahead = { queued += it })
        for (context in listOf("天天的我好你", "我你好", "的你")) {
            ahead.prewarm(context)
            assertTrue("read on the committing thread", working.isEmpty())
            queued.removeAt(0).run()
            assertTrue("not read ahead", working.isNotEmpty())
            assertEquals(Reranker(model).pick(context, readings, priors), ahead.pick(context, readings, priors))
            working.clear()
        }
        // a key before the read began reads the context itself; the read then does nothing
        ahead.prewarm("好的")
        assertEquals(Reranker(model).pick("好的", readings, priors), ahead.pick("好的", readings, priors))
        working.clear()
        queued.removeAt(0).run()
        assertTrue(working.isEmpty())
    }

    /** A key while the context is being read elsewhere waits for that read and uses it: no read of its own, the scores to the bit. */
    @Test
    fun aKeyDuringAReadAheadWaitsForItAndReadsNothingAgain() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reader = AtomicReference<Thread>()
        val calls = ConcurrentHashMap<Thread, AtomicInteger>()
        val kernel = object : MatrixKernel {
            override fun times(q: java.nio.ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, y: FloatArray) =
                MatrixKernel.JVM.times(q, scales, rows, columns, x, y)

            @Suppress("LongParameterList")
            override fun timesManyQ8(q: java.nio.ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, m: Int, y: FloatArray) {
                calls.getOrPut(Thread.currentThread()) { AtomicInteger() }.incrementAndGet()
                if (Thread.currentThread() === reader.get() && reading.count > 0) {
                    reading.countDown()
                    release.await()
                }
                super.timesManyQ8(q, scales, rows, columns, x, m, y)
            }
        }
        val context = "天天的我好你"
        val expected = Reranker(model).combined(context, readings, priors).map(Float::toRawBits)
        // a key first, so what the reader stops in is the context's read and not a first use's
        fun used(ahead: java.util.concurrent.Executor?) =
            Reranker(TinyModel(window = 32).load(kernel = kernel), ahead = ahead).apply { pick("的", readings, priors) }
        // what a key runs once its context is read
        val warm = used(null).apply { prewarm(context) }
        calls.clear()
        warm.combined(context, readings, priors)
        val scoring = calls.values.sumOf { it.get() }
        val ahead = used { Thread(it).also(reader::set).start() }
        calls.clear()
        ahead.prewarm(context)
        val key = FutureTask { ahead.combined(context, readings, priors).map(Float::toRawBits) }
        val keyThread = Thread(key)
        try {
            assertTrue(reading.await(1, TimeUnit.MINUTES))
            keyThread.start()
            // parked on the reranker's lock, or through without waiting
            fun onTheLock() = LockSupport.getBlocker(keyThread)?.javaClass?.enclosingClass == ReentrantLock::class.java
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1)
            while (!key.isDone && !onTheLock() && System.nanoTime() < deadline) Thread.sleep(1)
            assertFalse("the key did not wait for the read", key.isDone)
            assertTrue("the key is not waiting on the lock", onTheLock())
        } finally {
            release.countDown()
        }
        assertEquals(expected, key.get(1, TimeUnit.MINUTES))
        assertEquals("the key read the context again", scoring, calls[keyThread]?.get() ?: 0)
    }

    /** Reads ahead on other threads, keys and commits interleaved as they come: every pick as without them. */
    @Test
    fun readsAheadOnOtherThreadsPickAsReadsInPlace() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            fun loaded(picker: SentencePicker) = object : LargeHandle {
                override fun get() = picker

                override fun loaded() = picker
            }
            val large = TinyModel(window = 32, seed = 7).load()
            val ahead = GatedPicker.of(Reranker(model, ahead = pool), loaded(Reranker(large, ahead = pool)), nineKeys = false)
            val inPlace = GatedPicker.of(Reranker(model), loaded(Reranker(large)), nineKeys = false)
            var last: String? = null
            for ((context, readings) in keys(3)) {
                if (context != last) {
                    ahead.prewarm(context)
                    inPlace.prewarm(context)
                    last = context
                }
                assertEquals("'$context' $readings", inPlace.pick(context, readings, priors), ahead.pick(context, readings, priors))
            }
            assertTrue(ahead.escalations > 0)
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(1, java.util.concurrent.TimeUnit.MINUTES))
        }
    }

    private val chars = "一二三四五六七八九十百千万上下左右前后东南西北春夏秋冬风花雪月山水天地人你我他的是不了在有"

    // past the context that fits, so the next start's chain is read ahead
    private val wide = TinyModel(vocab = listOf("<pad>", "<unk>", "<bos>") + chars.map { it.toString() }, window = 128)

    /** A newer context asked for ends a read ahead at its next step, though a key took the ask before the read saw it. */
    @Test
    fun aReadAheadEndsForANewerContextThoughAKeyTookTheAsk() {
        val reader = AtomicReference<Thread>()
        val inStep = CountDownLatch(1)
        val release = CountDownLatch(1)
        val run = ConcurrentHashMap<Thread, AtomicInteger>()
        val limit = wide.window - 1 - SentenceModel.ROOM
        val random = java.util.Random(5)
        var context = (0 until limit + 1).joinToString("") { chars[random.nextInt(chars.length)].toString() }
        val plain = wide.load()
        while (SentenceModel.nextStart(plain.encode(context), limit, (limit + 3) / 4) < 0) context += chars[random.nextInt(chars.length)]
        val warmed = context.length - SentenceModel.tailStart(plain.encode(context), limit)
        val step = SentenceModel.NEXT_STEP * wide.layers
        val kernel = object : MatrixKernel {
            override fun times(q: java.nio.ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, y: FloatArray) =
                MatrixKernel.JVM.times(q, scales, rows, columns, x, y)

            @Suppress("LongParameterList")
            override fun timesManyQ8(q: java.nio.ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, m: Int, y: FloatArray) {
                if (rows == 3 * wide.width) {
                    val counted = run.getOrPut(Thread.currentThread()) { AtomicInteger() }
                    // the read ahead's second step begun
                    if (Thread.currentThread() === reader.get() && counted.get() == warmed * wide.layers + step && inStep.count > 0) {
                        inStep.countDown()
                        release.await()
                    }
                    counted.addAndGet(m)
                }
                super.timesManyQ8(q, scales, rows, columns, x, m, y)
            }
        }
        val queued = ArrayList<Runnable>()
        // <bos> read here, so the reader's first reads are the context's
        val counted = wide.load(kernel = kernel).apply { Scorer().prewarm("") }
        val ahead = Reranker(counted, ahead = { if (reader.get() == null) Thread(it).also(reader::set).start() else queued += it })
        ahead.prewarm(context)
        assertTrue(inStep.await(1, TimeUnit.MINUTES))
        val expected = Reranker(plain).combined(context, readings, priors).map(Float::toRawBits)
        try {
            ahead.prewarm(context + "的")
            // the context the scorer has: it takes no chain and leaves the read alone
            assertEquals(expected, ahead.combined(context, readings, priors).map(Float::toRawBits))
        } finally {
            release.countDown()
        }
        reader.get().join(TimeUnit.MINUTES.toMillis(1))
        assertEquals("the read ahead went on for a context no longer asked for", warmed * wide.layers + 2 * step, run.getValue(reader.get()).get())
        queued.single().run()
        assertEquals(expected, ahead.combined(context, readings, priors).map(Float::toRawBits))
    }

    /** A key that reads the commit's context before the read ahead does leaves the next start's chain to it, and the move takes that chain. */
    @Test
    fun aKeyBeforeTheReadAheadLeavesItTheNextChain() {
        var run = 0
        val kernel = object : MatrixKernel {
            override fun times(q: java.nio.ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, y: FloatArray) =
                MatrixKernel.JVM.times(q, scales, rows, columns, x, y)

            @Suppress("LongParameterList")
            override fun timesManyQ8(q: java.nio.ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, m: Int, y: FloatArray) {
                if (rows == 3 * wide.width) run += m
                super.timesManyQ8(q, scales, rows, columns, x, m, y)
            }
        }
        val limit = wide.window - 1 - SentenceModel.ROOM
        val plain = wide.load()
        val random = java.util.Random(5)
        fun more() = chars[random.nextInt(chars.length)].toString()
        var context = (0 until limit + 1).joinToString("") { more() }
        while (SentenceModel.nextStart(plain.encode(context), limit, (limit + 3) / 4) < 0) context += more()
        val from = SentenceModel.nextStart(plain.encode(context), limit, (limit + 3) / 4)
        var moved = context
        while (SentenceModel.tailStart(plain.encode(moved), limit) != from) moved += more()
        val counted = wide.load(kernel = kernel).apply { Scorer().prewarm("") }
        val queued = ArrayList<Runnable>()
        val ahead = Reranker(counted, ahead = { queued += it })
        ahead.prewarm(context)
        assertEquals(Reranker(plain).combined(context, readings, priors).map(Float::toRawBits), ahead.combined(context, readings, priors).map(Float::toRawBits))
        run = 0
        queued.removeAt(0).run()
        assertEquals("the next chain read", context.length - from, run / wide.layers)
        ahead.prewarm(moved)
        run = 0
        queued.removeAt(0).run()
        val next = SentenceModel.nextStart(plain.encode(moved), limit, (limit + 3) / 4)
        // what came after the chain taken, then the chain after the move
        assertEquals("the move read the tail anew", moved.length - context.length + (if (next < 0) 0 else moved.length - next), run / wide.layers)
        assertEquals(Reranker(plain).combined(moved, readings, priors).map(Float::toRawBits), ahead.combined(moved, readings, priors).map(Float::toRawBits))
    }

    /** Commits and keys as they come, the next start's chain read ahead on a thread of its own: every score as read in place. */
    @Test
    fun aChainReadAheadOnItsOwnThreadChangesNoScore() {
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val plain = wide.load()
            val main = Thread.currentThread()
            val pooled = AtomicInteger()
            val kernel = object : MatrixKernel {
                override fun times(q: java.nio.ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, y: FloatArray) =
                    MatrixKernel.JVM.times(q, scales, rows, columns, x, y)

                @Suppress("LongParameterList")
                override fun timesManyQ8(q: java.nio.ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, m: Int, y: FloatArray) {
                    if (Thread.currentThread() !== main) pooled.addAndGet(m)
                    super.timesManyQ8(q, scales, rows, columns, x, m, y)
                }
            }
            val ahead = Reranker(wide.load(kernel = kernel), ahead = pool)
            val random = java.util.Random(13)
            fun some(n: Int) = (0 until n).joinToString("") { chars[random.nextInt(chars.length)].toString() }
            var text = ""
            while (text.length < 900) {
                text += some(1 + random.nextInt(4))
                val context = text.takeLast(128)
                ahead.prewarm(context)
                repeat(1 + random.nextInt(3)) {
                    val keyed = List(5) { some(2 + random.nextInt(3)) }
                    val alone = Reranker(plain).combined(context, keyed, priors).map(Float::toRawBits)
                    assertEquals("'$context' $keyed", alone, ahead.combined(context, keyed, priors).map(Float::toRawBits))
                }
                // now and then the read ahead done before the next commit, so a commit takes a whole chain
                if (random.nextInt(4) == 0) pool.submit(Runnable {}).get(1, TimeUnit.MINUTES)
            }
            assertTrue("no chain was read ahead", pooled.get() > 0)
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(1, TimeUnit.MINUTES))
        }
    }

    @Test
    fun oneReadingIsLeftAlone() {
        assertEquals(0, Reranker(model).pick("的", readings.take(1), even))
        assertEquals(0, Reranker(model).pick("的", emptyList(), emptyList()))
    }
}
