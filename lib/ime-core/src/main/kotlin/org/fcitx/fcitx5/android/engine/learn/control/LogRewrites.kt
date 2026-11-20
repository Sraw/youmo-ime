/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.control

import org.fcitx.fcitx5.android.engine.learn.DayCounts
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Absorbed
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.BarRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.BatchRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Cut
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.DateRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnCommit
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnPairRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnWordRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.FirstRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Head
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.ImportedRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.MineRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.NoPredictRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PairRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PendingRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PhraseRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PinRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.StatRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableBlockRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableWordRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Undo
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Unknown
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.WordUseRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecords
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.backup.BackupMerge
import org.fcitx.fcitx5.android.engine.store.RecordFormat
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.OutputStream

/** How the user's acts rewrite the learning log: bytes in, what is kept out; run under `LearnLog.rewrite`. */
internal object LogRewrites {

    /** 清掉最近: [log] without commits at or after [cut], their undos and the words tables remembered then; the snapshot, other user acts (a word deleted stays so) and unknown records stay. False if nothing to take out. */
    fun clearSince(log: ByteArray, cut: Int, to: OutputStream): Boolean {
        val spans = spans(log)
        val reader = LearnRecords.Reader()
        val records = spans.map { decode(log, it, reader) }
        val keep = BooleanArray(spans.size) { true }
        // a taken-back English correction is a user act, not typing: what it set stays
        val kept = arrayOfNulls<ByteArray>(spans.size)
        val gone = HashSet<Long>()
        for (i in records.indexOfFirst { it === Cut } + 1 until records.size) {
            val r = records[i]
            val seq = when {
                r is Commit && r.minute >= cut -> r.seq
                r is EnCommit && r.minute >= cut -> r.seq.also { if (r.how == EnCommit.REVERTED) kept[i] = neverCorrected(r) }
                else -> null
            }
            if (seq != null) gone += seq
            // in the log after the commit it takes back, so that one is known by now
            keep[i] = seq == null && !(r is Undo && r.undoneSeq in gone) && !remembered(r, cut)
        }
        if (keep.all { it }) return false
        to.write(log, 0, RecordFormat.HEADER_SIZE)
        for (i in spans.indices) {
            if (keep[i]) write(log, spans[i], to) else kept[i]?.let(to::write)
        }
        return true
    }

    // a table's word remembered at or after [cut]: a word made then is no longer the user's
    private fun remembered(r: LearnRecord?, cut: Int) =
        r is Absorbed && r.minute >= cut && r.removed.isEmpty() && r.added.isNotEmpty() && r.added.all { it is TableWordRow }

    // the flag a reverted English commit set, without the commit
    private fun neverCorrected(c: EnCommit) =
        LearnRecords.encode(Absorbed(c.minute, listOf(EnWordRow(c.word, EnWordRow.NEVER_CORRECT, DayCounts())), emptyList()))

    /** [log] without its last records if they are [records] (the chip's 撤销); false, nothing written, otherwise. */
    fun dropLast(log: ByteArray, records: List<LearnRecord>, to: OutputStream): Boolean {
        val spans = spans(log)
        if (records.isEmpty() || spans.size < records.size) return false
        val from = spans.size - records.size
        // acts are events, which name no kind: a reader of their own
        val reader = LearnRecords.Reader()
        for (i in records.indices) if (decode(log, spans[from + i], reader) != records[i]) return false
        to.write(log, 0, RecordFormat.HEADER_SIZE)
        for (i in 0 until from) write(log, spans[i], to)
        return true
    }

    /**
     * 清空学到的: the snapshot rows that stay. Deliberate user acts stay (own words with uses zeroed, pins,
     * predictions off, a table's saved words and learned order, imports, English flags) and HEAD, so typing days
     * and seqs go on; learned counts go, as does whatever this build does not know. Restore offsets
     * ([BackupMerge.ORIGIN]) stay till [all] (「全部清空」), which clears everything.
     */
    fun cleared(state: LearnState, cut: Int, all: Boolean): List<LearnRecord> = state.snapshot(cut).mapNotNull { r ->
        when (r) {
            is Head, is Cut -> r
            is StatRow -> r.takeIf { !all && isTable(r.code.kind) }
            is FirstRow -> r.takeIf { !all && isTable(r.code.kind) }
            is PendingRow, is PairRow, is BarRow, is EnPairRow, is WordUseRow, is DateRow, is PhraseRow -> null
            is EnWordRow -> if (all || r.flags == 0) null else EnWordRow(r.word, r.flags, DayCounts())
            // restore offsets: rows they moved may stay, and the same backup restored again must land on the same days
            is Unknown -> r.takeIf { it.type == BackupMerge.ORIGIN && !all }
            // the user's own acts: only 全部清空 clears them. Every type named, no else: a new row must decide here
            is MineRow, is PinRow, is NoPredictRow, is TableBlockRow, is TableWordRow, is BatchRow, is ImportedRow -> r.takeIf { !all }
            // events: never in a snapshot
            is Commit, is Undo, is LearnRecord.Pin, is LearnRecord.Forget, is LearnRecord.PhraseForget, is LearnRecord.NoPredict,
            is LearnRecord.TableOrderReset, is LearnRecord.TableBlock, is LearnRecord.TableForget, is EnCommit, is LearnRecord.EnForget,
            is LearnRecord.EnAdd, is LearnRecord.EnCorrect, is LearnRecord.Restored, is LearnRecord.ImportBatch,
            is LearnRecord.Imported, is LearnRecord.ImportUndo, is Absorbed,
            -> r.takeIf { !all }
        }
    }

    private fun isTable(kind: String) = kind.startsWith(TABLE)

    private val TABLE = Kinds.table("")

    // each record's start (shifted up) and its end after the CRC
    private fun spans(log: ByteArray): List<Long> {
        val spans = ArrayList<Long>()
        // an emptied mark is RecordStore's, not a record: skipped, as LearnLog's walk does
        LearnRecords.FORMAT.spans(log) { start, end ->
            if (log[start + 4] != LearnLog.EMPTIED) spans += (start.toLong() shl 32) or end.toLong()
        }
        return spans
    }

    private fun write(log: ByteArray, span: Long, to: OutputStream) {
        val start = (span ushr 32).toInt()
        to.write(log, start, span.toInt() - start)
    }

    // one that does not read is kept as it is; null for a kind's name
    private fun decode(log: ByteArray, span: Long, reader: LearnRecords.Reader): LearnRecord? {
        val start = (span ushr 32).toInt()
        val end = span.toInt()
        return reader.decode(log[start + 4], DataInputStream(ByteArrayInputStream(log, start + 5, end - start - 9)))
    }
}
