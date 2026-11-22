/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.exp

class SentenceModelTest {

    private val tiny = TinyModel()
    private val model = tiny.load()

    /** log P of each token after the ones before it, the first after `<bos>`, run node by node. */
    private fun run(model: SentenceModel, tokens: IntArray): FloatArray {
        var node = model.next(null, SentenceModel.BOS)
        return FloatArray(tokens.size) { i -> model.logProb(node, tokens[i]).also { node = model.next(node, tokens[i]) } }
    }

    @Test
    fun itComputesWhatTheTextbookPassDoes() {
        val tokens = intArrayOf(3, 5, 4, 1, 7, 6)
        // and 12 wide: the kernels take eight columns at a time, and these leave four over
        for (t in listOf(tiny, TinyModel(width = 12))) {
            val expected = t.logProbs(intArrayOf(SentenceModel.BOS) + tokens)
            val got = run(t.load(), tokens)
            for (i in tokens.indices) assertEquals("${t.width} wide, at $i", expected[i][tokens[i]], got[i].toDouble(), 1e-4)
            // unpacked, nothing is rounded to int8
            val floats = t.logProbs(intArrayOf(SentenceModel.BOS) + tokens, q8 = false)
            val unpacked = run(t.load(unpack = true), tokens)
            for (i in tokens.indices) assertEquals("${t.width} wide, unpacked, at $i", floats[i][tokens[i]], unpacked[i].toDouble(), 1e-4)
        }
    }

    @Test
    fun int8WeightsAreMultipliedByTheKernelGivenOnInt8Activations() {
        val tokens = intArrayOf(3, 5, 4, 1, 7, 6)
        val shapes = HashSet<Pair<Int, Int>>()
        var floats = 0
        val kernel = object : MatrixKernel {
            override fun times(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, y: FloatArray) {
                floats++
            }

            override fun timesManyQ8(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, m: Int, y: FloatArray) {
                shapes += rows to columns
                // the app's kernel refuses a slice of the file that has more than its matrix
                assertEquals(rows * columns, q.capacity())
                MatrixKernel.JVM.timesManyQ8(q, scales, rows, columns, x, m, y)
            }
        }
        val got = run(SentenceModel.load(ByteBuffer.wrap(tiny.bytes()), kernel = kernel), tokens)
        assertArrayEquals(run(model, tokens), got, 0f)
        assertEquals(0, floats)
        val w = tiny.width
        assertEquals(setOf(tiny.vocab.size to w, 3 * w to w, 4 * w to w, w to 4 * w), shapes)
    }

    /** Each node of a batch, its parents in it or not, holds the floats it holds run alone. */
    @Test
    fun aBatchRunsEachNodeAsItRunsAlone() {
        val root = model.next(null, SentenceModel.BOS)
        val after = model.next(root, 3)
        // 你 and 好 after 我; 的 after that 你; 天 after <bos>
        val batch = model.nextMany(listOf(after, after, null, root), intArrayOf(-1, -1, 0, -1), intArrayOf(4, 5, 6, 7))
        val alone = listOf(model.next(after, 4), model.next(after, 5)).let { it + model.next(it[0], 6) + model.next(root, 7) }
        for (j in batch.indices) {
            assertArrayEquals("$j", alone[j].out, batch[j].out, 0f)
            for (l in 0 until tiny.layers) assertArrayEquals("$j at $l", alone[j].keys[l], batch[j].keys[l], 0f)
        }
        assertEquals(batch[0], batch[2].parent)
        model.logSums(batch)
        for (j in batch.indices) assertEquals("$j", model.logSum(alone[j]), batch[j].logSum, 0f)
        assertEquals(emptyList<SentenceModel.Node>(), model.nextMany(emptyList(), IntArray(0), IntArray(0)))
    }

