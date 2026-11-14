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
 * The user's controls over the learning record (PLAN §2.8, impl/streams/E-design.md): an act of a
 * long press made undoable, U-88 清掉最近 and U-86 清空学到的, each a rewrite of the log under its
 * compaction lock ([LearnLog.rewrite]). Holds no state of what was learned: only the last act,
 * for the chip to take back while nothing else was written after it.
 *
 * [log] is the engine's, opened again where it was closed (review-E-engine S6). One not on disk
 * (direct boot) has what was typed in its state alone, no time on it: [clearRecent] does nothing
 * there. [legacy] are the older builds' learning files no build writes any more (for a clear of
 * everything, or not), deleted with what was cleared (PLAN §2.8: 「装回旧版也看不到了」). [copies] are
 * the record's copies kept aside whole (RecordStore.KeptAside), by the names they were kept under:
 * deleted with every clear, of whatever time (user's decision decode-3).
 */
class LearningControl(
    private val log: () -> LearnLog,
    private val clock: LearnClock = JvmClock,
    private val legacy: (all: Boolean) -> List<LogFile> = { emptyList() },
    private val copies: () -> List<LogFile> = { emptyList() },
    internal val onError: (IOException) -> Unit = {},
) {
    /**
     * An act that may still be taken back: the records it wrote, each with whether the file took it
     * ([LearnLog.appended]), the log's [LearnLog.rewrites] then, and how to undo what it did outside the log.
     */
    private class Act(val token: Long, val records: List<Pair<LearnRecord, Boolean>>, val rewrites: Long, val inverse: () -> Unit)

    private var last: Act? = null
    private var tokens = 0L

    // what the act running wrote; null: none runs, and a record written takes the last act's undo away
    private var recording: MutableList<Pair<LearnRecord, Boolean>>? = null

    /** Hears what [log] writes from now on: the log the engine opened, again after it was closed. */
    fun attach(log: LearnLog) {
        last = null
        log.appended = { r, kept -> recording?.add(r to kept) ?: run { last = null } }
    }

    /**
     * Runs [act], what it writes to the log taken back as one by [undo] with the token returned,
     * and [inverse] run then to undo what it did elsewhere (a word blocked in the lists, say).
     * Only one act may be taken back: the last, while nothing was written after it.
     */
    fun undoable(inverse: () -> Unit = {}, act: () -> Unit): Long {
        val rewrites = log().rewrites
        val written = ArrayList<Pair<LearnRecord, Boolean>>()
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
     * a forget, by the log rewritten without it, or, the file having taken none of its records (storage
     * full, a record not saving) and nothing else being unwritten, by the state made what the file says, with
     * no rewrite ([takeBack]).
     * Not once the log was rewritten since (a restore,
     * a clear): its records may be gone, or others in their place, and what it did elsewhere
     * (a word blocked) may be what the restore brought (review-E-engine N6).
     */
    fun undo(token: Long): Boolean = takeBack(token) == TakenBack.DONE

    /** What [takeBack] did. */
    enum class TakenBack {
        /** Taken back. */
        DONE,

        /**
         * No longer to be taken back: not the last act, the log rewritten since, its records not the log's last,
         * or not in a file this run (a record that did not open learns in memory, where no rewrite lasts:
         * [LearnLog.rewrite] says false); or the file refused records of it and others' too, which taking it
         * back would lose ([takeBack]). Nothing changed.
         */
        GONE,

        /**
         * The file holds the act and the rewrite without it threw (the disk failed or is full, or the record
         * stopped taking writes), or the file did not read: nothing taken back, [onError] told.
         */
        FAILED,
    }

    /**
     * [undo], telling an act that can no longer be taken back from one whose rewrite the disk failed: the
     * user is told 「已经撤销不了了」 of the first, and that it was not done of the second, which was said
     * as the first (R25 F8038). Either way the act is no longer the last.
     *
     * An act the file took no record of (each write refused: storage full, or the record not saving) is in the
     * state alone, and so is taken back there, as done as any ([LearnLog.dropUnwritten]). It was looked for at
     * the file's end, not found, and said no longer to be taken back, the word still deleted (R25 F8050). Of one
     * the file took in part, the part it took is what the rewrite drops.
     *
     * Both make the state what the file says, exact only while the act's own records are all the file refused
     * ([LearnLog.unwritten]). With anything else in the state alone the act is [TakenBack.GONE] and nothing is
     * dropped: a word learned after the disk filled and then deleted is not in the file to come back, and it
     * was said taken back, the rest learned since lost with it (R25 F8066).
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
                // what the file holds of the act; with no file open, all of it: that rewrite says what becomes of it
                val written = act.records.filter { it.second || !log.onDisk }.map { it.first }
                // the state would be made the file's: not with more unwritten than the act's own (R25 F8066)
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
     * may have it later than the span's start). Today's also takes the older builds' files; any
     * span's, the copies of the record kept aside: a copy cannot lose a time alone, and the user was
     * told it deletes them (decode-3; R25 F7925: the shorter spans left them).
     * [invalidate] is told the cut first, for the backups saved since to go before the rewrite
     * (C25: a crash between must not leave them to bring it back). The epoch minute cleared from,
     * or null if nothing could be (no log on disk).
     *
     * @throws IOException if the disk failed, or the record stopped taking writes after one that failed (its
     * file still holds what was typed in the span: R25 F8018): nothing cleared. Of a record that stopped, the
     * backups and the last act's undo are left too ([invalidate] not called: R25 F8021); a disk that fails
     * in the rewrite has them gone already (C25's order)
     */
    @Throws(IOException::class)
    fun clearRecent(span: LearningOp.Span, invalidate: (Int) -> Unit = {}): Int? {
        val log = log().takeIf { it.onDisk } ?: return null
        val now = clock.now()
        val minute = LearnClock.floorDiv(now, LearnClock.MINUTE).toInt()
        val from = if (span == LearningOp.Span.TODAY) LearnClock.midnight(clock, LearnClock.localDay(clock, minute), now) else minute - span.minutes
        val cut = maxOf(from, log.state.cut, log.state.restoredMinute)
        // a rewrite sure to fail deletes nothing first: the backups and the copy before a restore were gone
        // with nothing cleared, at each try (R25 F8021)
        log.checkRewrites()
        last = null
        invalidate(cut)
        // nothing to take out (false) is cleared all the same: what a session keeps of the span too (review-1c S4),
        // and what the disk refused of it, in the state alone (R25 F7999)
        if (!log.rewrite { bytes, out -> LogRewrites.clearSince(bytes, cut, out) } && !log.dropUnwritten()) log.rewritten()
        if (span == LearningOp.Span.TODAY) dropLegacy(all = false) else drop(copies())
        return cut
    }

    /**
     * U-86: what was learned forgotten, what the user did on purpose kept ([LogRewrites.cleared]);
     * [all], everything. [invalidate] first, as for [clearRecent], with [LearningResult.ALL]; not
     * for a log not on disk, whose rewrite would not land: the backups are what a record that did
     * not open has left. Whether it was: not for a log that did not open.
     *
     * @throws IOException if the disk failed, or the record stopped taking writes after one that failed: nothing cleared
     */
    @Throws(IOException::class)
    fun clearLearned(all: Boolean, invalidate: (Int) -> Unit = {}): Boolean {
        val log = log()
        // as [clearRecent]: nothing deleted for a rewrite sure to fail (R25 F8021)
        log.checkRewrites()
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
        if (written) dropLegacy(all)
        return written
    }

    /**
     * The older builds' files gone, so a build before 0.4 put back shows none of what was cleared:
     * each renamed aside first, then deleted; a kill between leaves the renamed ones for [resumeDrop].
     * A whole clear sets aside first what only it takes: killed after pinyin.user alone, [resumeDrop]
     * would finish it as a partial clear and the table logs left would come back into the record.
     * The copies kept aside last: a kill that left them alone set aside is finished as a clear of a
     * shorter time, which takes nothing else ([resumeDrop]).
     */
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

    /**
     * Whether a clear was killed between setting the older builds' files aside and deleting them:
     * the rest of them set aside too, for [finishDrop] to delete as the log opens.
     */
    fun resumeDrop(): Boolean {
        // which clear it was: a whole one where a file only a whole clear takes was set aside; else the
        // partial one's alone, or a table log not yet read into the record would go with it
        val whole = legacy(true)
        val kept = copies()
        // the copies alone set aside: a clear of a shorter time, or one whose other files were all done
        // before them ([dropLegacy]); the older builds' files are not that clear's to take
        if (whole.none { aside(it).taken() }) return kept.any { aside(it).taken() }.also { if (it) setAside(kept) }
        val partial = legacy(false)
        val partialNames = partial.mapTo(HashSet()) { it.name }
        val files = if (whole.any { it.name !in partialNames && aside(it).taken() }) whole else partial
        setAside(files + kept)
        return true
    }

    private fun setAside(files: List<LogFile>) {
        for (f in files) if (f.taken() && !f.renameTo(aside(f))) onError(IOException("cannot set $f aside"))
    }

    /** What [resumeDrop] left, deleted. */
    fun finishDrop() = deleteAside((legacy(true) + copies()).map(::aside).filter { it.taken() })

    private fun deleteAside(files: List<LogFile>) {
        for (f in files) if (!f.delete()) onError(IOException("cannot delete $f"))
    }

    private fun aside(f: LogFile) = f.beside(CLEARED)

    companion object {
        // what a file a clear deletes is renamed to first
        internal const val CLEARED = ".cleared"

        /**
         * The older builds' learning files in [dir] no build writes any more (pinyin's, English's, the
         * tables' [tableLogs] and 0.4's mark beside each): a clear deletes them with what it cleared
         * (U-86, U-88 today; review E-S4), or a 0.4 put back would offer a phrase cleared. By name,
         * not by what [dir] lists: one set aside is found again by its name ([resumeDrop]).
         */
        fun retired(dir: UserFiles, tableLogs: List<LogFile> = emptyList()): List<LogFile> =
            listOf("pinyin.user", "english.user").map { dir.file(it) } + tableLogs.flatMap { listOf(it, it.beside(".told")) }
    }
}
