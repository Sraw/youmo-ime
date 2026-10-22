/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.learn.LearnRecord.BarRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.BatchRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Cut
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
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableOrderReset
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableBlock
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableBlockRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableWordRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Undo
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Unknown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** [LearnRecords]: every record as written is read back the same; what a later build adds is read past or kept. */
class LearnRecordsTest {

    private val commit = Commit(
        7, 29_874_000, 3, 20_746, Commit.NO_PREDICT,
        listOf(
            Row(Row.PICK or Row.CORRECTED, Code("t9:abc,def", "64"), "我", Words(listOf(word("你", "ni"), word("好", "hao"))), 'B', Row.NONE, "拟好"),
            row("zai", "再", "zai", prev = "你好"),
        ),
        listOf(Take("abc,def", "94", "zhi")),
    )

    private val events: List<LearnRecord> = listOf(
        commit,
        Undo(7, 29_874_001, 3, 20_746, commit, listOf(
            Before.OfFirst(Code("qp", "ni"), "", First(Words.of("拟", "ni"), listOf(Words.of("你", "ni")), 2, 5)),
            Before.OfFirst(Code("qp", "ni"), "我", null),
            Before.OfPending("拟再", Pending("ni zai", 1, 4)),
            Before.OfPending("你在", null),
            Before.OfMine("汪小明", Mine("wang xiao ming", Mine.FORMED, 2, 6, 3, 20_746, 6)),
            Before.OfMine("小明", null),
        )),
        Pin(29_874_002, 3, Code("qp", "dz"), "地址", "di zhi", true),
        Forget(29_874_003, 3, "拟"),
        NoPredict(29_874_004, "你", "好", false),
        TableOrderReset(29_874_005, "engine-wbx"),
        // blocked under a table's code, and no more
        TableBlock(29_874_005, "engine-wbx", "aaaa", "工工", on = true),
        TableBlock(29_874_005, "engine-wbx", "aaaa", "工工", on = false),
        EnCommit(8, 29_874_006, 3, 20_746, 0, "the", "cat", "cta", EnCommit.AUTOCORRECTED),
        EnForget(29_874_007, "cat"),
        EnAdd(29_874_008, "youmo", true),
        EnCorrect(29_874_008, "teh"),
        Restored(29_874_009),
        ImportBatch(1, "words.txt", 20_746, 2),
        Imported(1, 0, "鲸园", "jing yuan"),
        ImportUndo(1),
        // an older build's or an export's rows taken in, and a table's taken out
        LearnRecord.Absorbed(
            29_874_010,
            listOf(MineRow("鲸园", Mine("jing yuan", Mine.TAKEN_IN, 3, 3, 1, 20_746, 0)), TableWordRow("engine-wbx", "aaaa", "工工", 3)),
            listOf(TableWordRow("engine-wbx", "bbbb", "子子", 0)),
        ),
    )

    private val rows: List<LearnRecord> = listOf(
        Head(29_874_000, 29_874_010, 3, 20_746, 9, 1, 29_874_009, "engine-wbx,engine-cangjie"),
        StatRow(Code("qp", "nihao"), Words(listOf(word("你", "ni"), word("好", "hao"))), DayCounts.of(2 to 3, 3 to 1), DayCounts.of(3 to 1), 2),
        FirstRow(Code("t9:abc", "64"), "我", First(null, listOf(Words.of("拟", "ni")), 3, -1)),
        PendingRow("拟再", Pending("ni zai", 2, 4)),
        MineRow("汪小明", Mine("wang xiao ming", Mine.CONVERTED, 1, 1, 1, 20_744, 0)),
        PairRow("你", "好", "hao", 4, 3),
        PinRow(Code("sp:Ziranma", "dz"), "地址", "di zhi"),
        BarRow(Take("abc", "94", "zhi"), 2, 3),
        EnWordRow("youmo", EnWordRow.ADDED or EnWordRow.OWN, DayCounts.of(3 to 2)),
        EnPairRow("the", "cat", 1, 3),
        TableWordRow("engine-wbx", "aaaa", "工工", 1),
        NoPredictRow("你", "好"),
        TableBlockRow("engine-wbx", "aaaa", "式"),
        BatchRow(1, "words.txt", 20_746, 2),
        ImportedRow(1, 0, "鲸园", "jing yuan"),
        LearnRecord.WordUseRow("鲸园", "jing yuan", DayCounts.of(2 to 1, 3 to 2)),
        LearnRecord.DateRow(3, 20_746),
        Cut,
    )

