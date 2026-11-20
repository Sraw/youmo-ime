/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Guards against a theme 4.0 pack that crashes the keyboard on first draw (a crafted font): a mark is written before
 * the shown keyboard's first frame of a process and deleted once it commits. Still there at the next start, the pack's
 * settings reset ([ThemeManager]) and the user is told. Hidden views never mark, so other process deaths reset nothing.
 * Written unsynced (no main-thread disk wait), outside backups. A window hidden before the commit leaves the mark
 * pending till the next show's first commit; a process dying in that gap resets the pack falsely.
 */
object PackDrawGuard {

    private const val MARK = "theme-pack-drawing"

    /** [stamp]: the file time, so a reimport is another. */
    data class Key(val file: String, val stamp: Long)

    fun key(pack: Theme.Pack) = Key(pack.file, pack.stamp)

    @VisibleForTesting
    internal var dir: () -> File = { appContext.noBackupFilesDir }

    // first frames drawn this process (never marked again), and the one marked, its frame awaited
    private val drawn = HashSet<Key>()
    private var pending: Key? = null

    // frames asked per pack while its mark is pending ([askFrame])
    private val asked = HashMap<Key, Int>()

    private fun mark() = File(dir(), MARK)

    /** Marked till this process draws a frame of it; returns whether one is awaited (its commit calls [frameDrawn]). Main thread. */
    fun drawing(key: Key): Boolean {
        if (key in drawn) return false
        if (pending == key) return true
        pending = key
        try {
            mark().writeText(key.file)
        } catch (e: IOException) {
            Timber.w(e, "Theme pack draw mark not written")
        }
        return true
    }

    /**
     * A pending mark is cleared when an IME keyboard view (not in an activity) draws anything other than that pack: it is
     * no longer what the keyboard shows. Views in an activity (previews) clear nothing. Main thread.
     */
    fun shows(owner: View, key: Key?) {
        val p = pending ?: return
        if (p == key || inActivity(owner.context)) return
        pending = null
        mark().delete()
    }

    private fun inActivity(context: Context) =
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }.any { it is Activity }

    /** Another's frame clears nothing. Main thread. */
    fun frameDrawn(key: Key) {
        if (pending != key) return
        pending = null
        drawn.add(key)
        mark().delete()
    }

    /** Only the first [MAX_ASKED] times a process: a device committing no frame then draws no more; the mark stays. */
    fun askFrame(key: Key): Boolean {
        val n = asked.getOrDefault(key, 0)
        if (n >= MAX_ASKED) return false
        asked[key] = n + 1
        return true
    }

    const val MAX_ASKED = 3

    @VisibleForTesting
    internal fun confirmed(key: Key) = key in drawn

    /** The pack whose first frame a process did not live to draw; its mark gone. */
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
        asked.clear()
        pending = null
    }
}
