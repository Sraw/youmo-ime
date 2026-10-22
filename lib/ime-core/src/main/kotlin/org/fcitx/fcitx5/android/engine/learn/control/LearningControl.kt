/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.control

import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnRecords
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * The user's controls over the learning record (PLAN §2.8, impl/streams/E-design.md): an act of a
 * long press made undoable, U-88 清掉最近 and U-86 清空学到的, each a rewrite of the log under its
 * compaction lock ([LearnLog.rewrite]). Holds no state of what was learned: only the last act,
 * for the chip to take back while nothing else was written after it.
 *
 * [log] is the engine's, opened again where it was closed (review-E-engine S6). One not on disk
 * (direct boot) has what was typed in its state alone, no time on it: [clearRecent] does nothing
 * there. [legacy] are the older builds' learning files no build writes any more, deleted with what
 * was cleared (PLAN §2.8: 「装回旧版也看不到了」).
 */
class LearningControl(
    private val log: () -> LearnLog,
    private val clock: LearnClock = LearnClock.System,
    private val legacy: () -> List<File> = { emptyList() },
    internal val onError: (IOException) -> Unit = {},
) {
    /** An act that may still be taken back: the records it wrote, the log's [LearnLog.rewrites] then, and how to undo what it did outside the log. */
    private class Act(val token: Long, val records: List<LearnRecord>, val rewrites: Long, val inverse: () -> Unit)

    private var last: Act? = null
    private var tokens = 0L

    // what the act running wrote; null: none runs, and a record written takes the last act's undo away
    private var recording: MutableList<LearnRecord>? = null

    /** Hears what [log] writes from now on: the log the engine opened, again after it was closed. */
    fun attach(log: LearnLog) {
        last = null
        log.appended = { r -> recording?.add(r) ?: run { last = null } }
    }

    /**
     * Runs [act], what it writes to the log taken back as one by [undo] with the token returned,
     * and [inverse] run then to undo what it did elsewhere (a word blocked in the lists, say).
     * Only one act may be taken back: the last, while nothing was written after it.
     */
    fun undoable(inverse: () -> Unit = {}, act: () -> Unit): Long {
        val rewrites = log().rewrites
        val written = ArrayList<LearnRecord>()
        recording = written
        // an act that throws may have written: the act before it is no longer the last (see [attach])
        last = null
        try {
            act()
        } finally {
            recording = null
        }
        return (++tokens).also { last = Act(it, written, rewrites, inverse) }
    }

    /**
     * Takes back the act [token] names: whether it was the last, and taken back. What has its
     * opposite (a pin, a prediction turned off) is undone by writing that, as cheap as the act;
     * a forget, by the log rewritten without it. Not once the log was rewritten since (a restore,
     * a clear): its records may be gone, or others in their place, and what it did elsewhere
     * (a word blocked) may be what the restore brought (review-E-engine N6).
     */
    fun undo(token: Long): Boolean {
        val act = last?.takeIf { it.token == token } ?: return false
        last = null
        val log = log()
        if (log.rewrites != act.rewrites) return false
        if (act.records.isNotEmpty()) {
            val opposites = act.records.map(::opposite)
            if (opposites.all { it != null }) {
                opposites.asReversed().forEach { it?.invoke(log) }
            } else if (!rewritten(log) { bytes, out -> LogRewrites.dropLast(bytes, act.records, out) }) {
                return false
            }
        }
        act.inverse()
        return true
    }

    // a rewrite the disk failed told, as not done
    private fun rewritten(log: LearnLog, transform: (ByteArray, OutputStream) -> Boolean): Boolean = try {
        log.rewrite(transform)
    } catch (e: IOException) {
        onError(e)
        false
    }

    // what writes [r]'s opposite, the state then as before it; null if there is none
    private fun opposite(r: LearnRecord): ((LearnLog) -> Unit)? = when (r) {
        is LearnRecord.Pin -> { log -> log.pin(r.code, r.text, r.reading, !r.on) }
        is LearnRecord.NoPredict -> { log -> log.noPredict(r.prev, r.text, !r.on) }
        is LearnRecord.TableBlock -> { log -> log.event { minute, _ -> r.copy(minute = minute, on = !r.on) } }
        else -> null
    }

    /**
     * U-88: what was typed in [span] up to now forgotten, as though it never was; never what was
     * before the last restore (`RESTORED`) nor what the snapshot holds (its cut: a clock put back
     * may have it later than the span's start). Today's also takes the older builds' files.
     * [invalidate] is told the cut first, for the backups saved since to go before the rewrite
     * (C25: a crash between must not leave them to bring it back). The epoch minute cleared from,
     * or null if nothing could be (no log on disk).
     *
     * @throws IOException if the disk failed: nothing cleared
     */
    @Throws(IOException::class)
    fun clearRecent(span: LearningOp.Span, invalidate: (Int) -> Unit = {}): Int? {
        val log = log().takeIf { it.onDisk } ?: return null
        val now = clock.now()
        val minute = LearnClock.floorDiv(now, LearnClock.MINUTE).toInt()
        val from = if (span == LearningOp.Span.TODAY) LearnClock.midnight(clock, LearnClock.localDay(clock, minute), now) else minute - span.minutes
        val cut = maxOf(from, log.state.cut, log.state.restoredMinute)
        last = null
        invalidate(cut)
        // nothing to take out (false) is cleared all the same: what a session keeps of the span too (review-1c S4)
        if (!log.rewrite { bytes, out -> LogRewrites.clearSince(bytes, cut, out) }) log.rewritten()
        if (span == LearningOp.Span.TODAY) dropLegacy(log)
        return cut
    }

    /**
     * U-86: what was learned forgotten, what the user did on purpose kept ([LogRewrites.cleared]);
     * [all], everything. [invalidate] first, as for [clearRecent], with [LearningResult.ALL]; not
     * for a log not on disk, whose rewrite would not land: the backups are what a record that did
     * not open has left. Whether it was: not for a log that did not open.
     *
     * @throws IOException if the disk failed: nothing cleared
     */
    @Throws(IOException::class)
    fun clearLearned(all: Boolean, invalidate: (Int) -> Unit = {}): Boolean {
        val log = log()
        last = null
        // in memory (no user directory) there are no backups either: only an unopened record is spared
        if (log.onDisk) invalidate(LearningResult.ALL)
        val rows = LogRewrites.cleared(log.state, LearnClock.cut(clock), all)
        val written = log.rewrite { _, out ->
            out.write(LearnRecords.FORMAT.header())
            val writer = LearnRecords.SnapshotWriter(out::write)
            rows.forEach(writer::write)
            true
        }
        if (written) dropLegacy(log)
        return written
    }

    /**
     * The older builds' files gone, and the marker made to say so, lest the next start read them
     * gone as words forgotten. Crash-safe (review-E-engine M3): each renamed aside first, the marker
     * written without them, then deleted; a kill between leaves the renamed ones for [resumeDrop].
     */
    private fun dropLegacy(log: LearnLog) {
        val files = legacy().filter { it.exists() }
        if (files.isEmpty()) {
            // a conversion still owed (the tables' logs alone: none of them retired) owed no more, nor
            // merged in after the clear to bring back what it cleared (review-uat-fixes S2)
            if (log.conversionPending) log.remarkLegacy()
            return
        }
        val aside = files.mapNotNull { f ->
            aside(f).takeIf { f.renameTo(it) }.also { if (it == null) onError(IOException("cannot set $f aside")) }
        }
        // the marker not written, the files stay aside: the next open remarks them and deletes them then
        if (log.remarkLegacy()) deleteAside(aside)
    }

    /**
     * Whether a clear was killed between setting the older builds' files aside and saying so in
     * the marker: the rest of them set aside too, for the log to open remarking them
     * ([LearnLog.open]'s remark), then [finishDrop].
     */
    fun resumeDrop(): Boolean {
        val files = legacy()
        if (files.none { aside(it).exists() }) return false
        for (f in files) if (f.exists() && !f.renameTo(aside(f))) onError(IOException("cannot set $f aside"))
        return true
    }

    /** What [resumeDrop] left, deleted once the marker says them gone. */
    fun finishDrop() = deleteAside(legacy().map(::aside).filter { it.exists() })

    private fun deleteAside(files: List<File>) {
        for (f in files) if (!f.delete()) onError(IOException("cannot delete $f"))
    }

    private fun aside(f: File) = File(f.path + CLEARED)

    private companion object {
        const val CLEARED = ".cleared"
    }
}
