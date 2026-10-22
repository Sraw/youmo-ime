/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

// far more than a word list or phrases kept by hand run to
private const val MAX_IMPORTED = 8 shl 20

/** At most [max] bytes of this stream: all of it if it is shorter. */
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

/**
 * All of a file the user picked to import, read whole, or an IOException past [max]: one picked
 * by mistake would run the app out of memory, and the keyboard with it, in the same process.
 */
internal fun InputStream.readImported(max: Int = MAX_IMPORTED): ByteArray =
    readAtMost(max + 1).also { if (it.size > max) throw IOException("larger than ${max shr 20} MB") }

/**
 * A file picked that may be anything (a video): null where its first [head] bytes are not what
 * [accepts] takes, never read further, or where it is larger than [max]; else all of it.
 */
internal fun InputStream.readPicked(head: Int, max: Int, accepts: (ByteArray) -> Boolean): ByteArray? {
    val start = readAtMost(head)
    if (!accepts(start)) return null
    // past [max] (the whole file's, head included) it is no backup either, told as one (F3199, F3239)
    val rest = readAtMost(max - start.size + 1)
    return if (start.size + rest.size > max) null else start + rest
}
