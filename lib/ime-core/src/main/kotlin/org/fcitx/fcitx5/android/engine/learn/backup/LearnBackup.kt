/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.backup

import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnRecords
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.store.RecordFormat
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.UTFDataFormatException
import java.nio.ByteBuffer
import java.security.MessageDigest

/** A backup's first record: where and when it came from. */
data class BackupHead(
    val time: Long,
    /** The typing day reached: what another install's days are shifted by. */
    val day: Int,
    /** 0 if unknown (a bare `learn.log`, an older build's files). */
    val installId: Long,
    val kind: Int,
    val version: String = "",
) {
    companion object {
        const val AUTO = 0

        /** Saved by the user, or into the settings zip. */
        const val SAVED = 1

        const val BEFORE_RESTORE = 2
    }
}

/** [code]: syllables joined by `'`, as `words.added` has it. */
data class ListedWord(val text: String, val code: String) {
    fun line(blocked: Boolean) = "${if (blocked) "!" else ""}$text $code"
}

/** The address bar's learned data, carried opaquely. */
data class AddressData(val counts: String, val addresses: String)

/** [origin]: the install its days belong to (the head's, else derived from the bytes); [addresses] null in older backups; [voiceAlways]: added words voice always puts in. */
class Backup(
    val head: BackupHead?,
    val state: LearnState,
    val added: List<ListedWord>,
    val blocked: List<ListedWord>,
    val origin: Long = head?.installId ?: 0L,
    val addresses: AddressData? = null,
    val voiceAlways: List<String> = emptyList(),
)

/**
 * A learning backup: `learn.log` records under their own header ([FORMAT], `FXLB` 1): [HEAD], the
 * learning snapshot, the [ADDED] and [BLOCKED] word lists, [VOICE_ALWAYS], the address bar's data, and last exactly one
 * [END] holding the file's length, so a truncated or spliced file does not read. Evolves like the log.
 */
object LearnBackup {
    val FORMAT = RecordFormat("FXLB", 1)

    // record types 70–79
    const val HEAD: Byte = 70
    const val ADDED: Byte = 71
    const val BLOCKED: Byte = 72

    const val ADDRESS_COUNTS: Byte = 75

    const val ADDRESSES: Byte = 76

    /** An added word voice always puts in: its text. An older build drops it as a later build's type. */
    const val VOICE_ALWAYS: Byte = 77

    /** The last record: the file's length in bytes (an Int), this record included. */
    const val END: Byte = 74
    // internal for tests that cut a file at it
    internal const val END_SIZE = 4 + 1 + 4 + 4

    const val EXTENSION = ".fxlb"

    /** [state], lists and [addresses] as a backup headed by [head]; the pre-restore copy ([BackupHead.BEFORE_RESTORE]) takes every row, others only [backedUp] ones. */
    fun bytes(
        head: BackupHead,
        state: LearnState,
        added: List<ListedWord>,
        blocked: List<ListedWord>,
        addresses: AddressData? = null,
        voiceAlways: Collection<String> = emptyList(),
    ): ByteArray {
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
        // a later build's row of this file's range is dropped (ORIGIN aside): END must be the only 74, and last
        LearnLog.writeSnapshot(state, state.cut, { record: ByteArray ->
            if (record[4] !in HEAD..LAST || record[4] == BackupMerge.ORIGIN) out.write(record)
        }, keeps = if (head.kind == BackupHead.BEFORE_RESTORE) { _ -> true } else ::backedUp)
        for (w in added) out.write(word(ADDED, w))
        for (w in blocked) out.write(word(BLOCKED, w))
        for (t in voiceAlways) out.write(FORMAT.record(VOICE_ALWAYS) { writeUTF(t) })
        if (addresses != null) {
            // the address book's caps keep them under writeUTF's 64 KB; past it they are left out, not the whole backup
            try {
                val counts = FORMAT.record(ADDRESS_COUNTS) { writeUTF(addresses.counts) }
                val list = FORMAT.record(ADDRESSES) { writeUTF(addresses.addresses) }
                out.write(counts)
                out.write(list)
            } catch (_: UTFDataFormatException) {
            }
        }
        out.write(FORMAT.record(END) { writeInt(out.size() + END_SIZE) })
        return out.toByteArray()
    }

