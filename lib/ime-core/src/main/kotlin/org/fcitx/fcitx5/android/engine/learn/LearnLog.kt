/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Absorbed
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Cut
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnAdd
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnCorrect
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnCommit
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnForget
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Forget
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Head
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.NoPredict
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Pin
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Undo
import org.fcitx.fcitx5.android.engine.learn.backup.BackupMerge
import org.fcitx.fcitx5.android.engine.store.RecordFormat
import org.fcitx.fcitx5.android.engine.store.RecordStore
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/**
 * The learning record (PLAN §2.2, impl/design-1a.md): [file], `learn.log`, of [LearnRecords],
 * replayed into [state] at [open], each commit and act of the user appended as one write and
 * applied as written. Folded in the [background] from the file's own bytes: what is older than
 * the cut ([LearnClock.cut]) becomes a snapshot, what is newer stays as it was written, for U-88
 * to take back. Without a [file] (no user directory, a test) it learns in memory alone.
 *
 * What the user learned in older builds is converted once, when the log is first opened and
 * empty, unless the [MARKER] beside it says that was done: a log moved aside unreadable, or
 * cleared, does not bring the old learning back. What an older build did in its files since
 * (run again after a downgrade, or its backup restored) is taken in at the next open
 * ([ConvertedMarker], [Legacy.since]): its new words and the forgets its logs record, not its counts.
 */
