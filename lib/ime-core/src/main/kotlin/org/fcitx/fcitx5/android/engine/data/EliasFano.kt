/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Non-decreasing ints in Elias-Fano form (~3 bits each, not [BitPacked]'s 25) for trigram run starts; slower, so
 * unigram run starts stay [BitPacked]. Layout: `u32 lowWidth, u32 size, u32 upperWords, u32 sampleCount`, low bits,
 * upper bits in unary as 64-bit words, then every [SAMPLE]th set bit's position as u32.
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
        // bit positions are ints: more upper words and a lookup would wrap
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

    fun run(index: Int): Run {
        if (index < 0 || index >= size - 1) throw IndexOutOfBoundsException("$index and the next of $size")
        val bit = select(index)
        return Run(value(index, bit), value(index + 1, nextSet(bit + 1)))
    }

    // packed in one long: looked up for every word scored
    @JvmInline
    value class Run private constructor(private val bits: Long) {

        constructor(start: Int, end: Int) : this((start.toLong() shl Int.SIZE_BITS) or (end.toLong() and 0xffffffffL))

        val start: Int get() = (bits ushr Int.SIZE_BITS).toInt()
        val end: Int get() = bits.toInt()
    }

    /** Checks run starts as [BitPacked.checkRuns] does, and every sample: lookups index other arrays unchecked. */
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
        const val SAMPLE = 64
        private const val HEADER_BYTES = 16

        // the reader's bound, which encode keeps to
        private const val MAX_UPPER_WORDS = Int.MAX_VALUE / 64

        private fun lowWords(width: Int, size: Int): Long = (size.toLong() * width + 63) / 64

        private fun samplesFor(size: Int): Int = ((size.toLong() + SAMPLE - 1) / SAMPLE).toInt()

        fun encode(values: IntArray): ByteArray {
            val size = values.size
            val max = values.maxOrNull() ?: 0
            val out = java.io.ByteArrayOutputStream()
            Writer(out, size, max.coerceAtLeast(0)).apply { values.forEach(::add) }.finish()
            return out.toByteArray()
        }
    }

    /** Streams values as they come; upper bits and samples (some 2 bits a value) held till [finish]. */
    class Writer(private val out: java.io.OutputStream, private val size: Int, max: Int) {
        private val bytes = LittleEndian(out)
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
