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
 * A theme 4.0 pack that took the keyboard down as it was first drawn (a font crafted to crash the text's layout,
 * a file the engine draws wrong): a mark naming the pack written as the keyboard shown is about to draw its first
 * frame of a process (KeyboardCanvasView), deleted once that frame is committed, the glyphs and images drawn with
 * it. Still there at the next start, the pack is not drawn again on its own: the settings naming it go back to
 * their defaults ([ThemeManager]), the user told. Nothing built for a view not shown (a settings page, the keyboard
 * made again while hidden) marks it, so a process ended otherwise (an update, the system's) resets nothing. The
 * mark is in the files not backed up (a crash is this phone's); written, not synced: a process that dies leaves
 * what it wrote, and the main thread does not wait on the disk. A window hidden before the frame after the first
 * commits nothing: the mark is pending till the next show's first commit, and a process ended in that gap resets
 * the pack falsely (no commit seen, it cannot be cleared safely sooner).
 */
object PackDrawGuard {

    private const val MARK = "theme-pack-drawing"

    /** A pack as drawn: its name and its file's time (one imported again is another). */
    data class Key(val file: String, val stamp: Long)

    fun key(pack: Theme.Pack) = Key(pack.file, pack.stamp)

    @VisibleForTesting
    internal var dir: () -> File = { appContext.noBackupFilesDir }

    // the packs whose first frame this process drew (no mark for them again), and the one marked, its frame awaited
    private val drawn = HashSet<Key>()
    private var pending: Key? = null

    // the frames asked for while a pack's mark is pending ([askFrame]), a pack
    private val asked = HashMap<Key, Int>()

    private fun mark() = File(dir(), MARK)

    /**
     * The pack [key] about to be drawn in a frame shown: marked, till a frame of it of this process is drawn;
     * whether one is still awaited (the frame's commit to call [frameDrawn]). On the main thread.
     */
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
     * [owner] about to draw a frame shown of the pack [key] (null: a theme not a pack's). Where a mark is pending
     * (the keyboard's window hidden before its frame committed) and [owner] is a keyboard of the input method (a
     * view not in an activity, whichever instance: a theme change builds the keyboard's views anew) drawing anything
     * other than that pack (a built-in theme, another pack, even one already drawn this process), the mark is
     * cleared: that pack is no longer what the keyboard shows. A view in an activity (a settings preview, a theme
     * card) clears nothing. On the main thread.
     */
    fun shows(owner: View, key: Key?) {
        val p = pending ?: return
        if (p == key || inActivity(owner.context)) return
        pending = null
        mark().delete()
    }

    private fun inActivity(context: Context) =
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }.any { it is Activity }

    /** The frame [drawing] marked [key] for committed: its mark gone. Another's frame clears nothing. On the main thread. */
    fun frameDrawn(key: Key) {
        if (pending != key) return
        pending = null
        drawn.add(key)
        mark().delete()
    }

    /**
     * Whether the view drawing [key]'s pending frame may ask for another, whose commit tells that one drawn: the
     * first [MAX_ASKED] times a process. A device that commits no frame (or calls back none) then draws no more
     * than that; the mark stays till a later frame's commit.
     */
    fun askFrame(key: Key): Boolean {
        val n = asked.getOrDefault(key, 0)
        if (n >= MAX_ASKED) return false
        asked[key] = n + 1
        return true
    }

    const val MAX_ASKED = 3

    /** Whether the pack [key]'s first frame of this process was drawn. */
    @VisibleForTesting
    internal fun confirmed(key: Key) = key in drawn

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
        asked.clear()
        pending = null
    }
}
