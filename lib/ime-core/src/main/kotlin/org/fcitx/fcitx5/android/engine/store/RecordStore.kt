/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.store

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Keeps what the user taught an input method in [file], a log of [format]: [open] replays it into
 * memory, then each record [append]ed is one write. Once the log passes [compactAt] bytes (or
 * twice what it last compacted to) it is rewritten as the counts alone, into a new file renamed
 * over the old, so a crash leaves one or the other whole. That happens on the thread that learns,
 * at most every few thousand records.
 *
 * A log cut short loses its last record, which is cut from the file so appends go on from a
 * record's end. A file of another format or version is kept aside as `<name>.unreadable` rather
 * than overwritten, and learning starts afresh. A log that stops reading anywhere but in a last
 * record cut short, as a torn append leaves it, is damaged: it is kept aside whole as well, and
 * goes on from what read.
 *
 * Writing never fails the learning that asked for it: text the user picked must reach the app
 * whatever the disk does. A failed write is reported to [onError] and cut back off the file, so
 * later appends do not land behind torn bytes; learning goes on in memory regardless.
 */
class RecordStore internal constructor(
    private val file: File,
    private val format: RecordFormat,
    private val compactAt: Long,
    private val onError: (IOException) -> Unit,
    // a seam for tests: a disk that fails part-way through a write
    private val openAppend: (File) -> OutputStream,
) : Closeable {
    constructor(
        file: File,
        format: RecordFormat,
        compactAt: Long = DEFAULT_COMPACT_AT,
        onError: (IOException) -> Unit = {},
    ) : this(file, format, compactAt, onError, { FileOutputStream(it, true) })

    private var out: OutputStream? = null
    private var closed = false
    // the end of the last record known written whole
    private var end = 0L
    // the counts alone may outgrow compactAt: then compact again only once the log doubles
    private var compacted = 0L
    private var counts: ((ByteArray) -> Unit) -> Unit = {}

    /**
     * Hands each record of the log to [replay], and appends from now on. Once only. [counts]
     * writes what is in memory as records, the log compacted.
     *
     * A log with nothing in it (none yet, or a header alone) is first filled by [seed], from what
     * another input method learned, and written as the counts it leaves, all or nothing: until
     * that is written whole, the log stays empty and the next open seeds again. A seed that
     * cannot be written fails the open. A log compacted to no counts is not empty: what the user
     * had forgotten is not seeded back.
     */
    fun open(replay: (Byte, DataInputStream) -> Unit, counts: ((ByteArray) -> Unit) -> Unit, seed: (() -> Unit)? = null) {
        check(out == null && !closed) { "opened already" }
        this.counts = counts
        if (file.exists()) {
            val bytes = file.readBytes()
            when {
                format.hasHeader(bytes) -> {
                    var unread = 0
                    var cause: Exception? = null
                    val end = format.read(bytes, skipped = {
                        unread++
                        cause = cause ?: it
                    }) { type, input -> if (type != EMPTIED) replay(type, input) }
                    if (unread > 0) onError(IOException("$file: $unread unreadable records skipped", cause))
                    if (end < bytes.size) cut(bytes, end)
                }
                // killed before the header was all written: nothing to keep
                format.header().copyOf(bytes.size).contentEquals(bytes) -> file.writeBytes(format.header())
                else -> moveAside()
            }
        }
        val seeding = seed != null && (!file.exists() || file.length() <= RecordFormat.HEADER_SIZE)
        if (seeding) {
            seed?.invoke()
            writeCounts(seeding = true)
        }
        if (!file.exists()) file.writeBytes(format.header())
        end = file.length()
        // a seed already written as counts: no call to write them again yet
        if (seeding) compacted = end
        out = openAppend(file)
        if (tooLong()) compact()
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
            FileOutputStream(aside).use {
                it.write(bytes)
                it.fd.sync()
            }
            onError(IOException("$file damaged at byte $end: kept whole as ${aside.name}"))
        }
        RandomAccessFile(file, "rw").use {
            it.setLength(end.toLong())
            if (damaged && end <= RecordFormat.HEADER_SIZE) {
                it.seek(end.toLong())
                it.write(format.record(EMPTIED) {})
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
        onError(IOException("$file is not a log this reads: kept as ${aside.name}"))
    }

    // never over an earlier file kept aside: that may be a newer version's log, still wanted
    private fun aside(): File {
        var aside = File(file.path + UNREADABLE)
        var n = 1
        while (aside.exists()) aside = File(file.path + UNREADABLE + "." + n++)
        return aside
    }

    private fun tooLong() = end > maxOf(compactAt, 2 * compacted)

    /** Appends [record], unless not open, or longer than a reader would take for a record. */
    fun append(record: ByteArray) {
        val stream = out ?: return
        // the reader would take it for garbage and drop the rest of the log with it
        if (record.size > RecordFormat.MAX_RECORD) return
        try {
            stream.write(record)
            end += record.size
        } catch (e: IOException) {
            onError(e)
            recover()
            return
        }
        if (tooLong()) compact()
    }

    /** After a failed write: cuts the file back to its last whole record, or stops writing. */
    private fun recover() {
        try {
            out?.close()
            RandomAccessFile(file, "rw").use { it.setLength(end) }
            out = openAppend(file)
        } catch (e: IOException) {
            onError(e)
            out = null
        }
    }

    /** Rewrites the log as the counts. */
    internal fun compact() {
        check(out != null) { "not open" }
        try {
            writeCounts()
        } catch (e: IOException) {
            onError(e)
            // the log as it was still holds everything; try again once it has doubled
            compacted = end
            return
        }
        // the old stream writes to the file renamed over, now unlinked
        try {
            out?.close()
        } catch (e: IOException) {
            onError(e)
        }
        end = file.length()
        compacted = end
        out = try {
            openAppend(file)
        } catch (e: IOException) {
            onError(e)
            null
        }
    }

    /** Writes the counts into a new file, renamed over [file] once it is all on disk. */
    private fun writeCounts(seeding: Boolean = false) {
        val next = File(file.path + COMPACTING)
        try {
            FileOutputStream(next).use { raw ->
                val stream = BufferedOutputStream(raw)
                stream.write(format.header())
                var none = true
                counts {
                    stream.write(it)
                    none = false
                }
                // a header alone is seeded again: meant for a seed that found nothing, not for all forgotten
                if (none && !seeding) stream.write(format.record(EMPTIED) {})
                stream.flush()
                raw.fd.sync()
            }
            if (!next.renameTo(file)) throw IOException("cannot replace $file")
        } catch (e: IOException) {
            next.delete()
            throw e
        }
    }

    override fun close() {
        closed = true
        out?.close()
        out = null
    }

    companion object {
        const val DEFAULT_COMPACT_AT = 1L shl 20
        const val UNREADABLE = ".unreadable"
        private const val COMPACTING = ".compacting"
        // of no fields, and a type no format's records have: they count from 1, and skip types they do not know
        private const val EMPTIED: Byte = 0
    }
}
