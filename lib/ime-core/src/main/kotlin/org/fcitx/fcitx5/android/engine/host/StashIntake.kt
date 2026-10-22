/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.learn.backup.Backup
import org.fcitx.fcitx5.android.engine.learn.backup.RestoreStash
import java.io.File
import java.io.IOException

/**
 * The settings backups' learning, left under [userDir] by the app while the engine was stopped
 * (RestoreStash), taken in at [Engines]' start: each stash [read] and [merge]d, then gone; one that
 * does not read set aside. Apart from [Engines] for its size.
 */
internal class StashIntake(
    private val userDir: File?,
    private val onError: (IOException) -> Unit,
    // a stash's files as a backup, an older build's learning in them converted
    private val read: (File) -> Backup,
    // a backup merged in, as of the time it was left at
    private val merge: (Backup, Long) -> Unit,
) {
    /** Each stash merged, then gone. */
    fun takeAll() {
        val root = userDir?.let { File(it, RestoreStash.DIR) }?.takeIf { it.isDirectory } ?: return
        for ((dir, time) in RestoreStash.pending(root)) {
            // one not merged (the disk): it and those after it left for the next start, in their order
            if (!take(root, dir, time)) return
        }
        if (root.list().isNullOrEmpty()) root.delete()
    }

    // [dir], a stash left at [time]: merged and gone, or set aside if it does not read; false if left for the next start
    private fun take(root: File, dir: File, time: Long): Boolean {
        // more than a heavy user's learning by far: no reading it whole at every start
        if (dir.walk().sumOf { it.length() } > MAX_STASH) return unreadable(root, dir, IOException("$dir too large"))
        val backup = try {
            read(dir)
        } catch (e: IOException) {
            return unreadable(root, dir, e)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // old files, however they came to be: no failure reading them should stop typing
            return unreadable(root, dir, e)
        }
        try {
            // a copy for 退回恢复前 made by an earlier try of this one (killed mid-merge) is kept: it is the phone's before
            merge(backup, time)
        } catch (e: IOException) {
            onError(IOException("a restored backup's learning not merged yet", e))
            return false
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            return unreadable(root, dir, e)
        }
        dir.deleteRecursively()
        return true
    }

    // told once, moved aside, not tried at every start: the next stash goes on
    private fun unreadable(root: File, dir: File, e: Exception): Boolean {
        onError(IOException("a restored backup's learning does not read: set aside", e))
        RestoreStash.setAside(root, dir)
        return true
    }

    private companion object {
        // a settings zip's learning set aside unread past this
        const val MAX_STASH = 256L shl 20
    }
}
