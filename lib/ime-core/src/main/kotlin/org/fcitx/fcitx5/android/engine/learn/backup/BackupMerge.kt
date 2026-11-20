/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.backup

import org.fcitx.fcitx5.android.engine.learn.DayCounts
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.BarRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.BatchRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Cut
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.DateRow
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
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Unknown
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.WordUseRow
import org.fcitx.fcitx5.android.engine.learn.LearnState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Merges a backup into the phone's learning: counts take the per-day max, user settings (a first, a pin)
 * prefer the phone's, lists are unioned, so merging the same backup again changes nothing.
 *
 * Another install's typing days are shifted so its last is the phone's ([offset]), stored per install
 * ([ORIGIN]) so a later restore of the same backup lands on the same days. Seqs are not shifted: the merged
 * [LearnState.nextSeq] is past both. The backup's rows are applied first, so the phone's win on a shared key.
 */
object BackupMerge {

    /** Snapshot row: the day offset for install `i64 id`, `i32 offset`; kept as an unknown row so folds keep it. */
    const val ORIGIN: Byte = 73

    /** [phone] and [backup] as one state restored at [minute]; pins of [blocked] texts are dropped, and new import batches are numbered past [lastBatch]. */
    fun merge(phone: LearnState, backup: LearnState, from: Long, sameInstall: Boolean, minute: Int, blocked: Set<String> = emptySet(), lastBatch: Int = 0): LearnState {
        val origins = origins(phone)
        val shift = if (sameInstall) 0 else offset(phone, backup, origins[from])
        val today = maxOf(phone.day, backup.day + shift)
        // each side swept to its day first, so a day the phone swept is not counted again
        val pinBlocked = { r: LearnRecord -> r is PinRow && r.text in blocked }
        val ours = swept(phone.snapshot(phone.cut), today).filterNot { isOrigin(it) || pinBlocked(it) }
        val theirs = swept(
            renumbered(ours, backup.snapshot(backup.cut).filterNot { isOrigin(it) || pinBlocked(it) }, maxOf(phone.imports.lastId, lastBatch))
                .map { shifted(it, shift) },
            today,
        )
        val merged = LearnState()
        // a snapshot as of the restore; seq past both
        merged.apply(
            Head(
                cut = minute,
                lastMinute = maxOf(phone.lastMinute, minute),
                day = today,
                localDay = phone.localDay,
                nextSeq = maxOf(phone.nextSeq, backup.nextSeq),
                restoredMinute = minute,
            ),
        )
        for (row in rows(ours, theirs)) {
            if (row is Head || row === Cut) continue
            LearnLog.applyOrKeep(merged, row)
        }
        merged.sweepTo(today)
        for ((id, offset) in origins + if (sameInstall) emptyMap() else mapOf(from to shift)) merged.kept += origin(id, offset)
        return merged
    }

    private fun swept(rows: List<LearnRecord>, today: Int): List<LearnRecord> {
        val s = LearnState()
        for (r in rows) LearnLog.applyOrKeep(s, if (r is Head) r.copy(day = today) else r)
        s.sweepTo(today)
        return s.snapshot(s.cut)
    }

    /** The day shift for [backup]: [stored] for its install unless that puts it past the phone's last day, else its last day made the phone's. */
    fun offset(phone: LearnState, backup: LearnState, stored: Int?): Int =
        stored?.takeIf { backup.day + it <= phone.day } ?: (phone.day - backup.day)

    fun origins(state: LearnState): Map<Long, Int> = state.kept.filter(::isOrigin).associate { r ->
        DataInputStream(ByteArrayInputStream((r as Unknown).fields)).run { readLong() to readInt() }
    }

    private fun isOrigin(r: LearnRecord) = r is Unknown && r.type == ORIGIN

    /** An install whose days are the log's own (offset 0): written by the install that opens it. */
    fun lineage(install: Long): LearnRecord = origin(install, 0)

    private fun origin(id: Long, offset: Int) = Unknown(
        ORIGIN,
        ByteArrayOutputStream().also { DataOutputStream(it).run { writeLong(id); writeInt(offset) } }.toByteArray(),
    )

