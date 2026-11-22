/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.engine.rerank.MatrixKernel
import timber.log.Timber
import java.nio.ByteBuffer

/**
 * [MatrixKernel] in C++, in libyoumo-matrix: not native-lib, and not libyoumo-handwriting, which would load onnxruntime
 * at the first key. Every function gives [MatrixKernel.JVM]'s bits; the int8 kernel ([useKernel]) changes the speed only.
 */
object NativeMatrixKernel : MatrixKernel, CpuNative {
    /** [MatrixKernel.JVM] (slower, same sums) where the library does not load. */
    val loaded: MatrixKernel by lazy {
        try {
            System.loadLibrary("youmo-matrix")
            this
        } catch (e: UnsatisfiedLinkError) {
            Timber.w(e, "youmo-matrix did not load; the sentence models run on MatrixKernel.JVM")
            MatrixKernel.JVM
        }
    }

    /** [timesManyQ8]'s integer loop as the compiler vectorises it: any CPU. */
    const val PORTABLE = 1

    /** ARMv8.0 NEON's widening multiplies, for several vectors at once. */
    const val MLAL = 2

    /** ARMv8.2's int8 dot product, for column counts of 16s. */
    const val SDOT = 3

    external override fun times(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, y: FloatArray)

    external override fun timesMany(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, m: Int, y: FloatArray)

    external override fun timesManyQ8(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, m: Int, y: FloatArray)

    external override fun geluF(x: FloatArray, n: Int)

    external override fun logSumsF(logits: FloatArray, m: Int, vocab: Int, out: FloatArray)

    /**
     * [timesManyQ8]'s kernel for the process, until set again: [SDOT] only where AT_HWCAP has ASIMDDP or
     * [dotprodAllowed] (the CPU profile's self-test passed), else [MLAL]; off arm64 always [PORTABLE]. Returns the
     * kernel now used. Before the first call, the best AT_HWCAP allows.
     */
    external fun useKernel(kind: Int, dotprodAllowed: Boolean): Int

    /** Zeros off arm64, where AT_HWCAP's bits mean other things. */
    external override fun hwcaps(): LongArray

    external override fun affinityGet(): Long

    external override fun affinitySet(mask: Long): Long

    external override fun sdotSelfTest(cpu: Int): Int

    /** expF (`log` 0) or logF (1) natively over [n] floats in place, for the test that they are the JVM's bits. */
    @VisibleForTesting
    external fun mathF(log: Int, x: FloatArray, n: Int)

    /**
     * The int8 dot products alone, for activations [timesManyQ8] never quantizes to, such as -128; returns the kernel that
     * ran, as [timesManyQ8]'s would, which the sums alone cannot tell.
     */
    @VisibleForTesting
    @Suppress("LongParameterList")
    external fun dotsQ8(q: ByteArray, rows: Int, columns: Int, xq: ByteArray, m: Int, dots: IntArray): Int
}
