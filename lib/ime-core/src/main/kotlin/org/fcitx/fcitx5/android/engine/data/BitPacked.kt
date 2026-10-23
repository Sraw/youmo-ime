/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A read-only array of non-negative ints, each stored in exactly [width] bits, read
 * little-endian from a buffer (whatever its byte order) that is usually memory-mapped. The
 * language model is mostly arrays of word ids and offsets that need 19 to 23 bits; packing them
 * rather than using 32 is what keeps the data file near the size of libime's.
 *
 * Layout: `u32 width, u32 size`, then the values as little-endian 64-bit words, lowest bits
 * first. A value straddling two words only exists when the second word does, so no padding.
 */
class BitPacked(buffer: ByteBuffer) {

    // a view of its own: ByteBuffer.wrap's buffers are big-endian
    private val buffer: ByteBuffer = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)

    val width: Int
    val size: Int
    private val mask: Long

    init {
        val b = this.buffer
        require(b.capacity() >= HEADER_BYTES) { "truncated header" }
        width = b.getInt(0)
        size = b.getInt(4)
        require(width in 1..MAX_WIDTH) { "bad width $width" }
        require(size >= 0) { "bad size $size" }
        require(b.capacity() >= byteSize(width, size)) { "truncated: $size values of $width bits" }
        mask = (1L shl width) - 1
    }

    operator fun get(index: Int): Int {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("$index of $size")
        val bit = index.toLong() * width
        val word = HEADER_BYTES + (bit ushr 6).toInt() * 8
        val shift = (bit and 63).toInt()
        var value = buffer.getLong(word) ushr shift
        if (shift + width > 64) value = value or (buffer.getLong(word + 8) shl (64 - shift))
        return (value and mask).toInt()
    }

    /** Index of the first value in [from, to) that is >= [key], for a range sorted ascending. */
    fun lowerBound(from: Int, to: Int, key: Int): Int {
        var lo = from
        var hi = to
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (get(mid) < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Checks that this is a table of run starts: [first] first, never decreasing, [end] last.
     * Lookups index other arrays with these values unchecked, so a table that got this far must
     * not point outside them; otherwise a corrupt file would load and fail at the first key press.
     */
    fun checkRuns(what: String, first: Int, end: Int) {
        ensureFormat(size >= 1 && get(0) == first) { "$what: does not start at $first" }
        var previous = first
        for (i in 1 until size) {
            val v = get(i)
            ensureFormat(v >= previous) { "$what: decreases at $i" }
            previous = v
        }
        ensureFormat(previous == end) { "$what: ends at $previous, not $end" }
    }

    /** Checks that every value is below [limit]. */
    fun checkBelow(what: String, limit: Int) {
        for (i in 0 until size) ensureFormat(get(i) < limit) { "$what: ${get(i)} at $i is not below $limit" }
    }

    companion object {
        /** values are ints; 31 bits covers every non-negative one */
        const val MAX_WIDTH = 31
        private const val HEADER_BYTES = 8

        fun byteSize(width: Int, size: Int): Long = HEADER_BYTES + words(width, size) * 8

        private fun words(width: Int, size: Int): Long = (size.toLong() * width + 63) / 64

        /** Bits needed to store every value up to and including [max]; at least 1. */
        fun widthFor(max: Int): Int = maxOf(1, 32 - Integer.numberOfLeadingZeros(max))

        fun encode(values: IntArray): ByteArray = encode(values.size) { values[it] }

        fun encode(size: Int, value: (Int) -> Int): ByteArray {
            var max = 0
            for (i in 0 until size) {
                val v = value(i)
                require(v >= 0) { "negative value $v at $i" }
                if (v > max) max = v
            }
            val width = widthFor(max)
            val out = java.io.ByteArrayOutputStream(byteSize(width, size).toInt())
            Writer(out, width, size).apply { for (i in 0 until size) add(value(i)) }.finish()
            return out.toByteArray()
        }
    }

    /**
     * [size] values of [width] bits written to [out] as they come, the layout [BitPacked] reads:
     * for a table too large to build in the heap first (a dictionary the user merged, DictionaryCompiler).
     */
    class Writer(private val out: java.io.OutputStream, private val width: Int, private val size: Int) {
        private var bits = 0L
        private var used = 0
        private var count = 0

        init {
            require(width in 1..MAX_WIDTH && size >= 0) { "$size values of $width bits" }
            writeInt(width)
            writeInt(size)
        }

        fun add(value: Int) {
            require(value >= 0 && value < 1L shl width) { "value $value at $count of $width bits" }
            require(count < size) { "more than $size values" }
            count++
            bits = bits or (value.toLong() shl used)
            used += width
            if (used >= 64) {
                writeLong(bits)
                used -= 64
                // the high bits of a value straddling two words begin the next
                bits = if (used == 0) 0L else value.toLong() ushr (width - used)
            }
        }

        /** The last word written: every value added. */
        fun finish() {
            require(count == size) { "$count of $size values" }
            if (used > 0) writeLong(bits)
            used = 0
        }

        private fun writeInt(v: Int) {
            for (i in 0 until 4) out.write(v ushr (8 * i))
        }

        private fun writeLong(v: Long) {
            for (i in 0 until 8) out.write((v ushr (8 * i)).toInt())
        }
    }
}
