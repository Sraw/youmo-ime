/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.learn.backup.Backup
import org.fcitx.fcitx5.android.engine.learn.backup.RestoreStash
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import java.io.IOException

/** Learning left by a settings restore while the engine was stopped (RestoreStash), taken in at [Engines]' start: each [read], [merge]d and removed; an unreadable one set aside. */
internal class StashIntake(
    private val userDir: UserFiles?,
    private val onError: (IOException) -> Unit,
    private val read: (UserFiles) -> Backup,
    // merged as of the time it was left
    private val merge: (Backup, Long) -> Unit,
) {
    fun takeAll() {
        val userDir = userDir ?: return
        val root = userDir.dir(RestoreStash.DIR).takeIf { it.exists() } ?: return
        for ((dir, time) in RestoreStash.pending(root)) {
            // one not merged (disk): it and later ones wait for the next start, in order
            if (!take(userDir, dir, time)) return
        }
        if (root.files().isEmpty() && root.dirs().isEmpty()) root.delete()
    }

    // false if left for the next start
    private fun take(userDir: UserFiles, dir: UserFiles, time: Long): Boolean {
        // far beyond a heavy user's learning: never read whole at every start
        if (dir.size() > MAX_STASH) return unreadable(userDir, dir, IOException("$dir too large"))
        val backup = try {
            read(dir)
        } catch (e: IOException) {
            return unreadable(userDir, dir, e)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // old files, however they came to be: no failure reading them should stop typing
            return unreadable(userDir, dir, e)
        }
        try {
            // a 退回恢复前 copy from an earlier killed try is kept: it is the phone's prior state
            merge(backup, time)
        } catch (e: IOException) {
            onError(IOException("a restored backup's learning not merged yet", e))
            return false
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            return unreadable(userDir, dir, e)
        }
        dir.deleteAll()
        return true
    }

    // told once and moved aside, not retried every start
    private fun unreadable(userDir: UserFiles, dir: UserFiles, e: Exception): Boolean {
        onError(IOException("a restored backup's learning does not read: set aside", e))
        RestoreStash.setAside(userDir, dir)
        return true
    }

    private companion object {
        // larger stashes are set aside unread
        const val MAX_STASH = 256L shl 20
    }
}
