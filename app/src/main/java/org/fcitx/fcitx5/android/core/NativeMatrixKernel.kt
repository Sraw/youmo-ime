/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.engine.rerank.MatrixKernel
import timber.log.Timber
import java.nio.ByteBuffer

/**
 * [MatrixKernel] in C++ (`lib/handwriting/src/main/cpp/matrix-kernel.cpp`), in libyoumo-matrix: a
 * library of its own, not native-lib, so it outlives the native fcitx host, and not
 * libyoumo-handwriting, which would bring onnxruntime in with every user's first key.
 */
object NativeMatrixKernel : MatrixKernel {
    /** This kernel, or [MatrixKernel.JVM] (slower, same sums) where the library does not load. */
    val loaded: MatrixKernel by lazy {
        try {
            System.loadLibrary("youmo-matrix")
            this
        } catch (e: UnsatisfiedLinkError) {
            Timber.w(e, "youmo-matrix did not load; the sentence models run on MatrixKernel.JVM")
            MatrixKernel.JVM
        }
    }

    external override fun times(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, y: FloatArray)
}
