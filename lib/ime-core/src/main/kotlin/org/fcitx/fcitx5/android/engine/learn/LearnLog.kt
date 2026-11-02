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
import org.fcitx.fcitx5.android.engine.platform.LogFile
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmClock
import org.fcitx.fcitx5.android.engine.store.RecordFormat
import org.fcitx.fcitx5.android.engine.store.RecordStore
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
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
 */
class LearnLog(
    private val file: LogFile?,
    private val clock: LearnClock = JvmClock,
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

    /** Replays the log, and appends from now on; once only. One that does not open learns in memory this time. */
    fun open() {
        val file = file ?: return
        val oldestTail = openStore(file) ?: return
        if (foldDaily && oldestTail < LearnClock.cut(clock)) store?.compactSoon()
        state.takeChanges()
    }

    // the store opened on [file]: the oldest minute of its tail, or null if it did not open
    // (learning in memory this time)
    private fun openStore(file: LogFile): Int? {
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
            )
        } catch (e: IOException) {
            onError(e)
            // learned in memory this time
            state = LearnState()
            return null
        }
        this.store = store
        return oldestTail
    }

    /**
     * Appends and applies a commit of [rows] (and the nine keys' [takes]), stamped now: as written,
     * a commit too long for a record losing its level-3 rows first, then itself. Null if nothing
     * was kept.
     */
    fun record(rows: List<Row>, takes: List<Take> = emptyList(), flags: Int = 0, continued: String = ""): Commit? {
        if (rows.isEmpty()) return null
        val stamp = LearnClock.stamp(clock, state, commit = true)
        // a count of takes is a byte: more in one commit than anyone takes, the first so many
        var commit = Commit(state.nextSeq, stamp.minute, stamp.day, stamp.localDay, flags, rows, takes.take(MAX_TAKES), continued)
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
     * Whether all of them reached the disk.
     */
    fun absorb(rows: List<LearnRecord>): Boolean {
        if (rows.isEmpty()) return true
        val stamp = LearnClock.stamp(clock, state, commit = false)
        return rows.chunked(LearnRecords.ABSORBED_ROWS).map { absorbed(stamp.minute, it) }.all { it }
    }

    // long rows (an export's long phrases and readings) make so many pass a record's 64 KB: halved
    // till they fit, not dropped whole (R11 F2688)
    private fun absorbed(minute: Int, rows: List<LearnRecord>): Boolean {
        val record = Absorbed(minute, rows, emptyList())
        // [encoded] tells nothing, so a row too long for any record is told once, by the write of it alone (F3145)
        if (rows.size == 1 || encoded(record) != null) return put(record) == Put.ON_DISK
        val half = rows.size / 2
        val first = absorbed(minute, rows.subList(0, half))
        return absorbed(minute, rows.subList(half, rows.size)) && first
    }

    /** Takes [rows], phrases a table saved, out of the record, stamped now: forgotten in a table, or everywhere (U-84). */
    fun dropTableWords(rows: List<LearnRecord.TableWordRow>) {
        if (rows.isEmpty()) return
        val stamp = LearnClock.stamp(clock, state, commit = false)
        rows.chunked(LearnRecords.ABSORBED_ROWS).forEach { write(Absorbed(stamp.minute, emptyList(), it)) }
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

    private fun write(record: LearnRecord): Boolean = put(record) != Put.TOO_LONG

    private enum class Put { TOO_LONG, IN_MEMORY, ON_DISK }

    // [record] applied, unless too long to keep; on disk, or in the state alone
    private fun put(record: LearnRecord): Put {
        val bytes = encoded(record)
        if (bytes == null) {
            onError(IOException("a record too long to keep: $record"))
            return Put.TOO_LONG
        }
        val kept = store?.append(bytes) == true
        state.apply(record)
        appended?.invoke(record)
        changed()
        return if (kept) Put.ON_DISK else Put.IN_MEMORY
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

    /** Folds the log now, as the background would: for tests. */
    internal fun fold() = store?.compact()

    /** Closes the log. */
    override fun close() {
        store?.close()
        store = null
    }

    companion object {
        /** The log, in the engine's user directory. */
        const val FILE = "learn.log"

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
