/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * [check], when given, is asked as each entry is unpacked, a chunk at a time, with the entry's count,
 * what it has of it so far and what the entries before it had: it throws to stop, on the chunk that
 * is past a limit, not after a zip bomb is all on the disk.
 *
 * @return top-level files in zip file
 */
fun ZipInputStream.extract(destDir: File, check: ((count: Int, size: Long, total: Long) -> Unit)? = null): List<File> {
    var count = 0
    var total = 0L
    var entry = nextEntry
    val canonicalDest = destDir.canonicalPath
    while (entry != null) {
        val file = File(destDir, entry.name)
        val canonicalPath = file.canonicalPath
        // without the separator "../<dest>x/f" passes as inside; only a directory entry may be dest itself
        if (!canonicalPath.startsWith(canonicalDest + File.separator) &&
            !(entry.isDirectory && canonicalPath == canonicalDest)
        ) throw SecurityException("Zip entry outside of the destination: ${entry.name}")
        if (!entry.isDirectory) {
            // a zip need not have entries for the directories of its files
            file.parentFile?.mkdirs()
            count++
            file.outputStream().use { out ->
                total += if (check == null) copyTo(out) else copyChecked(out) { size -> check(count, size, total) }
            }
        } else {
            file.mkdirs()
        }
        entry = nextEntry
    }
    return destDir.listFiles()?.toList() ?: emptyList()
}

// the entry's bytes into [out], [check] asked with what it has so far before a chunk is written:
// one that throws leaves the chunk past the limit unwritten
private fun ZipInputStream.copyChecked(out: OutputStream, check: (size: Long) -> Unit): Long {
    var size = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    check(size)
    while (true) {
        val n = read(buffer)
        if (n < 0) return size
        size += n
        check(size)
        out.write(buffer, 0, n)
    }
}
