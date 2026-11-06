/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.handwriting

import android.content.res.AssetManager
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * One onnxruntime model of one input, floats ([run]) or int64s ([runLongs], the voice polish
 * tagger's character ids), and float outputs, an ORT-format asset stored
 * uncompressed: mapped from the APK and read where it lies, not onto the heap. [run] may be called
 * from any thread, also at once; [close] waits for runs under way.
 */
class OrtModel private constructor(private var handle: Long) : AutoCloseable {

    // how many outputs it has: runLongs' room for their dimensions
    private val outputs = nativeOutputs(handle)

    /** An output: [values] row-major, its [shape]. */
    class Output(val values: FloatArray, val shape: LongArray)

    private val lock = ReentrantReadWriteLock()

    /** The model run on [x], of [shape]. */
    fun run(x: FloatArray, shape: LongArray): Output = lock.read {
        check(handle != 0L) { "closed" }
        val dims = LongArray(MAX_RANK)
        val values = nativeRun(handle, x, shape, dims)
        Output(values, dims.filter { it >= 0 }.toLongArray())
    }

    /** Every output of the model run on [x], of [shape]: a model whose input is int64s. */
    fun runLongs(x: LongArray, shape: LongArray): List<Output> = lock.read {
        check(handle != 0L) { "closed" }
        val dims = LongArray(MAX_RANK * outputs)
        val values = nativeRunLongs(handle, x, shape, dims, MAX_RANK)
        values.mapIndexed { i, v -> Output(v, dims.copyOfRange(i * MAX_RANK, (i + 1) * MAX_RANK).filter { it >= 0 }.toLongArray()) }
    }

    override fun close() {
        lock.write {
            if (handle != 0L) nativeClose(handle)
            handle = 0L
        }
    }

    companion object {
        private const val MAX_RANK = 8

        init {
            System.loadLibrary("youmo-handwriting")
        }

        /** The model at [path] in [assets], run on [threads] threads. */
        fun open(assets: AssetManager, path: String, threads: Int = 1): OrtModel =
            OrtModel(nativeOpen(assets, path, threads))

        @JvmStatic
        private external fun nativeOpen(assets: AssetManager, path: String, threads: Int): Long

        @JvmStatic
        private external fun nativeRun(handle: Long, x: FloatArray, shape: LongArray, out: LongArray): FloatArray

        @JvmStatic
        private external fun nativeRunLongs(handle: Long, x: LongArray, shape: LongArray, dims: LongArray, rank: Int): Array<FloatArray>

        @JvmStatic
        private external fun nativeOutputs(handle: Long): Int

        @JvmStatic
        private external fun nativeClose(handle: Long)
    }
}