class LearnLog(
    private val file: File?,
    private val clock: LearnClock = LearnClock.System,
    private val onError: (IOException) -> Unit = {},
    private val background: Executor? = null,
    private val compactAt: Long = RecordStore.DEFAULT_COMPACT_AT,
    // a seam for tests: folded only as it grows, not on each calendar day and start
    private val foldDaily: Boolean = true,
) : Closeable {

    var state = LearnState()
        private set

    /**
     * Hears what each record applied from now on changed that the user model keeps apart
     * ([LearnState.Changes]: words formed and gone, the counts): one function for every record, a
     * commit, an undo, a forget, what an older build changed (impl/design-1b.md §3, NIT 1). What
     * [open] read is not told: the model is made from the state then.
     */
    var listener: ((LearnState.Changes) -> Unit)? = null

    /** Hears each record appended from now on, as written: what an act wrote, for it to be taken back (LearningControl). */
    var appended: ((LearnRecord) -> Unit)? = null

    internal var store: RecordStore? = null
        private set

    // the older builds' files, for a clear to say them as it leaves them: see remarkLegacy
    private var legacy: Legacy? = null

    /** Whether the last [open] wrote the marker: a clear's files set aside are deleted only then (verify-E F1). */
    var markedAtOpen = false
        private set

    /** A commit the user may still take back (U-03, written from step 1c): it and what it changed, as it was. */
    private class Undoable(val commit: LearnRecord, val befores: List<Before>)

    private val undoable = LinkedHashMap<Long, Undoable>()

    /**
     * Which writing of the record this is: another once it was rewritten (a stretch of it forgotten,
     * a backup restored: [rewritten]), and no other log's, so that what was read of it before may no
     * longer be there: an act to take back (LearningControl) and what a session keeps of commits
     * before (CommitJournal) are dropped, with a log replaced as much as with one rewritten (review-1c S4).
     */
    @Volatile
    var rewrites = GENERATIONS.incrementAndGet()
        internal set

    /** The record was rewritten: no commit before is taken back any more, nor is a session's kept (U-86, U-88). */
    fun rewritten() {
        undoable.clear()
        rewrites = GENERATIONS.incrementAndGet()
    }

    /** The older builds' files, as [open] converts them. */
    interface Legacy {
        /** Fills [state], empty, from the files, as of now by [clock]. */
        @Throws(IOException::class)
        fun into(state: LearnState, clock: LearnClock)

        /** The marker of the files as they are, [state] just filled from them. */
        @Throws(IOException::class)
        fun marker(state: LearnState): ConvertedMarker

        /**
         * What an older build did in the files since [was] (null: no marker that reads), as
         * records for [state] stamped [stamp], and the marker after them; null if nothing changed.
         */
        @Throws(IOException::class)
        fun since(was: ConvertedMarker?, state: LearnState, stamp: LearnClock.Stamp): Pair<List<LearnRecord>, ConvertedMarker>?

        /** Whether there are logs to convert, worth [open]'s deferring: with none, converting is quick. */
        val heavy: Boolean get() = false
    }

    /**
     * Replays the log, and appends from now on; once only. [legacy] fills an empty log from an
     * older build's files, where that was never done: one that fails (an IOException or any
     * RuntimeException, told) leaves the log empty and unmarked, learning on disk at once, the
     * conversion owed to the next open ([CONVERTING] beside it where [deferred]; not deferred, as
     * LearnLogBench opens it, the next open finds the log written and does not seed it). Where it was
     * done and the files changed since, what changed is taken in; with [remark], not: a clear
     * deleted them, killed before it said so in the marker, and the marker now says them as they are.
     *
     * [deferred]: a conversion is not done here but left [pending], the log opened as it is (fresh
     * for a first one) and learning at once, for [takeConversion] to merge in once made off this
     * thread: a heavy user's first open after the upgrade waited on it (uat-0.3.1: 19.5 s). Till it
     * is merged and the marker written, [CONVERTING] beside the log says it is owed, so a kill
     * before leaves it owed to the next open, not marked as done with nothing taken in.
     */
    fun open(legacy: Legacy? = null, remark: Boolean = false, deferred: Boolean = false) {
        markedAtOpen = false
        val file = file ?: return
        val marker = File(file.parentFile, MARKER)
        // a clear's remark converts nothing: not deferred
        val deferring = deferred && !remark
        if (deferring && legacy != null && defers(file, marker, legacy)) {
            val oldestTail = openStore(file, null) ?: return
            this.legacy = legacy
            pending = legacy
            // an old tail folded as below: owed across starts, the conversion may wait days (a fold makes it STALE, merged again)
            if (foldDaily && oldestTail < LearnClock.cut(clock)) store?.compactSoon()
            state.takeChanges()
            return
        }
        val seed = legacy?.takeIf { !marker.exists() }
        // null: no seed asked for; false: one that failed, owed to the next start
        var seeded: Boolean? = null
        val opened = openStore(file, seed?.let { c -> { seedFrom(c, file, deferred).also { seeded = it } } }) ?: return
        val oldestTail = opened
        this.legacy = legacy
        if (seeded != false) try {
            // a clear killed before its marker was written (LearningControl): the files said as they are, nothing taken in
            if (remark && legacy != null && seeded != true) remark(legacy, marker) else mark(marker, legacy, seeded == true)
            markedAtOpen = true
        } catch (e: IOException) {
            onError(e)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // old files a catch-up trips on: the log is fine, and learns to disk (review-1a-r2 S2)
            onError(IOException("the older builds' files not taken in", e))
        }
        if (foldDaily && oldestTail < LearnClock.cut(clock)) store?.compactSoon()
        state.takeChanges()
    }

    // [legacy] converted into the empty state, whether it was: an older build's files that do not
    // convert leave the store opening unseeded and with no marker, what was half done dropped, and
    // the conversion owed (the mark [defers] reads), so the next start tries it again though this
    // one learns to disk (review R11 F2725)
    private fun seedFrom(legacy: Legacy, file: File, deferred: Boolean): Boolean {
        val failure = try {
            legacy.into(state, clock)
            return true
        } catch (e: IOException) {
            e
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            IOException("the older builds' files not taken in", e)
        }
        onError(failure)
        state = LearnState()
        if (deferred) runCatching { File(file.parentFile, CONVERTING).createNewFile() }
        return false
    }

    // whether [open] leaves [legacy]'s conversion owed, the mark beside [file] saying so: logs to
    // convert, none converted yet into an empty log, or one begun and not finished (learned since)
    private fun defers(file: File, marker: File, legacy: Legacy): Boolean {
        val converting = File(file.parentFile, CONVERTING)
        if (marker.exists()) {
            converting.delete()
            return false
        }
        // a seed that failed (see [open]) owes it, logs or none
        if (!legacy.heavy && !converting.exists()) return false
        val owed = converting.exists() || !file.exists() || file.length() <= RecordFormat.HEADER_SIZE
        return owed && try {
            converting.exists() || converting.createNewFile()
        } catch (e: IOException) {
            // no mark to say it is owed: converted here, as before (review-uat-fixes S4)
            onError(e)
            false
        }
    }

    // the store opened on [file], [seed] filling it if empty: the oldest minute of its tail, or null
    // if it did not open (learning in memory this time)
    private fun openStore(file: File, seed: (() -> Boolean)?): Int? {
        val reader = LearnRecords.Reader()
        var inTail = true
        var oldestTail = Int.MAX_VALUE
        val store = RecordStore(file, LearnRecords.FORMAT, compactAt, onError, background, fold = { log, to -> fold(log, LearnClock.cut(clock), to) })
        try {
            store.open(
                replay = { type, input ->
                    val record = reader.decode(type, input)
                    if (record != null) {
                        applyOrKeep(state, record) { onError(IOException("a record not applied", it)) }
                        if (record is Head) inTail = false
                        if (record === Cut) inTail = true
                        record.minute?.let { if (inTail && it < oldestTail) oldestTail = it }
                    }
                },
                counts = { write -> writeSnapshot(state, LearnClock.cut(clock), write, reader.kinds) },
                seed = seed,
            )
        } catch (e: IOException) {
            onError(e)
            // learned in memory this time; what a conversion half did is not kept
            state = LearnState()
            return null
        }
        this.store = store
        return oldestTail
    }

    // the older builds' files whose conversion [open] left owed: see [conversion]
    private var pending: Legacy? = null

    /** Whether a conversion [open] deferred is still owed: [conversion] makes it, [takeConversion] merges it in. */
    val conversionPending: Boolean get() = pending != null

    /**
     * The conversion [open] deferred, as work for another thread: it reads the older builds' files
     * (by [using], the same files read with nothing of this thread's) and nothing of this log, and
     * gives the state they make. Null if none is owed.
     */
    fun conversion(using: Legacy? = pending): (() -> LearnState)? {
        if (pending == null || using == null) return null
        return { LearnState().also { using.into(it, clock) } }
    }

    /**
     * The log as it is now with [converted], what [conversion] made, merged into its snapshot and
     * its tail kept as written (review-uat-fixes S1): each count of the snapshot the larger, day by
     * day, as a backup restored, so merging it twice changes nothing and a kill between the merge and
     * the marker costs nothing at the next open; what was typed since the open, in the tail, counts
     * on top of the older build's and stays 清掉最近's to take out. [legacy]'s marker made here too,
     * as it reads every older log. Run off this thread, as [conversion] is: the log read as it is on
     * disk, for [takeConversion] to put in place if it is still that, records appended or not. Null
     * without a log on disk.
     */
    fun mergeConversion(converted: LearnState, legacy: Legacy? = pending): Merged? {
        val file = file ?: return null
        val read = try {
            file.readBytes()
        } catch (e: IOException) {
            onError(e)
            return null
        }
        if (!LearnRecords.FORMAT.hasHeader(read)) return null
        // the whole records alone: one read half written (appended meanwhile) is the tail's, after
        // the merge, not cut in two (verify-uat-fixes V1)
        val base = read.copyOf(LearnRecords.FORMAT.spans(read) { _, _ -> })
        return Merged(base, merged(base, converted), converted, legacy?.marker(converted))
    }

    /** The log [base] was, and [bytes] it is with [converted] merged in, the older logs then as [marker] says: see [mergeConversion]. */
    class Merged(val base: ByteArray, val bytes: ByteArray, val converted: LearnState, val marker: ConvertedMarker?)

    private fun merged(log: ByteArray, converted: LearnState): ByteArray {
        val walk = Walk(log)
        val snapshot = walk.state(walk.tail)
        val merged = BackupMerge.merge(snapshot, converted, 0L, sameInstall = true, minute = snapshot.cut, lastBatch = snapshot.imports.lastId)
        // the log's own head but for what the conversion says: its cut (U-88 reaches the tail), no restore
        merged.cut = snapshot.cut
        merged.restoredMinute = snapshot.restoredMinute
        merged.lastMinute = maxOf(snapshot.lastMinute, converted.lastMinute)
        merged.localDay = maxOf(snapshot.localDay, converted.localDay)
        merged.convertedDay = converted.convertedDay
        merged.convertedTables = converted.convertedTables
        val out = ByteArrayOutputStream()
        out.write(LearnRecords.FORMAT.header())
        writeSnapshot(merged, merged.cut, out::write, walk.reader.kinds)
        walk.writeFrom(walk.tail, out)
        return out.toByteArray()
    }

    /** What [takeConversion] did. */
    enum class Taken {
        /** Merged in and marked. */
        DONE,

        /** Not: the log was rewritten since the merge was made (a fold), to be made again on it. */
        STALE,

        /** Not: the log could not be rewritten, the conversion owed to the next open. */
        FAILED,

        /** None owed (a clear came first). */
        NONE,
    }

    /**
     * [merged] put in place of the log, if the log is still what it was made from, records
     * appended to it or not (typing went on: those kept after it); then the marker written and
     * nothing owed. See [Taken].
     */
    fun takeConversion(merged: Merged): Taken {
        val legacy = pending ?: return Taken.NONE
        val file = file ?: return Taken.FAILED
        var stale = false
        val done = try {
            rewrite(endUndo = false) { log, out ->
                val appended = log.size >= merged.base.size && log.copyOf(merged.base.size).contentEquals(merged.base)
                stale = !appended
                if (appended) {
                    out.write(merged.bytes)
                    out.write(log, merged.base.size, log.size - merged.base.size)
                }
                appended
            }
        } catch (e: IOException) {
            onError(e)
            false
        }
        if (stale) return Taken.STALE
        if (!done) return Taken.FAILED
        pending = null
        try {
            ConvertedMarker.write(File(file.parentFile, MARKER), merged.marker ?: legacy.marker(merged.converted))
            File(file.parentFile, CONVERTING).delete()
        } catch (e: IOException) {
            // owed still: the next open merges it again, which changes nothing
            onError(e)
        }
        return Taken.DONE
    }

    /**
     * The conversion owed merged in now, on this thread (review-uat-fixes S3: before what reads or
     * rewrites the whole record): [made] if it was made already and is not stale, else converted
     * (or [made]'s conversion merged again) here.
     */
    fun finishConversion(made: Merged? = null): Taken {
        if (pending == null) return Taken.NONE
        made?.let { m -> takeConversion(m).let { if (it != Taken.STALE) return it } }
        val merged = try {
            mergeConversion(made?.converted ?: conversion()?.invoke() ?: return Taken.NONE)
        } catch (e: IOException) {
            onError(e)
            null
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            onError(IOException("the older builds' files not taken in", e))
            null
        }
        return merged?.let(::takeConversion) ?: Taken.FAILED
    }

    /**
     * Appends and applies a commit of [rows] (and the nine keys' [takes]), stamped now: as written,
     * a commit too long for a record losing its level-3 rows first, then itself. Null if nothing
     * was kept.
     */
    fun record(rows: List<Row>, takes: List<Take> = emptyList(), flags: Int = 0): Commit? {
        if (rows.isEmpty()) return null
        val stamp = LearnClock.stamp(clock, state, commit = true)
        // a count of takes is a byte: more in one commit than anyone takes, the first so many
        var commit = Commit(state.nextSeq, stamp.minute, stamp.day, stamp.localDay, flags, rows, takes.take(MAX_TAKES))
        var bytes = encoded(commit)
        if (bytes == null) {
            commit = commit.copy(rows = rows.filter { it.flags and (Row.PICK or Row.WHOLE) != 0 })
            bytes = encoded(commit)
        }
        if (bytes == null) {
            onError(IOException("a commit of ${rows.size} rows is too long to keep"))
            return null
        }
        val newDay = newDay(commit.localDay)
        store?.append(bytes)
        val befores = state.apply(commit)
        appended?.invoke(commit)
        changed()
        remember(commit.seq, commit, befores, newDay)
        return commit
    }

    private fun newDay(localDay: Int) = state.localDay != Int.MIN_VALUE && localDay > state.localDay

    // a commit written: one of the last few to take back, and the first of a calendar day lets yesterday's records be folded
    private fun remember(seq: Long, commit: LearnRecord, befores: List<Before>, newDay: Boolean) {
        undoable[seq] = Undoable(commit, befores)
        while (undoable.size > UNDOABLE) undoable.remove(undoable.keys.first())
        if (newDay && foldDaily) store?.compactSoon()
    }

    /** Takes the commit [seq] back (U-03), if it is one of the last few: whether it was. */
    fun undo(seq: Long): Boolean {
        val u = undoable[seq] ?: return false
        val stamp = LearnClock.stamp(clock, state, commit = false)
        // taken off the list once written: an Undo too long for a record (it holds the commit and its befores) says so, not silently nothing
        if (!write(Undo(seq, stamp.minute, stamp.day, state.localDay, u.commit, u.befores))) return false
        undoable.remove(seq)
        return true
    }

    /**
     * [undo], for a commit deleted as soon as written (U-03): only one in the tail (PLAN §2.5,
     * written in the last hour or the same calendar day). One older is learned as it was.
     */
    fun undoRecent(seq: Long, commit: LearnRecord? = null): Boolean {
        val c = undoable[seq]?.commit
        // the commit kept, not another of its seq: a record rolled back gives its seqs again (review-1c S4)
        if (commit != null && c != commit) return false
        val (minute, localDay) = when (c) {
            is Commit -> c.minute to c.localDay
            is LearnRecord.EnCommit -> c.minute to c.localDay
            else -> return false
        }
        val now = LearnClock.stamp(clock, state, commit = false).minute
        if (now - minute > TAIL_MINUTES && localDay != LearnClock.localDay(clock, now)) return false
        return undo(seq)
    }

    /** Pins [text] first under [code] (U-83), or unpins it. */
    fun pin(code: Code, text: String, reading: String, on: Boolean) {
        val stamp = LearnClock.stamp(clock, state, commit = false)
        // another pinned there unpinned first, in its own record: an act's undo, the opposites, pins it again (U-82)
        val was = state.pins[code]?.takeIf { on && (it.text != text || it.reading != reading) }
        if (was != null) write(Pin(stamp.minute, stamp.day, code, was.text, was.reading, false))
        write(Pin(stamp.minute, stamp.day, code, text, reading, on))
    }

    /**
     * Takes in [rows] (of [LearnRecord.MineRow], [LearnRecord.WordUseRow], [LearnRecord.TableWordRow], [LearnRecord.EnWordRow]) where the log has none of them,
     * stamped now: an export's words, a table's saved phrases, as [Absorbed] takes an older build's.
     */
    fun absorb(rows: List<LearnRecord>) {
        if (rows.isEmpty()) return
        val stamp = LearnClock.stamp(clock, state, commit = false)
        rows.chunked(LearnRecords.ABSORBED_ROWS).forEach { absorbed(stamp.minute, it) }
    }

    // long rows (an export's long phrases and readings) make so many pass a record's 64 KB: halved
    // till they fit, not dropped whole (R11 F2688)
    private fun absorbed(minute: Int, rows: List<LearnRecord>) {
        val record = Absorbed(minute, rows, emptyList())
        // [encoded] tells nothing, so a row too long for any record is told once, by the write of it alone (F3145)
        if (rows.size == 1 || encoded(record) != null) {
            write(record)
            return
        }
        val half = rows.size / 2
        absorbed(minute, rows.subList(0, half))
        absorbed(minute, rows.subList(half, rows.size))
    }

    /** Turns [text] off as a prediction after [prev] (U-72 不再联想), or on again. */
    fun noPredict(prev: String, text: String, on: Boolean) {
        val stamp = LearnClock.stamp(clock, state, commit = false)
        write(NoPredict(stamp.minute, prev, text, on))
    }

    /** Forgets [text] everywhere (U-84). */
    fun forget(text: String) {
        val stamp = LearnClock.stamp(clock, state, commit = false)
        write(Forget(stamp.minute, stamp.day, text))
    }

    /**
     * Appends and applies an English [word] committed after [prev] ("" for none), [typed] as the
     * keys spelt it, [how] it came ([EnCommit.how]), stamped now: a commit as [record]'s, of a
     * typing day, and one of the last few [undo] takes back. Null if it was too long to keep.
     */
    fun recordEnglish(prev: String, word: String, typed: String, how: Int, flags: Int = 0): EnCommit? {
        val stamp = LearnClock.stamp(clock, state, commit = true)
        val commit = EnCommit(state.nextSeq, stamp.minute, stamp.day, stamp.localDay, flags, prev, word, typed, how)
        val newDay = newDay(commit.localDay)
        if (!write(commit)) return null
        remember(commit.seq, commit, emptyList(), newDay)
        return commit
    }

    /** Adds the English [word] by hand, or takes that back ([EnAdd]). */
    fun addEnglish(word: String, on: Boolean) {
        write(EnAdd(LearnClock.stamp(clock, state, commit = false).minute, word, on))
    }

    /** Has the English [word] corrected again, its uses and pairs kept ([EnCorrect]). */
    fun correctEnglish(word: String) {
        write(EnCorrect(LearnClock.stamp(clock, state, commit = false).minute, word))
    }

    /** Forgets the English [word]: its uses, its pairs, and that it was added or is never corrected ([EnForget]). */
    fun forgetEnglish(word: String) {
        write(EnForget(LearnClock.stamp(clock, state, commit = false).minute, word))
    }

    /**
     * Writes an import's [records] (U-101, U-102: [LearnRecord.ImportBatch] and its words, an
     * [LearnRecord.ImportUndo]), each applied as written, the listener told once at the end: a
     * list of 100 000 words is as many records. One too long to keep is left out, reported.
     */
    fun imports(records: List<LearnRecord>) {
        require(records.all { it is LearnRecord.ImportBatch || it is LearnRecord.Imported || it is LearnRecord.ImportUndo }) { "not an import's" }
        val kept = ArrayList<LearnRecord>(records.size)
        val bytes = ArrayList<ByteArray>(records.size)
        for (record in records) {
            val b = encoded(record)
            if (b == null) {
                onError(IOException("a record too long to keep: $record"))
                continue
            }
            kept += record
            bytes += b
        }
        // one write for them all: a write per word took 100 000 words over a second on the fcitx thread (F3058, F3078)
        store?.appendAll(bytes)
        for (record in kept) {
            // as a replay would: one failing is reported, the rest go on, and the listener is told (review-G NIT 2)
            applyOrKeep(state, record) { onError(IOException("a record not applied", it)) }
            // an act's records as written: the last act before them is no longer one to take back (LearningControl)
            appended?.invoke(record)
        }
        changed()
    }

    /**
     * Appends and applies the event [make] gives for the minute and typing day now: one no method
     * here writes (a table's order reset, its per-code blocks). Whether it was kept.
     */
    fun event(make: (minute: Int, day: Int) -> LearnRecord): Boolean {
        val stamp = LearnClock.stamp(clock, state, commit = false)
        return write(make(stamp.minute, stamp.day))
    }

    private fun write(record: LearnRecord): Boolean {
        val bytes = encoded(record)
        if (bytes == null) {
            onError(IOException("a record too long to keep: $record"))
            return false
        }
        store?.append(bytes)
        state.apply(record)
        appended?.invoke(record)
        changed()
        return true
    }

    /**
     * Rewrites the log whole (U-88, U-86, an act taken back: impl/streams/E-design.md §3), as
     * [RecordStore.rewrite] has [transform] do it; the state is then what the bytes written replay
     * to, the listener told what that changed of the user's words, and no commit before it may be
     * taken back. False if nothing was written: a file that did not open (learned in memory this
     * run), or [transform] said so; with no file at all, the state's own snapshot is rewritten.
     *
     * @throws IOException if the disk failed: the log and the state as they were
     */
    @Throws(IOException::class)
    fun rewrite(transform: (ByteArray, OutputStream) -> Boolean): Boolean = rewrite(endUndo = true, transform)

    // [endUndo] false: a rewrite the user did not make (the conversion landing) leaves an import's 撤销 (F3209)
    private fun rewrite(endUndo: Boolean, transform: (ByteArray, OutputStream) -> Boolean): Boolean {
        val store = store
        // a record that did not open learns in memory, where a rewrite would not last: not done
        if (store == null && file != null) return false
        val bytes = if (store != null) store.rewrite(transform) else inMemory(transform)
        bytes ?: return false
        // written: what was read of it before is no longer there, whatever reading it again throws (review-int-records N5)
        rewritten()
        val was = state
        // what replaying it changed is no change of the user's: [Changes.between] says that (review-int-records S1)
        state = replay(bytes).also { it.takeChanges() }
        // the words imported where they were: a model over them, and a session typing, go on (review-G M1)
        state.keepImportsOf(was, endUndo)
        listener?.invoke(LearnState.Changes.between(was, state))
        return true
    }

    /** Whether what is written lasts: a file, opened. Without, what was typed has no times kept to clear a span of (U-88). */
    val onDisk: Boolean get() = store != null

    // learned in memory (no user directory yet): the log the state would fold to, rewritten as one on disk would be
    private fun inMemory(transform: (ByteArray, OutputStream) -> Boolean): ByteArray? {
        val log = ByteArrayOutputStream()
        log.write(LearnRecords.FORMAT.header())
        writeSnapshot(state, LearnClock.cut(clock), log::write)
        val written = ByteArrayOutputStream()
        return if (transform(log.toByteArray(), written)) written.toByteArray() else null
    }

    /**
     * The older builds' files changed under the marker (the user cleared what was learned, and
     * they went with it): the marker made to say them as they are now, nothing taken in, so the
     * next open takes in nothing the clear took away (a table's saved words read again). Whether the files may go: not when
     * the marker could not be written, so the next open remarks them (verify-E F1); with no marker
     * to keep (none read, or a log in memory), they may.
     */
    fun remarkLegacy(): Boolean {
        val legacy = legacy ?: return true
        val file = file ?: return true
        return try {
            remark(legacy, File(file.parentFile, MARKER))
            // a conversion owed is owed no more: what it would bring back was cleared
            pending = null
            File(file.parentFile, CONVERTING).delete()
            true
        } catch (e: IOException) {
            onError(e)
            false
        }
    }

    private fun remark(legacy: Legacy, marker: File) {
        val now = LearnState().also { legacy.into(it, clock) }
        ConvertedMarker.write(marker, legacy.marker(now))
    }

    private fun changed() {
        val changes = state.takeChanges()
        if (!changes.isEmpty()) listener?.invoke(changes)
    }

    // null for a record too long to keep, or one that cannot be written at all (a field past 64 KB
    // as UTF-8): never the key's failure, and told by its caller, once (F3145)
    private fun encoded(record: LearnRecord): ByteArray? = try {
        LearnRecords.encode(record).takeIf(::fits)
    } catch (_: IOException) {
        null
    }

    // the marker written, or what an older build did since it was, taken in
    private fun mark(marker: File, legacy: Legacy?, seeded: Boolean) {
        when {
            // what was converted is the state; with nothing to convert (a test, the eval), nothing
            seeded -> ConvertedMarker.write(marker, checkNotNull(legacy).marker(state))
            legacy == null -> if (!marker.exists()) ConvertedMarker.write(marker, ConvertedMarker.NONE)
            // the log not empty but no marker (restored without one), or one that does not read:
            // what the files have is taken in where the log lacks it, nothing forgotten by it
            else -> {
                val was = if (marker.exists()) ConvertedMarker.read(marker) else null
                val (records, now) = legacy.since(was, state, LearnClock.stamp(clock, state, commit = false)) ?: return
                records.forEach(::write)
                ConvertedMarker.write(marker, now)
            }
        }
    }

    /** Folds the log now, as the background would: for tests. */
    internal fun fold() = store?.compact()

    /**
     * Closes the log. The marker is not written: a process killed is not closed, and the next open
     * must read the old files the same either way (review-1a-r2 M1).
     */
    override fun close() {
        store?.close()
        store = null
    }

    companion object {
        /** The log, in the engine's user directory. */
        const val FILE = "learn.log"

        /** Beside the log: the older builds' learning was converted (impl/design-1a.md §5). */
        const val MARKER = "learn.converted"

        /** Beside the log: a conversion begun ([open]'s deferred) and not yet merged and marked. */
        const val CONVERTING = "learn.converting"

        private const val MAX_TAKES = 255

        /** How many of the last commits may be taken back: PLAN §2.5's 16. */
        const val UNDOABLE = 16

        /** How long a commit may be taken back, or else the same calendar day: the tail (PLAN §2.5). */
        const val TAIL_MINUTES = 60

        // what [rewrites] is taken from: one for every log and rewrite of this process
        private val GENERATIONS = AtomicLong()

        private fun fits(record: ByteArray) = record.size <= 4 + RecordFormat.MAX_RECORD + 4

        /** [state] as a snapshot cut at [cut], each record handed to [write]. */
        fun writeSnapshot(state: LearnState, cut: Int, write: (ByteArray) -> Unit, kinds: Map<Int, String> = emptyMap()) {
            val writer = LearnRecords.SnapshotWriter(write, kinds)
            state.snapshot(cut).forEach(writer::write)
        }

        /** The state the log [bytes] (header and records) say: what a start would read. */
        fun replay(bytes: ByteArray): LearnState {
            val state = LearnState()
            val reader = LearnRecords.Reader()
            LearnRecords.FORMAT.read(bytes) { type, input -> if (type != EMPTIED) reader.decode(type, input)?.let { applyOrKeep(state, it) } }
            return state
        }

        /**
         * Folds [log] (header and records) at [cut]: the snapshot and the records before the last
         * one older than the cut, replayed afresh, written as a snapshot; the records after it as
         * they are. False, nothing written, if no record of the tail is older than the cut.
         */
        fun fold(log: ByteArray, cut: Int, to: OutputStream): Boolean {
            val walk = Walk(log)
            val records = walk.records
            // after the last record of the tail older than the cut; one of no minute goes with those
            // before it, but an import's is as old as the record before it: a tail of imports alone,
            // 100 000 words of a list, folds with no commit after it
            var last = -1
            var before = Int.MIN_VALUE
            for ((i, r) in records.withIndex()) {
                val minute = r?.minute ?: if (r is LearnRecord.ImportBatch || r is LearnRecord.Imported || r is LearnRecord.ImportUndo) before else Int.MAX_VALUE
                r?.minute?.let { before = it }
                if (minute < cut) last = i
            }
            if (last < walk.tail) return false
            val split = last + 1
            val out = BufferedOutputStream(to)
            out.write(LearnRecords.FORMAT.header())
            writeSnapshot(walk.state(split), cut, out::write, walk.reader.kinds)
            walk.writeFrom(split, out)
            out.flush()
            return true
        }

        // a log's whole records but those emptied, read: [fold]'s and [merged]'s one walk, as the two
        // must agree on where the tail starts (R11 F2682)
        private class Walk(val log: ByteArray) {
            val spans = ArrayList<Long>().also { s ->
                LearnRecords.FORMAT.spans(log) { start, end -> if (log[start + 4] != EMPTIED) s += (start.toLong() shl 32) or end.toLong() }
            }
            val reader = LearnRecords.Reader()
            val records = spans.map { decode(log, it, reader) }

            /** The first record after the snapshot's [Cut]. */
            val tail = records.indexOfFirst { it === Cut } + 1

            /** The records before [until] replayed afresh. */
            fun state(until: Int) = LearnState().also { s -> for (i in 0 until until) records[i]?.let { applyOrKeep(s, it) } }

            /** The records from [from] on, as written. */
            fun writeFrom(from: Int, out: OutputStream) {
                if (from >= spans.size) return
                val start = (spans[from] ushr 32).toInt()
                out.write(log, start, spans.last().toInt() - start)
            }
        }

        // one that does not read is kept as it is (LearnRecords.Reader)
        private fun decode(log: ByteArray, span: Long, reader: LearnRecords.Reader): LearnRecord? {
            val start = (span ushr 32).toInt()
            val end = span.toInt()
            return reader.decode(log[start + 4], DataInputStream(ByteArrayInputStream(log, start + 5, end - start - 9)))
        }

        /**
         * [record] applied to [state]: one that does not read is kept as it is ([LearnRecord.Unknown],
         * by [LearnState.apply]); one this build fails on applying is reported and left out, as
         * keeping it would apply what it half did again at each fold (review-1a-r2 NIT 1).
         */
        internal fun applyOrKeep(state: LearnState, record: LearnRecord, onError: (Exception) -> Unit = {}) {
            try {
                state.apply(record)
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                onError(e)
            }
        }

        // RecordStore's mark of a log emptied, which no format's records have
        internal const val EMPTIED: Byte = 0
    }
}