    /** Denominators of more nodes than run at once: each the bits it has alone, no more than [SentenceModel.SUM_ROWS] held at a time. */
    @Test
    fun manyDenominatorsRunABoundedBatchAtATime() {
        var most = 0
        val kernel = object : MatrixKernel {
            override fun times(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, y: FloatArray) =
                MatrixKernel.JVM.times(q, scales, rows, columns, x, y)

            override fun logSumsF(logits: FloatArray, m: Int, vocab: Int, out: FloatArray) {
                most = maxOf(most, m)
                super.logSumsF(logits, m, vocab, out)
            }
        }
        fun tree(model: SentenceModel): List<SentenceModel.Node> {
            val tokens = 3 until tiny.vocab.size
            val firsts = tokens.map { model.next(model.next(null, SentenceModel.BOS), it) }
            return firsts + firsts.flatMap { f -> tokens.map { model.next(f, it) } }
        }
        val counted = tiny.load(kernel = kernel)
        val nodes = tree(counted)
        assertTrue(nodes.size > SentenceModel.SUM_ROWS + 1)
        counted.logSums(nodes)
        assertEquals(SentenceModel.SUM_ROWS, most)
        val one = tiny.load()
        val alone = tree(one).map { one.logSum(it).toRawBits() }
        assertEquals(alone, nodes.map { it.logSum.toRawBits() })
    }

    @Test
    fun whatFollowsIsADistribution() {
        val node = model.next(model.next(null, SentenceModel.BOS), 3)
        val total = (0 until tiny.vocab.size).sumOf { exp(model.logProb(node, it).toDouble()) }
        assertEquals(1.0, total, 1e-5)
    }

    @Test
    fun charactersAreTokensAndTheRestUnknown() {
        assertArrayEquals(intArrayOf(3, 5, 1, 4), model.encode("我好x你"))
        // one unknown per non-BMP character; longer entries and specials never match text
        assertArrayEquals(intArrayOf(3, 1, 1, 1), model.encode("我\uD840\uDC00ab"))
        assertArrayEquals(intArrayOf(1, 1, 1, 1, 1), model.encode("<bos>"))
        assertEquals("Apache-2.0", model.license)
        assertEquals("made up in a test", model.attribution)
    }

    @Test
    fun aScoreIsTheLogProbabilityOfTheReading() {
        val scorer = model.Scorer(room = 3)
        val expected = tiny.logProbs(intArrayOf(SentenceModel.BOS, 3, 5, 4, 6))
        val both = expected[2][4] + expected[3][6]
        assertEquals(both, scorer.score("我好", listOf("你的"))[0].toDouble(), 1e-4)
        val again = scorer.score("我好", listOf("你", "你的", ""))
        assertEquals(expected[2][4], again[0].toDouble(), 1e-4)
        assertEquals(both, again[1].toDouble(), 1e-4)
        assertEquals(Float.NEGATIVE_INFINITY, again[2])
        val other = tiny.logProbs(intArrayOf(SentenceModel.BOS, 4, 4))
        assertEquals(other[1][4], scorer.score("你", listOf("你"))[0].toDouble(), 1e-4)
    }

    @Test
    fun relativeScoresDifferAsTheWholeOnesDo() {
        // sharing a start, one the start of another, and none shared at all
        for (readings in listOf(listOf("我好你", "我好的", "我天"), listOf("你好", "你好的"), listOf("我", "你的天"))) {
            val whole = model.Scorer().score("好", readings)
            val relative = model.Scorer().score("好", readings, relative = true)
            for (i in readings.indices) assertEquals("$readings $i", whole[i] - whole[0], relative[i] - relative[0], 1e-4f)
        }
    }

    /** What a scorer kept, from the last key or other readings, changes no score, not a bit. */
    @Test
    fun scoresAreTheSameWhateverWasKept() {
        val readings = listOf("我好你", "你好的", "天的我")
        val fresh = model.Scorer().score("好", readings, relative = true)
        val tight = model.Scorer(limit = 3)
        val roomy = model.Scorer()
        for (scorer in listOf(tight, roomy)) {
            // a key at a time, then other readings, then these again
            for (k in 1..3) scorer.score("好", readings.map { it.take(k) }, relative = true)
            scorer.score("好", listOf("好的", "的好"))
            assertArrayEquals(fresh, scorer.score("好", readings, relative = true), 0f)
        }
    }

