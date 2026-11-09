/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * A theme 4.0 pack that took the keyboard down as it was first drawn (a font crafted to crash the text's layout,
 * a file the engine draws wrong): a mark naming the pack written before its first frame of a process and deleted
 * after it. Still there at the next start, the pack is not drawn again on its own: the settings naming it go back
 * to their defaults ([ThemeManager]), the user told. The mark is in the files not backed up (a crash is this
 * phone's), synced, as the process may die the moment after.
 */
object PackDrawGuard {

    private const val MARK = "theme-pack-drawing"

    @VisibleForTesting
    internal var dir: () -> File = { appContext.noBackupFilesDir }

    // the packs drawn in this process (no mark for them again), and the one marked, its first frame awaited
    private val drawn = HashSet<String>()

    @Volatile
    private var pending: String? = null

    private fun mark() = File(dir(), MARK)

    /** The pack [file] about to be drawn: marked, the first time in this process. On the main thread. */
    fun drawing(file: String) {
        if (file in drawn || pending == file) return
        pending = file
        try {
            FileOutputStream(mark()).use { out ->
                out.write(file.encodeToByteArray())
                out.fd.sync()
            }
        } catch (e: IOException) {
            Timber.w(e, "Theme pack draw mark not written")
        }
    }

    /** A keyboard's frame drawn: the pack marked drew one, its mark gone. On the main thread, each frame. */
    fun frameDrawn() {
        val file = pending ?: return
        pending = null
        drawn.add(file)
        mark().delete()
    }

    /** The pack whose first frame a process did not live to draw, its mark gone; null where none. */
    fun crashed(): String? {
        val mark = mark()
        if (!mark.isFile) return null
        val file = runCatching { mark.readText() }.getOrNull()
        mark.delete()
        return file?.takeIf { it.isNotEmpty() }
    }

    @VisibleForTesting
    internal fun reset() {
        drawn.clear()
        pending = null
    }
}