    // the backup's batches as the phone's (same file, day and size match); others get new ids past [last],
    // so a phone batch undone and later restored does not lose its words
    private fun renumbered(ours: List<LearnRecord>, theirs: List<LearnRecord>, last: Int): List<LearnRecord> {
        val batches = ours.filterIsInstance<BatchRow>()
        if (theirs.none { it is BatchRow }) return theirs
        val known = batches.associate { Triple(it.file, it.localDay, it.count) to it.id }
        var next = maxOf(batches.maxOfOrNull { it.id } ?: 0, last) + 1
        val ids = HashMap<Int, Int>()
        for (b in theirs.filterIsInstance<BatchRow>()) ids[b.id] = known[Triple(b.file, b.localDay, b.count)] ?: next++
        return theirs.map { r ->
            when (r) {
                is BatchRow -> BatchRow(ids.getValue(r.id), r.file, r.localDay, r.count)
                is ImportedRow -> ids[r.batch]?.let { ImportedRow(it, r.order, r.text, r.reading) } ?: r
                else -> r
            }
        }
    }

    // batches first, then the phone's words, then the backup's: no word before its batch, and a shared
    // word keeps the phone's batch
    private fun rows(ours: List<LearnRecord>, theirs: List<LearnRecord>): List<LearnRecord> {
        val out = ArrayList<LearnRecord>()
        (ours + theirs).filterIsInstance<BatchRow>().distinctBy { it.id }.forEach(out::add)
        out += ours.filterIsInstance<ImportedRow>()
        out += theirs.filterIsInstance<ImportedRow>()
        val phoneByKey = LinkedHashMap<Any, LearnRecord>()
        val phoneUnkeyed = ArrayList<LearnRecord>()
        for (r in ours) if (!imports(r)) key(r)?.let { phoneByKey[it] = r } ?: phoneUnkeyed.add(r)
        for (r in theirs.filterNot(::imports)) {
            val k = key(r)
            if (k == null) {
                // unkeyed rows (kept, a later build's): both sides', duplicates once
                if (r !in phoneUnkeyed) out += r
                continue
            }
            val ourRow = phoneByKey.remove(k)
            out += if (ourRow == null) r else both(ourRow, r)
        }
        out += phoneByKey.values
        out += phoneUnkeyed
        return out
    }

    private fun imports(r: LearnRecord) = r is BatchRow || r is ImportedRow

    // every type named, no else: a new row type must decide how it merges
    private fun key(r: LearnRecord): Any? = when (r) {
        is StatRow -> listOf("stat", r.code, r.words.text, r.words.reading)
        is WordUseRow -> "use" to (r.text to r.reading)
        is FirstRow -> "first" to (r.code to r.prev)
        is PendingRow -> "pending" to r.text
        is MineRow -> "mine" to r.text
        is PairRow -> listOf("pair", r.prev, r.next, r.nextReading)
        is PhraseRow -> "phrase" to r.text
        is BarRow -> "bar" to r.take
        is EnWordRow -> "en" to r.word
        is EnPairRow -> "enPair" to (r.prev to r.word)
        is TableWordRow -> listOf("table", r.im, r.code, r.text)
        is PinRow -> "pin" to r.code
        is DateRow -> "date" to r.day
        // equal rows kept once, others from both sides
        is NoPredictRow, is TableBlockRow, is Unknown, is BatchRow, is ImportedRow, is Head, Cut -> null
        // events: never in a snapshot
        is LearnRecord.Commit, is LearnRecord.Undo, is LearnRecord.Pin, is LearnRecord.Forget, is LearnRecord.PhraseForget, is LearnRecord.NoPredict,
        is LearnRecord.TableOrderReset, is LearnRecord.TableBlock, is LearnRecord.TableForget, is LearnRecord.EnCommit, is LearnRecord.EnForget,
        is LearnRecord.EnAdd, is LearnRecord.EnCorrect, is LearnRecord.Restored, is LearnRecord.ImportBatch,
        is LearnRecord.Imported, is LearnRecord.ImportUndo, is LearnRecord.Absorbed,
        -> null
    }

