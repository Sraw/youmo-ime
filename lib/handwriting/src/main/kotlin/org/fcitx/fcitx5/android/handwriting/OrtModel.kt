/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.handwriting

import android.content.res.AssetManager

/**
 * One onnxruntime model of one float input and one float output, an ORT-format asset stored
 * uncompressed: mapped from the APK and read where it lies, not onto the heap. [run] may be called
 * from any thread, also at once; [close] waits for runs under way.
 */
class OrtModel private constructor(private var handle: Long) : AutoCloseable {

    /** An output: [values] row-major, its [shape]. */
    class Output(val values: FloatArray, val shape: LongArray)

    private val lock = java.util.concurrent.locks.ReentrantReadWriteLock()

    /** The model run on [x], of [shape]. */
    fun run(x: FloatArray, shape: LongArray): Output {
        lock.readLock().lock()
        try {
            check(handle != 0L) { "closed" }
            val dims = LongArray(MAX_RANK)
            val values = nativeRun(handle, x, shape, dims)
            return Output(values, dims.filter { it >= 0 }.toLongArray())
        } finally {
            lock.readLock().unlock()
        }
    }

    override fun close() {
        lock.writeLock().lock()
        try {
            if (handle != 0L) nativeClose(handle)
            handle = 0L
        } finally {
            lock.writeLock().unlock()
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
        private external fun nativeClose(handle: Long)
    }
}
