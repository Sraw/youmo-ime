/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.engine.rerank.MatrixKernel

/** [MatrixKernel] in C++ (`matrix-kernel.cpp`), in native-lib: loaded by [Fcitx] before the engine runs. */
object NativeMatrixKernel : MatrixKernel {
    external override fun times(q: ByteArray, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, y: FloatArray)
}