    /** A whole phrase used once is left out (most never recur, and the file is handed around); the phone still counts it. */
    fun backedUp(r: LearnRecord): Boolean = r !is LearnRecord.PhraseRow || r.count >= MIN_PHRASE_USES

    const val MIN_PHRASE_USES = 2

    private fun word(type: Byte, w: ListedWord) = FORMAT.record(type) {
        writeUTF(w.text)
        writeUTF(w.code)
    }

    fun isBackup(bytes: ByteArray) = FORMAT.hasHeader(bytes) || LearnRecords.FORMAT.hasHeader(bytes)

    /**
     * The backup [bytes] hold, or a bare `learn.log` (no head, no lists). The last record read is noted before
     * parsing and the length starts at -1, as [RecordFormat.read] skips unparsable records: an unreadable END is none.
     *
     * @throws IOException if they are neither, or a backup is not whole or does not end in [END]
     */
    fun read(bytes: ByteArray): Backup {
        if (LearnRecords.FORMAT.hasHeader(bytes)) return Backup(null, LearnLog.replay(bytes), emptyList(), emptyList(), origin(bytes))
        if (!FORMAT.hasHeader(bytes)) throw IOException("not a learning backup")
        var head: BackupHead? = null
        val added = ArrayList<ListedWord>()
        val blocked = ArrayList<ListedWord>()
        val always = ArrayList<String>()
        val listed = mapOf(ADDED to added, BLOCKED to blocked)
        var counts: String? = null
        var addresses: String? = null
        val state = LearnState()
        val reader = LearnRecords.Reader()
        // a backup is written whole: one ending early was cut, never merged in part
        var lastType: Byte = 0
        var ended = -1
        val whole = FORMAT.read(bytes) { type, input ->
            lastType = type
            when (type) {
                HEAD -> head = head(input)
                ADDED, BLOCKED -> listed.getValue(type) += ListedWord(input.readUTF(), input.readUTF())
                VOICE_ALWAYS -> always += input.readUTF()
                ADDRESS_COUNTS -> counts = input.readUTF()
                ADDRESSES -> addresses = input.readUTF()
                END -> ended = input.readInt()
                // this file's range: a later build's type, except ORIGIN, kept as the log keeps unknown types
                in HEAD..LAST -> if (type == BackupMerge.ORIGIN) reader.decode(type, input)?.let { LearnLog.applyOrKeep(state, it) }
                else -> reader.decode(type, input)?.let { LearnLog.applyOrKeep(state, it) }
            }
        }
        val ends = lastType == END && ended == bytes.size
        if (whole != bytes.size || head == null || !ends) {
            throw IOException("a learning backup not whole: ${bytes.size} bytes, whole to $whole, end $ended, head ${head != null}")
        }
        val book = if (counts == null && addresses == null) null else AddressData(counts.orEmpty(), addresses.orEmpty())
        return Backup(head, state, added, blocked, addresses = book, voiceAlways = always)
    }

    private fun head(input: DataInputStream) = BackupHead(input.readLong(), input.readInt(), input.readLong(), input.readUnsignedByte(), input.readUTF())

    /** Reads only the first record; null without a head. */
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

    /** [state] written as a whole log, [kinds] named as [replay] read them. */
    fun writeLog(out: OutputStream, state: LearnState, kinds: Map<Int, String> = emptyMap()) {
        out.write(LearnRecords.FORMAT.header())
        LearnLog.writeSnapshot(state, state.cut, out::write, kinds)
    }

    /** As [LearnLog.replay], plus the kinds its snapshot named, for [writeLog] to keep. */
    fun replay(bytes: ByteArray): Pair<LearnState, Map<Int, String>> {
        val state = LearnState()
        val reader = LearnRecords.Reader()
        LearnRecords.FORMAT.read(bytes) { type, input -> if (type != LearnLog.EMPTIED) reader.decode(type, input)?.let { LearnLog.applyOrKeep(state, it) } }
        return state to reader.kinds
    }

    /** An install id derived from [bytes] for a headless file: stable, never 0 nor negative. */
    fun origin(vararg bytes: ByteArray): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        bytes.forEach(digest::update)
        return (ByteBuffer.wrap(digest.digest()).long and Long.MAX_VALUE).takeIf { it != 0L } ?: 1L
    }

    private const val LAST: Byte = 79
}
