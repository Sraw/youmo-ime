/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A read-only non-decreasing array of non-negative ints in Elias-Fano form: where each bigram's run of
 * trigrams starts in the language model. [BitPacked] stored those in 25 bits each, 43 MB of the pinyin
 * model; here they take some 3 bits, and its file 38 MB less. A lookup costs more than [BitPacked]'s,
 * so the unigrams' run starts, looked up for every bigram and only 1 MB, stay [BitPacked]: in
 * Elias-Fano they cost the decoder 70% at the 95th percentile of a key.
 *
 * Each value is split into its [lowWidth] low bits, packed as [BitPacked] packs them, and the rest,
 * written in unary into the upper bit array: value i sets bit `(value ushr lowWidth) + i`. Value i's
 * high part is then the position of the i-th set bit, less i. Finding that bit from the start would
 * be slow, so the position of every [SAMPLE]th set bit is kept: a lookup starts there and counts the
 * set bits of a word or two.
 *
 * Layout: `u32 lowWidth, u32 size, u32 upperWords, u32 sampleCount`, then the low bits and the upper bits
 * as little-endian 64-bit words, then the samples as u32s.
 */
class EliasFano(buffer: ByteBuffer) {

    // a view of its own: ByteBuffer.wrap's buffers are big-endian
    private val buffer: ByteBuffer = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)

    val size: Int
    private val lowWidth: Int
    private val lowMask: Long
    private val upper: Int
    private val samples: Int

    init {
        val b = this.buffer
        require(b.capacity() >= HEADER_BYTES) { "truncated header" }
        lowWidth = b.getInt(0)
        size = b.getInt(4)
        val upperWords = b.getInt(8)
        val sampleCount = b.getInt(12)
        require(lowWidth in 0..BitPacked.MAX_WIDTH) { "bad low width $lowWidth" }
        require(size >= 0) { "bad size $size" }
        // bit positions are ints: more upper words than that and a lookup would wrap onto another word
        require(upperWords in 0..MAX_UPPER_WORDS && sampleCount == samplesFor(size)) { "bad upper bits or samples" }
        // in longs: a damaged count would overflow an int past the check
        val upperAt = HEADER_BYTES + lowWords(lowWidth, size) * 8L
        val samplesAt = upperAt + upperWords * 8L
        require(b.capacity().toLong() >= samplesAt + sampleCount * 4L) { "truncated: $size values" }
        upper = upperAt.toInt()
        samples = samplesAt.toInt()
        lowMask = (1L shl lowWidth) - 1
    }

    operator fun get(index: Int): Int {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("$index of $size")
        return value(index, select(index))
    }

    /** Values [index] and [index] + 1 together: a run's start and end. */
    fun run(index: Int): Run {
        if (index < 0 || index >= size - 1) throw IndexOutOfBoundsException("$index and the next of $size")
        val bit = select(index)
        return Run(value(index, bit), value(index + 1, nextSet(bit + 1)))
    }

    /** A run's [start] and [end], held in one long: the decoder looks one up for each word it scores, and none is an object. */
    @JvmInline
    value class Run private constructor(private val bits: Long) {

        constructor(start: Int, end: Int) : this((start.toLong() shl Int.SIZE_BITS) or (end.toLong() and 0xffffffffL))

        val start: Int get() = (bits ushr Int.SIZE_BITS).toInt()
        val end: Int get() = bits.toInt()
    }

    /**
     * Checks that this is a table of run starts: [first] first, never decreasing, [end] last; and that
     * every lookup will find its bit, the samples pointing where they should. Lookups index other
     * arrays with these values unchecked, so a table that got this far must not point outside them.
     * Reads the table through once, a few MB, at load.
     */
    fun checkRuns(what: String, first: Int, end: Int) {
        ensureFormat(size >= 1) { "$what: empty" }
        val words = (samples - upper) / 8
        var w = 0
        var bits = if (words > 0) word(0) else 0L
        var previous = first
        for (i in 0 until size) {
            while (bits == 0L) {
                w++
                ensureFormat(w < words) { "$what: $i of $size values" }
                bits = word(w)
            }
            val bit = w * 64 + java.lang.Long.numberOfTrailingZeros(bits)
            bits = bits and (bits - 1)
            if (i % SAMPLE == 0) ensureFormat(buffer.getInt(samples + i / SAMPLE * 4) == bit) { "$what: sample ${i / SAMPLE}" }
            val v = value(i, bit)
            ensureFormat(if (i == 0) v == first else v >= previous) { if (i == 0) "$what: does not start at $first" else "$what: decreases at $i" }
            previous = v
        }
        ensureFormat(previous == end) { "$what: ends at $previous, not $end" }
    }

    private fun word(w: Int): Long = buffer.getLong(upper + w * 8)

    /** The position in the upper bits of the set bit of value [index]. */
    private fun select(index: Int): Int {
        val start = buffer.getInt(samples + index / SAMPLE * 4)
        var left = index % SAMPLE
        var w = start ushr 6
        var bits = word(w) and (-1L shl (start and 63))
        while (true) {
            val count = java.lang.Long.bitCount(bits)
            if (left < count) break
            left -= count
            bits = word(++w)
        }
        return w * 64 + selectInWord(bits, left)
    }

    /** The position of the set bit of [bits] with [rank] set bits below it: six halvings of the word, not up to 63 bits cleared one by one. */
    private fun selectInWord(bits: Long, rank: Int): Int {
        var x = bits
        var r = rank
        var at = 0
        var width = 32
        while (width > 0) {
            val count = java.lang.Long.bitCount(x and ((1L shl width) - 1))
            if (r >= count) {
                r -= count
                x = x ushr width
                at += width
            }
            width = width shr 1
        }
        return at
    }

    /** The first set bit at or after [from]. */
    private fun nextSet(from: Int): Int {
        var w = from ushr 6
        var bits = word(w) and (-1L shl (from and 63))
        while (bits == 0L) bits = word(++w)
        return w * 64 + java.lang.Long.numberOfTrailingZeros(bits)
    }

    private fun value(index: Int, bit: Int): Int {
        val high = (bit - index).toLong() shl lowWidth
        if (lowWidth == 0) return high.toInt()
        val at = index.toLong() * lowWidth
        val word = HEADER_BYTES + (at ushr 6).toInt() * 8
        val shift = (at and 63).toInt()
        var low = buffer.getLong(word) ushr shift
        if (shift + lowWidth > 64) low = low or (buffer.getLong(word + 8) shl (64 - shift))
        return (high or (low and lowMask)).toInt()
    }

    companion object {
        /** one sample per this many values: a lookup counts the set bits of a word or two past it */
        const val SAMPLE = 64
        private const val HEADER_BYTES = 16

        // the reader's bound, which encode keeps to: a table it builds is one a reader takes
        private const val MAX_UPPER_WORDS = Int.MAX_VALUE / 64

        private fun lowWords(width: Int, size: Int): Long = (size.toLong() * width + 63) / 64

        private fun samplesFor(size: Int): Int = ((size.toLong() + SAMPLE - 1) / SAMPLE).toInt()

        /** [values], which must not decrease, in this form. */
        fun encode(values: IntArray): ByteArray {
            val size = values.size
            val max = values.maxOrNull() ?: 0
            val out = java.io.ByteArrayOutputStream()
            Writer(out, size, max.coerceAtLeast(0)).apply { values.forEach(::add) }.finish()
            return out.toByteArray()
        }
    }

    /**
     * [size] values, which must not decrease and end at most at [max], written to [out] as they
     * come, the layout [EliasFano] reads: the low bits streamed, the upper bits and samples (some 2
     * bits a value) held till [finish]. For a table too large to build in the heap first (a
     * dictionary the user merged, DictionaryCompiler).
     */
    class Writer(private val out: java.io.OutputStream, private val size: Int, max: Int) {
        private val bytes = LittleEndian(out)
        // the low width that makes the whole smallest: about log2 of the mean gap
        private val lowWidth = if (size == 0) 0 else (63 - java.lang.Long.numberOfLeadingZeros(maxOf(1L, (max.toLong() + 1) / size)))
        private val upper: LongArray
        private val maxValue = max
        private val samples = IntArray(samplesFor(size))
        private var count = 0
        private var previous = 0
        private var lowUsed = 0
        private var lowWord = 0L

        init {
            require(size >= 0 && max >= 0) { "$size values up to $max" }
            val upperBits = size.toLong() + (max.toLong() ushr lowWidth) + 1
            require(upperBits <= MAX_UPPER_WORDS * 64L) { "$size values up to $max: bit positions past an int" }
            upper = LongArray(((upperBits + 63) / 64).toInt())
            writeInt(lowWidth)
            writeInt(size)
            writeInt(upper.size)
            writeInt(samples.size)
        }

        fun add(value: Int) {
            require(count < size) { "more than $size values" }
            require(value >= 0) { "negative value $value at $count" }
            require(value <= maxValue) { "value $value at $count past $maxValue" }
            require(count == 0 || value >= previous) { "decreasing at $count" }
            previous = value
            val v = value.toLong()
            if (lowWidth > 0) {
                // packed as BitPacked packs them, without its header
                val bits = v and ((1L shl lowWidth) - 1)
                lowWord = lowWord or (bits shl lowUsed)
                lowUsed += lowWidth
                if (lowUsed >= 64) {
                    writeLong(lowWord)
                    lowUsed -= 64
                    lowWord = if (lowUsed == 0) 0L else bits ushr (lowWidth - lowUsed)
                }
            }
            val bit = (v ushr lowWidth) + count
            upper[(bit ushr 6).toInt()] = upper[(bit ushr 6).toInt()] or (1L shl (bit and 63).toInt())
            if (count % SAMPLE == 0) samples[count / SAMPLE] = bit.toInt()
            count++
        }

        fun finish() {
            require(count == size) { "$count of $size values" }
            if (lowUsed > 0) writeLong(lowWord)
            lowUsed = 0
            upper.forEach(::writeLong)
            samples.forEach(::writeInt)
        }

        private fun writeInt(v: Int) = bytes.writeInt(v)

        private fun writeLong(v: Long) = bytes.writeLong(v)
    }
}
