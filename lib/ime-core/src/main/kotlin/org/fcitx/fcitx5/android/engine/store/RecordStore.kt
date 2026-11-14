/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.store

import org.fcitx.fcitx5.android.engine.platform.LogFile
import org.fcitx.fcitx5.android.engine.platform.write
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps what the user taught an input method in [file], a log of [format]: [open] replays it into
 * memory, then each record [append]ed is one write. Once the log passes [compactAt] bytes (or
 * twice what it last compacted to) it is rewritten as the counts alone, into a new file renamed
 * over the old, so a crash leaves one or the other whole. That happens on the thread that learns,
 * at most every few thousand records; given a [background] executor, that thread only reads the
 * counts when an append (or [open]) makes it due, and the executor writes and syncs them, so a
 * keystroke does not wait on the disk. The next append then puts them in place, with what was
 * appended meanwhile behind them.
 *
 * A log cut short loses its last record, which is cut from the file so appends go on from a
 * record's end. A file of another format or version is kept aside as `<name>.unreadable` rather
 * than overwritten, and learning starts afresh. A record garbled (its CRC failing) with whole
 * records after it is dropped and the rest read on (user's decision B2); a log that stops reading
 * anywhere else but in a last record cut short, as a torn append leaves it, is damaged and goes on
 * from what read. Either way the log is first kept aside whole, and [onError] hears a [KeptAside].
 *
 * Writing never fails the learning that asked for it: text the user picked must reach the app
 * whatever the disk does. A failed write is reported to [onError] and cut back off the file, so
 * later appends do not land behind torn bytes; learning goes on in memory regardless, and [writing]
 * is false until an append is written again, or the log whole.
 */
class RecordStore internal constructor(
    private val file: LogFile,
    private val format: RecordFormat,
    private val compactAt: Long,
    private val onError: (IOException) -> Unit,
    // a seam for tests: a disk that fails part-way through a write
    private val openAppend: (LogFile) -> OutputStream,
    // writes a compaction an append made due; null: the thread that learns does
    private val background: Executor? = null,
    // compacts from the log's own bytes rather than the counts in memory: see [Fold]
    private val fold: Fold? = null,
) : Closeable {
    constructor(
        file: LogFile,
        format: RecordFormat,
        compactAt: Long = DEFAULT_COMPACT_AT,
        onError: (IOException) -> Unit = {},
        background: Executor? = null,
        fold: Fold? = null,
    ) : this(file, format, compactAt, onError, LogFile::append, background, fold)

    /**
     * A log compacted from its own bytes: the file's first bytes as they were when the compaction
     * was due ([log]; appends do not change them), written compacted to [to]; false if there is
     * nothing to compact, the log then left as it is. Run on the [background] executor if there
     * is one, so it reads nothing the thread that learns changes. The counts in memory still seed
     * a log ([open]).
     */
    fun interface Fold {
        @Throws(IOException::class)
        fun fold(log: ByteArray, to: OutputStream): Boolean
    }

    private var out: OutputStream? = null

    // out is null before open and once a failed write stopped the appends: only the first is not open
    private var opened = false
    private var closed = false
    // the last append failed: the cut-back and the open after it tell nothing of the next write (a full
    // disk allows both), so only an append written whole clears it, or the log written whole since (a
    // rewrite, a compaction: R25 F7953, or 「没在保存」 stayed after a clear that rewrote the file till a word was next learned,
    // for good with learning off)
    private var refused = false
    // the end of the last record known written whole
    private var end = 0L
    // the counts alone may outgrow compactAt: then compact again only once the log doubles
    private var compacted = 0L
    private var counts: ((ByteArray) -> Unit) -> Unit = {}
    private var pending: Pending? = null

    /**
     * A compaction being written by [background], as long as its task returns (-1: nothing to
     * compact), and the records appended to the log since.
     */
    private class Pending(val task: FutureTask<Long>, private val countsWritten: AtomicBoolean) {
        val since = ArrayList<ByteArray>()
        // an append failed once its counts were written: they tell nothing of the disk now. Not one that failed
        // before they were (the task run by finish, say, no thread having started it): those counts the disk took
        // after the refusal (R25 F7982). Written, not the task done: it syncs after its last byte, and a refusal
        // meanwhile had 「没在保存」 gone one write early, no byte written since (R25 F8008)
        var refusedSince = false

        fun refused() {
            if (countsWritten.get()) refusedSince = true
        }
    }

    /**
     * Hands each record of the log to [replay], and appends from now on. Once only. [counts]
     * writes what is in memory as records, the log compacted.
     *
     * A log with nothing in it (none yet, or a header alone) is first filled by [seed], from what
     * another input method learned, and written as the counts it leaves, all or nothing: until
     * that is written whole, the log stays empty and the next open seeds again. A seed that
     * cannot be written fails the open. A log compacted to no counts is not empty: what the user
     * had forgotten is not seeded back. A seed that says it seeded nothing (its source did not
     * read) leaves the log empty, to be seeded at the next open.
     */
    fun open(replay: (Byte, DataInputStream) -> Unit, counts: ((ByteArray) -> Unit) -> Unit, seed: (() -> Boolean)? = null) {
        check(out == null && !closed) { "opened already" }
        this.counts = counts
        if (file.taken()) {
            val bytes = file.read()
            when {
                format.hasHeader(bytes) -> {
                    var unread = 0
                    var cause: Exception? = null
                    val garbled = ArrayList<IntRange>()
                    val end = format.read(
                        bytes,
                        skipped = {
                            unread++
                            cause = cause ?: it
                        },
                        garbled = { from, to -> garbled += from until to },
                    ) { type, input -> if (type != EMPTIED) replay(type, input) }
                    if (unread > 0) onError(IOException("$file: $unread unreadable records skipped", cause))
                    if (garbled.isNotEmpty()) dropGarbled(bytes, end, garbled) else if (end < bytes.size) cut(bytes, end)
                }
                // killed before the header was all written: nothing to keep
                format.header().copyOf(bytes.size).contentEquals(bytes) -> file.write(format.header())
                else -> moveAside()
            }
        }
        val seeding = seed != null && (!file.taken() || file.length() <= RecordFormat.HEADER_SIZE) && seed()
        if (seeding) writeCounts(seeding = true)
        if (!file.taken()) file.write(format.header())
        end = file.length()
        // a seed already written as counts: no call to write them again yet
        if (seeding) compacted = end
        out = openAppend(file)
        opened = true
        // due, say, after a kill before a compaction was put in place: open runs at the first keystroke
        if (tooLong()) compactDue()
    }

    /**
     * Cuts the log back to [end], where its records stop reading whole. Anywhere but in a last
     * record cut short, that is damage: the log is first kept whole aside, and, left with no
     * record, it is marked emptied so that no seed is written over what the user had learned.
     */
    private fun cut(bytes: ByteArray, end: Int) {
        val damaged = !tornAppend(bytes, end)
        if (damaged) {
            val aside = aside()
            // on disk before the log is cut: else a power cut could lose both
            writeSynced(aside) { it.write(bytes) }
            onError(KeptAside("$file damaged at byte $end: kept whole as ${aside.name}"))
        }
        file.truncate(end.toLong())
        if (damaged && end <= RecordFormat.HEADER_SIZE) file.append().use { it.write(format.record(EMPTIED) {}) }
    }

    /**
     * Rewrites the log without the [garbled] records [open] read past, and without what follows
     * [end], once it is kept whole aside: the other readers of the log (a fold, a rewrite) then
     * find no record they would stop at, and the next open neither drops nor keeps aside again.
     */
    private fun dropGarbled(bytes: ByteArray, end: Int, garbled: List<IntRange>) {
        val aside = aside()
        writeSynced(aside) { it.write(bytes) }
        val tail = if (end < bytes.size && !tornAppend(bytes, end)) ", and stops reading at byte $end" else ""
        onError(KeptAside("$file: ${garbled.size} garbled records skipped$tail: kept whole as ${aside.name}"))
        replaceFile(file, file.beside(COMPACTING)) { next ->
            next.create().use { stream ->
                var at = 0
                for (range in garbled) {
                    stream.write(bytes, at, range.first - at)
                    at = range.last + 1
                }
                stream.write(bytes, at, end - at)
            }
        }
    }

    /** Whether the records stop at [end] as a torn append leaves them: in a last record whose length, or the bytes it says, run past the end. */
    private fun tornAppend(bytes: ByteArray, end: Int): Boolean {
        val left = bytes.size - end
        if (left < 4) return true
        val length = ByteBuffer.wrap(bytes).getInt(end)
        // append writes none longer: a length past it, or none at all, is garbage
        return length in 1..RecordFormat.MAX_RECORD && left < 4 + length + 4
    }

    private fun moveAside() {
        val aside = aside()
        if (!file.renameTo(aside)) throw IOException("cannot move $file aside")
        onError(KeptAside("$file is not a log this reads: kept as ${aside.name}"))
    }

    // never over an earlier file kept aside: that may be a newer version's log, still wanted
    private fun aside(): LogFile {
        var aside = file.beside(UNREADABLE)
        var n = 1
        while (aside.taken()) aside = file.beside(UNREADABLE + "." + n++)
        return aside
    }

    /**
     * Whether appends reach the disk: opened, not closed, and the last one written. After a write that
     * failed it is false, though the file was cut back and opened again (a full disk allows both, and
     * refuses the next write as it did this one: R25 F7819), until a later append is written, or the log
     * whole (a rewrite, a compaction) and opened again; and for good, till the next start, once the file
     * could not be cut back or opened.
     */
    val writing: Boolean get() = out != null && !refused

    /**
     * Whether a [rewrite] is still tried: false once the log stopped taking writes (one failed, and the file
     * could not be cut back or opened again), till the next start. True while a full disk refuses the appends
     * ([writing] false, the log open): a rewrite goes through there once there is room, with no restart, which
     * is what a caller tells the user apart by (R25 F8037). Asks nothing of the disk, so a compaction being
     * written that [checkRewrites] would fail to put in place is not known yet.
     */
    val takesRewrites: Boolean get() = out != null

    private fun tooLong() = end > maxOf(compactAt, 2 * compacted)

    /**
     * Appends [record], unless not open, or longer than a reader would take for a record ([onError]
     * hears of that): whether it was written.
     */
    fun append(record: ByteArray): Boolean {
        val stream = out ?: return false
        // the reader would take it for garbage and drop the rest of the log with it
        if (!fits(record)) return false
        try {
            stream.write(record)
            end += record.size
            refused = false
        } catch (e: IOException) {
            refused = true
            pending?.refused()
            onError(e)
            recover()
            return false
        }
        val compacting = pending
        if (compacting != null) {
            compacting.since += record
            if (compacting.task.isDone) finish(compacting)
        } else if (tooLong()) {
            compactDue()
        }
        return true
    }

    /**
     * Appends [records] as [append] does each, in one write: an import of many words is not a
     * write per word (review R11 F3058). One that does not fit is left out and reported. Whether
     * all of them were written.
     */
    fun appendAll(records: List<ByteArray>): Boolean {
        val stream = out ?: return records.isEmpty()
        val kept = records.filter(::fits)
        if (kept.isEmpty()) return records.isEmpty()
        val all = ByteArray(kept.sumOf { it.size })
        var at = 0
        kept.forEach { it.copyInto(all, at); at += it.size }
        try {
            stream.write(all)
            end += all.size
            refused = false
        } catch (e: IOException) {
            refused = true
            pending?.refused()
            onError(e)
            recover()
            return false
        }
        val compacting = pending
        if (compacting != null) {
            compacting.since += kept
            if (compacting.task.isDone) finish(compacting)
        } else if (tooLong()) {
            compactDue()
        }
        return kept.size == records.size
    }

    /** Compacts the log soon, as though it had grown long enough: one already being compacted is not compacted again. */
    fun compactSoon() {
        if (!opened || closed) return
        if (out != null && pending == null) compactDue()
    }

    /** Compacts the log now due: only its counts are read here if there is a [background] to write them. */
    private fun compactDue() {
        if (background != null) compactInBackground(background) else compact()
    }

    /**
     * Whether a reader takes [record]: one longer reads as garbage, and so does the rest of the
     * log after it; one of [EMPTIED]'s type, which no format may use, [open] reads past. One that
     * does not fit is reported, and kept in memory alone.
     */
    private fun fits(record: ByteArray): Boolean {
        // the reader limits the fields alone, not the length before them and the CRC after
        if (record.size > 4 + RecordFormat.MAX_RECORD + 4) {
            onError(IOException("$file: a record of ${record.size} bytes not written, longer than a reader takes"))
            return false
        }
        // the type follows the length
        if (record.size > 4 && record[4] == EMPTIED) {
            onError(IOException("$file: a record of type $EMPTIED not written, the type that marks a log emptied"))
            return false
        }
        return true
    }

    /** After a failed write: cuts the file back to its last whole record, or stops writing. */
    private fun recover() {
        try {
            out?.close()
            file.truncate(end)
            out = openAppend(file)
        } catch (e: IOException) {
            onError(e)
            out = null
        }
    }

    /** Rewrites the log as the counts; not once it stopped taking writes after a failed one, as [append]. */
    internal fun compact() {
        check(opened && !closed) { "not open" }
        if (out == null) return
        // one being written in the background goes first: both would write the same file
        pending?.let { finish(it) }
        if (fold != null) {
            val written = AtomicBoolean()
            finish(Pending(foldTask(fold, written), written).also { it.task.run() })
            return
        }
        try {
            writeCounts()
        } catch (e: IOException) {
            onError(e)
            // the log as it was still holds everything; try again once it has doubled
            compacted = end
            return
        }
        reopen(compactedTo = file.length())
    }

    /**
     * Rewrites the log whole, as the user asked (U-88, U-86, a long press taken back): [transform]
     * reads it (header and records) and writes what is kept to its stream, the header first; false
     * leaves the log as it was. A compaction being written is put in place first, so the bytes read
     * are the log as it is, and none is renamed over the rewrite after: this thread alone renames.
     *
     * A rewrite that keeps no record (a header alone: 清掉最近 of a log that holds that time alone, R25
     * F8000) is written as a log emptied, as a compaction to no counts is: declined, the log kept what
     * the user was told is cleared.
     *
     * A log that stopped taking writes (one failed, and the file could not be cut back or opened again) is not
     * rewritten, and that is a failure, not nothing to write: read as "nothing of that time in the log", a 清掉最近
     * was told cleared with the time's records still in the file (R25 F8018).
     *
     * @return the bytes written, null if nothing was (false, or not so much as a header: [onError])
     * @throws IOException if the disk failed, or the log takes no writes: the log as it was
     */
    @Throws(IOException::class)
    fun rewrite(transform: (ByteArray, OutputStream) -> Boolean): ByteArray? {
        checkRewrites()
        val written = ByteArrayOutputStream()
        if (!transform(file.read(), written)) return null
        if (written.size() < RecordFormat.HEADER_SIZE) {
            onError(IOException("a rewrite to less than a header declined"))
            return null
        }
        // a header alone is a log a start seeds again (as a first one): one emptied is marked so, as by [countsTo]
        if (written.size() == RecordFormat.HEADER_SIZE) written.write(format.record(EMPTIED) {})
        // a failure the caller's to tell (a clear that did not clear): the file as it was, still open
        replaceFile(file, file.beside(COMPACTING)) { it.write(written.toByteArray()) }
        reopen(compactedTo = file.length())
        return written.toByteArray()
    }

    /**
     * What [rewrite] checks before it reads the log, for a caller with something to undo first that a rewrite
     * sure to fail must leave (a clear's backups, deleted before its rewrite: R25 F8021): a compaction being
     * written is put in place, as [rewrite] does.
     *
     * @throws IOException if the log takes no writes: [rewrite] would throw the same
     */
    @Throws(IOException::class)
    fun checkRewrites() {
        check(opened && !closed) { "not open" }
        if (out == null) throw stopped()
        pending?.let { finish(it) }
        // putting the compaction in place may have failed to open the log again
        if (out == null) throw stopped()
    }

    private fun stopped() = IOException("$file takes no writes (a write failed, or it did not open again): not rewritten")

    /**
     * Folds the log's first [end] bytes into the next file, written and synced: its length, or -1 for nothing to fold.
     * [written] is set once its last byte is written, before the sync ([Pending.refused]).
     */
    private fun foldTask(fold: Fold, written: AtomicBoolean): FutureTask<Long> {
        val length = end
        val next = file.beside(COMPACTING)
        return FutureTask(
            Callable {
                val log = file.read(length.toInt())
                var folded = false
                writeSynced(next) {
                    folded = fold.fold(log, it)
                    // nothing folded, nothing written
                    if (folded) written.set(true)
                }
                if (folded) {
                    next.length()
                } else {
                    next.delete()
                    -1L
                }
            },
        )
    }

    /**
     * As [compact], but only the counts are read here: [executor] writes and syncs them, and
     * [finish] puts them in place.
     */
    private fun compactInBackground(executor: Executor) {
        // set by the task, on the executor's thread, once its last byte is written; read by this one at a refusal
        val written = AtomicBoolean()
        val task = if (fold != null) {
            foldTask(fold, written)
        } else {
            // as long as the log, which open read whole, so seldom grown; handed on as it is, not copied
            val bytes = ByteArrayOutputStream(end.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).also { countsTo(it, seeding = false) }
            val next = file.beside(COMPACTING)
            FutureTask(
                Callable {
                    writeSynced(next) {
                        bytes.writeTo(it)
                        written.set(true)
                    }
                    bytes.size().toLong()
                },
            )
        }
        val compacting = Pending(task, written)
        pending = compacting
        try {
            executor.execute(compacting.task)
        } catch (_: RejectedExecutionException) {
            // an executor shut down must not fail the learning: written here after all
            compacting.task.run()
        }
    }

    /** Puts the counts [compacting] wrote in place of the log, with what was appended since behind them. */
    private fun finish(compacting: Pending) {
        pending = null
        val next = file.beside(COMPACTING)
        val size: Long
        try {
            size = written(compacting.task).getOrThrow()
            // nothing to fold: the log stays, and is not due again till it has doubled
            if (size < 0) {
                compacted = end
                return
            }
            appendSince(next, size, compacting)
            if (!next.renameTo(file)) throw IOException("cannot replace $file")
        } catch (e: IOException) {
            next.delete()
            onError(e)
            // the log as it was still holds everything, what was appended since too
            compacted = end
            return
        }
        // written after the last refusal: the counts (their last byte after it), or what was appended since behind them just now
        reopen(compactedTo = size, wrote = !compacting.refusedSince || compacting.since.isNotEmpty())
    }

    /** Appends to the [size] bytes of counts [compacting] wrote in [next] what was appended to the log since, and syncs it. */
    private fun appendSince(next: LogFile, size: Long, compacting: Pending) {
        // one gone would be made anew, of no header nor counts, and renamed over the log
        if (next.length() != size) throw IOException("$next is not the $size bytes of counts written")
        // synced before the rename: a power cut after it would otherwise lose what was appended meanwhile
        next.append().use { stream ->
            compacting.since.forEach { stream.write(it) }
            stream.sync()
        }
    }

    /**
     * Waits for [task] to have written its file, running it here if no thread has started it.
     *
     * @return the length it wrote, -1 for nothing to compact; or why it failed, an [IOException]
     */
    private fun written(task: FutureTask<Long>): Result<Long> {
        task.run()
        var interrupted = false
        try {
            while (true) {
                try {
                    return Result.success(task.get())
                } catch (_: InterruptedException) {
                    // its file is still being written: not to be renamed or deleted under it
                    interrupted = true
                }
            }
        } catch (e: ExecutionException) {
            return Result.failure(e.cause as? IOException ?: IOException(e.cause))
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /**
     * Appends from the end of the log just renamed over the old one, of which [compactedTo] bytes are counts.
     * [wrote]: the disk took bytes of it after the last append it refused, so, opened, it is [writing] again.
     */
    private fun reopen(compactedTo: Long, wrote: Boolean = true) {
        // the old stream writes to the file renamed over, now unlinked
        try {
            out?.close()
        } catch (e: IOException) {
            onError(e)
        }
        end = file.length()
        compacted = compactedTo
        out = try {
            openAppend(file).also { if (wrote) refused = false }
        } catch (e: IOException) {
            onError(e)
            null
        }
    }

    /** Writes the counts into a new file, renamed over [file] once it is all on disk. */
    private fun writeCounts(seeding: Boolean = false) {
        replaceFile(file, file.beside(COMPACTING)) { next ->
            next.create().use { raw ->
                val stream = BufferedOutputStream(raw)
                countsTo(stream, seeding)
                stream.flush()
            }
        }
    }

    /** Writes the log compacted to [stream]: the header, then the counts. */
    private fun countsTo(stream: OutputStream, seeding: Boolean) {
        stream.write(format.header())
        var none = true
        counts {
            // one a reader would not take would cost every count after it
            if (fits(it)) {
                stream.write(it)
                none = false
            }
        }
        // a header alone is seeded again: meant for a seed that found nothing, not for all forgotten
        if (none && !seeding) stream.write(format.record(EMPTIED) {})
    }

    /** Writes to [to] what [write] puts in its stream, and syncs it, or leaves no [to] at all. */
    private fun writeSynced(to: LogFile, write: (OutputStream) -> Unit) {
        try {
            to.create().use {
                write(it)
                it.sync()
            }
        } catch (e: IOException) {
            // a copy aside cut short (the disk full) is no copy, and each start would leave another
            to.delete()
            throw e
        }
    }

    override fun close() {
        closed = true
        // counts written in the background are not to be written again at the next open
        pending?.let { finish(it) }
        out?.close()
        out = null
    }

    /**
     * What [onError] hears when the log, or part of it, could not be read and was kept aside whole
     * ([isAside]): learning goes on from what read, which the user is to be told (0.6.1 audit decode-1).
     */
    class KeptAside(message: String) : IOException(message)

    companion object {
        const val DEFAULT_COMPACT_AT = 1L shl 20
        const val UNREADABLE = ".unreadable"

        /**
         * Whether [name] is a copy of the log named [log] kept aside ([KeptAside]): `<log>.unreadable`,
         * then `.unreadable.1`, `.2`... The one rule for the app and the clears, which name it nowhere else.
         */
        fun isAside(log: String, name: String): Boolean {
            if (!name.startsWith(log + UNREADABLE)) return false
            val rest = name.substring(log.length + UNREADABLE.length)
            return rest.isEmpty() || (rest.length > 1 && rest[0] == '.' && rest.drop(1).all { it in '0'..'9' })
        }
        private const val COMPACTING = ".compacting"
        // of no fields, and a type no format's records have: they count from 1, and skip types they do not know
        private const val EMPTIED: Byte = 0
    }
}
