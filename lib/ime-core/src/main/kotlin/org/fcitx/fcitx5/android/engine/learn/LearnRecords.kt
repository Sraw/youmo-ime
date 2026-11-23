/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Absorbed
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.BarRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.BatchRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Cut
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.DateRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnAdd
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnCorrect
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnCommit
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnForget
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnPairRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnWordRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.FirstRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Forget
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Head
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.ImportBatch
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.ImportUndo
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Imported
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.ImportedRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.MineRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.NoPredict
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.NoPredictRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PairRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PendingRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PhraseForget
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PhraseRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Pin
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PinRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Restored
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.StatRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableBlock
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableForget
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableOrderReset
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableBlockRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableWordRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Undo
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Unknown
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.WordUseRow
import org.fcitx.fcitx5.android.engine.store.RecordFormat
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * `learn.log`'s records: events as they happen, and a snapshot's rows once compacted, between HEAD and CUT.
 *
 * The format stays version 1 for good: [RecordFormat.hasHeader] wants the exact version, and a build
 * reading another moves the file aside. A change is a new type, or fields appended to a type's end, read
 * only while bytes remain ([intOr], [utfOr]): an older reader stops before them, as [RecordFormat.read]
 * does not require reading a record to its end. An appended field may be omitted when empty only while
 * it is the type's last: [Commit.continued] is written only when present, so the next field appended to
 * a commit needs it always written first, "" too. Types are never reused. Allocated: events 1–17 (17
 * [TABLE_FORGET]) and 28 ([EN_CORRECT]); events 18–27, 29–31 free (31 the last event type); rows 32–49
 * (49 [PHRASE_ROW]), 65 ([TABLE_BLOCK_ROW]) and 80 ([DATE_ROW]); 70–79 the backup file's
 * ([backup.LearnBackup]; 73 [backup.BackupMerge.ORIGIN] the one a log may also hold, 74 its END, 75–79
 * reserved for the file), never a log's. Free rows: 50–64, 66–69, 81 on. Only snapshot rows name a kind
 * by id ([KIND], scoped to the snapshot); events, and rows an [Undo] restores, name it in full. A fold
 * keeps the ids of the snapshot it read ([SnapshotWriter]), so a later build's row naming a kind by id
 * still names the same one.
 *
 * A record this build cannot read (a later type, or a known type holding something unknown: an [Undo]
 * restoring a row of a new type) is kept as it is ([Unknown]) and written back so: an older build neither
 * loses nor misreads it. A fold writes a kept event after the snapshot, so a later build applies it after
 * what the snapshot holds, including the commits after it (a later build's [Undo] restoring rows over them).
 */
object LearnRecords {
    val FORMAT = RecordFormat("FXLR", 1)

    const val COMMIT: Byte = 1
    const val UNDO: Byte = 2
    const val PIN: Byte = 3
    const val FORGET: Byte = 4
    const val NO_PREDICT: Byte = 5
    const val TABLE_ORDER_RESET: Byte = 6
    const val EN_COMMIT: Byte = 7
    const val EN_FORGET: Byte = 8
    const val EN_ADD: Byte = 9
    const val RESTORED: Byte = 10
    const val IMPORT_BATCH: Byte = 11
    const val IMPORTED: Byte = 12
    const val IMPORT_UNDO: Byte = 13
    const val ABSORBED: Byte = 14

    /** The most rows per [ABSORBED] record: that many short rows stay well within a record's 64 KB (LearnLog halves longer ones). */
    const val ABSORBED_ROWS = 200
    const val TABLE_BLOCK: Byte = 15

    /** [PhraseForget]. */
    const val PHRASE_FORGET: Byte = 16

    /** [TableForget]. */
    const val TABLE_FORGET: Byte = 17

    // 18–27 free (see the class's allocation)
    const val EN_CORRECT: Byte = 28

    /** A snapshot's rows are of this type and after: its [HEAD] first, its [CUT] last. */
    const val HEAD: Byte = 32
    const val KIND: Byte = 33
    const val STAT_ROW: Byte = 34
    const val FIRST_OF: Byte = 35
    const val PENDING_ROW: Byte = 36
    const val MINE_ROW: Byte = 37
    const val PAIR_ROW: Byte = 38
    const val PIN_ROW: Byte = 39
    const val BAR_ROW: Byte = 40
    const val EN_WORD_ROW: Byte = 41
    const val EN_PAIR_ROW: Byte = 42
    const val TABLE_WORD_ROW: Byte = 43
    const val NO_PREDICT_ROW: Byte = 44
    const val CUT: Byte = 45
    const val BATCH_ROW: Byte = 46
    const val IMPORTED_ROW: Byte = 47
    const val WORD_USE_ROW: Byte = 48
    /** [PhraseRow]. */
    const val PHRASE_ROW: Byte = 49
    /** A table word the user blocked: added and removed by TableBlock (15) as they block and unblock it, kept by a snapshot. */
    const val TABLE_BLOCK_ROW: Byte = 65
    const val DATE_ROW: Byte = 80

    /** [record] as one log record; an event, or a row that names no kind (see [SnapshotWriter]). */
    fun encode(record: LearnRecord): ByteArray = encode(record) { error("a snapshot row needs its kinds: $record") }

    private fun encode(record: LearnRecord, kind: (String) -> Int): ByteArray = when (record) {
        is Unknown -> FORMAT.record(record.type) { write(record.fields) }
        else -> FORMAT.record(typeOf(record)) { fields(record, kind) }
    }

    private fun typeOf(record: LearnRecord): Byte = when (record) {
        is Commit -> COMMIT
        is Undo -> UNDO
        is Pin -> PIN
        is Forget -> FORGET
        is PhraseForget -> PHRASE_FORGET
        is NoPredict -> NO_PREDICT
        is TableOrderReset -> TABLE_ORDER_RESET
        is TableBlock -> TABLE_BLOCK
        is TableForget -> TABLE_FORGET
        is EnCommit -> EN_COMMIT
        is EnForget -> EN_FORGET
        is EnAdd -> EN_ADD
        is EnCorrect -> EN_CORRECT
        is Restored -> RESTORED
        is ImportBatch -> IMPORT_BATCH
        is Imported -> IMPORTED
        is ImportUndo -> IMPORT_UNDO
        is Absorbed -> ABSORBED
        else -> rowType(record)
    }

    private fun rowType(record: LearnRecord): Byte = when (record) {
        is Head -> HEAD
        is StatRow -> STAT_ROW
        is FirstRow -> FIRST_OF
        is PendingRow -> PENDING_ROW
        is MineRow -> MINE_ROW
        is PairRow -> PAIR_ROW
        is PinRow -> PIN_ROW
        is BarRow -> BAR_ROW
        is EnWordRow -> EN_WORD_ROW
        is EnPairRow -> EN_PAIR_ROW
        is TableWordRow -> TABLE_WORD_ROW
        is NoPredictRow -> NO_PREDICT_ROW
        is Cut -> CUT
        is BatchRow -> BATCH_ROW
        is ImportedRow -> IMPORTED_ROW
        is WordUseRow -> WORD_USE_ROW
        else -> laterRowType(record)
    }

    // rows added after the first format, and a later build's
    private fun laterRowType(record: LearnRecord): Byte = when (record) {
        is DateRow -> DATE_ROW
        is TableBlockRow -> TABLE_BLOCK_ROW
        is PhraseRow -> PHRASE_ROW
        is Unknown -> record.type
        else -> error("not a row: $record")
    }

    private fun DataOutputStream.fields(r: LearnRecord, kind: (String) -> Int) {
        when (r) {
            is Commit -> {
                stamp(r.seq, r.minute, r.day, r.localDay)
                writeByte(r.flags)
                writeShort(r.rows.size)
                r.rows.forEach { row(it) }
                writeByte(r.takes.size)
                r.takes.forEach { take(it) }
                // appended later, only when present: an older reader stops before it. The last field a commit may omit:
                // one appended after it needs it always written ("" too), or a reader could not tell them apart
                if (r.continued.isNotEmpty()) writeUTF(r.continued)
            }
            is Undo -> {
                stamp(r.undoneSeq, r.minute, r.day, r.localDay)
                val undone = encode(r.undone)
                // the record whole, length, CRC and all: a reader not knowing its type still knows its end
                writeInt(undone.size)
                write(undone)
                writeShort(r.befores.size)
                r.befores.forEach { before(it) }
            }
            is Pin -> {
                writeInt(r.minute)
                writeInt(r.day)
                code(r.code)
                writeUTF(r.text)
                writeUTF(r.reading)
                writeBoolean(r.on)
            }
            is Forget -> {
                writeInt(r.minute)
                writeInt(r.day)
                writeUTF(r.text)
                writeBoolean(!r.older)
            }
            is PhraseForget -> {
                writeInt(r.minute)
                writeUTF(r.text)
            }
            is NoPredict -> {
                writeInt(r.minute)
                writeUTF(r.prev)
                writeUTF(r.text)
                writeBoolean(r.on)
            }
            is TableOrderReset, is TableBlock, is TableForget -> table(r)
            is EnCommit -> {
                stamp(r.seq, r.minute, r.day, r.localDay)
                writeByte(r.flags)
                writeUTF(r.prev)
                writeUTF(r.word)
                writeUTF(r.typed)
                writeByte(r.how)
            }
            is EnForget -> {
                writeInt(r.minute)
                writeUTF(r.word)
            }
            is EnAdd -> {
                writeInt(r.minute)
                writeUTF(r.word)
                writeBoolean(r.on)
            }
            is EnCorrect -> {
                writeInt(r.minute)
                writeUTF(r.word)
            }
            is Restored -> writeInt(r.minute)
            is ImportBatch, is Imported, is ImportUndo, is Absorbed -> bulk(r)
            else -> rowFields(r, kind)
        }
    }

    private fun DataOutputStream.table(r: LearnRecord) {
        when (r) {
            is TableOrderReset -> {
                writeInt(r.minute)
                writeUTF(r.im)
            }
            is TableBlock -> {
                writeInt(r.minute)
                listOf(r.im, r.code, r.text).forEach(::writeUTF)
                writeBoolean(r.on)
            }
            is TableForget -> {
                writeInt(r.minute)
                listOf(r.im, r.code, r.text).forEach(::writeUTF)
            }
            else -> error("not one: $r")
        }
    }

    // events of what comes in at once: an import, an older build's files taken in
    private fun DataOutputStream.bulk(r: LearnRecord) {
        when (r) {
            is ImportBatch -> batch(r.id, r.file, r.localDay, r.count)
            is Imported -> imported(r.batch, r.order, r.text, r.reading)
            is ImportUndo -> writeInt(r.batch)
            is Absorbed -> {
                writeInt(r.minute)
                for (rows in listOf(r.added, r.removed)) {
                    writeInt(rows.size)
                    // rows that name no kind
                    rows.forEach { writeByte(rowType(it).toInt()); rowFields(it) { error("an absorbed row names no kind: $it") } }
                }
                // only where set: every other one's bytes as before
                if (r.remembered) writeBoolean(true)
            }
            else -> error("not one: $r")
        }
    }

    private fun DataOutputStream.rowFields(r: LearnRecord, kind: (String) -> Int) {
        when (r) {
            is Head -> {
                writeInt(r.cut)
                writeInt(r.lastMinute)
                writeInt(r.day)
                writeInt(r.localDay)
                writeLong(r.nextSeq)
                writeInt(r.convertedDay)
                writeInt(r.restoredMinute)
                writeUTF(r.convertedTables)
            }
            is StatRow -> {
                writeShort(kind(r.code.kind))
                writeUTF(r.code.keys)
                words(r.words)
                days(r.uses)
                days(r.corrections)
                writeInt(r.older)
                writeLong(r.firstSeq)
            }
            is FirstRow -> {
                writeShort(kind(r.code.kind))
                writeUTF(r.code.keys)
                writeUTF(r.prev)
                first(r.value)
            }
            is PendingRow -> {
                writeUTF(r.text)
                pending(r.value)
            }
            is MineRow -> {
                writeUTF(r.text)
                mine(r.value)
            }
            is PairRow -> pair(r)
            is PinRow -> {
                writeShort(kind(r.code.kind))
                writeUTF(r.code.keys)
                writeUTF(r.text)
                writeUTF(r.reading)
            }
            is BarRow -> {
                take(r.take)
                writeInt(r.count)
                writeInt(r.lastDay)
            }
            is EnWordRow -> {
                writeUTF(r.word)
                writeByte(r.flags)
                days(r.uses)
            }
            is EnPairRow -> {
                writeUTF(r.prev)
                writeUTF(r.word)
                writeInt(r.count)
                writeInt(r.lastDay)
            }
            is TableWordRow -> {
                writeUTF(r.im)
                writeUTF(r.code)
                writeUTF(r.text)
                writeInt(r.since)
            }
            is NoPredictRow -> {
                writeUTF(r.prev)
                writeUTF(r.text)
            }
            is Cut -> Unit
            is BatchRow -> batch(r.id, r.file, r.localDay, r.count)
            is ImportedRow -> imported(r.batch, r.order, r.text, r.reading)
            is WordUseRow -> wordUse(r)
            else -> laterRowFields(r)
        }
    }

    // as [laterRowType]
    private fun DataOutputStream.laterRowFields(r: LearnRecord) {
        when (r) {
            is DateRow -> {
                writeInt(r.day)
                writeInt(r.localDay)
            }
            is TableBlockRow -> listOf(r.im, r.code, r.text).forEach(::writeUTF)
            is PhraseRow -> {
                writeUTF(r.text)
                writeInt(r.count)
                writeInt(r.lastDay)
            }
            is Unknown -> write(r.fields)
            else -> error("not a row: $r")
        }
    }

    private fun DataOutputStream.stamp(seq: Long, minute: Int, day: Int, localDay: Int) {
        writeLong(seq)
        writeInt(minute)
        writeInt(day)
        writeInt(localDay)
    }

    private fun DataOutputStream.code(code: Code) {
        writeUTF(code.kind)
        writeUTF(code.keys)
    }

    private fun DataOutputStream.words(words: Words?) {
        val list = words?.list.orEmpty()
        writeShort(list.size)
        for (w in list) {
            writeUTF(w.text)
            writeUTF(w.reading)
        }
    }

    private fun DataOutputStream.row(row: Row) {
        writeByte(row.flags)
        code(row.code)
        writeUTF(row.prev)
        words(row.words)
        writeByte(row.how.code)
        writeByte(row.origin)
        writeUTF(row.shown)
    }

    private fun DataOutputStream.take(take: Take) {
        writeUTF(take.layout)
        writeUTF(take.digits)
        writeUTF(take.syllable)
    }

    private fun DataOutputStream.days(days: DayCounts) {
        writeShort(days.size)
        for (i in 0 until days.size) {
            writeInt(days.day(i))
            writeShort(days.count(i))
        }
    }

    private fun DataOutputStream.first(first: First) {
        words(first.first)
        writeShort(first.set.size)
        first.set.forEach { words(it) }
        writeInt(first.lastDay)
        writeLong(first.lastBy)
    }

    private fun DataOutputStream.pending(p: Pending) {
        writeUTF(p.reading)
        writeInt(p.firstDay)
        writeLong(p.lastBy)
    }

    private fun DataOutputStream.mine(m: Mine) {
        writeUTF(m.reading)
        writeByte(m.how)
        writeInt(m.since)
        writeLong(m.sinceSeq)
        writeInt(m.lastUsedDay)
        writeInt(m.lastUsedLocalDay)
        writeLong(m.lastBy)
    }

    private fun DataOutputStream.before(b: Before) {
        when (b) {
            is Before.OfFirst -> {
                writeByte(FIRST_OF.toInt())
                writeBoolean(b.value != null)
                code(b.code)
                writeUTF(b.prev)
                b.value?.let { first(it) }
            }
            is Before.OfPending -> {
                writeByte(PENDING_ROW.toInt())
                writeBoolean(b.value != null)
                writeUTF(b.text)
                b.value?.let { pending(it) }
            }
            is Before.OfMine -> {
                writeByte(MINE_ROW.toInt())
                writeBoolean(b.value != null)
                writeUTF(b.text)
                b.value?.let { mine(it) }
            }
        }
    }

    private fun DataOutputStream.pair(r: PairRow) {
        writeUTF(r.prev)
        writeUTF(r.next)
        writeUTF(r.nextReading)
        writeInt(r.count)
        writeInt(r.lastDay)
    }

    private fun DataOutputStream.wordUse(r: WordUseRow) {
        writeUTF(r.text)
        writeUTF(r.reading)
        days(r.uses)
    }

    private fun DataOutputStream.batch(id: Int, file: String, localDay: Int, count: Int) {
        writeInt(id)
        writeUTF(file)
        writeInt(localDay)
        writeInt(count)
    }

    private fun DataOutputStream.imported(batch: Int, order: Int, text: String, reading: String) {
        writeInt(batch)
        writeInt(order)
        writeUTF(text)
        writeUTF(reading)
    }

    /** Reads records as one log has them: the kinds a snapshot's rows name, as its [KIND] rows say. */
    class Reader {

        private val named = HashMap<Int, String>()

        /** The kinds the last snapshot read named, by id, for the next snapshot to keep (see [SnapshotWriter]). */
        val kinds: Map<Int, String> get() = named

        /** Null for a [KIND] row, which only names a kind for those after it. One that does not read is [Unknown], its fields as they are. */
        fun decode(type: Byte, input: DataInputStream): LearnRecord? {
            val fields = ByteArray(input.available()).also { input.readFully(it) }
            return try {
                DataInputStream(ByteArrayInputStream(fields)).use { if (type >= HEAD) row(type, it) else event(type, it) }
            } catch (_: java.io.IOException) {
                Unknown(type, fields)
            } catch (@Suppress("TooGenericExceptionCaught") _: RuntimeException) {
                Unknown(type, fields)
            }
        }

        private fun event(type: Byte, input: DataInputStream): LearnRecord = with(input) {
            when (type) {
                COMMIT -> Commit(
                    readLong(), readInt(), readInt(), readInt(), readUnsignedByte(),
                    List(readUnsignedShort()) { row() },
                    List(readUnsignedByte()) { take() },
                    utfOr(""),
                )
                UNDO -> {
                    val seq = readLong()
                    val minute = readInt()
                    val day = readInt()
                    val localDay = readInt()
                    val nested = ByteArray(readInt()).also { readFully(it) }
                    Undo(seq, minute, day, localDay, nested(nested), List(readUnsignedShort()) { before() })
                }
                PIN -> Pin(readInt(), readInt(), code(), readUTF(), readUTF(), readBoolean())
                FORGET -> Forget(readInt(), readInt(), readUTF(), older = available() == 0 || !readBoolean())
                NO_PREDICT -> NoPredict(readInt(), readUTF(), readUTF(), readBoolean())
                TABLE_ORDER_RESET -> TableOrderReset(readInt(), readUTF())
                EN_COMMIT -> EnCommit(readLong(), readInt(), readInt(), readInt(), readUnsignedByte(), readUTF(), readUTF(), readUTF(), readUnsignedByte())
                EN_FORGET -> EnForget(readInt(), readUTF())
                EN_ADD -> EnAdd(readInt(), readUTF(), readBoolean())
                RESTORED -> Restored(readInt())
                IMPORT_BATCH -> ImportBatch(readInt(), readUTF(), readInt(), readInt())
                IMPORTED -> Imported(readInt(), readInt(), readUTF(), readUTF())
                IMPORT_UNDO -> ImportUndo(readInt())
                ABSORBED -> Absorbed(readInt(), absorbed(input), absorbed(input), remembered = available() > 0 && readBoolean())
                else -> laterEvent(type, input)
            }
        }

        // events added after the first format, and a later build's
        private fun laterEvent(type: Byte, input: DataInputStream): LearnRecord = with(input) {
            when (type) {
                TABLE_BLOCK -> TableBlock(readInt(), readUTF(), readUTF(), readUTF(), readBoolean())
                PHRASE_FORGET -> PhraseForget(readInt(), readUTF())
                TABLE_FORGET -> TableForget(readInt(), readUTF(), readUTF(), readUTF())
                EN_CORRECT -> EnCorrect(readInt(), readUTF())
                else -> Unknown(type, ByteArray(available()).also { readFully(it) })
            }
        }

        private fun absorbed(input: DataInputStream) = List(input.readInt()) { checkNotNull(row(input.readByte(), input)) }

        private fun row(type: Byte, input: DataInputStream): LearnRecord? = with(input) {
            when (type) {
                HEAD -> {
                    // a snapshot starts: its kinds are its own
                    named.clear()
                    Head(readInt(), readInt(), readInt(), readInt(), readLong(), intOr(-1), intOr(-1), utfOr(""))
                }
                KIND -> {
                    named[readUnsignedShort()] = readUTF()
                    null
                }
                // firstSeq appended later: an older row has none
                STAT_ROW -> StatRow(Code(kind(), readUTF()), words(), days(), days(), readInt(), longOr(0))
                FIRST_OF -> FirstRow(Code(kind(), readUTF()), readUTF(), first())
                PENDING_ROW -> PendingRow(readUTF(), pending())
                MINE_ROW -> MineRow(readUTF(), mine())
                PAIR_ROW -> PairRow(readUTF(), readUTF(), readUTF(), readInt(), readInt())
                PIN_ROW -> PinRow(Code(kind(), readUTF()), readUTF(), readUTF())
                BAR_ROW -> BarRow(take(), readInt(), readInt())
                CUT -> Cut
                else -> otherRow(type, input)
            }
        }

        // the rows of English, the tables, imports and word uses
        private fun otherRow(type: Byte, input: DataInputStream): LearnRecord = with(input) {
            when (type) {
                EN_WORD_ROW -> EnWordRow(readUTF(), readUnsignedByte(), days())
                EN_PAIR_ROW -> EnPairRow(readUTF(), readUTF(), readInt(), readInt())
                TABLE_WORD_ROW -> TableWordRow(readUTF(), readUTF(), readUTF(), readInt())
                NO_PREDICT_ROW -> NoPredictRow(readUTF(), readUTF())
                BATCH_ROW -> BatchRow(readInt(), readUTF(), readInt(), readInt())
                IMPORTED_ROW -> ImportedRow(readInt(), readInt(), readUTF(), readUTF())
                WORD_USE_ROW -> WordUseRow(readUTF(), readUTF(), days())
                DATE_ROW -> DateRow(readInt(), readInt())
                TABLE_BLOCK_ROW -> TableBlockRow(readUTF(), readUTF(), readUTF())
                PHRASE_ROW -> PhraseRow(readUTF(), readInt(), readInt())
                else -> Unknown(type, ByteArray(available()).also { readFully(it) })
            }
        }

        private fun DataInputStream.kind(): String {
            val id = readUnsignedShort()
            return named[id] ?: throw java.io.IOException("kind $id not named")
        }
    }

    /** The record [bytes] holds whole (length, type, fields, CRC); one that does not read is kept as type and fields, to be written back as it was. */
    private fun nested(bytes: ByteArray): LearnRecord {
        decode(bytes)?.let { return it }
        if (bytes.size < NESTED_OVERHEAD) throw java.io.IOException("an undone record of ${bytes.size} bytes")
        return Unknown(bytes[4], bytes.copyOfRange(5, bytes.size - 4))
    }

    // a record's length before it, CRC after it, and its type
    private const val NESTED_OVERHEAD = 4 + 1 + 4

    private fun DataInputStream.intOr(default: Int) = if (available() > 0) readInt() else default
    private fun DataInputStream.utfOr(default: String) = if (available() > 0) readUTF() else default
    private fun DataInputStream.longOr(default: Long) = if (available() > 0) readLong() else default

    private fun DataInputStream.code() = Code(readUTF(), readUTF())

    private fun DataInputStream.words(): Words = Words(List(readUnsignedShort()) { Word(readUTF(), readUTF()) })

    private fun DataInputStream.row() = Row(readUnsignedByte(), code(), readUTF(), words(), readUnsignedByte().toChar(), readUnsignedByte(), readUTF())

    private fun DataInputStream.take() = Take(readUTF(), readUTF(), readUTF())

    private fun DataInputStream.days(): DayCounts {
        val days = DayCounts()
        repeat(readUnsignedShort()) { days.add(readInt(), readShort().toInt()) }
        return days
    }

    private fun DataInputStream.first(): First {
        val first = words().takeIf { it.list.isNotEmpty() }
        return First(first, List(readUnsignedShort()) { words() }, readInt(), readLong())
    }

    private fun DataInputStream.pending() = Pending(readUTF(), readInt(), readLong())

    private fun DataInputStream.mine() = Mine(readUTF(), readUnsignedByte(), readInt(), readLong(), readInt(), readInt(), readLong())

    private fun DataInputStream.before(): Before {
        val type = readByte()
        val present = readBoolean()
        return when (type) {
            FIRST_OF -> {
                val code = code()
                val prev = readUTF()
                Before.OfFirst(code, prev, if (present) first() else null)
            }
            PENDING_ROW -> {
                val text = readUTF()
                Before.OfPending(text, if (present) pending() else null)
            }
            MINE_ROW -> {
                val text = readUTF()
                Before.OfMine(text, if (present) mine() else null)
            }
            else -> throw java.io.IOException("a row of type $type to put back")
        }
    }

    /** Writes a snapshot's rows to [write], each kind named by a [KIND] row before the first row using it. */
    class SnapshotWriter(private val write: (ByteArray) -> Unit, private val kept: Map<Int, String> = emptyMap()) {
        private val kinds = HashMap<String, Int>()
        private var next = kept.keys.maxOrNull()?.plus(1) ?: 0

        fun write(record: LearnRecord) {
            write(encode(record) { tag -> kinds[tag] ?: next++.also { id -> kinds[tag] = id; write(kindRow(id, tag)) } })
            // the ids the folded snapshot had, named after its HEAD (which starts kinds anew), each its old kind
            if (record is Head) {
                for ((id, tag) in kept.entries.sortedBy { it.key }) {
                    if (tag in kinds) continue
                    kinds[tag] = id
                    write(kindRow(id, tag))
                }
            }
        }

        private fun kindRow(id: Int, tag: String) = FORMAT.record(KIND) {
            writeShort(id)
            writeUTF(tag)
        }
    }

    /** Decodes a whole log record, as [encode] gives it: for tests and the fold. */
    fun decode(bytes: ByteArray, reader: Reader = Reader()): LearnRecord? {
        var record: LearnRecord? = null
        FORMAT.read(FORMAT.header() + bytes) { type, input -> record = reader.decode(type, input) }
        return record
    }
}