    @Test
    fun everyEventReadsBackAsWritten() {
        for (e in events) assertEquals(e, LearnRecords.decode(LearnRecords.encode(e)))
    }

    @Test
    fun aSnapshotReadsBackAsWrittenItsKindsNamedOnce() {
        val bytes = ByteArrayOutputStream()
        bytes.write(LearnRecords.FORMAT.header())
        val writer = LearnRecords.SnapshotWriter(bytes::write)
        rows.forEach(writer::write)
        val read = ArrayList<LearnRecord>()
        val reader = LearnRecords.Reader()
        var kinds = 0
        LearnRecords.FORMAT.read(bytes.toByteArray()) { type, input ->
            if (type == LearnRecords.KIND) kinds++
            reader.decode(type, input)?.let(read::add)
        }
        assertEquals(rows, read)
        // qp, t9:abc, sp:Ziranma
        assertEquals(3, kinds)
    }

    @Test
    fun aTypeOfALaterBuildIsKeptAsItIs() {
        val later = LearnRecords.FORMAT.record(99) { writeUTF("later"); writeInt(5) }
        val read = LearnRecords.decode(later)
        assertEquals(Unknown(99, LearnRecords.FORMAT.record(99) { writeUTF("later"); writeInt(5) }.copyOfRange(5, later.size - 4)), read)
        // written again as it was
        assertEquals(later.toList(), LearnRecords.encode(read!!).toList())
    }

    @Test
    fun fieldsALaterBuildAppendsAreReadPast() {
        val longer = LearnRecords.FORMAT.record(LearnRecords.FORGET) {
            writeInt(5)
            writeInt(1)
            writeUTF("拟")
            writeUTF("a field of a later build")
        }
        assertEquals(Forget(5, 1, "拟"), LearnRecords.decode(longer))
    }

    @Test
    fun aHeadOfFewerFieldsTakesTheDefaults() {
        val older = LearnRecords.FORMAT.record(LearnRecords.HEAD) {
            writeInt(1); writeInt(2); writeInt(3); writeInt(4); writeLong(5)
        }
        assertEquals(Head(1, 2, 3, 4, 5), LearnRecords.decode(older))
    }

    @Test
    fun anUndoneRecordOfALaterTypeIsKept() {
        val later = Unknown(99, byteArrayOf(1, 2, 3))
        val undo = Undo(3, 10, 1, 2, later, emptyList())
        assertEquals(undo, LearnRecords.decode(LearnRecords.encode(undo)))
    }

    @Test
    fun aRowOfAKindNotNamedIsKeptAsItIs() {
        val bytes = ByteArrayOutputStream()
        LearnRecords.SnapshotWriter(bytes::write).write(PinRow(Code("qp", "dz"), "地址", ""))
        // the KIND row left out
        val all = bytes.toByteArray()
        val kindRow = LearnRecords.FORMAT.record(LearnRecords.KIND) { writeShort(0); writeUTF("qp") }
        val pin = all.copyOfRange(kindRow.size, all.size)
        // review-1a-core M2: not read, so kept as it is (LearnState.kept), written again the same
        val read = LearnRecords.decode(pin)
        assertTrue(read is Unknown)
        assertEquals(pin.toList(), LearnRecords.encode(read!!).toList())
    }
}