    @Test
    fun onlyWhatFitsTheWindowIsRead() {
        // window 8, room for 5: 2 of the context fit, and 1 from tailStart is read when it all does not
        val scorer = model.Scorer(room = 5)
        fun after(vararg tokens: Int) = tiny.logProbs(intArrayOf(SentenceModel.BOS) + tokens)[tokens.size][4].toDouble()
        fun tail(text: String) = model.encode(text).let { it.copyOfRange(SentenceModel.tailStart(it, 2), it.size) }
        for (text in listOf("的的的我好", "的的的我好我", "的的的我好我好", "天我好")) {
            assertEquals(text, after(*tail(text)), scorer.score(text, listOf("你"))[0].toDouble(), 1e-4)
        }
        assertEquals(after(6, 3), scorer.score("的我", listOf("你"))[0].toDouble(), 1e-4)
        assertEquals(after(), scorer.score("", listOf("你"))[0].toDouble(), 1e-4)
        val long = "我".repeat(20)
        val fit = "我".repeat(tiny.window)
        val whole = model.Scorer()
        assertEquals(whole.score("", listOf(fit))[0], whole.score("", listOf(long))[0], 1e-6f)
        assertThrows(IllegalArgumentException::class.java) {
            var node = model.next(null, SentenceModel.BOS)
            repeat(tiny.window) { node = model.next(node, 3) }
        }
        assertThrows(IllegalArgumentException::class.java) { model.next(null, tiny.vocab.size) }
    }

    /** A context is read the same whatever was read before, or read ahead of the key: the scores depend on (context, readings) alone. */
    @Test
    fun theContextIsReadAsTheContextAloneSays() {
        val big = TinyModel(window = 32).load()
        val readings = listOf("我好", "你的", "天")
        val texts = listOf("我", "你", "好", "的", "天")
        val random = java.util.Random(20261121)
        val contexts = List(40) { (0 until random.nextInt(14)).joinToString("") { texts[random.nextInt(texts.size)] } }
        // room 20: 11 of the context fit, beyond it 6 to 8 are read
        val going = big.Scorer(room = 20)
        val warmed = big.Scorer(room = 20)
        var text = ""
        for (context in contexts + contexts.map { text.also { text += it } }) {
            val alone = big.Scorer(room = 20).score(context, readings, relative = true)
            assertArrayEquals("'$context'", alone, going.score(context, readings, relative = true), 0f)
            warmed.prewarm(context)
            assertArrayEquals("'$context' read ahead", alone, warmed.score(context, readings, relative = true), 0f)
            // nor what lies before the 12 tokens the start is chosen from
            val end = context.takeLast(12 + random.nextInt(4))
            if (context.length > 12) assertArrayEquals("'$context' as '$end'", alone, going.score(end, readings, relative = true), 0f)
        }
    }

    /**
     * A long text's start is its last tokens' alone: half to three quarters of what fits, the same whatever came
     * before them, and kept as text is added until it no longer fits or the start just let in hashes higher.
     */
    @Test
    fun aLongContextIsReadFromAStartTheTextAloneSays() {
        val random = java.util.Random(20261121)
        val text = IntArray(3000) { 3 + random.nextInt(200) }
        for (limit in listOf(1, 2, 39, 103)) {
            val shortest = (limit + 1) / 2
            val longest = maxOf(shortest, 3 * limit / 4)
            var last = -1
            var moves = 0
            for (n in limit + 1..text.size) {
                val start = SentenceModel.tailStart(text.copyOf(n), limit)
                assertTrue("$limit at $n: $start", n - start in shortest..longest)
                // what lies before the last limit + 1 tokens changes nothing
                val from = n - limit - 1 - random.nextInt(n - limit)
                assertEquals("$limit at $n from $from", start, from + SentenceModel.tailStart(text.copyOfRange(from, n), limit))
                if (last >= 0 && start != last) {
                    moves++
                    assertTrue("$limit at $n: moved from $last to $start", n - last > longest || start == n - shortest)
                }
                last = start
            }
            // a start held while it is in range: about twice a range's width apart
            if (limit >= 39) assertTrue("$limit: moved $moves times", moves < text.size * 3 / (longest - shortest + 1))
        }
        assertEquals(5, SentenceModel.tailStart(IntArray(5) { 3 }, 0))
        assertEquals(0, SentenceModel.tailStart(IntArray(5) { 3 }, 5))
    }

