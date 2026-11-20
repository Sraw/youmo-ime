/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.control

import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnRecords
import org.fcitx.fcitx5.android.engine.platform.LogFile
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmClock
import java.io.IOException

/**
 * The user's controls over the learning record: an undoable long-press act, 清掉最近 and 清空学到的, each
 * a log rewrite under its compaction lock ([LearnLog.rewrite]). Keeps only the last act, for the chip to
 * take back while nothing was written after it. [legacy] are the older builds' learning files, deleted (never read)
 * with what is cleared, so a build put back shows none of it; [copies] (records kept aside whole) go with every clear.
 */
class LearningControl(
    private val log: () -> LearnLog,
    private val clock: LearnClock = JvmClock,
    private val legacy: (all: Boolean) -> List<LogFile> = { emptyList() },
    private val copies: () -> List<LogFile> = { emptyList() },
    internal val onError: (IOException) -> Unit = {},
) {
    /** An act that may still be taken back: its records (each with whether the file took it), the log's [LearnLog.rewrites] then, and its outside undo. */
    private class Act(val token: Long, val records: List<Pair<LearnRecord, Boolean>>, val rewrites: Long, val inverse: () -> Unit)

    private var last: Act? = null
    private var tokens = 0L

    // what the running act wrote; null: none runs, and any record written cancels the last act's undo
    private var recording: MutableList<Pair<LearnRecord, Boolean>>? = null

    fun attach(log: LearnLog) {
        last = null
        log.appended = { r, kept -> recording?.add(r to kept) ?: run { last = null } }
    }

    /** Runs [act] so [undo] with the returned token takes back its log writes as one, running [inverse] for effects elsewhere; only the last act, while nothing was written after it. */
    fun undoable(inverse: () -> Unit = {}, act: () -> Unit): Long {
        val rewrites = log().rewrites
        val written = ArrayList<Pair<LearnRecord, Boolean>>()
        recording = written
        // an act that throws may have written: the act before is no longer the last
        last = null
        try {
            act()
        } finally {
            recording = null
        }
        return (++tokens).also { last = Act(it, written, rewrites, inverse) }
    }

    /**
     * Takes back the act [token] names, if still the last: by writing opposites where they exist (pin,
     * prediction off), else by rewriting the log without it, or, if the file took none of its records, by
     * resetting the state to the file. Not after a rewrite (restore, clear): its records may be gone.
     */
    fun undo(token: Long): Boolean = takeBack(token) == TakenBack.DONE

    enum class TakenBack {
        DONE,

        /** Not the last act, the log rewritten since, or not in a file this run; or the file refused others' records too, which taking it back would lose. Nothing changed. */
        GONE,

        /** The rewrite without the act threw (disk failed or full, or writes stopped), or the file did not read: nothing taken back, [onError] told. */
        FAILED,
    }

    /**
     * [undo], telling an act no longer undoable apart from one whose rewrite failed, so the user hears the
     * right message. An act the file took no record of lives in the state alone and is dropped there
     * ([LearnLog.dropUnwritten]), but only while its records are all that is unwritten
     * ([LearnLog.unwritten]); otherwise it is [TakenBack.GONE] and nothing is dropped.
     */
    fun takeBack(token: Long): TakenBack {
        val act = last?.takeIf { it.token == token } ?: return TakenBack.GONE
        last = null
        val log = log()
        if (log.rewrites != act.rewrites) return TakenBack.GONE
        if (act.records.isNotEmpty()) {
            val opposites = act.records.map { opposite(it.first) }
            if (opposites.all { it != null }) {
                opposites.asReversed().forEach { it?.invoke(log) }
            } else {
                // what the file holds of the act; with no file open, all of it
                val written = act.records.filter { it.second || !log.onDisk }.map { it.first }
                // resetting the state to the file must not drop more than the act's own records
                if (written.size < act.records.size && log.unwritten != act.records.size - written.size) return TakenBack.GONE
                val rewritten = try {
                    if (written.isEmpty()) log.dropUnwritten() else log.rewrite { bytes, out -> LogRewrites.dropLast(bytes, written, out) }
                } catch (e: IOException) {
                    onError(e)
                    return TakenBack.FAILED
                }
                if (!rewritten) return TakenBack.GONE
            }
        }
        act.inverse()
        return TakenBack.DONE
    }

    // what writes [r]'s opposite; null if none
    private fun opposite(r: LearnRecord): ((LearnLog) -> Unit)? = when (r) {
        is LearnRecord.Pin -> { log -> log.pin(r.code, r.text, r.reading, !r.on) }
        is LearnRecord.NoPredict -> { log -> log.noPredict(r.prev, r.text, !r.on) }
        is LearnRecord.TableBlock -> { log -> log.event { minute, _ -> r.copy(minute = minute, on = !r.on) } }
        else -> null
    }

    /**
     * 清掉最近: forgets what was typed in [span], never before the last restore nor inside the snapshot, and
     * deletes the kept-aside copies; today's, the older builds' files too. [invalidate] runs first so backups since go before the rewrite (a crash
     * between must not let them bring it back). Returns the epoch minute cleared from, or null (no log on disk).
     *
     * @throws IOException if the disk failed or the record stopped taking writes: nothing cleared
     */
    @Throws(IOException::class)
    fun clearRecent(span: LearningOp.Span, invalidate: (Int) -> Unit = {}): Int? {
        val log = log().takeIf { it.onDisk } ?: return null
        val now = clock.now()
        val minute = LearnClock.floorDiv(now, LearnClock.MINUTE).toInt()
        val from = if (span == LearningOp.Span.TODAY) LearnClock.midnight(clock, LearnClock.localDay(clock, minute), now) else minute - span.minutes
        val cut = maxOf(from, log.state.cut, log.state.restoredMinute)
        // a rewrite sure to fail must not delete backups first
        log.checkRewrites()
        last = null
        invalidate(cut)
        // nothing to take out still clears a session's kept span and what the disk refused
        if (!log.rewrite { bytes, out -> LogRewrites.clearSince(bytes, cut, out) } && !log.dropUnwritten()) log.rewritten()
        if (span == LearningOp.Span.TODAY) dropLegacy(all = false) else drop(copies())
        return cut
    }

    /**
     * 清空学到的: forgets what was learned, keeping what the user did on purpose ([LogRewrites.cleared]); [all]
     * clears everything. [invalidate] first, except for a log not on disk. Whether done.
     *
     * @throws IOException if the disk failed, or the record stopped taking writes: nothing cleared
     */
    @Throws(IOException::class)
    fun clearLearned(all: Boolean, invalidate: (Int) -> Unit = {}): Boolean {
        val log = log()
        // as [clearRecent]: nothing deleted for a rewrite sure to fail
        log.checkRewrites()
        last = null
        // in memory there are no backups either
        if (log.onDisk) invalidate(LearningResult.ALL)
        val rows = LogRewrites.cleared(log.state, LearnClock.cut(clock), all)
        val written = log.rewrite { _, out ->
            out.write(LearnRecords.FORMAT.header())
            val writer = LearnRecords.SnapshotWriter(out::write)
            rows.forEach(writer::write)
            true
        }
        if (written) dropLegacy(all)
        return written
    }

    // a whole clear sets aside first what only it takes, so [resumeDrop] tells it from a partial one; the copies
    // last, so a kill that left them alone aside is finished as a shorter clear, which takes no older file
    private fun dropLegacy(all: Boolean) {
        val partialNames = if (all) legacy(false).mapTo(HashSet()) { it.name } else emptySet()
        drop(legacy(all).sortedBy { it.name in partialNames } + copies())
    }

    private fun drop(files: List<LogFile>) {
        val aside = files.filter { it.taken() }.mapNotNull { f ->
            aside(f).takeIf { f.renameTo(it) }.also { if (it == null) onError(IOException("cannot set $f aside")) }
        }
        deleteAside(aside)
    }

    /** A clear killed between setting files aside and deleting them: sets the rest of that clear's aside for [finishDrop] at open. */
    fun resumeDrop(): Boolean {
        val whole = legacy(true)
        val kept = copies()
        if (whole.none { aside(it).taken() }) return kept.any { aside(it).taken() }.also { if (it) setAside(kept) }
        // a partial clear must not take a table log not yet read into the record
        val partial = legacy(false)
        val partialNames = partial.mapTo(HashSet()) { it.name }
        val files = if (whole.any { it.name !in partialNames && aside(it).taken() }) whole else partial
        setAside(files + kept)
        return true
    }

    private fun setAside(files: List<LogFile>) {
        for (f in files) if (f.taken() && !f.renameTo(aside(f))) onError(IOException("cannot set $f aside"))
    }

    fun finishDrop() = deleteAside((legacy(true) + copies()).map(::aside).filter { it.taken() })

    private fun deleteAside(files: List<LogFile>) {
        for (f in files) if (!f.delete()) onError(IOException("cannot delete $f"))
    }

    private fun aside(f: LogFile) = f.beside(CLEARED)

    companion object {
        // a file a clear deletes is renamed to this first
        internal const val CLEARED = ".cleared"

        /** The older builds' files in [dir], by name so one set aside is found again: pinyin's, English's, [tableLogs] and 0.4's mark beside each. */
        fun retired(dir: UserFiles, tableLogs: List<LogFile> = emptyList()): List<LogFile> =
            listOf("pinyin.user", "english.user").map { dir.file(it) } + tableLogs.flatMap { listOf(it, it.beside(".told")) }
    }
}
