/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.backup

import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import org.fcitx.fcitx5.android.engine.user.WordLists
import java.io.File
import java.io.IOException

/**
 * The learning of a settings backup restored (U-100, PLAN §2.9), left by the app in [DIR] in the
 * engine's directory while the engine was stopped, one directory per import named by its epoch
 * milliseconds ([pending], oldest first), to be merged as the engine next starts, not laid over
 * the phone's; each deleted only once merged, so a kill mid-merge has it merged again (which
 * changes nothing twice: BackupMerge), and one that does not read set aside as [UNREADABLE].
 * Each: the zip's [BACKUP]; or, a zip of an earlier build, its `learn.log`; or, older still,
 * its old logs (`pinyin.user`, the tables', English's), converted as the phone's were once; with
 * the lists of words it has (`words.added`, `words.blocked`).
 */
object RestoreStash {
    const val DIR = "learn.restore"

    /** Beside [DIR]: the stashes that did not read, told once, not tried at every start. */
    const val UNREADABLE = "learn.restore.unreadable"

    /** A settings backup's learning, at the zip's root. */
    const val BACKUP = "learning" + LearnBackup.EXTENSION

    /** The stashes in [root] ([DIR]), oldest first, with the epoch milliseconds each was left at. */
    fun pending(root: File): List<Pair<File, Long>> =
        root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { f -> f.name.toLongOrNull()?.let { f to it } }.sortedBy { it.second }

    /** [stash] moved to [UNREADABLE] beside [root], in place of one there before. */
    fun setAside(root: File, stash: File) {
        val aside = File(root.parentFile, UNREADABLE)
        aside.deleteRecursively()
        if (!stash.renameTo(aside)) stash.deleteRecursively()
    }

    /**
     * What [dir] holds, as a backup; [convert] fills a state, empty, from the old logs in it (the
     * learning record's conversion).
     *
     * @throws IOException if what it holds does not read
     */
    fun read(dir: File, convert: (LearnState) -> Unit, onError: (IOException) -> Unit = {}): Backup {
        File(dir, BACKUP).takeIf { it.isFile }?.let { return LearnBackup.read(it.readBytes()) }
        val log = File(dir, LearnLog.FILE)
        val state = if (log.isFile) LearnLog.replay(log.readBytes()) else LearnState().also(convert)
        val lists = WordLists(dir, onError)
        val listed = { e: Entry -> ListedWord(e.text, WordLists.code(e.syllables)) }
        // its days of an install its files tell: the same zip again (a retry, another import) the same one
        val files = dir.listFiles { f -> f.isFile }.orEmpty().sortedBy { it.name }
        val origin = LearnBackup.origin(*files.flatMap { listOf(it.name.toByteArray(), it.readBytes()) }.toTypedArray())
        return Backup(null, state, lists.addedWords.map(listed), lists.blockedWords.map(listed), origin)
    }
}
