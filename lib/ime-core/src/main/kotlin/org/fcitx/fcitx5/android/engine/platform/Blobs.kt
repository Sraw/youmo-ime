/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.platform

import java.io.IOException
import java.nio.ByteBuffer

/**
 * The engine's read-only data, as the platform ships it (the app's assets on Android, a build's
 * output on the JVM): a file by its path there, whole, as a buffer the engine only reads. Mapped
 * where the platform can: the data runs to hundreds of megabytes, and each file is asked for once
 * and kept.
 */
fun interface Blobs {
    /**
     * The file at [path].
     *
     * @throws java.io.FileNotFoundException if the platform has no such file (a build without it)
     * @throws IOException if it cannot be read
     */
    @Throws(IOException::class)
    fun load(path: String): ByteBuffer
}
