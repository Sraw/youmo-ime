/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import java.io.File
import java.util.zip.ZipInputStream

/**
 * @return top-level files in zip file
 */
fun ZipInputStream.extract(destDir: File): List<File> {
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
            file.outputStream().use { copyTo(it) }
        } else {
            file.mkdirs()
        }
        entry = nextEntry
    }
    return destDir.listFiles()?.toList() ?: emptyList()
}
