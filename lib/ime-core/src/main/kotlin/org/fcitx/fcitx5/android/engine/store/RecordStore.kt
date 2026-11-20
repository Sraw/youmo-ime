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
 * A user's learning in [file], a log of [format]: [open] replays it, each [append] is one write. Past [compactAt]
 * bytes (or twice the last compaction) it is rewritten as counts alone into a new file renamed over the old, on
 * the [background] executor if given so a keystroke never waits on the disk.
 *
 * A last record cut short is cut off; a file of another format is kept aside as `<name>.unreadable` and learning
 * starts afresh; garbled records with whole ones after are dropped. Damage keeps the log aside whole first and
 * tells [onError] a [KeptAside].
 *
 * Writing never fails the learning that asked for it: a failed write is reported and cut back off the file, and
 * [writing] is false until something is written whole again.
 */
class RecordStore internal constructor(
    private val file: LogFile,
    private val format: RecordFormat,
    private val compactAt: Long,
    private val onError: (IOException) -> Unit,
    // a seam for tests: a disk that fails part-way through a write
    private val openAppend: (LogFile) -> OutputStream,
    private val background: Executor? = null,
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

    /** Compacts the log from its own first bytes as of when due (appends do not change them) into [to]; false for nothing to compact. Runs on [background], reading nothing the learning thread changes. */
    fun interface Fold {
        @Throws(IOException::class)
        fun fold(log: ByteArray, to: OutputStream): Boolean
    }

    private var out: OutputStream? = null

    private var opened = false
    private var closed = false
    // the last append failed: a cut-back and reopen prove nothing (a full disk allows both), so only a write whole clears it
    private var refused = false
    private var end = 0L
    // the counts alone may outgrow compactAt: then compact again only once the log doubles
    private var compacted = 0L
    private var counts: ((ByteArray) -> Unit) -> Unit = {}
    private var pending: Pending? = null

    /** A compaction [background] is writing (-1: nothing to compact), and the records appended since. */
    private class Pending(val task: FutureTask<Long>, private val countsWritten: AtomicBoolean) {
        val since = ArrayList<ByteArray>()
        // an append failed after the counts were written (not before: those counts did reach the disk after it).
        // Written, not done: the task syncs after its last byte
        var refusedSince = false

        fun refused() {
            if (countsWritten.get()) refusedSince = true
        }
    }

    /**
     * Replays the log into [replay] and appends from now on; once only. An empty log is first filled by [seed]
     * (another input method's learning) and written as counts, all or nothing, else it stays empty and the next
     * open seeds again. A log compacted to no counts is not empty: what the user forgot is not seeded back.
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
        if (seeding) compacted = end
        out = openAppend(file)
        opened = true
        // due after a kill before a compaction was put in place
        if (tooLong()) compactDue()
    }

    /** Cuts the log back to [end]; anywhere but a torn last record that is damage, so the log is kept aside first and, left empty, marked emptied so no seed overwrites it. */
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

    /** Rewrites the log without the [garbled] records and what follows [end], once kept aside, so no other reader stops at them and the next open does nothing again. */
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

    /** Whether the records stop at [end] as a torn append leaves them: a last record running past the end. */
    private fun tornAppend(bytes: ByteArray, end: Int): Boolean {
        val left = bytes.size - end
        if (left < 4) return true
        val length = ByteBuffer.wrap(bytes).getInt(end)
        return length in 1..RecordFormat.MAX_RECORD && left < 4 + length + 4
    }

    private fun moveAside() {
        val aside = aside()
        if (!file.renameTo(aside)) throw IOException("cannot move $file aside")
        onError(KeptAside("$file is not a log this reads: kept as ${aside.name}"))
    }

    // never over an earlier file kept aside: it may be a newer version's log
    private fun aside(): LogFile {
        var aside = file.beside(UNREADABLE)
        var n = 1
        while (aside.taken()) aside = file.beside(UNREADABLE + "." + n++)
        return aside
    }

    /** Whether appends reach the disk: open, and the last one written. False after a failed write until something is written whole; for good once the file could not be reopened. */
    val writing: Boolean get() = out != null && !refused

    /** Whether a [rewrite] is still tried: true while a full disk refuses appends, false once the log could not be reopened. Asks nothing of the disk. */
    val takesRewrites: Boolean get() = out != null

    private fun tooLong() = end > maxOf(compactAt, 2 * compacted)

    /** Appends [record], unless not open or too long for a reader (told to [onError]); whether it was written. */
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

    /** Appends [records] in one write (an import of many words); one that does not fit is left out and reported. */
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

    fun compactSoon() {
        if (!opened || closed) return
        if (out != null && pending == null) compactDue()
    }

    private fun compactDue() {
        if (background != null) compactInBackground(background) else compact()
    }

    /** Whether a reader takes [record]: not too long, nor of [EMPTIED]'s type; one that does not fit is reported and kept in memory alone. */
    private fun fits(record: ByteArray): Boolean {
        // the reader limits the fields alone, not the length before them and the CRC after
        if (record.size > 4 + RecordFormat.MAX_RECORD + 4) {
            onError(IOException("$file: a record of ${record.size} bytes not written, longer than a reader takes"))
            return false
        }
        if (record.size > 4 && record[4] == EMPTIED) {
            onError(IOException("$file: a record of type $EMPTIED not written, the type that marks a log emptied"))
            return false
        }
        return true
    }

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
            compacted = end
            return
        }
        reopen(compactedTo = file.length())
    }

    /**
     * Rewrites the log whole as the user asked: [transform] reads it and writes what is kept, header first; false
     * leaves it. A pending compaction is put in place first, so this thread alone renames. A rewrite keeping no
     * record is written as emptied, else the next start would seed back what was cleared. A log that stopped taking
     * writes is a failure, not nothing to write.
     *
     * @return the bytes written, null if nothing was
     * @throws IOException if the disk failed or the log takes no writes
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
        // a header alone is a log a start seeds again: mark it emptied
        if (written.size() == RecordFormat.HEADER_SIZE) written.write(format.record(EMPTIED) {})
        replaceFile(file, file.beside(COMPACTING)) { it.write(written.toByteArray()) }
        reopen(compactedTo = file.length())
        return written.toByteArray()
    }

    /**
     * What [rewrite] checks first, for a caller that must undo something before a rewrite sure to fail.
     *
     * @throws IOException if the log takes no writes
     */
    @Throws(IOException::class)
    fun checkRewrites() {
        check(opened && !closed) { "not open" }
        if (out == null) throw stopped()
        pending?.let { finish(it) }
        if (out == null) throw stopped()
    }

    private fun stopped() = IOException("$file takes no writes (a write failed, or it did not open again): not rewritten")

    /** Folds the log's first [end] bytes into the next file, synced: its length, or -1 for nothing. [written] is set after the last byte, before the sync. */
    private fun foldTask(fold: Fold, written: AtomicBoolean): FutureTask<Long> {
        val length = end
        val next = file.beside(COMPACTING)
        return FutureTask(
            Callable {
                val log = file.read(length.toInt())
                var folded = false
                writeSynced(next) {
                    folded = fold.fold(log, it)
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

    /** As [compact], but [executor] writes and syncs the counts and [finish] puts them in place. */
    private fun compactInBackground(executor: Executor) {
        // set on the executor's thread once its last byte is written; read here at a refusal
        val written = AtomicBoolean()
        val task = if (fold != null) {
            foldTask(fold, written)
        } else {
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

    private fun finish(compacting: Pending) {
        pending = null
        val next = file.beside(COMPACTING)
        val size: Long
        try {
            size = written(compacting.task).getOrThrow()
            if (size < 0) {
                compacted = end
                return
            }
            appendSince(next, size, compacting)
            if (!next.renameTo(file)) throw IOException("cannot replace $file")
        } catch (e: IOException) {
            next.delete()
            onError(e)
            compacted = end
            return
        }
        reopen(compactedTo = size, wrote = !compacting.refusedSince || compacting.since.isNotEmpty())
    }

    private fun appendSince(next: LogFile, size: Long, compacting: Pending) {
        // one gone would be made anew, empty, and renamed over the log
        if (next.length() != size) throw IOException("$next is not the $size bytes of counts written")
        // synced before the rename: a power cut after it would otherwise lose what was appended meanwhile
        next.append().use { stream ->
            compacting.since.forEach { stream.write(it) }
            stream.sync()
        }
    }

    /**
     * Waits for [task]'s file, running it here if no thread has started it.
     *
     * @return the length it wrote, -1 for nothing to compact, or its [IOException]
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

    /** Appends from the end of the log just renamed in; [wrote]: the disk took bytes since the last refusal, so it is [writing] again. */
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

    private fun writeCounts(seeding: Boolean = false) {
        replaceFile(file, file.beside(COMPACTING)) { next ->
            next.create().use { raw ->
                val stream = BufferedOutputStream(raw)
                countsTo(stream, seeding)
                stream.flush()
            }
        }
    }

    private fun countsTo(stream: OutputStream, seeding: Boolean) {
        stream.write(format.header())
        var none = true
        counts {
            if (fits(it)) {
                stream.write(it)
                none = false
            }
        }
        // a header alone is seeded again: meant for a seed that found nothing, not for all forgotten
        if (none && !seeding) stream.write(format.record(EMPTIED) {})
    }

    /** Writes and syncs [to], or leaves no [to] at all. */
    private fun writeSynced(to: LogFile, write: (OutputStream) -> Unit) {
        try {
            to.create().use {
                write(it)
                it.sync()
            }
        } catch (e: IOException) {
            // a copy aside cut short is no copy, and each start would leave another
            to.delete()
            throw e
        }
    }

    override fun close() {
        closed = true
        pending?.let { finish(it) }
        out?.close()
        out = null
    }

    /** Told to [onError] when the log or part of it was kept aside unread: learning goes on from what read, and the user is to be told. */
    class KeptAside(message: String) : IOException(message)

    companion object {
        const val DEFAULT_COMPACT_AT = 1L shl 20
        const val UNREADABLE = ".unreadable"

        /** Whether [name] is a copy of log [log] kept aside: `<log>.unreadable`, then `.unreadable.1`, ...; the one rule for the app and the clears. */
        fun isAside(log: String, name: String): Boolean {
            if (!name.startsWith(log + UNREADABLE)) return false
            val rest = name.substring(log.length + UNREADABLE.length)
            return rest.isEmpty() || (rest.length > 1 && rest[0] == '.' && rest.drop(1).all { it in '0'..'9' })
        }
        private const val COMPACTING = ".compacting"
        // no fields, and a type no format uses: they count from 1 and skip unknown types
        private const val EMPTIED: Byte = 0
    }
}
