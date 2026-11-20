/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.backup

import org.fcitx.fcitx5.android.engine.runtime.LearningOps
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
 * Restores and makes learning backups over the engine's [state] and [lists]. A restore merges
 * ([BackupMerge]) after saving the phone's learning to disk for 退回恢复前, transforming the log as on disk
 * (not [state]) so what is written is what a restart reads.
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
    /** The log as on disk, for an automatic backup off the keyboard thread; null: made here from [state]. */
    private val logBytes: (() -> ByteArray?)? = null,
    private val addresses: () -> AddressData? = { null },
    /** Merge (true) or replace (false, 退回恢复前) the app's address book. */
    private val putAddresses: (AddressData, merge: Boolean) -> Unit = { _, _ -> },
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
        // a user-picked file: report, never crash the keyboard
        BackupResult(ok = false, error = e.toString())
    }

    private fun peek(bytes: ByteArray) = if (isExport(bytes)) {
        BackupResult()
    } else {
        LearnBackup.read(bytes).head.let { BackupResult(head = it, own = isOwn(it)) }
    }

    /** Marks [log]'s days as this install's, so a backup made by it is restored on the log's own day line, not shifted and counted twice. */
    fun claim(log: LearnLog) {
        val id = backups.installId
        if (id == 0L || !log.onDisk || id in BackupMerge.origins(log.state)) return
        log.event { _, _ -> BackupMerge.lineage(id) }
    }

    private fun isOwn(head: BackupHead?) = head != null && backups.installId != 0L && head.installId == backups.installId

    // the user dictionary's export: restored by its import
    private fun isExport(bytes: ByteArray) = !LearnBackup.isBackup(bytes) && WordListFiles.isUserExport(bytes.copyOf(minOf(bytes.size, EXPORT_HEAD)))

    fun bytes(kind: Int): ByteArray {
        val (added, blocked) = lists()
        return bytes(kind, state(), added, blocked, clock.now(), addresses())
    }

    // timed no earlier than its last commit, so after a clock set back a clear's cut still finds it
    private fun bytes(kind: Int, s: LearnState, added: List<ListedWord>, blocked: List<ListedWord>, time: Long, book: AddressData?) =
        LearnBackup.bytes(BackupHead(maxOf(time, s.lastMinute * LearnClock.MINUTE), s.day, backups.installId, kind, version), s, added, blocked, book)

    /** Merges in a backup, a bare log or the user dictionary's export; returns its head. */
    @Throws(IOException::class)
    fun restore(bytes: ByteArray): BackupHead? {
        if (!isExport(bytes)) return restore(LearnBackup.read(bytes))
        val previous = backups.beforeRestore()
        backups.saveBeforeRestore(bytes(BackupHead.BEFORE_RESTORE))
        val lists = lists()
        // the log too: an import writes learning lines after its words (WordImport.importWords)
        val learned = state().let { it to it.version }
        val unchanged = { lists() == lists && state().let { it === learned.first && it.version == learned.second } }
        var imported = false
        try {
            importLines(WordLists.decode(bytes).lineSequence())
            imported = true
        } finally {
            // nothing restored: keep the old 退回恢复前 target, unless a partial import must stay undoable
            if (!imported && unchanged()) {
                if (previous != null) backups.saveBeforeRestore(previous) else backups.dropBeforeRestore()
            }
        }
        return null
    }

    /**
     * Merges [backup] into the phone's learning, in an order a kill at any point leaves whole: copy for
     * 退回恢复前 on disk, log rewritten, lists merged. A copy made at or after [since] with nothing learned
     * since is from an earlier try of this restore, and kept.
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
        // a backup's block of a word the phone added does not apply
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
            // nothing restored: keep the old 退回恢复前 target
            if (!written && copy) {
                if (previous != null) backups.saveBeforeRestore(previous) else backups.dropBeforeRestore()
            }
        }
        if (!written) throw IOException("the learning was not written")
        if (add.isNotEmpty() || block.isNotEmpty()) addLists(add, block)
        // a backup without addresses: keep the phone's
        backup.addresses?.let { putAddresses(it, true) }
        return backup.head
    }

    private fun minute() = LearnClock.floorDiv(clock.now(), LearnClock.MINUTE).toInt()

    private fun keepsCopy(since: Long?): Boolean {
        since ?: return false
        val made = backups.beforeRestoreTime()?.takeIf { it >= since } ?: return false
        val s = state()
        // typed after it: not a copy of the phone now
        return s.lastMinute <= maxOf(LearnClock.floorDiv(made, LearnClock.MINUTE).toInt(), s.restoredMinute)
    }

    /** 退回恢复前: restores lists and learning from the pre-restore copy, then deletes it; typing day and seq stay the phone's so later backups are not in its future. */
    @Throws(IOException::class)
    fun revert(): Boolean {
        val copy = LearnBackup.read(backups.beforeRestore() ?: return false)
        val minute = minute()
        val written = rewrite { log, out ->
            val (phone, kinds) = LearnBackup.replay(log)
            val s = copy.state
            val day = maxOf(phone.day, s.day)
            // its own head but the restore's minute: clears cut no earlier than this
            s.apply(
                Head(
                    cut = minute,
                    lastMinute = maxOf(phone.lastMinute, s.lastMinute, minute),
                    day = day,
                    localDay = maxOf(phone.localDay, s.localDay),
                    nextSeq = maxOf(phone.nextSeq, s.nextSeq),
                    restoredMinute = minute,
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
        // a copy without addresses: keep the phone's
        copy.addresses?.let { putAddresses(it, false) }
        backups.dropBeforeRestore()
        return true
    }

    /** Makes an automatic backup if due, off the keyboard thread where possible. */
    fun afterCommit() {
        val s = state()
        if (!backups.due(s.day)) return
        val (added, blocked) = lists()
        val book = addresses()
        val read = logBytes
        if (read == null) {
            val bytes = bytes(BackupHead.AUTO, s, added, blocked, clock.now(), book)
            backups.saveAuto(s.day) { bytes }
        } else {
            // timed after the read, so a clear's cut between the two finds it
            backups.saveAuto(s.day) { read()?.let { log -> LearnBackup.replay(log).first.let { bytes(BackupHead.AUTO, it, added, blocked, clock.now(), book) } } }
        }
    }

    /** Before a clear's rewrite: finishes pending automatic backups, then deletes this install's made at or after [fromMinute] and the pre-restore copy. */
    override fun invalidate(fromMinute: Int) = backups.invalidate(fromMinute)

    override fun saveIfOn() {
        val s = state()
        if (!backups.auto || backups.installId == 0L || s.day < 1) return
        val (added, blocked) = lists()
        val bytes = bytes(BackupHead.AUTO, s, added, blocked, clock.now(), addresses())
        backups.saveAuto(s.day) { bytes }
    }

    private companion object {
        // enough of a file to tell an export from another list
        const val EXPORT_HEAD = 4096
    }
}