    private fun both(ours: LearnRecord, theirs: LearnRecord): LearnRecord = when (ours) {
        is StatRow -> (theirs as StatRow).let {
            StatRow(ours.code, ours.words, max(ours.uses, it.uses), max(ours.corrections, it.corrections), maxOf(ours.older, it.older), ours.firstSeq)
        }
        is WordUseRow -> WordUseRow(ours.text, ours.reading, max(ours.uses, (theirs as WordUseRow).uses))
        is MineRow -> (theirs as MineRow).value.let { t ->
            val o = ours.value
            // another reading is another word: the phone's
            if (t.reading != o.reading) {
                ours
            } else {
                val earlier = if (t.since < o.since) t else o
                MineRow(ours.text, o.copy(since = earlier.since, sinceSeq = earlier.sinceSeq, lastUsedDay = maxOf(o.lastUsedDay, t.lastUsedDay), lastUsedLocalDay = maxOf(o.lastUsedLocalDay, t.lastUsedLocalDay)))
            }
        }
        is PairRow -> (theirs as PairRow).let { PairRow(ours.prev, ours.next, ours.nextReading, maxOf(ours.count, it.count), maxOf(ours.lastDay, it.lastDay)) }
        is PhraseRow -> (theirs as PhraseRow).let { PhraseRow(ours.text, maxOf(ours.count, it.count), maxOf(ours.lastDay, it.lastDay)) }
        is BarRow -> (theirs as BarRow).let { BarRow(ours.take, maxOf(ours.count, it.count), maxOf(ours.lastDay, it.lastDay)) }
        is EnWordRow -> (theirs as EnWordRow).let { EnWordRow(ours.word, ours.flags or it.flags, max(ours.uses, it.uses)) }
        is EnPairRow -> (theirs as EnPairRow).let { EnPairRow(ours.prev, ours.word, maxOf(ours.count, it.count), maxOf(ours.lastDay, it.lastDay)) }
        is TableWordRow -> TableWordRow(ours.im, ours.code, ours.text, minOf(ours.since, (theirs as TableWordRow).since))
        else -> ours
    }

    private fun max(a: DayCounts, b: DayCounts): DayCounts {
        val out = a.copy()
        for (i in 0 until b.size) {
            val day = b.day(i)
            val ours = (0 until a.size).firstOrNull { a.day(it) == day }?.let(a::count) ?: 0
            if (b.count(i) > ours) out.add(day, b.count(i) - ours)
        }
        return out
    }

    private fun DayCounts.shifted(by: Int) = if (by == 0) copy() else DayCounts().also { c -> for (i in 0 until size) c.add(day(i) + by, count(i)) }

    // every type named, no else: a new row with a typing day must not keep the other install's days
    private fun shifted(r: LearnRecord, by: Int): LearnRecord = if (by == 0) r else when (r) {
        is StatRow -> StatRow(r.code, r.words, r.uses.shifted(by), r.corrections.shifted(by), r.older, r.firstSeq)
        is WordUseRow -> WordUseRow(r.text, r.reading, r.uses.shifted(by))
        is FirstRow -> FirstRow(r.code, r.prev, r.value.copy(lastDay = r.value.lastDay + by))
        is PendingRow -> PendingRow(r.text, r.value.copy(firstDay = r.value.firstDay + by))
        is MineRow -> MineRow(r.text, r.value.copy(since = r.value.since + by, lastUsedDay = r.value.lastUsedDay + by))
        is PairRow -> PairRow(r.prev, r.next, r.nextReading, r.count, r.lastDay + by)
        is BarRow -> BarRow(r.take, r.count, r.lastDay + by)
        is PhraseRow -> PhraseRow(r.text, r.count, r.lastDay + by)
        is EnWordRow -> EnWordRow(r.word, r.flags, r.uses.shifted(by))
        is EnPairRow -> EnPairRow(r.prev, r.word, r.count, r.lastDay + by)
        is TableWordRow -> TableWordRow(r.im, r.code, r.text, r.since + by)
        is DateRow -> DateRow(r.day + by, r.localDay)
        // no typing day (a batch's day is a calendar one), or unread
        is Head, Cut, is PinRow, is NoPredictRow, is TableBlockRow, is BatchRow, is ImportedRow, is Unknown -> r
        is LearnRecord.Commit, is LearnRecord.Undo, is LearnRecord.Pin, is LearnRecord.Forget, is LearnRecord.PhraseForget, is LearnRecord.NoPredict,
        is LearnRecord.TableOrderReset, is LearnRecord.TableBlock, is LearnRecord.TableForget, is LearnRecord.EnCommit, is LearnRecord.EnForget,
        is LearnRecord.EnAdd, is LearnRecord.EnCorrect, is LearnRecord.Restored, is LearnRecord.ImportBatch,
        is LearnRecord.Imported, is LearnRecord.ImportUndo, is LearnRecord.Absorbed,
        -> r
    }

    /** Words to add and block so the phone's lists take in the backup's; a word on both of the backup's lists is blocked. */
    fun lists(phoneAdded: Collection<ListedWord>, phoneBlocked: Collection<ListedWord>, added: Collection<ListedWord>, blocked: Collection<ListedWord>): Pair<List<ListedWord>, List<ListedWord>> {
        val ours = (phoneAdded + phoneBlocked).mapTo(HashSet()) { it.text to it.code }
        val block = blocked.filter { (it.text to it.code) !in ours }.distinct()
        val blocking = blocked.mapTo(HashSet()) { it.text to it.code }
        val add = added.filter { (it.text to it.code).let { k -> k !in ours && k !in blocking } }.distinct()
        return add to block
    }
}
