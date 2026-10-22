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
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Pin
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PinRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Restored
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.StatRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableBlock
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
 * `learn.log`'s records (impl/design-1a.md §1): events as they happen, and a snapshot's rows
 * once compacted, between a HEAD and a CUT.
 *
 * The format stays version 1 for good: [RecordFormat.hasHeader] wants the exact version, and a
 * build that reads another moves the file aside. A change is a new type, or fields appended to a
 * type's end, read only while some are left ([intOr], [utfOr]): an older reader stops before
 * them, as [RecordFormat.read] does not ask that a record be read to its end. Types are never
 * reused. Allocated: events 1–15 and 28 ([EN_CORRECT]); events 16–27, 29–31 free (31 the
 * last event type); rows 32–48, 65 ([TABLE_BLOCK_ROW]) and 80 ([DATE_ROW]); 70–79 the backup
 * file's ([backup.LearnBackup]; 73 [backup.BackupMerge.ORIGIN] the one a log may also hold, 74 its
 * END, 75–79 kept for the file), never a log's. Free rows: 49–64, 66–69, 81 on. Only snapshot
 * rows name a kind by its id ([KIND], scoped to the snapshot); events, and the rows an [Undo] puts
 * back, name it in full. A fold keeps the ids the snapshot it read had ([SnapshotWriter]), so a row
 * of a later build's that names a kind by id still names the same one.
 *
 * A record this build cannot read (a later build's type, or one of its types with what this one
 * does not know inside: an [Undo] putting back a row of a new type) is kept as it is ([Unknown])
 * and written again so: an older build neither loses nor misreads it. A fold writes a kept event
 * after the snapshot, so a later build that reads it applies it after what the snapshot holds,
 * the commits after it among them (an [Undo] of a later build's putting back rows over them).
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

    /** An [ABSORBED] record's rows at most: so many short rows stay well within a record's 64 KB (LearnLog halves longer ones). */
    const val ABSORBED_ROWS = 200
    const val TABLE_BLOCK: Byte = 15

    // 16–27 free (see the class's allocation)
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
    const val DATE_ROW: Byte = 80
    /** Step 2's (tables): a table word the user blocked, put in and taken out by TableBlock (15) as they block it and let it show again, and kept by a snapshot. */
    const val TABLE_BLOCK_ROW: Byte = 65

    /** [record] as one record of the log; an event, or a row that names no kind (see [SnapshotWriter]). */
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
        is NoPredict -> NO_PREDICT
        is TableOrderReset -> TABLE_ORDER_RESET
        is TableBlock -> TABLE_BLOCK
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

    // the rows the later streams added (impl/design-1a.md, "Added by later streams"), and a later build's
    private fun laterRowType(record: LearnRecord): Byte = when (record) {
        is DateRow -> DATE_ROW
        is TableBlockRow -> TABLE_BLOCK_ROW
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
            }
            is Undo -> {
                stamp(r.undoneSeq, r.minute, r.day, r.localDay)
                val undone = encode(r.undone)
                // the record whole, length, CRC and all: a reader that does not know its type still knows its end
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
            }
            is NoPredict -> {
                writeInt(r.minute)
                writeUTF(r.prev)
                writeUTF(r.text)
                writeBoolean(r.on)
            }
            is TableOrderReset -> {
                writeInt(r.minute)
                writeUTF(r.im)
            }
            is TableBlock -> {
                writeInt(r.minute)
                listOf(r.im, r.code, r.text).forEach(::writeUTF)
                writeBoolean(r.on)
            }
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

    // the events of what comes in at once: an import, an older build's files taken in
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

        /** The kinds the snapshot read last named, by id: for the next snapshot to keep them (see [SnapshotWriter]). */
        val kinds: Map<Int, String> get() = named

        /**
         * The record of [type] [input] holds; null for a [KIND] row, which only names a kind for
         * those after it. One that does not read is [Unknown], its fields as they are.
         */
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
                FORGET -> Forget(readInt(), readInt(), readUTF())
                NO_PREDICT -> NoPredict(readInt(), readUTF(), readUTF(), readBoolean())
                TABLE_ORDER_RESET -> TableOrderReset(readInt(), readUTF())
                EN_COMMIT -> EnCommit(readLong(), readInt(), readInt(), readInt(), readUnsignedByte(), readUTF(), readUTF(), readUTF(), readUnsignedByte())
                EN_FORGET -> EnForget(readInt(), readUTF())
                EN_ADD -> EnAdd(readInt(), readUTF(), readBoolean())
                RESTORED -> Restored(readInt())
                IMPORT_BATCH -> ImportBatch(readInt(), readUTF(), readInt(), readInt())
                IMPORTED -> Imported(readInt(), readInt(), readUTF(), readUTF())
                IMPORT_UNDO -> ImportUndo(readInt())
                ABSORBED -> Absorbed(readInt(), absorbed(input), absorbed(input))
                else -> laterEvent(type, input)
            }
        }

        // the events the later streams added (impl/design-1a.md, "Added by later streams"), and a later build's
        private fun laterEvent(type: Byte, input: DataInputStream): LearnRecord = with(input) {
            when (type) {
                TABLE_BLOCK -> TableBlock(readInt(), readUTF(), readUTF(), readUTF(), readBoolean())
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
                // firstSeq appended in 1b: a row of 1a's has none
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

        // the rows of English, the tables, imports and the words' uses
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
                else -> Unknown(type, ByteArray(available()).also { readFully(it) })
            }
        }

        private fun DataInputStream.kind(): String {
            val id = readUnsignedShort()
            return named[id] ?: throw java.io.IOException("kind $id not named")
        }
    }

    /**
     * The record [bytes] holds whole (length, type, fields, CRC); one that does not read is kept
     * as its type and fields, to be written again as it was.
     */
    private fun nested(bytes: ByteArray): LearnRecord {
        decode(bytes)?.let { return it }
        if (bytes.size < NESTED_OVERHEAD) throw java.io.IOException("an undone record of ${bytes.size} bytes")
        return Unknown(bytes[4], bytes.copyOfRange(5, bytes.size - 4))
    }

    // a record's length before it and CRC after it, and its type
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

    /**
     * Writes a snapshot's rows to [write], each kind named by a [KIND] row before the first row
     * that has it.
     */
    class SnapshotWriter(private val write: (ByteArray) -> Unit, private val kept: Map<Int, String> = emptyMap()) {
        private val kinds = HashMap<String, Int>()
        private var next = kept.keys.maxOrNull()?.plus(1) ?: 0

        fun write(record: LearnRecord) {
            write(encode(record) { tag -> kinds[tag] ?: next++.also { id -> kinds[tag] = id; write(kindRow(id, tag)) } })
            // the ids the snapshot folded had, named after its HEAD (which starts the kinds anew), each the kind it was
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

    /** Decodes the record of [bytes] (a log's whole record, as [encode] gives it): for tests and the fold. */
    fun decode(bytes: ByteArray, reader: Reader = Reader()): LearnRecord? {
        var record: LearnRecord? = null
        FORMAT.read(FORMAT.header() + bytes) { type, input -> record = reader.decode(type, input) }
        return record
    }
}
