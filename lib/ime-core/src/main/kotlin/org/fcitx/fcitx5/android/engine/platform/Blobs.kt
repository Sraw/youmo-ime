/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.platform

import java.io.IOException
import java.nio.ByteBuffer

/** The engine's read-only data files, whole, mapped where possible: hundreds of megabytes, each asked for once and kept. */
fun interface Blobs {
    /**
     * @throws java.io.FileNotFoundException if the platform has no such file
     * @throws IOException if it cannot be read
     */
    @Throws(IOException::class)
    fun load(path: String): ByteBuffer
}
