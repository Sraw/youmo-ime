/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.util.Log
import org.fcitx.fcitx5.android.engine.rerank.MatrixKernel
import org.fcitx.fcitx5.android.engine.rerank.expF
import org.fcitx.fcitx5.android.engine.rerank.geluF
import org.fcitx.fcitx5.android.engine.rerank.logF
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.Buffer
import java.nio.ByteBuffer
import java.util.Random

/**
 * The phone's C++ kernel against [MatrixKernel.JVM], to the bit, on every int8 kernel the CPU can run (an arm64
 * device for SDOT and MLAL; the x86_64 emulator has only the portable loop). Which kernel a CPU without a
 * feature gets is DeviceProfile's rule table, on the JVM: no emulator lacks a feature for real.
 */
class NativeMatrixKernelTest {

    private companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            System.loadLibrary("youmo-matrix")
        }

        val arm64 = System.getProperty("os.arch") == "aarch64"
        val hwcapDotprod by lazy { NativeMatrixKernel.hwcaps()[0] and HWCAP_ASIMDDP != 0L }

        // AT_HWCAP's ASIMDDP bit on arm64
        const val HWCAP_ASIMDDP = 1L shl 20

        val kernels by lazy {
            when {
                !arm64 -> listOf(NativeMatrixKernel.PORTABLE)
                hwcapDotprod -> listOf(NativeMatrixKernel.SDOT, NativeMatrixKernel.MLAL, NativeMatrixKernel.PORTABLE)
                else -> listOf(NativeMatrixKernel.MLAL, NativeMatrixKernel.PORTABLE)
            }
        }
    }

    private val random = Random(20261009)

    // the kernel is the process's: a failed test must not leave the next on a forced one (SDOT without
    // permission is the default, the best AT_HWCAP allows)
    @After
    fun reset() {
        NativeMatrixKernel.useKernel(NativeMatrixKernel.SDOT, false)
    }

    private fun forced(kernel: Int) = assertEquals("kernel $kernel", kernel, NativeMatrixKernel.useKernel(kernel, false))

    /** [q] as Safetensors.matrix hands it: a read-only slice starting [offset] bytes into a longer mapping. */
    private fun mapped(q: ByteArray, offset: Int): ByteBuffer {
        val file = ByteArray(offset + q.size + offset) { Byte.MAX_VALUE }
        q.copyInto(file, offset)
        val buffer = ByteBuffer.allocateDirect(file.size).put(file)
        // via Buffer: ByteBuffer.position(int) returning ByteBuffer is Java 9, not Android's
        (buffer as Buffer).position(offset)
        (buffer as Buffer).limit(offset + q.size)
        return buffer.slice().asReadOnlyBuffer()
    }

    private fun direct(q: ByteArray) = ByteBuffer.allocateDirect(q.size).put(q)

    /** To the bit; a NaN only as a NaN, its payload being the hardware's. */
    private fun assertBits(what: String, expected: Float, actual: Float) {
        if (expected.isNaN() && actual.isNaN()) return
        assertEquals("$what: $expected against $actual", expected.toRawBits(), actual.toRawBits())
    }

    private fun assertMatches(rows: Int, columns: Int, offset: Int? = null) {
        val q = ByteArray(rows * columns).also { random.nextBytes(it) }
        val scales = FloatArray(rows) { (random.nextFloat() - 0.5f) / 16 }
        val x = FloatArray(columns) { random.nextGaussian().toFloat() }
        val expected = FloatArray(rows) { Float.NaN }
        val actual = FloatArray(rows) { Float.NaN }
        MatrixKernel.JVM.times(ByteBuffer.wrap(q), scales, rows, columns, x, expected)
        // a buffer of its own has its position at the end: both kernels read from index 0
        NativeMatrixKernel.times(if (offset == null) direct(q) else mapped(q, offset), scales, rows, columns, x, actual)
        for (r in 0 until rows) assertBits("$rows × $columns, row $r", expected[r], actual[r])
    }

    @Test
    fun rowsAndColumnsLeftOverAreMultipliedAsTheJvmDoes() {
        for ((rows, columns) in listOf(7 to 13, 4 to 8, 9 to 1027, 0 to 0, 1 to 1, 5 to 3, 3 to 16, 2 to 7)) {
            assertMatches(rows, columns)
        }
    }

    @Test
    fun anySizeIsMultipliedAsTheJvmDoes() {
        repeat(50) { assertMatches(random.nextInt(40), random.nextInt(300)) }
    }

    @Test
    fun aMatrixPartWayIntoAMappedFileIsReadFromWhereItStarts() {
        // a tensor lies wherever the file's header leaves it, at no multiple of four
        for ((rows, columns) in listOf(7 to 13, 4 to 8, 9 to 1027, 1 to 1)) assertMatches(rows, columns, offset = 37)
    }

    @Test
    fun manyVectorsAreTheJvmsBits() {
        for (m in 1..7) {
            for ((rows, columns) in listOf(7 to 13, 64 to 448, 9 to 1027, 5 to 16, 0 to 8, 3 to 0)) {
                val q = ByteArray(rows * columns).also { random.nextBytes(it) }
                val scales = FloatArray(rows) { (random.nextFloat() - 0.5f) / 16 }
                val x = FloatArray(m * columns) { random.nextGaussian().toFloat() }
                val expected = FloatArray(m * rows).also { MatrixKernel.JVM.timesMany(ByteBuffer.wrap(q), scales, rows, columns, x, m, it) }
                val actual = FloatArray(m * rows).also { NativeMatrixKernel.timesMany(direct(q), scales, rows, columns, x, m, it) }
                for (k in expected.indices) assertBits("float $rows × $columns m $m [$k]", expected[k], actual[k])
            }
        }
    }

    /** Activations of every shape the int8 kernels and quantize meet, one vector after another. */
    private fun activations(kind: Int, columns: Int, m: Int): FloatArray = FloatArray(m * columns) { i ->
        val c = i % columns
        val g = random.nextGaussian().toFloat()
        when (kind) {
            0 -> g
            // all at one magnitude, so all ±127: the widening kernel's largest int16 sums
            1 -> if ((c + i / columns) % 2 == 0) -3f else 3f
            2 -> 0f
            3 -> g * 1e30f
            4 -> g * 1e-30f
            // subnormals beside zeros: 127 / max is infinite and 0 × ∞ a NaN, which quantize makes 0
            5 -> if (c % 3 == 0) 0f else Float.MIN_VALUE * (1 + c % 5)
            6 -> if (c == 0) Float.MAX_VALUE else g
            else -> if (c % 2 == 0) -Float.MAX_VALUE else g
        }
    }

    private val weightsOf = listOf<(Random, Int) -> Byte>(
        { r, _ -> r.nextInt(256).toByte() },
        { _, _ -> Byte.MIN_VALUE },
        { _, c -> if (c % 2 == 0) Byte.MIN_VALUE else Byte.MAX_VALUE },
        { r, c -> if (c % 3 == 0) Byte.MIN_VALUE else r.nextInt(256).toByte() },
    )

    private fun assertQ8(kernel: Int, rows: Int, columns: Int, m: Int, kind: Int) {
        val weights = weightsOf[(m + kind) % weightsOf.size]
        val q = ByteArray(rows * columns) { weights(random, it % columns) }
        val scales = FloatArray(rows) { (random.nextFloat() - 0.5f) / 16 }
        val x = activations(kind, columns, m)
        val expected = FloatArray(m * rows)
        MatrixKernel.JVM.timesManyQ8(ByteBuffer.wrap(q), scales, rows, columns, x, m, expected)
        val actual = FloatArray(m * rows)
        NativeMatrixKernel.timesManyQ8(direct(q), scales, rows, columns, x, m, actual)
        for (k in expected.indices) assertBits("kernel $kernel $rows × $columns m $m activations $kind [$k]", expected[k], actual[k])
    }

    @Test
    fun int8ProductsAreTheJvmsBitsOnEveryKernel() {
        for (kernel in kernels) {
            forced(kernel)
            for (m in 1..7) {
                // 16s for SDOT, the rest for its fallbacks and the loops' tails
                for ((rows, columns) in listOf(5 to 16, 64 to 448, 3 to 1792, 7 to 13, 9 to 1027, 4 to 33, 1 to 1)) {
                    for (kind in 0..7) assertQ8(kernel, rows, columns, m, kind)
                }
            }
        }
    }

    // the sums are the same on every kernel: a dispatch gone wrong (all portable) shows here, not in the bits
    @Test
    fun eachKernelRunsWhereTheTableSays() {
        fun ran(columns: Int, m: Int, minus128: Boolean = false): Int {
            val q = ByteArray(3 * columns) { random.nextInt(255).toByte() }
            val xq = ByteArray(m * columns) { if (minus128 && it == 0) Byte.MIN_VALUE else (random.nextInt(255) - 127).toByte() }
            return NativeMatrixKernel.dotsQ8(q, 3, columns, xq, m, IntArray(m * 3))
        }
        val portable = NativeMatrixKernel.PORTABLE
        val mlal = NativeMatrixKernel.MLAL
        for (kernel in kernels) {
            forced(kernel)
            val table = when (kernel) {
                NativeMatrixKernel.SDOT -> listOf(ran(448, 1), ran(448, 3), ran(448, 3, minus128 = true)).map { it to NativeMatrixKernel.SDOT } +
                    listOf(ran(33, 1) to portable, ran(33, 2) to mlal, ran(33, 2, minus128 = true) to portable)
                mlal -> listOf(ran(448, 1) to portable, ran(448, 2) to mlal, ran(33, 7) to mlal, ran(448, 3, minus128 = true) to portable)
                else -> listOf(ran(448, 1), ran(448, 3), ran(33, 2)).map { it to portable }
            }
            table.forEachIndexed { i, (got, expected) -> assertEquals("kernel $kernel case $i", expected, got) }
        }
    }

    @Test
    fun minus128ActivationsAreSummedExactlyOnEveryKernel() {
        // quantize never gives -128; a -128 × -128 pair would overflow MLAL's int16, so those go to the portable loop
        for (kernel in kernels) {
            forced(kernel)
            for (m in 1..7) {
                for ((rows, columns) in listOf(5 to 16, 9 to 448, 4 to 33, 6 to 1027)) {
                    val q = ByteArray(rows * columns) { if (it % 4 == 0) Byte.MIN_VALUE else random.nextInt(256).toByte() }
                    val xq = ByteArray(m * columns) { if (it % 2 == 0) Byte.MIN_VALUE else random.nextInt(256).toByte() }
                    val expected = IntArray(m * rows) { k ->
                        val v = k / rows
                        val r = k % rows
                        (0 until columns).sumOf { q[r * columns + it] * xq[v * columns + it] }
                    }
                    val actual = IntArray(m * rows)
                    NativeMatrixKernel.dotsQ8(q, rows, columns, xq, m, actual)
                    assertArrayEquals("kernel $kernel $rows × $columns m $m", expected, actual)
                }
            }
        }
    }

    @Test
    fun geluLogSumExpAndLogAreTheJvmsBits() {
        val edges = floatArrayOf(
            0f, -0f, 88f, -88f, 88.3f, -87.3f, 88.72f, -103f, 1f, -1f, 0.5f, 0.70710677f, 0.7071068f,
            Float.MIN_VALUE, -Float.MIN_VALUE, Float.MIN_VALUE * 1000, 1.1754942e-38f, 1.17549435e-38f,
            Float.MAX_VALUE, -Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
        )
        val n = 100_000
        fun check(name: String, xs: FloatArray, jvm: (Float) -> Float, native: (FloatArray) -> Unit) {
            val out = xs.copyOf().also(native)
            for (i in xs.indices) assertBits("$name(${xs[i]})", jvm(xs[i]), out[i])
        }
        val wide = FloatArray(n) { (random.nextFloat() - 0.5f) * 200f } + edges
        check("expF", wide, ::expF) { NativeMatrixKernel.mathF(0, it, it.size) }
        // every positive float's bits, subnormals too, besides NaN and negatives
        val positive = FloatArray(n) { Float.fromBits(random.nextInt(0x7f800000)) } + edges + floatArrayOf(Float.NaN, -2f)
        check("logF", positive, ::logF) { NativeMatrixKernel.mathF(1, it, it.size) }
        val gelu = FloatArray(n) { random.nextGaussian().toFloat() * 8 } + edges
        check("geluF", gelu, ::geluF) { NativeMatrixKernel.geluF(it, it.size) }

        for (vocab in listOf(1, 7, 11855)) {
            val m = 9
            val logits = FloatArray(m * vocab) { random.nextGaussian().toFloat() * 6 }
            // a row with an edge in it, one all equal
            logits[0] = 88f
            for (i in vocab until 2 * vocab) logits[i] = -3f
            val expected = FloatArray(m).also { MatrixKernel.JVM.logSumsF(logits, m, vocab, it) }
            val actual = FloatArray(m).also { NativeMatrixKernel.logSumsF(logits, m, vocab, it) }
            for (v in 0 until m) assertBits("logSumF vocab $vocab row $v", expected[v], actual[v])
        }
    }

    @Test
    fun sdotIsUsedOnlyWhereTheHwcapOrTheSelfTestAllowsIt() {
        if (!arm64) {
            for (kind in listOf(NativeMatrixKernel.SDOT, NativeMatrixKernel.MLAL, NativeMatrixKernel.PORTABLE)) {
                assertEquals(NativeMatrixKernel.PORTABLE, NativeMatrixKernel.useKernel(kind, true))
            }
            assertArrayEquals(longArrayOf(0, 0), NativeMatrixKernel.hwcaps())
        } else if (hwcapDotprod) {
            assertEquals(NativeMatrixKernel.SDOT, NativeMatrixKernel.useKernel(NativeMatrixKernel.SDOT, false))
        } else {
            // without the hwcap a refused SDOT would be SIGILL at the first key
            assertEquals(NativeMatrixKernel.MLAL, NativeMatrixKernel.useKernel(NativeMatrixKernel.SDOT, false))
        }
        assertThrows(IllegalArgumentException::class.java) { NativeMatrixKernel.useKernel(0, false) }
        assertThrows(IllegalArgumentException::class.java) { NativeMatrixKernel.useKernel(4, false) }
        Log.i(TAG, "hwcaps ${NativeMatrixKernel.hwcaps().joinToString { "0x" + it.toString(16) }}, kernels $kernels")
    }

    /** [block] with the thread's mask restored after, as the next test runs on this thread. */
    private fun <T> keepingMask(block: (Long) -> T): T {
        val mask = NativeMatrixKernel.affinityGet()
        try {
            return block(mask)
        } finally {
            assertEquals(0L, NativeMatrixKernel.affinitySet(mask))
        }
    }

    private fun cores(mask: Long) = (0 until Long.SIZE_BITS).filter { mask and (1L shl it) != 0L }

    @Test
    fun theSelfTestPassesOnEachCoreAndSaysWhenItRanElsewhere() = keepingMask { mask ->
        if (!arm64) {
            assertEquals(CpuNative.SDOT_FAILED, NativeMatrixKernel.sdotSelfTest(0))
            return@keepingMask
        }
        // SDOT on a CPU without it is SIGILL: nothing to run on such a phone
        if (!hwcapDotprod) return@keepingMask
        val cores = cores(mask)
        for (c in cores) {
            assertEquals("pinned to $c", 0L, NativeMatrixKernel.affinitySet(1L shl c))
            assertEquals("core $c", CpuNative.SDOT_PASSED, NativeMatrixKernel.sdotSelfTest(c))
            // as a kernel isolating the core would leave it: the thread on another
            val other = cores.firstOrNull { it != c } ?: continue
            assertEquals("asked for $other on $c", CpuNative.SDOT_ELSEWHERE, NativeMatrixKernel.sdotSelfTest(other))
        }
    }

    @Test
    fun theMaskIsSetReadAndRefused() = keepingMask { mask ->
        val cores = cores(mask)
        assertTrue("mask ${mask.toString(2)}", cores.isNotEmpty())
        val last = cores.last()
        assertEquals(0L, NativeMatrixKernel.affinitySet(1L shl last))
        assertEquals(1L shl last, NativeMatrixKernel.affinityGet())
        // no core: EINVAL, and the thread stays where it was
        assertEquals(-1L, NativeMatrixKernel.affinitySet(0))
        assertEquals(1L shl last, NativeMatrixKernel.affinityGet())
        assertEquals(0L, NativeMatrixKernel.affinitySet(mask))
        assertEquals(mask, NativeMatrixKernel.affinityGet())
    }

    /** What a pinned section costs a key: the mask read, set to the big cores, set back. */
    @Test
    fun pinningAKeyCostsMicroseconds() = keepingMask { mask ->
        val target = 1L shl cores(mask).last()
        val rounds = 10_000
        val nanos = LongArray(rounds)
        for (i in 0 until rounds) {
            val start = System.nanoTime()
            val before = NativeMatrixKernel.affinityGet()
            NativeMatrixKernel.affinitySet(target)
            NativeMatrixKernel.affinitySet(before)
            nanos[i] = System.nanoTime() - start
        }
        nanos.sort()
        val p50 = nanos[rounds / 2] / 1000.0
        val p99 = nanos[rounds * 99 / 100] / 1000.0
        Log.i(TAG, "affinity get + set + set, $rounds rounds: p50 $p50 µs, p99 $p99 µs")
        assertTrue("p99 $p99 µs", p99 < PIN_P99_MICROS)
    }

    @Test
    fun sizesThatDifferAreRefused() {
        @Suppress("LongParameterList")
        fun refused(q: Int, scales: Int, rows: Int, columns: Int, x: Int, y: Int) {
            assertThrows(IllegalArgumentException::class.java) {
                NativeMatrixKernel.times(ByteBuffer.allocateDirect(q), FloatArray(scales), rows, columns, FloatArray(x), FloatArray(y))
            }
        }
        refused(q = 11, scales = 3, rows = 3, columns = 4, x = 4, y = 3)
        refused(q = 13, scales = 3, rows = 3, columns = 4, x = 4, y = 3)
        refused(q = 12, scales = 2, rows = 3, columns = 4, x = 4, y = 3)
        refused(q = 12, scales = 3, rows = 3, columns = 4, x = 3, y = 3)
        refused(q = 12, scales = 3, rows = 3, columns = 4, x = 4, y = 2)
        refused(q = 0, scales = 0, rows = -1, columns = 0, x = 0, y = 0)
        refused(q = 0, scales = 1, rows = 1, columns = -1, x = 0, y = 1)
        val heap = assertThrows(IllegalArgumentException::class.java) {
            NativeMatrixKernel.times(ByteBuffer.wrap(ByteArray(12)), FloatArray(3), 3, 4, FloatArray(4), FloatArray(3))
        }
        assertEquals("q is not a direct buffer", heap.message)
        val y = FloatArray(4)
        NativeMatrixKernel.times(ByteBuffer.allocateDirect(12).put(ByteArray(12) { 1 }), FloatArray(4) { 1f }, 3, 4, FloatArray(5) { 1f }, y)
        assertEquals(listOf(4f, 4f, 4f, 0f), y.toList())
        // the batched ones: m vectors of x and y
        for (batched in listOf(NativeMatrixKernel::timesMany, NativeMatrixKernel::timesManyQ8)) {
            assertThrows(IllegalArgumentException::class.java) {
                batched(ByteBuffer.allocateDirect(12), FloatArray(3), 3, 4, FloatArray(7), 2, FloatArray(6))
            }
            assertThrows(IllegalArgumentException::class.java) {
                batched(ByteBuffer.allocateDirect(12), FloatArray(3), 3, 4, FloatArray(8), 2, FloatArray(5))
            }
            assertThrows(IllegalArgumentException::class.java) {
                batched(ByteBuffer.allocateDirect(12), FloatArray(3), 3, 4, FloatArray(8), -1, FloatArray(6))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { NativeMatrixKernel.logSumsF(FloatArray(5), 2, 3, FloatArray(2)) }
        assertThrows(IllegalArgumentException::class.java) { NativeMatrixKernel.geluF(FloatArray(2), 3) }
    }
}

private const val TAG = "NativeMatrixKernelTest"

// a key pins once; past this the pinning eats into the few milliseconds it is meant to save
private const val PIN_P99_MICROS = 50.0
