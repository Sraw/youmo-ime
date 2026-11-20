/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.backup

import org.fcitx.fcitx5.android.engine.english.EnglishBlocks
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import org.fcitx.fcitx5.android.engine.user.WordLists
import java.io.IOException

/**
 * A restored settings backup's learning, left by the app in [DIR] while the engine was stopped (one
 * directory per import, named by epoch millis), merged at the next start rather than laid over the phone's.
 * Each is deleted only once merged (merging twice changes nothing); an unreadable one is set aside.
 */
object RestoreStash {
    const val DIR = "learn.restore"

    /** Beside [DIR]: unreadable stashes, reported once rather than retried every start. */
    const val UNREADABLE = "learn.restore.unreadable"

    const val BACKUP = "learning" + LearnBackup.EXTENSION

    /** Oldest first, with the epoch millis each was left at. */
    fun pending(root: UserFiles): List<Pair<UserFiles, Long>> =
        root.dirs().mapNotNull { name -> name.toLongOrNull()?.let { root.dir(name) to it } }.sortedBy { it.second }

    fun setAside(userDir: UserFiles, stash: UserFiles) {
        val aside = userDir.dir(UNREADABLE)
        aside.deleteAll()
        if (!stash.renameTo(aside)) stash.deleteAll()
    }

    /** @throws IOException if what it holds does not read, or is only an older build's logs */
    fun read(dir: UserFiles, onError: (IOException) -> Unit = {}): Backup {
        dir.file(BACKUP).takeIf { it.exists() }?.let { return LearnBackup.read(it.read()) }
        val log = dir.file(LearnLog.FILE)
        val oldLogs = (dir.files() + dir.dirs()).any { it.endsWith(".user") }
        if (!log.exists() && oldLogs) throw IOException("$dir: the learning of a build before 0.3.1, which this one no longer reads")
        val state = if (log.exists()) LearnLog.replay(log.read()) else LearnState()
        val lists = WordLists(dir, onError)
        val listed = { e: Entry -> ListedWord(e.text, e.apostrophed()) }
        // its install derived from the files: the same zip again is the same install
        val files = dir.files().sorted()
        val origin = LearnBackup.origin(*files.flatMap { listOf(it.toByteArray(), dir.file(it).read()) }.toTypedArray())
        val english = EnglishBlocks(dir, onError).blocked.map { ListedWord(it, "") }
        return Backup(null, state, lists.addedWords.map(listed), lists.blockedWords.map(listed) + english, origin)
    }
}
