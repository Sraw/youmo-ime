/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.data.PinyinData.Section

/** A backoff model's n-grams by id in [NgramModel]'s layout, for pinyin and English data. */
internal class NgramTables(private val name: (Int) -> String) {
    private val uniProb = FloatList()
    private val uniBackoff = FloatList()
    private val biPrev = IntList()
    private val biWord = IntList()
    private val biProb = FloatList()
    private val biBackoff = FloatList()
    private val triPrev2 = IntList()
    private val triPrev = IntList()
    private val triWord = IntList()
    private val triProb = FloatList()

    fun unigram(prob: Float, backoff: Float) {
        uniProb += prob
        uniBackoff += backoff
    }

    fun unigramProb(word: Int): Float = uniProb[word]

    fun bigram(prev: Int, word: Int, prob: Float, backoff: Float) {
        biPrev += prev
        biWord += word
        biProb += prob
        biBackoff += backoff
    }

    fun trigram(prev2: Int, prev: Int, word: Int, prob: Float) {
        triPrev2 += prev2
        triPrev += prev
        triWord += word
        triProb += prob
    }

    fun write(out: DataFile.Writer, lmSize: Int) {
        val bi = order(biWord.size, lmSize, { biPrev[it] }, { biWord[it] })
        val biKeys = LongArray(bi.size) { key(biPrev[bi[it]], biWord[bi[it]]) }
        for (k in 1 until biKeys.size) require(biKeys[k - 1] != biKeys[k]) { "duplicate bigram at ${bi[k]}" }
        // each trigram's context is its leading bigram's position in sorted order
        val triContext = IntArray(triWord.size) { t ->
            val at = biKeys.binarySearch(key(triPrev2[t], triPrev[t]))
            require(at >= 0) { "trigram ${name(triPrev2[t])} ${name(triPrev[t])} ${name(triWord[t])} has no leading bigram" }
            at
        }
        val tri = order(triWord.size, bi.size, { triContext[it] }, { triWord[it] })
        for (k in 1 until tri.size) {
            require(triContext[tri[k - 1]] != triContext[tri[k]] || triWord[tri[k - 1]] != triWord[tri[k]]) { "duplicate trigram at ${tri[k]}" }
        }

        out.add(Section.UNI_PROB, floats(lmSize) { uniProb[it] })
        out.add(Section.UNI_BACKOFF, floats(lmSize) { uniBackoff[it] })
        out.add(Section.UNI_BIGRAM_START, BitPacked.encode(starts(lmSize, bi.size) { biPrev[bi[it]] }))
        out.add(Section.BI_WORD, BitPacked.encode(bi.size) { biWord[bi[it]] })
        val biProbQ = Quantizer.build(biProb.toArray(), exactZero = false)
        val biBackoffQ = Quantizer.build(biBackoff.toArray(), exactZero = true)
        out.add(Section.BI_PROB, ByteArray(bi.size) { biProbQ.encode(biProb[bi[it]]).toByte() })
        out.add(Section.BI_BACKOFF, ByteArray(bi.size) { biBackoffQ.encode(biBackoff[bi[it]]).toByte() })
        out.add(Section.BI_PROB_CODEBOOK, biProbQ.codebook())
        out.add(Section.BI_BACKOFF_CODEBOOK, biBackoffQ.codebook())
        out.add(Section.BI_TRIGRAM_START, EliasFano.encode(starts(bi.size, tri.size) { triContext[tri[it]] }))
        val triProbQ = Quantizer.build(triProb.toArray(), exactZero = false)
        out.add(Section.TRI_WORD, BitPacked.encode(tri.size) { triWord[tri[it]] })
        out.add(Section.TRI_PROB, ByteArray(tri.size) { triProbQ.encode(triProb[tri[it]]).toByte() })
        out.add(Section.TRI_PROB_CODEBOOK, triProbQ.codebook())
    }

    private fun key(a: Int, b: Int) = (a.toLong() shl 32) or b.toLong()

    private fun floats(size: Int, value: (Int) -> Float): ByteArray =
        LittleEndianOutput(size * 4).apply { for (i in 0 until size) writeFloat(value(i)) }.toByteArray()

    private inline fun starts(owners: Int, count: Int, owner: (Int) -> Int): IntArray {
        val starts = IntArray(owners + 1)
        for (i in 0 until count) starts[owner(i) + 1]++
        for (o in 0 until owners) starts[o + 1] += starts[o]
        return starts
    }

    // a counting pass, not sortedWith: that boxes millions of indices; inline so the lambdas do not box either
    private inline fun order(count: Int, majors: Int, major: (Int) -> Int, minor: (Int) -> Int): IntArray {
        val runs = starts(majors, count, major)
        val next = runs.copyOf()
        val keys = LongArray(count)
        for (i in 0 until count) keys[next[major(i)]++] = (minor(i).toLong() shl 32) or i.toLong()
        for (m in 0 until majors) keys.sort(runs[m], runs[m + 1])
        return IntArray(count) { keys[it].toInt() }
    }
}
