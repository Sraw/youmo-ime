/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.backup

import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecords
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.store.RecordFormat
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Where a backup came from and when (U-100's confirmation names both): its first record. */
data class BackupHead(
    /** Epoch milliseconds it was made at. */
    val time: Long,
    /** The typing day the learning had reached ([LearnState.day]): what another install's days are shifted by. */
    val day: Int,
    /** The install that made it ([Backups.installId]); 0 for not known (a bare `learn.log`, an older build's files). */
    val installId: Long,
    val kind: Int,
    /** The app's version that made it, for the user to tell backups apart. */
    val version: String = "",
) {
    companion object {
        /** Made every few typing days (U-106). */
        const val AUTO = 0

        /** Saved by the user where they can see it, or into the settings' zip. */
        const val SAVED = 1

        /** The phone's learning as it was before a restore, for 退回恢复前 (C38). */
        const val BEFORE_RESTORE = 2
    }
}

/** A word of the lists the user keeps by hand: [code] its syllables apart by `'`, as `words.added` has it. */
data class ListedWord(val text: String, val code: String) {
    /** As the user dictionary's import reads it: `你好 ni'hao`, `!你好 ni'hao` blocked. */
    fun line(blocked: Boolean) = "${if (blocked) "!" else ""}$text $code"
}

/**
 * A backup read: its [head] (null for a bare log), the learning [state], the words added and
 * blocked; [origin], the install its days are of (BackupMerge.ORIGIN): the head's, else one told
 * by the file's bytes ([LearnBackup.origin]), so two older builds' files are not one install's.
 */
class Backup(
    val head: BackupHead?,
    val state: LearnState,
    val added: List<ListedWord>,
    val blocked: List<ListedWord>,
    val origin: Long = head?.installId ?: 0L,
)

/**
 * A learning backup (PLAN §2.9, impl/streams/D-design.md §1): `learn.log`'s records under a
 * header of their own ([FORMAT], `FXLB` 1), so a file the user picks is told apart from a log, a
 * zip or a word list. A [HEAD] record, then the learning as a snapshot ([LearnLog.writeSnapshot]:
 * 不再联想, pins and whatever rows a later build keeps included), then the words added by hand
 * ([ADDED]) and blocked ([BLOCKED]), which live in their own lists, not the log. Never the clipboard.
 * Last, always and once, an [END] holding the file's length in bytes, itself included: a file cut
 * at a record's boundary, or one missing a record or given one more, does not read (WD-10d). A
 * file without it, a draft build's included, is refused.
 *
 * Evolves as the log does: version 1 for good, new record types or fields appended; one this
 * build does not know is skipped, or kept with the snapshot's rows.
 */
object LearnBackup {
    val FORMAT = RecordFormat("FXLB", 1)

    // stream D's record types, 70–79 (impl/streams/D-backup.md)
    const val HEAD: Byte = 70
    const val ADDED: Byte = 71
    const val BLOCKED: Byte = 72

    /** The last record: the file's length in bytes (an Int), this record included. */
    const val END: Byte = 74
    // the END record's whole size, framed: internal for the tests that cut a file at it
    internal const val END_SIZE = 4 + 1 + 4 + 4

    /** The file name a backup is saved under, by the user or in the settings' zip. */
    const val EXTENSION = ".fxlb"

    /** [state] and the lists as a backup's bytes, headed by [head]. */
    fun bytes(head: BackupHead, state: LearnState, added: List<ListedWord>, blocked: List<ListedWord>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(FORMAT.header())
        out.write(
            FORMAT.record(HEAD) {
                writeLong(head.time)
                writeInt(head.day)
                writeLong(head.installId)
                writeByte(head.kind)
                writeUTF(head.version)
            },
        )
        // a kept row of this file's range (a later build's, one per write) is dropped, ORIGIN aside:
        // END must be the only 74, and last, or the file would not read
        LearnLog.writeSnapshot(state, state.cut, { record: ByteArray ->
            if (record[4] !in HEAD..LAST || record[4] == BackupMerge.ORIGIN) out.write(record)
        })
        for (w in added) out.write(word(ADDED, w))
        for (w in blocked) out.write(word(BLOCKED, w))
        out.write(FORMAT.record(END) { writeInt(out.size() + END_SIZE) })
        return out.toByteArray()
    }

    private fun word(type: Byte, w: ListedWord) = FORMAT.record(type) {
        writeUTF(w.text)
        writeUTF(w.code)
    }

    /** Whether [bytes] start as a backup or a learning log does: what [read] takes. */
    fun isBackup(bytes: ByteArray) = FORMAT.hasHeader(bytes) || LearnRecords.FORMAT.hasHeader(bytes)

    /**
     * The backup [bytes] hold, or a bare `learn.log` (a zip of an earlier build has one), which
     * has no head and no lists.
     *
     * The last record read is noted before it is parsed, and the length starts at -1, as
     * [RecordFormat.read] skips a record that fails to parse: an END that does not read is not one.
     *
     * @throws IOException if they are neither, or a backup is not whole or does not end in [END]
     */
    fun read(bytes: ByteArray): Backup {
        if (LearnRecords.FORMAT.hasHeader(bytes)) return Backup(null, LearnLog.replay(bytes), emptyList(), emptyList(), origin(bytes))
        if (!FORMAT.hasHeader(bytes)) throw IOException("not a learning backup")
        var head: BackupHead? = null
        val added = ArrayList<ListedWord>()
        val blocked = ArrayList<ListedWord>()
        val state = LearnState()
        val reader = LearnRecords.Reader()
        // a log stops at a record cut short (the app killed while writing), but a backup is written
        // whole: one that ends before its last byte was cut (a copy broken off), never merged in part (WD-10d)
        var lastType: Byte = 0
        var ended = -1
        val whole = FORMAT.read(bytes) { type, input ->
            lastType = type
            when (type) {
                HEAD -> head = head(input)
                ADDED -> added += ListedWord(input.readUTF(), input.readUTF())
                BLOCKED -> blocked += ListedWord(input.readUTF(), input.readUTF())
                END -> ended = input.readInt()
                // this file's own range: a later build's type, not one of the log's; but ORIGIN, the log's
                // own row of the range, kept as the log keeps a type it does not know (review-int-records M1)
                in HEAD..LAST -> if (type == BackupMerge.ORIGIN) reader.decode(type, input)?.let { LearnLog.applyOrKeep(state, it) }
                else -> reader.decode(type, input)?.let { LearnLog.applyOrKeep(state, it) }
            }
        }
        val ends = lastType == END && ended == bytes.size
        if (whole != bytes.size || head == null || !ends) {
            throw IOException("a learning backup not whole: ${bytes.size} bytes, whole to $whole, end $ended, head ${head != null}")
        }
        return Backup(head, state, added, blocked)
    }

    private fun head(input: DataInputStream) = BackupHead(input.readLong(), input.readInt(), input.readLong(), input.readUnsignedByte(), input.readUTF())

    /** The head of the backup in [input], read no further than its first record; null for a file of no head. */
    fun head(input: InputStream): BackupHead? {
        val start = ByteArray(RecordFormat.HEADER_SIZE + 4)
        DataInputStream(input).use { data ->
            data.readFully(start)
            if (!FORMAT.hasHeader(start)) return null
            val length = ByteBuffer.wrap(start, RecordFormat.HEADER_SIZE, 4).int
            if (length !in 1..RecordFormat.MAX_RECORD) return null
            val record = ByteArray(length + 4)
            data.readFully(record)
            var head: BackupHead? = null
            FORMAT.read(start + record) { type, fields -> if (type == HEAD) head = head(fields) }
            return head
        }
    }

    /** [state] written to [out] as a whole log: its header, then the state as a snapshot, [kinds] named as [replay] read them. */
    fun writeLog(out: OutputStream, state: LearnState, kinds: Map<Int, String> = emptyMap()) {
        out.write(LearnRecords.FORMAT.header())
        LearnLog.writeSnapshot(state, state.cut, out::write, kinds)
    }

    /** What the log [bytes] (header and records) say, as [LearnLog.replay], and the kinds its snapshot named: for [writeLog] to keep. */
    fun replay(bytes: ByteArray): Pair<LearnState, Map<Int, String>> {
        val state = LearnState()
        val reader = LearnRecords.Reader()
        LearnRecords.FORMAT.read(bytes) { type, input -> if (type != LearnLog.EMPTIED) reader.decode(type, input)?.let { LearnLog.applyOrKeep(state, it) } }
        return state to reader.kinds
    }

    /** An install id for a file of no head, told by [bytes]: the same file the same, never 0 (no known install) nor negative. */
    fun origin(vararg bytes: ByteArray): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        bytes.forEach(digest::update)
        return (ByteBuffer.wrap(digest.digest()).long and Long.MAX_VALUE).takeIf { it != 0L } ?: 1L
    }

    private const val LAST: Byte = 79
}