    /** Text added to a long context is all that is read of it, mostly: the start moves seldom. */
    @Test
    fun theNextStartIsKnownWhateverTextFollows() {
        val random = java.util.Random(3)
        val text = IntArray(2000) { 3 + random.nextInt(200) }
        for (limit in listOf(39, 103)) {
            val within = (limit + 3) / 4
            var positions = 0
            var known = 0
            for (n in limit - within..text.size - within) {
                val next = SentenceModel.nextStart(text.copyOf(n), limit, within)
                // where the start goes as the text goes on, and as other text would
                for (more in listOf(text.copyOf(n + within), text.copyOf(n) + IntArray(within) { 3 + random.nextInt(200) })) {
                    val now = SentenceModel.tailStart(more.copyOf(n), limit)
                    val moved = (n + 1..n + within).map { SentenceModel.tailStart(more.copyOf(it), limit) }.firstOrNull { it != now }
                    if (next >= 0) assertEquals("$limit at $n", moved, next)
                }
                if (next >= 0) known++
                if (n > limit) positions++
            }
            assertTrue("$limit: $known of $positions known", known > positions * 9 / 10)
        }
        assertEquals(-1, SentenceModel.nextStart(IntArray(5) { 3 }, 0, 4))
    }

    /** The next start's chain read ahead, whole, one step of it or not at all, changes no score; read, a commit reads less. */
    @Test
    fun aChainReadAheadChangesNoScore() {
        val chars = "一二三四五六七八九十百千万上下左右前后东南西北春夏秋冬风花雪月山水天地人你我他的是不了在有"
        val t = TinyModel(vocab = listOf("<pad>", "<unk>", "<bos>") + chars.map { it.toString() }, window = 128)
        var run = 0
        val kernel = object : MatrixKernel {
            override fun times(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, y: FloatArray) =
                MatrixKernel.JVM.times(q, scales, rows, columns, x, y)

            override fun timesManyQ8(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, m: Int, y: FloatArray) {
                if (rows == 3 * t.width) run += m
                super.timesManyQ8(q, scales, rows, columns, x, m, y)
            }
        }
        val counted = t.load(kernel = kernel)
        val plain = t.load()
        val scorers = listOf(counted.Scorer(), counted.Scorer(readsNext = true), counted.Scorer(readsNext = true))
        val read = IntArray(scorers.size)
        val readings = listOf("我你", "天地")
        val random = java.util.Random(11)
        var text = ""
        while (text.length < 900) {
            text += (0 until 1 + random.nextInt(4)).joinToString("") { chars[random.nextInt(chars.length)].toString() }
            val context = text.takeLast(128)
            val alone = plain.Scorer().score(context, readings)
            for ((i, scorer) in scorers.withIndex()) {
                run = 0
                scorer.prewarm(context)
                if (text.length > 300) read[i] += run / t.layers
                assertArrayEquals("$i '$context'", alone, scorer.score(context, readings), 0f)
                var steps = 0
                if (i == 1) scorer.readNext(context)
                if (i == 2) scorer.readNext(context) { steps++ > 0 }
            }
        }
        assertTrue(read.joinToString(), read[1] < read[0] / 2 && read[2] < read[0])
    }

