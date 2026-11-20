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
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PhraseForget
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
 * The learning record: [file] (`learn.log`) of [LearnRecords], replayed into [state] at [open], each
 * commit or user act appended as one write and applied as written. Folded in the [background] from the
 * file's own bytes: what is older than the cut ([LearnClock.cut]) becomes a snapshot, newer stays as
 * written so it can still be cleared. Without a [file] (no user directory, a test) it learns in memory
 * alone. [unkept] is told once, at the first applied record that did not reach disk (no file, one that
 * did not open, a refused write): what is learned from then may vanish with the process, which the user
 * must be told.
 */
class LearnLog(
    private val file: LogFile?,
    private val clock: LearnClock = JvmClock,
    private val onError: (IOException) -> Unit = {},
    private val background: Executor? = null,
    private val compactAt: Long = RecordStore.DEFAULT_COMPACT_AT,
    // a seam for tests: folded only as it grows, not each calendar day and start
    private val foldDaily: Boolean = true,
    private val unkept: () -> Unit = {},
    // a seam for tests: a disk that refuses a write ([RecordStore]'s)
    private val openAppend: (LogFile) -> OutputStream = LogFile::append,
) : Closeable {

    var state = LearnState()
        private set

    /**
     * Hears what each record applied from now on changed that the user model keeps apart
     * ([LearnState.Changes]: words formed and gone, counts), for every kind of record alike. What [open]
     * read is not told: the model is built from the state then.
     */
    var listener: ((LearnState.Changes) -> Unit)? = null

    /**
     * Hears each record appended from now on, as written, so an act can be taken back (LearningControl).
     * `kept` false: the file did not take it, and it is in the state alone, where taking it back needs no rewrite.
     */
    var appended: ((record: LearnRecord, kept: Boolean) -> Unit)? = null

    internal var store: RecordStore? = null
        private set

    /** A commit the user may still take back: it and what it changed, as it was. */
    private class Undoable(val commit: LearnRecord, val befores: List<Before>)

    private val undoable = LinkedHashMap<Long, Undoable>()

    /**
     * Which writing of the record this is: changes on every rewrite (a span forgotten, a backup restored:
     * [rewritten]) and differs from any other log's, so what was read of it before may be gone: an act to
     * take back (LearningControl) and a session's kept commits (CommitJournal) are dropped.
     */
    @Volatile
    var rewrites = GENERATIONS.incrementAndGet()
        internal set

    /** No commit before is taken back any more, nor a session's kept. */
    fun rewritten() {
        undoable.clear()
        rewrites = GENERATIONS.incrementAndGet()
    }

    /** Replays the log, then appends; once only. If it does not open, learns in memory this time. */
    fun open() {
        val file = file ?: return
        val oldestTail = openStore(file) ?: return
        if (foldDaily && oldestTail < LearnClock.cut(clock)) store?.compactSoon()
        state.takeChanges()
    }

    // the store on [file]: its tail's oldest minute, or null if it did not open (in memory this time)
    private fun openStore(file: LogFile): Int? {
        val reader = LearnRecords.Reader()
        var inTail = true
        var oldestTail = Int.MAX_VALUE
        val store = RecordStore(file, LearnRecords.FORMAT, compactAt, onError, openAppend, background, fold = { log, to -> fold(log, LearnClock.cut(clock), to) })
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

    /** Appends and applies a commit of [rows] (and nine-key [takes]), stamped now; a commit too long for a record drops its level-3 rows first, then itself. Null if not kept. */
    fun record(rows: List<Row>, takes: List<Take> = emptyList(), flags: Int = 0, continued: String = ""): Commit? {
        if (rows.isEmpty()) return null
        val stamp = LearnClock.stamp(clock, state, commit = true)
        // the take count is a byte: more than anyone takes in one commit; the first so many
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
        val kept = store?.append(bytes) == true
        tellIfUnkept(kept)
        val befores = state.apply(commit)
        appended?.invoke(commit, kept)
        changed()
        remember(commit.seq, commit, befores, newDay)
        return commit
    }

    private fun newDay(localDay: Int) = state.localDay != Int.MIN_VALUE && localDay > state.localDay

    // one of the last few to take back; the first commit of a calendar day lets yesterday's records be folded
    private fun remember(seq: Long, commit: LearnRecord, befores: List<Before>, newDay: Boolean) {
        undoable[seq] = Undoable(commit, befores)
        while (undoable.size > UNDOABLE) undoable.remove(undoable.keys.first())
        if (newDay && foldDaily) store?.compactSoon()
    }

    /** If one of the last few; whether it was. */
    fun undo(seq: Long): Boolean {
        val u = undoable[seq] ?: return false
        val stamp = LearnClock.stamp(clock, state, commit = false)
        // taken off the list once written: an Undo too long for a record (it holds the commit and befores) says so, not silently nothing
        if (!write(Undo(seq, stamp.minute, stamp.day, state.localDay, u.commit, u.befores))) return false
        undoable.remove(seq)
        return true
    }

    /** [undo] for a commit deleted right after writing: only one in the tail (the last hour or the same calendar day). An older one stays learned. */
    fun undoRecent(seq: Long, commit: LearnRecord? = null): Boolean {
        val c = undoable[seq]?.commit
        // the commit kept, not another with its seq: a rolled-back record reissues seqs
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

    /** Pins [text] first under [code], or unpins it. */
    fun pin(code: Code, text: String, reading: String, on: Boolean) {
        val stamp = LearnClock.stamp(clock, state, commit = false)
        // another pin there is unpinned first in its own record, so an act's undo (the opposites) pins it again
        val was = state.pins[code]?.takeIf { on && (it.text != text || it.reading != reading) }
        if (was != null) write(Pin(stamp.minute, stamp.day, code, was.text, was.reading, false))
        write(Pin(stamp.minute, stamp.day, code, text, reading, on))
    }

    /**
     * Takes in [rows] (of [LearnRecord.MineRow], [LearnRecord.WordUseRow], [LearnRecord.TableWordRow],
     * [LearnRecord.EnWordRow]) the log lacks, stamped now: an export's words, a table's saved phrases, as
     * [Absorbed] takes an older build's. Whether all reached the disk.
     */
    fun absorb(rows: List<LearnRecord>): Boolean {
        if (rows.isEmpty()) return true
        val stamp = LearnClock.stamp(clock, state, commit = false)
        return rows.chunked(LearnRecords.ABSORBED_ROWS).map { absorbed(stamp.minute, it) }.all { it }
    }

    // long rows (an export's long phrases and readings) can push a record past 64 KB: halved till they fit,
    // not dropped whole
    private fun absorbed(minute: Int, rows: List<LearnRecord>): Boolean {
        val record = Absorbed(minute, rows, emptyList())
        // [encoded] tells nothing, so a row too long for any record is reported once, by writing it alone
        if (rows.size == 1 || encoded(record) != null) return put(record) == Put.ON_DISK
        val half = rows.size / 2
        val first = absorbed(minute, rows.subList(0, half))
        return absorbed(minute, rows.subList(half, rows.size)) && first
    }

    /** Removes phrases a table saved, stamped now: forgotten in a table, or everywhere. */
    fun dropTableWords(rows: List<LearnRecord.TableWordRow>) {
        if (rows.isEmpty()) return
        val stamp = LearnClock.stamp(clock, state, commit = false)
        rows.chunked(LearnRecords.ABSORBED_ROWS).forEach { write(Absorbed(stamp.minute, emptyList(), it)) }
    }

    /** 不再联想: turns [text] off as a prediction after [prev], or on again. */
    fun noPredict(prev: String, text: String, on: Boolean) {
        val stamp = LearnClock.stamp(clock, state, commit = false)
        write(NoPredict(stamp.minute, prev, text, on))
    }

    fun forget(text: String) {
        val stamp = LearnClock.stamp(clock, state, commit = false)
        write(Forget(stamp.minute, stamp.day, text))
    }

    /** Forgets only the whole phrase [text]'s count ([PhraseForget]). */
    fun forgetPhrase(text: String) {
        write(PhraseForget(LearnClock.stamp(clock, state, commit = false).minute, text))
    }

    /**
     * Appends and applies an English [word] committed after [prev] ("" for none), [typed] as keyed, [how] as
     * in [EnCommit.how], stamped now: like [record]'s commits it makes a typing day and [undo] can take it
     * back. Null if too long to keep.
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

    /** Lets the English [word] be corrected again, keeping its uses and pairs ([EnCorrect]). */
    fun correctEnglish(word: String) {
        write(EnCorrect(LearnClock.stamp(clock, state, commit = false).minute, word))
    }

    /** Forgets the English [word]: uses, pairs, and whether it was added or never corrected ([EnForget]). */
    fun forgetEnglish(word: String) {
        write(EnForget(LearnClock.stamp(clock, state, commit = false).minute, word))
    }

    /**
     * Writes an import's [records] ([LearnRecord.ImportBatch] and its words, or an [LearnRecord.ImportUndo]),
     * each applied as written, the listener told once at the end: a 100 000-word list is as many records.
     * One too long to keep is left out and reported.
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
        // one write for all: a write per word took over a second for 100 000 words on the fcitx thread
        val written = bytes.isEmpty() || store?.appendAll(bytes) == true
        tellIfUnkept(written, kept.size)
        for (record in kept) {
            // as a replay would: a failing one is reported, the rest go on, and the listener is told
            applyOrKeep(state, record) { onError(IOException("a record not applied", it)) }
            // an act's records as written: the last act before them can no longer be taken back (LearningControl)
            appended?.invoke(record, written)
        }
        changed()
    }

    /** Appends and applies the record [make] builds for the current minute and typing day, for events no method here writes (a table's order reset, per-code blocks). Whether kept. */
    fun event(make: (minute: Int, day: Int) -> LearnRecord): Boolean {
        val stamp = LearnClock.stamp(clock, state, commit = false)
        return write(make(stamp.minute, stamp.day))
    }

    private fun write(record: LearnRecord): Boolean = put(record) != Put.TOO_LONG

    private enum class Put { TOO_LONG, IN_MEMORY, ON_DISK }

    // [record] applied unless too long to keep; on disk or in the state alone
    private fun put(record: LearnRecord): Put {
        val bytes = encoded(record)
        if (bytes == null) {
            onError(IOException("a record too long to keep: $record"))
            return Put.TOO_LONG
        }
        val kept = store?.append(bytes) == true
        tellIfUnkept(kept)
        state.apply(record)
        appended?.invoke(record, kept)
        changed()
        return if (kept) Put.ON_DISK else Put.IN_MEMORY
    }

    private var toldUnkept = false

    /**
     * Records the open store did not take, in the state alone, since it last matched the file: lets an act
     * taken back in memory know its own are all of them ([dropUnwritten] drops every one).
     */
    internal var unwritten = 0
        private set

    // [records] about to be applied, [written] or not: the first not written is reported
    private fun tellIfUnkept(written: Boolean, records: Int = 1) {
        if (!written && store != null) unwritten += records
        if (written || toldUnkept) return
        toldUnkept = true
        unkept()
    }

    /**
     * Rewrites the whole log (clears, an act taken back) via [RecordStore.rewrite] with [transform]; the
     * state becomes what the written bytes replay to, the listener is told what that changed of the user's
     * words, and no earlier commit may be taken back. False if nothing was written: a file that did not open
     * (in memory this run), or [transform] declined; with no file at all, the state's own snapshot is rewritten.
     *
     * @throws IOException if the disk failed, or the file stopped taking writes after a failed one (not
     * "nothing written", else a clear would report done with its records still in the file): log and state as they were
     */
    @Throws(IOException::class)
    fun rewrite(transform: (ByteArray, OutputStream) -> Boolean): Boolean = rewrite(endUndo = true, transform)

    /**
     * Throws as [rewrite] would for a file that stopped taking writes, before anything is read or written
     * ([RecordStore.checkRewrites]): a clear deletes the backups before its rewrite. Nothing for an in-memory
     * or unopened log.
     */
    @Throws(IOException::class)
    fun checkRewrites() {
        store?.checkRewrites()
    }

    // [endUndo] false: a rewrite the user did not make (the conversion landing) keeps an import's 撤销
    private fun rewrite(endUndo: Boolean, transform: (ByteArray, OutputStream) -> Boolean): Boolean {
        val store = store
        // a record that did not open learns in memory, where a rewrite would not last: not done
        if (store == null && file != null) return false
        val bytes = if (store != null) store.rewrite(transform) else inMemory(transform)
        bytes ?: return false
        // written: what was read of it before is gone, whatever rereading throws
        rewritten()
        read(bytes, endUndo)
        return true
    }

    /**
     * Makes the state what the file says where the disk refused records: else a clear finding nothing of its
     * span in the file would keep offering what was typed then, though reported cleared. Anything else
     * unwritten goes too, as with a rewrite: none of it would outlast the process. Likewise an act taken back
     * whose records the file never took, but only where they are all that is unwritten ([unwritten]), so
     * nothing else goes with it (LearningControl.takeBack). Whether there was any; then no earlier commit may
     * be taken back.
     *
     * @throws IOException if the file did not read: the state as it was
     */
    @Throws(IOException::class)
    fun dropUnwritten(): Boolean {
        val file = file?.takeIf { store != null && unwritten > 0 } ?: return false
        val bytes = file.read()
        rewritten()
        read(bytes, endUndo = true)
        return true
    }

    // the state made what [bytes] replay to, the listener told what that changed of the user's words
    private fun read(bytes: ByteArray, endUndo: Boolean) {
        val was = state
        // replaying is no change of the user's: [Changes.between] reports that
        state = replay(bytes).also { it.takeChanges() }
        // imported words keep their places, so a model over them and a typing session go on
        state.keepImportsOf(was, endUndo)
        unwritten = 0
        listener?.invoke(LearnState.Changes.between(was, state))
    }

    /** Whether writes last: a file, opened. Without, typing keeps no times to clear a span by. */
    val onDisk: Boolean get() = store != null

    /**
     * Whether learning reaches disk now: [onDisk], with writes not stopped by a failure that could not be cut
     * back. False: learning lives in memory till the process ends, shown in the word list and status panel.
     */
    val saving: Boolean get() = store?.writing == true

    /**
     * False once the file stopped taking writes this run ([RecordStore.takesRewrites]): [checkRewrites] and
     * [rewrite] throw from then on till a new start reopens it. False too for a file that did not open (or is
     * closed): [rewrite] writes nothing there till a start opens it, so a failed restore must not say "try
     * again". True otherwise, including while a full disk refuses appends ([saving] false), and for an
     * in-memory log, rewritten there.
     */
    val takesRewrites: Boolean get() = store?.takesRewrites ?: (file == null)

    // in memory (no user directory yet): the log the state would fold to, rewritten as a disk one would be
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

    // null for a record too long to keep or unwritable (a field past 64 KB as UTF-8): never the key's
    // failure, reported by the caller once
    private fun encoded(record: LearnRecord): ByteArray? = try {
        LearnRecords.encode(record).takeIf(::fits)
    } catch (_: IOException) {
        null
    }

    /** Folds now, as the background would: for tests. */
    internal fun fold() = store?.compact()

    override fun close() {
        store?.close()
        store = null
    }

    companion object {
        /** In the engine's user directory. */
        const val FILE = "learn.log"

        private const val MAX_TAKES = 255

        /** How many of the last commits may be taken back. */
        const val UNDOABLE = 16

        /** How long a commit may be taken back (or else the same calendar day): the tail. */
        const val TAIL_MINUTES = 60

        // [rewrites] source: one per log and rewrite in this process
        private val GENERATIONS = AtomicLong()

        private fun fits(record: ByteArray) = record.size <= 4 + RecordFormat.MAX_RECORD + 4

        /** [state] as a snapshot cut at [cut], each record handed to [write]; only those it [keeps]. */
        fun writeSnapshot(
            state: LearnState,
            cut: Int,
            write: (ByteArray) -> Unit,
            kinds: Map<Int, String> = emptyMap(),
            keeps: (LearnRecord) -> Boolean = { true },
        ) {
            val writer = LearnRecords.SnapshotWriter(write, kinds)
            state.snapshot(cut).forEach { if (keeps(it)) writer.write(it) }
        }

        /** The state log [bytes] (header and records) replay to: what a start reads. */
        fun replay(bytes: ByteArray): LearnState {
            val state = LearnState()
            val reader = LearnRecords.Reader()
            LearnRecords.FORMAT.read(bytes) { type, input -> if (type != EMPTIED) reader.decode(type, input)?.let { applyOrKeep(state, it) } }
            return state
        }

        /**
         * Folds [log] (header and records) at [cut]: the snapshot and the records up to the last one older than
         * the cut, replayed, become a snapshot; later records stay as written. False, nothing written, if no tail
         * record is older than the cut.
         */
        fun fold(log: ByteArray, cut: Int, to: OutputStream): Boolean {
            val walk = Walk(log)
            val records = walk.records
            // after the last tail record older than the cut; one with no minute goes with those before it, but an
            // import's is as old as the record before it: a tail of imports alone (a 100 000-word list) folds with no
            // commit after it
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

        // a log's records except emptied ones: one walk for [fold] and [merged], which must agree where the tail starts
        private class Walk(val log: ByteArray) {
            val spans = ArrayList<Long>().also { s ->
                LearnRecords.FORMAT.spans(log) { start, end -> if (log[start + 4] != EMPTIED) s += (start.toLong() shl 32) or end.toLong() }
            }
            val reader = LearnRecords.Reader()
            val records = spans.map { decode(log, it, reader) }

            /** The first record after the snapshot's [Cut]. */
            val tail = records.indexOfFirst { it === Cut } + 1

            /** The records before [until], replayed afresh. */
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
         * Applies [record]: one that does not read is kept as it is ([LearnRecord.Unknown], via
         * [LearnState.apply]); one this build fails to apply is reported and left out, as keeping it would
         * reapply its half-done part at each fold.
         */
        internal fun applyOrKeep(state: LearnState, record: LearnRecord, onError: (Exception) -> Unit = {}) {
            try {
                state.apply(record)
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                onError(e)
            }
        }

        // RecordStore's mark of an emptied log, which no format's records start with
        internal const val EMPTIED: Byte = 0
    }
}
