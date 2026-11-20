/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * One rename, no [staged] left behind; on failure [dest] and its open files are as they were. A [dest] left aside by a
 * kill between renames is restored first ([restoreDirectory]).
 */
fun replaceDirectory(dest: File, staged: File, fill: (File) -> Unit) {
    val aside = asideOf(dest)
    restoreDirectory(dest)
    staged.deleteRecursively()
    aside.deleteRecursively()
    try {
        fill(staged)
        // on disk before the renames: the old copy is deleted right after
        staged.walkTopDown().filter { it.isFile }.forEach { file -> FileOutputStream(file, true).use { it.fd.sync() } }
        val moved = dest.exists()
        if (moved) dest.renameOrThrow(aside, "cannot move ${dest.name} aside")
        if (!staged.renameTo(dest)) {
            // back as it was; else restoreDirectory puts it back before the next use
            if (moved) aside.renameOrThrow(dest, "cannot replace ${dest.name}, left as ${aside.name}")
            throw IOException("cannot replace ${dest.name}")
        }
    } finally {
        staged.deleteRecursively()
    }
    aside.deleteRecursively()
}

/** Call before opening [dest]: anything that recreates it would hide the aside copy, the only one. */
fun restoreDirectory(dest: File) {
    val aside = asideOf(dest)
    if (dest.exists() || !aside.exists()) return
    if (!aside.renameTo(dest)) throw IOException("cannot put ${aside.name} back as ${dest.name}")
    Timber.i("%s put back as %s", aside.name, dest.name)
}

private fun asideOf(dest: File) = File(dest.path + ".old")

private fun File.renameOrThrow(to: File, message: String) {
    if (!renameTo(to)) throw IOException(message)
}
