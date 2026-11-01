/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.backup

import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import org.fcitx.fcitx5.android.engine.user.WordLists
import java.io.IOException

/**
 * The learning of a settings backup restored (U-100, PLAN §2.9), left by the app in [DIR] in the
 * engine's directory while the engine was stopped, one directory per import named by its epoch
 * milliseconds ([pending], oldest first), to be merged as the engine next starts, not laid over
 * the phone's; each deleted only once merged, so a kill mid-merge has it merged again (which
 * changes nothing twice: BackupMerge), and one that does not read set aside as [UNREADABLE].
 * Each: the zip's [BACKUP]; or, a zip of an earlier build, its `learn.log`; with the lists of
 * words it has (`words.added`, `words.blocked`). A zip older still (0.3.0's, its learning in the
 * old logs alone) is refused as the app imports it; one left here all the same does not read.
 */
object RestoreStash {
    const val DIR = "learn.restore"

    /** Beside [DIR]: the stashes that did not read, told once, not tried at every start. */
    const val UNREADABLE = "learn.restore.unreadable"

    /** A settings backup's learning, at the zip's root. */
    const val BACKUP = "learning" + LearnBackup.EXTENSION

    /** The stashes in [root] ([DIR]), oldest first, with the epoch milliseconds each was left at. */
    fun pending(root: UserFiles): List<Pair<UserFiles, Long>> =
        root.dirs().mapNotNull { name -> name.toLongOrNull()?.let { root.dir(name) to it } }.sortedBy { it.second }

    /** [stash] moved to [UNREADABLE] in [userDir], beside [DIR], in place of one there before. */
    fun setAside(userDir: UserFiles, stash: UserFiles) {
        val aside = userDir.dir(UNREADABLE)
        aside.deleteAll()
        if (!stash.renameTo(aside)) stash.deleteAll()
    }

    /**
     * What [dir] holds, as a backup.
     *
     * @throws IOException if what it holds does not read, or is only an older build's logs
     */
    fun read(dir: UserFiles, onError: (IOException) -> Unit = {}): Backup {
        dir.file(BACKUP).takeIf { it.exists() }?.let { return LearnBackup.read(it.read()) }
        val log = dir.file(LearnLog.FILE)
        val oldLogs = (dir.files() + dir.dirs()).any { it.endsWith(".user") }
        if (!log.exists() && oldLogs) throw IOException("$dir: the learning of a build before 0.3.1, which this one no longer reads")
        val state = if (log.exists()) LearnLog.replay(log.read()) else LearnState()
        val lists = WordLists(dir, onError)
        val listed = { e: Entry -> ListedWord(e.text, e.apostrophed()) }
        // its days of an install its files tell: the same zip again (a retry, another import) the same one
        val files = dir.files().sorted()
        val origin = LearnBackup.origin(*files.flatMap { listOf(it.toByteArray(), dir.file(it).read()) }.toTypedArray())
        return Backup(null, state, lists.addedWords.map(listed), lists.blockedWords.map(listed), origin)
    }
}