    /**
     * A key taking another context while the chain is read ahead waits for the step being read and no other, takes
     * the half-read chain and scores as a scorer of its own; the read ends there.
     */
    @Test
    fun aKeyWaitsForTheStepOfAReadAheadAndNoMore() {
        val chars = "一二三四五六七八九十百千万上下左右前后东南西北春夏秋冬风花雪月山水天地人你我他的是不了在有"
        val t = TinyModel(vocab = listOf("<pad>", "<unk>", "<bos>") + chars.map { it.toString() }, window = 128)
        val reader = AtomicReference<Thread>()
        val inStep = CountDownLatch(1)
        val release = CountDownLatch(1)
        val run = ConcurrentHashMap<Thread, AtomicInteger>()
        val step = SentenceModel.NEXT_STEP * t.layers
        val kernel = object : MatrixKernel {
            override fun times(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, y: FloatArray) =
                MatrixKernel.JVM.times(q, scales, rows, columns, x, y)

            override fun timesManyQ8(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, m: Int, y: FloatArray) {
                if (rows == 3 * t.width) {
                    val counted = run.getOrPut(Thread.currentThread()) { AtomicInteger() }
                    // the reader's second step begun: on the chain's lock
                    if (Thread.currentThread() === reader.get() && counted.get() == step && inStep.count > 0) {
                        inStep.countDown()
                        release.await()
                    }
                    counted.addAndGet(m)
                }
                super.timesManyQ8(q, scales, rows, columns, x, m, y)
            }
        }
        val counted = t.load(kernel = kernel)
        val limit = t.window - 1 - SentenceModel.ROOM
        val random = java.util.Random(5)
        fun more() = chars[random.nextInt(chars.length)].toString()
        // a context whose start moves soon, and the text that moves it
        var context = (0 until limit + 1).joinToString("") { more() }
        while (SentenceModel.nextStart(counted.encode(context), limit, (limit + 3) / 4) < 0) context += more()
        val from = SentenceModel.nextStart(counted.encode(context), limit, (limit + 3) / 4)
        var moved = context
        while (SentenceModel.tailStart(counted.encode(moved), limit) != from) moved += more()
        assertTrue("${moved.length - from} of ${context.length - from}", context.length - from > 3 * SentenceModel.NEXT_STEP)
        val readings = listOf("我你", "天地")
        val alone = counted.Scorer()
        // <bos> read first, so what is counted is the context's and the readings'
        alone.prewarm("")
        run.clear()
        val expected = alone.score(moved, readings)
        val readAlone = run.values.sumOf { it.get() }

        val scorer = counted.Scorer(readsNext = true)
        scorer.prewarm(context)
        val reading = Thread { scorer.readNext(context) }.also(reader::set)
        reading.start()
        assertTrue(inStep.await(1, TimeUnit.MINUTES))
        val key = FutureTask { scorer.score(moved, readings) }
        val keyThread = Thread(key).apply { start() }
        try {
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1)
            while (LockSupport.getBlocker(keyThread) == null && !key.isDone && System.nanoTime() < deadline) Thread.yield()
            assertTrue("the key is not waiting on the chain's lock", LockSupport.getBlocker(keyThread) != null)
        } finally {
            release.countDown()
        }
        assertArrayEquals(expected, key.get(1, TimeUnit.MINUTES), 0f)
        reading.join(TimeUnit.MINUTES.toMillis(1))
        assertEquals("the read ahead went on past the key", 2 * step, run.getValue(reading).get())
        assertEquals("the key did not take the two steps read", readAlone - 2 * step, run.getValue(keyThread).get())
    }

    @Test
    fun aCommitReadsWhatItAddedNotTheWholeContext() {
        val chars = "一二三四五六七八九十百千万上下左右前后东南西北春夏秋冬风花雪月山水天地人你我他的是不了在有"
        val t = TinyModel(vocab = listOf("<pad>", "<unk>", "<bos>") + chars.map { it.toString() }, window = 128)
        var run = 0
        val kernel = object : MatrixKernel {
            override fun times(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, y: FloatArray) =
                MatrixKernel.JVM.times(q, scales, rows, columns, x, y)

            override fun timesManyQ8(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, m: Int, y: FloatArray) {
                // the attention's input, once a layer for each node run
                if (rows == 3 * t.width) run += m
                super.timesManyQ8(q, scales, rows, columns, x, m, y)
            }
        }
        val counted = t.load(kernel = kernel)
        val scorer = counted.Scorer()
        val limit = t.window - 1 - SentenceModel.ROOM
        val random = java.util.Random(7)
        var text = ""
        var commits = 0
        while (text.length < 1200) {
            text += (0 until 1 + random.nextInt(4)).joinToString("") { chars[random.nextInt(chars.length)].toString() }
            // as the session keeps it: its last 128 chars
            scorer.prewarm(text.takeLast(128))
            if (text.length > 300) commits++ else run = 0
        }
        val tokens = run / t.layers
        // a start moved every commit (half of what fits) would read limit / 2 each
        assertTrue("$tokens tokens over $commits commits", tokens < commits * (limit / 2) / 3)
        val readings = listOf("我你", "天地")
        assertArrayEquals(t.load().Scorer().score(text.takeLast(128), readings), scorer.score(text.takeLast(128), readings), 0f)
    }

    private fun rejects(bytes: ByteArray) {
        assertThrows(IllegalArgumentException::class.java) { SentenceModel.load(ByteBuffer.wrap(bytes)) }
    }

    @Test
    fun whatIsNoModelIsRejected() {
        val good = tiny.bytes()
        rejects(good.copyOf(4))
        rejects(good.copyOf(100))
        rejects(good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(0, good.size.toLong()) })
        rejects(good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(0, -1) })
        // the data cut short: the last tensor's range runs past the end
        rejects(good.copyOf(good.size - 1))
        rejects(tiny.bytes(mapOf("format" to "gguf")))
        rejects(tiny.bytes(mapOf("version" to "2")))
        rejects(tiny.bytes(mapOf("config" to "{\"vocab\":9,\"n_layer\":2,\"n_head\":3,\"n_embd\":8,\"context\":8}")))
        rejects(tiny.bytes(mapOf("config" to "{\"vocab\":9,\"n_layer\":2,\"n_head\":2,\"n_embd\":8,\"context\":1e9}")))
        rejects(tiny.bytes(mapOf("config" to "{\"vocab\":9,\"n_layer\":2,\"n_head\":2,\"n_embd\":8}")))
        rejects(tiny.bytes(mapOf("config" to "[]")))
        rejects(tiny.bytes(mapOf("vocab" to "[\"a\"]")))
        rejects(tiny.bytes(mapOf("vocab" to "[1,2,3,4,5,6,7,8,9]")))
        // a tensor missing, or of another shape or type than the config says
        rejects(TinyModel().apply { tensors.remove("ln_f.bias") }.bytes())
        rejects(TinyModel().apply { tensors["ln_f.bias"] = intArrayOf(7) to FloatArray(7) }.bytes())
        rejects(TinyModel().apply { quantized += "ln_f.bias"; tensors["ln_f.bias"] = intArrayOf(1, 8) to FloatArray(8) }.bytes())
    }

    @Test
    fun jsonIsReadAsASafetensorsHeaderNeedsIt() {
        assertEquals(
            mapOf("a" to listOf(1.0, -2.5e3, true, false, null), "b\"/\n" to "\u4f60", "c" to emptyMap<String, Any>(), "d" to emptyList<Any>()),
            Json.parse(" {\"a\": [1, -2.5e3, true, false, null], \"b\\\"\\/\\n\": \"\\u4f60\", \"c\": {}, \"d\": []} "),
        )
        assertEquals("\b\u000c\r\t\\", Json.parse("\"\\b\\f\\r\\t\\\\\""))
        for (bad in listOf("", "{", "[1,", "[1 2]", "{\"a\" 1}", "{\"a\":1 \"b\":2}", "{1:2}", "\"abc", "\"\\x\"", "\"\\u12\"", "\"\\uzzzz\"", "\"\\u+123\"", "nul", "1-", "[] x", "\"\\")) {
            assertThrows(bad, IllegalArgumentException::class.java) { Json.parse(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { Json.parse("[".repeat(Json.MAX_DEPTH + 2) + "]".repeat(Json.MAX_DEPTH + 2)) }
        assertTrue(Json.parse("[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH)) is List<*>)
        assertThrows(IllegalArgumentException::class.java) { Json.parse("[" + "0,".repeat(Json.MAX_VALUES) + "0]") }
        // at the limits: the depth counted from 0, so one array more than MAX_DEPTH; the array a value too
        assertTrue(Json.parse("[".repeat(Json.MAX_DEPTH + 1) + "]".repeat(Json.MAX_DEPTH + 1)) is List<*>)
        assertEquals(Json.MAX_VALUES - 1, (Json.parse("[" + "0,".repeat(Json.MAX_VALUES - 2) + "0]") as List<*>).size)
        assertThrows(IllegalArgumentException::class.java) { Json.parse("[" + "0,".repeat(Json.MAX_VALUES - 1) + "0]") }
    }
}
