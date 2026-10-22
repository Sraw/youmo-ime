/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.backup

import org.fcitx.fcitx5.android.engine.host.LearningOps
import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Head
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.BackupResult
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.user.WordListFiles
import org.fcitx.fcitx5.android.engine.user.WordLists
import java.io.IOException
import java.io.OutputStream

/**
 * Restores and makes the learning backups (U-100, U-106, C38) over what the engine has: the
 * learning [state], and the lists ([lists]: added, blocked). The engine says how the log is
 * rewritten whole ([rewrite]: LearnLog.rewrite, the sessions dropped) and how words go on and off
 * the lists ([addLists], [removeLists]; [importLines], the user dictionary's import, for its export).
 *
 * A restore merges ([BackupMerge]), after the phone's learning is kept as it was
 * ([Backups.saveBeforeRestore], on disk first), so 退回恢复前 puts that back whole: what was
 * learned, added, deleted, pinned and blocked since (C38). The merge is a transform of the log as
 * it is on disk, not of [state], so what is written is what a restart would read.
 */
class BackupControl(
    val backups: Backups,
    private val clock: LearnClock,
    private val version: String,
    private val state: () -> LearnState,
    private val lists: () -> Pair<List<ListedWord>, List<ListedWord>>,
    private val rewrite: ((ByteArray, OutputStream) -> Boolean) -> Boolean,
    private val addLists: (added: List<ListedWord>, blocked: List<ListedWord>) -> Unit,
    private val removeLists: (added: List<ListedWord>, blocked: List<ListedWord>) -> Unit,
    private val importLines: (Sequence<String>) -> Unit,
    /** The phone's log as on disk, for an automatic backup made off the keyboard's thread; null: none, made here from [state]. */
    private val logBytes: (() -> ByteArray?)? = null,
    /** Whether an older build's learning is still to be converted in: no automatic backup till it is, else the first lacks it. */
    private val conversionPending: () -> Boolean = { false },
) : LearningOps.Backups {
    fun run(op: BackupOp): BackupResult = try {
        when (op) {
            BackupOp.Kept -> BackupResult(backups = backups.list(), canRevert = backups.hasBeforeRestore())
            is BackupOp.Delete -> BackupResult(ok = backups.delete(op.name))
            is BackupOp.Peek -> peek(op.bytes)
            is BackupOp.Restore -> BackupResult(head = restore(op.bytes))
            is BackupOp.RestoreKept -> BackupResult(head = restore(backups.read(op.name) ?: throw IOException("no backup ${op.name}")))
            BackupOp.Revert -> BackupResult(ok = revert())
            BackupOp.Copy -> BackupResult(bytes = bytes(BackupHead.SAVED))
            is BackupOp.SetAuto -> BackupResult().also { backups.auto = op.on }
        }
    } catch (e: IOException) {
        BackupResult(ok = false, error = e.message)
    } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
        // a file the user picked, however it came to be: told, never the keyboard stopped
        BackupResult(ok = false, error = e.toString())
    }

    private fun peek(bytes: ByteArray) = if (isExport(bytes)) {
        BackupResult()
    } else {
        LearnBackup.read(bytes).head.let { BackupResult(head = it, own = isOwn(it)) }
    }

    /**
     * Writes in [log] that its days are this install's (review-int-records S5): a log carried to
     * another phone (D2D, a settings zip) keeps each install that wrote it, so a backup one of them
     * made is restored on the log's own day line, not moved past the phone's last day and counted
     * twice. None for a log in memory or an install of no id.
     */
    fun claim(log: LearnLog) {
        val id = backups.installId
        if (id == 0L || !log.onDisk || id in BackupMerge.origins(log.state)) return
        log.event { _, _ -> BackupMerge.lineage(id) }
    }

    private fun isOwn(head: BackupHead?) = head != null && backups.installId != 0L && head.installId == backups.installId

    // the user dictionary's export (`# youmo-words`, an older one's header, or blocked lines): restored by its import
    private fun isExport(bytes: ByteArray) = !LearnBackup.isBackup(bytes) && WordListFiles.isUserExport(bytes.copyOf(minOf(bytes.size, EXPORT_HEAD)))

    /** The phone's learning as a backup of [kind]. */
    fun bytes(kind: Int): ByteArray {
        val (added, blocked) = lists()
        return bytes(kind, state(), added, blocked, clock.now())
    }

    // timed no earlier than its last commit, whose minute never goes back: after a clock set back,
    // a clear's cut still finds a backup holding what it clears made at or after it (C25)
    private fun bytes(kind: Int, s: LearnState, added: List<ListedWord>, blocked: List<ListedWord>, time: Long) =
        LearnBackup.bytes(BackupHead(maxOf(time, s.lastMinute * LearnClock.MINUTE), s.day, backups.installId, kind, version), s, added, blocked)

    /** [bytes], a backup, a bare log or the user dictionary's export, merged in (U-100): its head. */
    @Throws(IOException::class)
    fun restore(bytes: ByteArray): BackupHead? {
        if (!isExport(bytes)) return restore(LearnBackup.read(bytes))
        // a restore as any other (U-100 lists 旧版幽默的导出): 退回恢复前 takes it back
        val previous = backups.beforeRestore()
        backups.saveBeforeRestore(bytes(BackupHead.BEFORE_RESTORE))
        val lists = lists()
        // the log too: an import's learning lines are written after its words (UserLexicon.importWords)
        val learned = state().let { it to it.version }
        val unchanged = { lists() == lists && state().let { it === learned.first && it.version == learned.second } }
        var imported = false
        try {
            importLines(WordLists.decode(bytes).lineSequence())
            imported = true
        } finally {
            // nothing restored: 退回恢复前 goes back where it went before, as for a backup's; an import
            // that failed part way (its words added, its blocks not) keeps this copy, else what it
            // added could not be taken back (F3139), nor what it learned, its words the same (F3202)
            if (!imported && unchanged()) {
                if (previous != null) backups.saveBeforeRestore(previous) else backups.dropBeforeRestore()
            }
        }
        return null
    }

    /**
     * Merges [backup] into the phone's learning: its head back. A head of no known install (a
     * bare log, an older build's files) is another install's. In the order a kill at any point
     * leaves whole (review-DE-design D-M2): the phone's copy for 退回恢复前 on disk, the log
     * rewritten, the lists merged. A restore that fails puts back the copy there was before it.
     *
     * [since], a settings zip's stash's time (epoch milliseconds): a copy made at or after it,
     * with nothing learned since but the restore, is of an earlier try of this one (taken again
     * after a kill), the phone's as it was before, and kept.
     */
    @Throws(IOException::class)
    fun restore(backup: Backup, since: Long? = null): BackupHead? {
        val previous = backups.beforeRestore()
        val copy = !keepsCopy(since)
        if (copy) backups.saveBeforeRestore(bytes(BackupHead.BEFORE_RESTORE))
        val same = isOwn(backup.head)
        val minute = minute()
        val (added, blocked) = lists()
        val (add, block) = BackupMerge.lists(added, blocked, backup.added, backup.blocked)
        // what is blocked once restored: the backup's block of a word the phone added is not (the phone's stays)
        val blocks = (blocked + block).mapTo(HashSet()) { it.text }
        val lastBatch = state().imports.lastId
        var written = false
        try {
            written = rewrite { log, out ->
                val (phone, kinds) = LearnBackup.replay(log)
                LearnBackup.writeLog(out, BackupMerge.merge(phone, backup.state, backup.origin, same, minute, blocks, lastBatch), kinds)
                true
            }
        } finally {
            // nothing restored: 退回恢复前 goes back where it went before
            if (!written && copy) {
                if (previous != null) backups.saveBeforeRestore(previous) else backups.dropBeforeRestore()
            }
        }
        if (!written) throw IOException("the learning was not written")
        if (add.isNotEmpty() || block.isNotEmpty()) addLists(add, block)
        return backup.head
    }

    private fun minute() = LearnClock.floorDiv(clock.now(), LearnClock.MINUTE).toInt()

    private fun keepsCopy(since: Long?): Boolean {
        since ?: return false
        val made = backups.beforeRestoreTime()?.takeIf { it >= since } ?: return false
        val s = state()
        // typed after it (the try's rewrite failed, the phone used since): that is no copy of the phone now
        return s.lastMinute <= maxOf(LearnClock.floorDiv(made, LearnClock.MINUTE).toInt(), s.restoredMinute)
    }

    /**
     * 退回恢复前: the lists and the learning as the copy made before the latest restore has them,
     * whole, and the copy gone last; false if there is none (cleared since, or reverted). The
     * typing day and the sequence stay where the phone has them, so backups made since are not
     * in its future (review-DE-design D-S4).
     */
    @Throws(IOException::class)
    fun revert(): Boolean {
        val copy = LearnBackup.read(backups.beforeRestore() ?: return false)
        val minute = minute()
        val written = rewrite { log, out ->
            val (phone, kinds) = LearnBackup.replay(log)
            val s = copy.state
            val day = maxOf(phone.day, s.day)
            // its own head but for the restore's minute: U-88 cuts no earlier than this
            s.apply(
                Head(
                    cut = minute,
                    lastMinute = maxOf(phone.lastMinute, s.lastMinute, minute),
                    day = day,
                    localDay = maxOf(phone.localDay, s.localDay),
                    nextSeq = maxOf(phone.nextSeq, s.nextSeq),
                    convertedDay = phone.convertedDay,
                    restoredMinute = minute,
                    convertedTables = phone.convertedTables,
                ),
            )
            s.sweepTo(day)
            LearnBackup.writeLog(out, s, kinds)
            true
        }
        if (!written) throw IOException("the learning was not written")
        val (added, blocked) = lists()
        val keepAdded = copy.added.toHashSet()
        val keepBlocked = copy.blocked.toHashSet()
        removeLists(added.filter { it !in keepAdded }, blocked.filter { it !in keepBlocked })
        val (nowAdded, nowBlocked) = lists()
        val add = copy.added.filter { it !in nowAdded }
        val block = copy.blocked.filter { it !in nowBlocked }
        if (add.isNotEmpty() || block.isNotEmpty()) addLists(add, block)
        backups.dropBeforeRestore()
        return true
    }

    /**
     * After a commit: an automatic backup made if one is due (U-106), off the keyboard's thread
     * where the log can be read there ([logBytes]): only the lists are read here.
     */
    fun afterCommit() {
        val s = state()
        if (!backups.due(s.day) || conversionPending()) return
        val (added, blocked) = lists()
        val read = logBytes
        if (read == null) {
            val bytes = bytes(BackupHead.AUTO, s, added, blocked, clock.now())
            backups.saveAuto(s.day) { bytes }
        } else {
            // its time after the read: a clear's cut between the two finds it made after (C25)
            backups.saveAuto(s.day) { read()?.let { log -> LearnBackup.replay(log).first.let { bytes(BackupHead.AUTO, it, added, blocked, clock.now()) } } }
        }
    }

    /**
     * Before U-86 or U-88's rewrite (C25, review-DE-design DE-M1): an automatic backup being
     * written finished, then this install's made at or after [fromMinute] (epoch minutes;
     * [LearningResult.ALL], all of them) and the copy before a restore gone.
     */
    override fun invalidate(fromMinute: Int) = backups.invalidate(fromMinute)

    /** After U-86 or U-88's rewrite: one made at once of what is left, if they are made at all. */
    override fun saveIfOn() {
        val s = state()
        if (!backups.auto || backups.installId == 0L || s.day < 1) return
        val (added, blocked) = lists()
        val bytes = bytes(BackupHead.AUTO, s, added, blocked, clock.now())
        backups.saveAuto(s.day) { bytes }
    }

    private companion object {
        // as much of a file as tells an export from another list
        const val EXPORT_HEAD = 4096
    }
}
