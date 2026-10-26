/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.engine.rerank.MatrixKernel
import org.junit.Assert.assertSame
import org.junit.Test

class NativeMatrixKernelLoadTest {
    // no libyoumo-matrix on the JVM's library path: the engines still get a kernel, the JVM's
    @Test
    fun aLibraryThatDoesNotLoadFallsBackToTheJvmKernel() {
        assertSame(MatrixKernel.JVM, NativeMatrixKernel.loaded)
    }
}
