/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.libime

import org.fcitx.fcitx5.android.engine.data.DataFormatException
import java.util.Arrays

/**
 * A Zstandard decoder (RFC 8878), for the files libime compresses: frames as zstd's library
 * writes them, one after another, skippable frames skipped. A frame that needs a dictionary is
 * refused; one whose content is not the size its header says, or not what its checksum says
 * (libime writes one), is corrupt, as zstd's library takes it.
 *
 * The whole output is kept, so a match may reach back as far as the frame goes; libime's files
 * are a few megabytes at most.
 */
object Zstd {

    private const val FRAME_MAGIC = 0xFD2FB528.toInt()
    private const val SKIPPABLE_MAGIC = 0x184D2A50
    private const val SKIPPABLE_MASK = 0xFFFFFFF0.toInt()

    // libime's own pinyin dictionary is 9 MB unpacked; a big imported one some times that
    private const val MAX_OUTPUT = 1 shl 27

    // what a frame's claimed size is given before any of it is read: a claim is not the bytes
    private const val PRESIZE = 1 shl 24

    /** Whether [data] starts as a zstd frame does. */
    fun isFrame(data: ByteArray, from: Int = 0): Boolean = data.size - from >= 4 && le32(data, from) == FRAME_MAGIC

    /**
     * The frames in [data] from [from] to its end, decompressed.
     *
     * @throws DataFormatException past [limit] bytes too: a few bytes of zstd can claim gigabytes
     */
    fun decompress(data: ByteArray, from: Int = 0, limit: Int = MAX_OUTPUT): ByteArray {
        val out = Output(data.size * 4, limit)
        var pos = from
        try {
            while (pos < data.size) {
                val magic = le32(data, pos)
                pos = when {
                    magic == FRAME_MAGIC -> Frame(data, out).read(pos + 4)
                    magic and SKIPPABLE_MASK == SKIPPABLE_MAGIC -> {
                        val size = le32(data, pos + 4)
                        if (size < 0) corrupt("zstd skippable frame too large")
                        pos + 8 + size
                    }
                    else -> corrupt("not zstd at $pos")
                }
                if (pos > data.size || pos < 0) corrupt("zstd frame runs past the end")
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: IndexOutOfBoundsException) {
            throw DataFormatException("zstd data cut short or corrupt", e)
        }
        return if (out.size == out.bytes.size) out.bytes else out.bytes.copyOf(out.size)
    }

    private class Output(capacity: Int, private val limit: Int) {
        var bytes = ByteArray(capacity.coerceIn(minOf(64, limit), minOf(limit, PRESIZE)))
        var size = 0

        /** Room for [extra] bytes to come, as a frame's header claims: checked, taken on trust only so far. */
        fun reserve(extra: Long) {
            if (extra > limit - size) corrupt("zstd output past $limit bytes")
            val want = size + minOf(extra, PRESIZE.toLong()).toInt()
            if (want > bytes.size) bytes = bytes.copyOf(want)
        }

        fun ensure(extra: Int) {
            if (extra < 0 || extra > limit - size) corrupt("zstd output past $limit bytes")
            if (size + extra > bytes.size) bytes = bytes.copyOf(minOf(maxOf(bytes.size * 2, size + extra), limit))
        }

        fun write(src: ByteArray, from: Int, length: Int) {
            ensure(length)
            System.arraycopy(src, from, bytes, size, length)
            size += length
        }

        fun fill(value: Byte, length: Int) {
            ensure(length)
            Arrays.fill(bytes, size, size + length, value)
            size += length
        }

        /** [length] bytes from [offset] back, which may overlap what they write. */
        fun match(offset: Int, length: Int, frameStart: Int) {
            if (offset <= 0 || offset > size - frameStart) corrupt("zstd offset $offset out of reach")
            ensure(length)
            var from = size - offset
            if (offset >= length) {
                System.arraycopy(bytes, from, bytes, size, length)
                size += length
            } else {
                repeat(length) { bytes[size++] = bytes[from++] }
            }
        }
    }

    /** One frame: its blocks and what they carry from one to the next. */
    private class Frame(private val src: ByteArray, private val out: Output) {
        private val frameStart = out.size
        private val repeats = intArrayOf(1, 4, 8)
        private var huffman: Huffman? = null
        private var literalLengths: Fse? = null
        private var offsets: Fse? = null
        private var matchLengths: Fse? = null

        fun read(start: Int): Int {
            var p = start
            val descriptor = u8(src, p++)
            val contentSizeFlag = descriptor ushr 6
            val singleSegment = descriptor and 0x20 != 0
            if (descriptor and 0x08 != 0) corrupt("zstd reserved bit set")
            if (!singleSegment) p++ // the window: all of the output is kept anyway
            val dictionarySize = intArrayOf(0, 1, 2, 4)[descriptor and 3]
            if (leN(src, p, dictionarySize) != 0L) corrupt("zstd frame needs a dictionary")
            p += dictionarySize
            val contentSizeBytes = when (contentSizeFlag) {
                0 -> if (singleSegment) 1 else 0
                1 -> 2
                2 -> 4
                else -> 8
            }
            val contentSize = contentSize(p, contentSizeBytes)
            if (contentSize >= 0) out.reserve(contentSize)
            p = blocks(p + contentSizeBytes)
            val produced = out.size - frameStart
            if (contentSize >= 0 && produced.toLong() != contentSize) corrupt("zstd frame is $produced bytes, not $contentSize")
            if (descriptor and 0x04 != 0) {
                if (le32(src, p) != xxh64(out.bytes, frameStart, produced).toInt()) corrupt("zstd checksum does not match")
                p += 4
            }
            return p
        }

        /** The size the frame says it is, in [bytes] bytes at [p]; -1 if it does not say. */
        private fun contentSize(p: Int, bytes: Int): Long {
            if (bytes == 0) return -1
            val size = leN(src, p, bytes) + if (bytes == 2) 256 else 0
            // past a Long, as 8 bytes can say: past any limit too
            return if (size < 0) Long.MAX_VALUE else size
        }

        /** The blocks from [start] to the last; where it ends. */
        private fun blocks(start: Int): Int {
            var p = start
            do {
                val header = u8(src, p) or (u8(src, p + 1) shl 8) or (u8(src, p + 2) shl 16)
                p += 3
                val size = header ushr 3
                when ((header ushr 1) and 3) {
                    0 -> {
                        out.write(src, p, size)
                        p += size
                    }
                    1 -> out.fill(src[p++], size)
                    2 -> {
                        if (p + size > src.size) corrupt("zstd block runs past the end")
                        block(p, p + size)
                        p += size
                    }
                    else -> corrupt("zstd reserved block type")
                }
            } while (header and 1 == 0)
            return p
        }

        private fun block(start: Int, end: Int) {
            var p = start
            val b0 = u8(src, p)
            val sizeFormat = (b0 ushr 2) and 3
            val literals: ByteArray
            val literalsFrom: Int
            val literalsSize: Int
            when (b0 and 3) {
                0, 1 -> {
                    // 0 and 2 a byte, 1 two, 3 three
                    val headerSize = if (sizeFormat and 1 == 0) 1 else sizeFormat / 2 + 2
                    literalsSize = when (headerSize) {
                        1 -> b0 ushr 3
                        2 -> (b0 ushr 4) + (u8(src, p + 1) shl 4)
                        else -> (b0 ushr 4) + (u8(src, p + 1) shl 4) + (u8(src, p + 2) shl 12)
                    }
                    p += headerSize
                    if (b0 and 3 == 0) {
                        if (p + literalsSize > end) corrupt("zstd literals run past the block")
                        literals = src
                        literalsFrom = p
                        p += literalsSize
                    } else {
                        literals = ByteArray(literalsSize)
                        Arrays.fill(literals, src[p++])
                        literalsFrom = 0
                    }
                }
                else -> {
                    val (bits, headerSize) = when (sizeFormat) {
                        0, 1 -> 10 to 3
                        2 -> 14 to 4
                        else -> 18 to 5
                    }
                    val header = leN(src, p, headerSize)
                    val mask = (1L shl bits) - 1
                    literalsSize = ((header ushr 4) and mask).toInt()
                    val compressedSize = ((header ushr (4 + bits)) and mask).toInt()
                    p += headerSize
                    val streamsEnd = p + compressedSize
                    if (streamsEnd > end) corrupt("zstd literals run past the block")
                    var q = p
                    if (b0 and 3 == 2) {
                        val read = Huffman.read(src, q, streamsEnd)
                        huffman = read.first
                        q = read.second
                    }
                    val table = huffman ?: corrupt("zstd literals repeat no table")
                    literals = ByteArray(literalsSize)
                    literalsFrom = 0
                    if (sizeFormat == 0) {
                        table.decode(src, q, streamsEnd, literals, 0, literalsSize)
                    } else {
                        table.decode4(src, q, streamsEnd, literals)
                    }
                    p = streamsEnd
                }
            }
            sequences(p, end, literals, literalsFrom, literalsSize)
        }

        private fun sequences(start: Int, end: Int, literals: ByteArray, literalsFrom: Int, literalsSize: Int) {
            var p = start
            val b0 = u8(src, p++)
            val count = when {
                b0 < 128 -> b0
                b0 < 255 -> ((b0 - 128) shl 8) + u8(src, p++)
                else -> (u8(src, p) + (u8(src, p + 1) shl 8) + 0x7F00).also { p += 2 }
            }
            var literal = literalsFrom
            val literalsEnd = literalsFrom + literalsSize
            if (count > 0) {
                val modes = u8(src, p++)
                if (modes and 3 != 0) corrupt("zstd reserved sequence mode bits")
                literalLengths = Fse.table(modes ushr 6, literalLengths, src, p, end, LL_DEFAULT, 35, 9).also { p = it.second }.first
                offsets = Fse.table((modes ushr 4) and 3, offsets, src, p, end, OF_DEFAULT, 31, 8).also { p = it.second }.first
                matchLengths = Fse.table((modes ushr 2) and 3, matchLengths, src, p, end, ML_DEFAULT, 52, 9).also { p = it.second }.first
                val ll = literalLengths!!
                val of = offsets!!
                val ml = matchLengths!!
                val bits = BackwardBits(src, p, end)
                var llState = bits.read(ll.log)
                var ofState = bits.read(of.log)
                var mlState = bits.read(ml.log)
                for (i in 0 until count) {
                    val ofCode = of.symbol[ofState]
                    val mlCode = ml.symbol[mlState]
                    val llCode = ll.symbol[llState]
                    if (ofCode > 31 || mlCode > 52 || llCode > 35) corrupt("zstd code out of range")
                    val offsetValue = (1L shl ofCode) + bits.read(ofCode)
                    val matchLength = ML_BASE[mlCode] + bits.read(ML_BITS[mlCode])
                    val literalLength = LL_BASE[llCode] + bits.read(LL_BITS[llCode])
                    val offset = offset(offsetValue, literalLength == 0)
                    if (i != count - 1) {
                        llState = ll.next(llState, bits)
                        mlState = ml.next(mlState, bits)
                        ofState = of.next(ofState, bits)
                    }
                    if (literal + literalLength > literalsEnd) corrupt("zstd sequence past the literals")
                    out.write(literals, literal, literalLength)
                    literal += literalLength
                    out.match(offset, matchLength, frameStart)
                }
                if (!bits.finished) corrupt("zstd sequences do not end their bitstream")
            } else if (p != end) {
                corrupt("zstd block has bytes past its sequences")
            }
            out.write(literals, literal, literalsEnd - literal)
        }

        /** The offset [value] stands for, the repeated ones kept as the RFC says. */
        private fun offset(value: Long, noLiterals: Boolean): Int {
            if (value > 3) {
                val offset = value - 3
                if (offset > Int.MAX_VALUE) corrupt("zstd offset too far")
                repeats[2] = repeats[1]
                repeats[1] = repeats[0]
                repeats[0] = offset.toInt()
                return repeats[0]
            }
            val index = value.toInt() + if (noLiterals) 1 else 0
            if (index == 1) return repeats[0]
            val offset = if (index == 4) repeats[0] - 1 else repeats[index - 1]
            if (index != 2) repeats[2] = repeats[1]
            repeats[1] = repeats[0]
            repeats[0] = offset
            return offset
        }
    }

    /** A Huffman decoding table for literals: indexed by the next [maxBits] bits. */
    private class Huffman(val maxBits: Int, val symbols: ByteArray, val lengths: ByteArray) {

        fun decode(src: ByteArray, start: Int, end: Int, out: ByteArray, from: Int, to: Int) {
            val bits = BackwardBits(src, start, end)
            for (i in from until to) {
                val index = bits.peek(maxBits)
                out[i] = symbols[index]
                bits.skip(lengths[index].toInt())
            }
            if (!bits.finished) corrupt("zstd literals do not end their stream")
        }

        fun decode4(src: ByteArray, start: Int, end: Int, out: ByteArray) {
            val s1 = u8(src, start) or (u8(src, start + 1) shl 8)
            val s2 = u8(src, start + 2) or (u8(src, start + 3) shl 8)
            val s3 = u8(src, start + 4) or (u8(src, start + 5) shl 8)
            val p1 = start + 6
            val p2 = p1 + s1
            val p3 = p2 + s2
            val p4 = p3 + s3
            if (p4 >= end) corrupt("zstd literal streams run past their size")
            val segment = (out.size + 3) / 4
            if (3 * segment > out.size) corrupt("zstd too few literals for four streams")
            decode(src, p1, p2, out, 0, segment)
            decode(src, p2, p3, out, segment, 2 * segment)
            decode(src, p3, p4, out, 2 * segment, 3 * segment)
            decode(src, p4, end, out, 3 * segment, out.size)
        }

        companion object {
            private const val MAX_BITS = 11

            /** The table described at [start], and where its description ends. */
            fun read(src: ByteArray, start: Int, end: Int): Pair<Huffman, Int> {
                var p = start
                val header = u8(src, p++)
                val weights = IntArray(256)
                var count: Int
                if (header < 128) {
                    if (p + header > end) corrupt("zstd Huffman weights run past the literals")
                    count = fseWeights(src, p, p + header, weights)
                    p += header
                } else {
                    count = header - 127
                    for (i in 0 until count) {
                        val b = u8(src, p + i / 2)
                        weights[i] = if (i % 2 == 0) b ushr 4 else b and 15
                    }
                    p += (count + 1) / 2
                }
                var sum = 0
                for (i in 0 until count) {
                    if (weights[i] > MAX_BITS) corrupt("zstd Huffman weight ${weights[i]}")
                    if (weights[i] > 0) sum += 1 shl (weights[i] - 1)
                }
                if (sum == 0) corrupt("zstd Huffman table empty")
                val maxBits = highBit(sum) + 1
                val rest = (1 shl maxBits) - sum
                if (maxBits > MAX_BITS || rest and (rest - 1) != 0) corrupt("zstd Huffman weights do not add up")
                weights[count++] = highBit(rest) + 1
                // lowest weights first, each symbol taking 2^(weight-1) slots in order
                val next = IntArray(maxBits + 2)
                for (i in 0 until count) if (weights[i] > 0) next[weights[i] + 1] += 1 shl (weights[i] - 1)
                for (w in 1..maxBits) next[w + 1] += next[w]
                val symbols = ByteArray(1 shl maxBits)
                val lengths = ByteArray(1 shl maxBits)
                for (symbol in 0 until count) {
                    val w = weights[symbol]
                    if (w == 0) continue
                    val at = next[w]
                    val length = 1 shl (w - 1)
                    Arrays.fill(symbols, at, at + length, symbol.toByte())
                    Arrays.fill(lengths, at, at + length, (maxBits + 1 - w).toByte())
                    next[w] += length
                }
                return Huffman(maxBits, symbols, lengths) to p
            }

            /** Weights FSE-compressed, two states taking turns; how many there are. */
            private fun fseWeights(src: ByteArray, start: Int, end: Int, weights: IntArray): Int {
                val (table, from) = Fse.read(src, start, end, 255, 6)
                val bits = BackwardBits(src, from, end)
                val states = intArrayOf(bits.read(table.log), bits.read(table.log))
                var n = 0
                var turn = 0
                while (true) {
                    if (n >= 254) corrupt("zstd too many Huffman weights")
                    weights[n++] = table.symbol[states[turn]]
                    states[turn] = table.next(states[turn], bits)
                    // the stream read to its start: the other state has the last weight
                    if (bits.overflowed) break
                    turn = 1 - turn
                }
                weights[n++] = table.symbol[states[1 - turn]]
                return n
            }
        }
    }

    /** An FSE decoding table of 2^[log] states. */
    private class Fse(val log: Int, val symbol: IntArray, val bits: IntArray, val base: IntArray) {

        fun next(state: Int, stream: BackwardBits): Int = base[state] + stream.read(bits[state])

        companion object {
            /** The table [mode] says, and where its description ends. */
            fun table(mode: Int, previous: Fse?, src: ByteArray, start: Int, end: Int, default: Fse, maxSymbol: Int, maxLog: Int): Pair<Fse, Int> = when (mode) {
                0 -> default to start
                1 -> {
                    val s = u8(src, start)
                    if (s > maxSymbol) corrupt("zstd RLE symbol $s")
                    Fse(0, intArrayOf(s), intArrayOf(0), intArrayOf(0)) to start + 1
                }
                2 -> read(src, start, end, maxSymbol, maxLog)
                else -> (previous ?: corrupt("zstd repeats no table")) to start
            }

            /** A table as zstd describes one (its normalized counts), and where that ends. */
            fun read(src: ByteArray, start: Int, end: Int, maxSymbol: Int, maxLog: Int): Pair<Fse, Int> {
                val bits = ForwardBits(src, start, end)
                val log = bits.read(4) + 5
                if (log > maxLog) corrupt("zstd FSE accuracy $log")
                val counts = IntArray(maxSymbol + 1)
                var remaining = (1 shl log) + 1
                var threshold = 1 shl log
                var width = log + 1
                var symbol = 0
                var previousZero = false
                while (remaining > 1 && symbol <= maxSymbol) {
                    if (previousZero) {
                        var to = symbol
                        while (bits.peek(16) == 0xFFFF) {
                            to += 24
                            bits.skip(16)
                        }
                        while (bits.peek(2) == 3) {
                            to += 3
                            bits.skip(2)
                        }
                        to += bits.read(2)
                        if (to > maxSymbol + 1) corrupt("zstd FSE zeros past the last symbol")
                        symbol = to
                        if (symbol > maxSymbol) break
                    }
                    val max = 2 * threshold - 1 - remaining
                    val value = bits.peek(width)
                    var count: Int
                    if (value and (threshold - 1) < max) {
                        count = value and (threshold - 1)
                        bits.skip(width - 1)
                    } else {
                        count = value and (2 * threshold - 1)
                        if (count >= threshold) count -= max
                        bits.skip(width)
                    }
                    count--
                    remaining -= if (count < 0) -count else count
                    counts[symbol++] = count
                    previousZero = count == 0
                    while (remaining < threshold) {
                        width--
                        threshold = threshold ushr 1
                    }
                }
                if (remaining != 1) corrupt("zstd FSE counts do not add up")
                val used = bits.bytesUsed()
                if (start + used > end) corrupt("zstd FSE description runs past its end")
                return build(counts, symbol, log) to start + used
            }

            fun build(counts: IntArray, symbols: Int, log: Int): Fse {
                val size = 1 shl log
                val symbol = IntArray(size)
                val next = IntArray(symbols)
                var high = size - 1
                for (s in 0 until symbols) {
                    if (counts[s] == -1) {
                        symbol[high--] = s
                        next[s] = 1
                    } else {
                        next[s] = counts[s]
                    }
                }
                val step = (size ushr 1) + (size ushr 3) + 3
                val mask = size - 1
                var pos = 0
                for (s in 0 until symbols) {
                    repeat(counts[s]) {
                        symbol[pos] = s
                        do pos = (pos + step) and mask while (pos > high)
                    }
                }
                if (pos != 0) corrupt("zstd FSE table does not spread")
                val bits = IntArray(size)
                val base = IntArray(size)
                for (u in 0 until size) {
                    val x = next[symbol[u]]++
                    bits[u] = log - highBit(x)
                    base[u] = (x shl bits[u]) - size
                }
                return Fse(log, symbol, bits, base)
            }
        }
    }

    /** Bits read from the end of a stream back, as zstd writes them: its last 1 bit marks the start. */
    private class BackwardBits(private val src: ByteArray, private val start: Int, end: Int) {
        private var pos: Int

        init {
            if (end <= start) corrupt("zstd empty bitstream")
            val last = u8(src, end - 1)
            if (last == 0) corrupt("zstd bitstream without its end mark")
            pos = (end - 1 - start) * 8 + highBit(last)
        }

        /** All read, none past. */
        val finished get() = pos == 0

        /** Read past the start: what was read there counted as 0s. */
        val overflowed get() = pos < 0

        fun peek(n: Int): Int = bits(pos - n, n)

        fun skip(n: Int) {
            pos -= n
        }

        fun read(n: Int): Int {
            if (n == 0) return 0
            pos -= n
            return bits(pos, n)
        }

        // bits [low, low + n) of the stream taken as one little-endian number, 0 below its start
        private fun bits(low: Int, n: Int): Int {
            val high = low + n
            if (high <= 0) return 0
            val first = if (low < 0) 0 else low ushr 3
            val last = (high - 1) ushr 3
            var acc = 0L
            for (i in last downTo first) acc = (acc shl 8) or u8(src, start + i).toLong()
            val value = if (low < 0) acc shl -low else acc ushr (low - first * 8)
            return (value and ((1L shl n) - 1)).toInt()
        }
    }

    /** Bits read from the start of a stream on, lowest first. */
    private class ForwardBits(private val src: ByteArray, private val start: Int, private val end: Int) {
        private var pos = 0

        fun peek(n: Int): Int {
            var acc = 0L
            val first = pos ushr 3
            val last = (pos + n - 1) ushr 3
            for (i in last downTo first) acc = (acc shl 8) or (if (start + i < end) u8(src, start + i) else 0).toLong()
            return ((acc ushr (pos - first * 8)) and ((1L shl n) - 1)).toInt()
        }

        fun skip(n: Int) {
            pos += n
        }

        fun read(n: Int): Int = peek(n).also { pos += n }

        fun bytesUsed(): Int = (pos + 7) ushr 3
    }

    private val LL_BASE = IntArray(36) { if (it < 16) it else intArrayOf(16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536)[it - 16] }
    private val LL_BITS = IntArray(36) { if (it < 16) 0 else intArrayOf(1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)[it - 16] }
    private val ML_BASE = IntArray(53) {
        if (it < 32) it + 3 else intArrayOf(35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051, 4099, 8195, 16387, 32771, 65539)[it - 32]
    }
    private val ML_BITS = IntArray(53) { if (it < 32) 0 else intArrayOf(1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)[it - 32] }

    // the predefined distributions, as normalized counts
    private val LL_DEFAULT = defaultTable(
        6, 4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1, -1, -1, -1, -1,
    )
    private val ML_DEFAULT = defaultTable(
        6, 1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1, -1, -1,
    )
    private val OF_DEFAULT = defaultTable(5, 1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1)

    private fun defaultTable(log: Int, vararg counts: Int) = Fse.build(counts, counts.size, log)

    private fun u8(src: ByteArray, at: Int): Int = src[at].toInt() and 0xFF

    private fun le32(src: ByteArray, at: Int): Int =
        u8(src, at) or (u8(src, at + 1) shl 8) or (u8(src, at + 2) shl 16) or (u8(src, at + 3) shl 24)

    private fun leN(src: ByteArray, at: Int, n: Int): Long {
        var v = 0L
        for (i in n - 1 downTo 0) v = (v shl 8) or u8(src, at + i).toLong()
        return v
    }

    private fun le64(src: ByteArray, at: Int): Long = leN(src, at, 8)

    private const val P1 = -7046029288634856825L // 0x9E3779B185EBCA87
    private const val P2 = -4417276706812531889L // 0xC2B2AE3D27D4EB4F
    private const val P3 = 0x165667B19E3779F9L
    private const val P4 = -8796714831421723037L // 0x85EBCA77C2B2AE63
    private const val P5 = 0x27D4EB2F165667C5L

    /** XXH64 of [length] bytes of [src] from [from], seed 0: a frame's checksum is its low 32 bits. */
    internal fun xxh64(src: ByteArray, from: Int, length: Int): Long {
        var p = from
        val end = from + length
        var h: Long
        if (length >= 32) {
            val v = longArrayOf(P1 + P2, P2, 0, -P1)
            while (p <= end - 32) {
                for (i in 0 until 4) v[i] = round(v[i], le64(src, p + 8 * i))
                p += 32
            }
            h = rotl(v[0], 1) + rotl(v[1], 7) + rotl(v[2], 12) + rotl(v[3], 18)
            for (x in v) h = (h xor round(0, x)) * P1 + P4
        } else {
            h = P5
        }
        h += length
        while (p + 8 <= end) {
            h = rotl(h xor round(0, le64(src, p)), 27) * P1 + P4
            p += 8
        }
        if (p + 4 <= end) {
            h = rotl(h xor ((le32(src, p).toLong() and 0xFFFFFFFFL) * P1), 23) * P2 + P3
            p += 4
        }
        while (p < end) h = rotl(h xor (u8(src, p++).toLong() * P5), 11) * P1
        h = (h xor (h ushr 33)) * P2
        h = (h xor (h ushr 29)) * P3
        return h xor (h ushr 32)
    }

    private fun round(acc: Long, input: Long) = rotl(acc + input * P2, 31) * P1

    private fun rotl(v: Long, bits: Int) = java.lang.Long.rotateLeft(v, bits)

    private fun highBit(v: Int): Int = 31 - Integer.numberOfLeadingZeros(v)

    private fun corrupt(message: String): Nothing = throw DataFormatException(message)
}
