/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Keeps a [UserModel] in [file], a [UserLog]: [open] replays it, then each sentence learned is
 * appended as one write. Once the log passes [compactAt] bytes (or twice what it last compacted
 * to) it is rewritten as the counts alone, into a new file renamed over the old, so a crash
 * leaves one or the other whole. That happens on the thread that learns, at most every few
 * thousand sentences.
 *
 * A log cut short loses its last record, which is cut from the file so appends go on from a
 * record's end. A file of another format or version is kept aside as `<name>.unreadable` rather
 * than overwritten, and learning starts afresh.
 *
 * Writing never fails the learning that asked for it: text the user picked must reach the app
 * whatever the disk does. A failed write is reported to [onError] and cut back off the file, so
 * later appends do not land behind torn bytes; learning goes on in memory regardless.
 */
class UserStore internal constructor(
    private val file: File,
    private val model: UserModel,
    private val compactAt: Long,
    private val onError: (IOException) -> Unit,
    // a seam for tests: a disk that fails part-way through a write
    private val openAppend: (File) -> OutputStream,
) : Closeable {
    constructor(
        file: File,
        model: UserModel,
        compactAt: Long = DEFAULT_COMPACT_AT,
        onError: (IOException) -> Unit = {},
    ) : this(file, model, compactAt, onError, { FileOutputStream(it, true) })

    private var out: OutputStream? = null
    private var closed = false
    // the end of the last record known written whole
    private var end = 0L
    // the counts alone may outgrow compactAt: then compact again only once the log doubles
    private var compacted = 0L

    /**
     * Loads the log into the model, and keeps what the model learns from now on. Once only.
     *
     * A log with nothing learned in it (none yet, or a header alone) is first filled by [seed],
     * from what another input method learned, and written as the counts it leaves, all or
     * nothing: until that is written whole, the log stays empty and the next open seeds again.
     * A seed that cannot be written fails the open.
     */
    fun open(seed: ((UserModel) -> Unit)? = null) {
        check(out == null && !closed) { "opened already" }
        if (file.exists()) {
            val bytes = file.readBytes()
            when {
                UserLog.hasHeader(bytes) -> {
                    val end = UserLog.read(bytes, model)
                    if (end < bytes.size) RandomAccessFile(file, "rw").use { it.setLength(end.toLong()) }
                }
                // killed before the header was all written: nothing to keep
                UserLog.header().copyOf(bytes.size).contentEquals(bytes) -> file.writeBytes(UserLog.header())
                else -> moveAside()
            }
        }
        val seeding = seed != null && (!file.exists() || file.length() <= UserLog.header().size)
        if (seeding) {
            seed?.invoke(model)
            writeCounts()
        }
        if (!file.exists()) file.writeBytes(UserLog.header())
        end = file.length()
        // a seed already written as counts: no call to write them again yet
        if (seeding) compacted = end
        out = openAppend(file)
        model.journal = UserModel.Journal { prev, sentence -> append(prev, sentence) }
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

    private fun append(prev: Entry?, sentence: List<Entry>) {
        val stream = out ?: return
        val record = UserLog.sentence(prev, sentence)
        // the reader would take it for garbage and drop the rest of the log with it
        if (record.size > UserLog.MAX_RECORD) return
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

    /** Rewrites the log as the model's counts. */
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

    /** Writes the model's counts into a new file, renamed over [file] once it is all on disk. */
    private fun writeCounts() {
        val next = File(file.path + COMPACTING)
        try {
            FileOutputStream(next).use { raw ->
                val stream = BufferedOutputStream(raw)
                stream.write(UserLog.header())
                model.forEachCount(
                    { entry, count -> stream.write(UserLog.word(entry, count)) },
                    { first, second, count -> stream.write(UserLog.pair(first, second, count)) },
                )
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
        model.journal = null
        out?.close()
        out = null
    }

    companion object {
        const val DEFAULT_COMPACT_AT = 1L shl 20
        const val UNREADABLE = ".unreadable"
        private const val COMPACTING = ".compacting"
    }
}
