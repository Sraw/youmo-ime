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

/**
 * Keeps what the user taught an input method in [file], a log of [format]: [open] replays it into
 * memory, then each record [append]ed is one write. Once the log passes [compactAt] bytes (or
 * twice what it last compacted to) it is rewritten as the counts alone, into a new file renamed
 * over the old, so a crash leaves one or the other whole. That happens on the thread that learns,
 * at most every few thousand records.
 *
 * A log cut short loses its last record, which is cut from the file so appends go on from a
 * record's end. A file of another format or version is kept aside as `<name>.unreadable` rather
 * than overwritten, and learning starts afresh.
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
     * cannot be written fails the open.
     */
    fun open(replay: (Byte, DataInputStream) -> Unit, counts: ((ByteArray) -> Unit) -> Unit, seed: (() -> Unit)? = null) {
        check(out == null && !closed) { "opened already" }
        this.counts = counts
        if (file.exists()) {
            val bytes = file.readBytes()
            when {
                format.hasHeader(bytes) -> {
                    val end = format.read(bytes, replay)
                    if (end < bytes.size) RandomAccessFile(file, "rw").use { it.setLength(end.toLong()) }
                }
                // killed before the header was all written: nothing to keep
                format.header().copyOf(bytes.size).contentEquals(bytes) -> file.writeBytes(format.header())
                else -> moveAside()
            }
        }
        val seeding = seed != null && (!file.exists() || file.length() <= RecordFormat.HEADER_SIZE)
        if (seeding) {
            seed?.invoke()
            writeCounts()
        }
        if (!file.exists()) file.writeBytes(format.header())
        end = file.length()
        // a seed already written as counts: no call to write them again yet
        if (seeding) compacted = end
        out = openAppend(file)
        if (tooLong()) compact()
    }

    // never over an earlier file kept aside: that may be a newer version's log, still wanted
    private fun moveAside() {
        var aside = File(file.path + UNREADABLE)
        var n = 1
        while (aside.exists()) aside = File(file.path + UNREADABLE + "." + n++)
        if (!file.renameTo(aside)) throw IOException("cannot move $file aside")
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
    private fun writeCounts() {
        val next = File(file.path + COMPACTING)
        try {
            FileOutputStream(next).use { raw ->
                val stream = BufferedOutputStream(raw)
                stream.write(format.header())
                counts { stream.write(it) }
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
    }
}
