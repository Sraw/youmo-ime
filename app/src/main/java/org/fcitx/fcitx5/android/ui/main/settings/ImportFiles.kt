/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

// far more than a hand-kept word list runs to
private const val MAX_IMPORTED = 8 shl 20

internal fun InputStream.readAtMost(max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(minOf(max, DEFAULT_BUFFER_SIZE))
    while (out.size() < max) {
        val n = read(buffer, 0, minOf(buffer.size, max - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** Past [max] an IOException: a file picked by mistake would run the app, and the keyboard in its process, out of memory. */
internal fun InputStream.readImported(max: Int = MAX_IMPORTED): ByteArray =
    readAtMost(max + 1).also { if (it.size > max) throw IOException("larger than ${max shr 20} MB") }

/** Null where its first [head] bytes are not what [accepts] takes (never read further) or it is over [max]. */
internal fun InputStream.readPicked(head: Int, max: Int, accepts: (ByteArray) -> Boolean): ByteArray? {
    val start = readAtMost(head)
    if (!accepts(start)) return null
    // past [max] (head included) it is no backup either
    val rest = readAtMost(max - start.size + 1)
    return if (start.size + rest.size > max) null else start + rest
}
