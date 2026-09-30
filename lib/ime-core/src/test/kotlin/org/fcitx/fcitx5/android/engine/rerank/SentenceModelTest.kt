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
        val expected = tiny.logProbs(intArrayOf(SentenceModel.BOS) + tokens)
        val got = run(model, tokens)
        for (i in tokens.indices) assertEquals("at $i", expected[i][tokens[i]], got[i].toDouble(), 1e-4)
        // floats instead of int8: the same weights, the same numbers
        assertArrayEquals(got, run(tiny.load(unpack = true), tokens), 1e-4f)
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
        // one unknown for a character beyond the BMP, not two; entries longer than a character
        // (ab) and the specials are never matched by text
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
        // the context keeps what it is and scores nothing
        assertEquals(both, scorer.score("我好", listOf("你的"))[0].toDouble(), 1e-4)
        // again, from what was kept, and for readings sharing a start
        val again = scorer.score("我好", listOf("你", "你的", ""))
        assertEquals(expected[2][4], again[0].toDouble(), 1e-4)
        assertEquals(both, again[1].toDouble(), 1e-4)
        assertEquals(Float.NEGATIVE_INFINITY, again[2])
        // another context starts over
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

    @Test
    fun whatTakesTooLongIsLeftForLater() {
        val readings = listOf("我好你", "我好的", "我天")
        val whole = model.Scorer().score("好", readings, relative = true)
        val scorer = model.Scorer()
        assertEquals(null, scorer.within("好", readings, relative = true, budget = 0))
        // what each try got done is kept for the next: a few keystrokes later it is all there
        var got: FloatArray? = null
        var tries = 0
        while (got == null) {
            got = scorer.within("好", readings, relative = true, budget = 2)
            tries++
        }
        assertTrue(tries > 1)
        assertArrayEquals(whole, got, 1e-6f)
        assertArrayEquals(whole, scorer.within("好", readings, relative = true, budget = 0), 1e-6f)
    }

    @Test
    fun onlyWhatFitsTheWindowIsRead() {
        // window 8, room for 5: 2 of the context fit, and 1 is read when it all does not
        val scorer = model.Scorer(room = 5)
        fun after(vararg tokens: Int) = tiny.logProbs(intArrayOf(SentenceModel.BOS) + tokens)[tokens.size][4].toDouble()
        assertEquals(after(5), scorer.score("的的的我好", listOf("你"))[0].toDouble(), 1e-4)
        // going on from what was read while it fits, then from the last half again
        assertEquals(after(5, 3), scorer.score("的的的我好我", listOf("你"))[0].toDouble(), 1e-4)
        assertEquals(after(5), scorer.score("的的的我好我好", listOf("你"))[0].toDouble(), 1e-4)
        assertEquals(after(6, 3), scorer.score("的我", listOf("你"))[0].toDouble(), 1e-4)
        assertEquals(after(), scorer.score("", listOf("你"))[0].toDouble(), 1e-4)
        // a reading longer than what is left is scored on what fits
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

    @Test
    fun theContextIsReadWithinTheBudgetAndOnlyWhatIsNew() {
        val scorer = model.Scorer(room = 3)
        // nothing to score: what it costs is the context, a position a try
        fun tries(context: String, budget: Int): Int {
            var tries = 1
            while (scorer.within(context, listOf(""), relative = false, budget = budget) == null) tries++
            return tries
        }
        assertEquals(3, tries("我好你", 1))
        assertEquals(null, scorer.within("我好你的", listOf(""), relative = false, budget = 0))
        assertEquals(1, tries("我好你的", 1))
        assertEquals(1, tries("我好你的", 0))
        // a context the one read is not in is read again
        assertEquals(null, scorer.within("你我", listOf(""), relative = false, budget = 0))
        assertEquals(1, tries("你我", 2))
        // committed text is read on from what was, until the window is full; then from half of it
        assertEquals(1, tries("的的你我好的", 2))
        assertEquals(2, tries("的你我好的天", 1))
        assertEquals(1, tries("你我好的天我", 1))
    }

    @Test
    fun whatWasReadIsFoundWhereTheMostOfTheContextFits() {
        val scorer = model.Scorer(room = 3)
        scorer.score("我", listOf("你"))
        assertArrayEquals(model.Scorer(room = 3).score("我好我", listOf("你")), scorer.score("我好我", listOf("你")), 1e-6f)
    }

    @Test
    fun erfIsCloseToTheTrueFunction() {
        assertEquals(0.0, SentenceModel.erf(0.0), 1e-7)
        assertEquals(0.8427007929, SentenceModel.erf(1.0), 1e-7)
        assertEquals(-0.9953222650, SentenceModel.erf(-2.0), 1e-7)
        assertEquals(1.0, SentenceModel.erf(10.0), 1e-7)
        assertEquals(0f, SentenceModel.gelu(0f), 0f)
        assertEquals(0.8413447f, SentenceModel.gelu(1f), 1e-6f)
    }

    private fun rejects(bytes: ByteArray) {
        assertThrows(IllegalArgumentException::class.java) { SentenceModel.load(ByteBuffer.wrap(bytes)) }
    }

    @Test
    fun whatIsNoModelIsRejected() {
        val good = tiny.bytes()
        rejects(good.copyOf(4))
        rejects(good.copyOf(100))
        // a header size past the file, or absurd
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
    }
}
