/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.learn.backup.BackupMerge
import org.fcitx.fcitx5.android.engine.learn.backup.LearnBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * The record types of `learn.log` (LearnRecords) and of a backup (LearnBackup, BackupMerge.ORIGIN)
 * are never reused (LearnRecords' allocation, F2 S1): a log written by one build is read by every
 * later one, so an id once given means that record for good, even after its type is deleted.
 * [ALLOCATED] is every id ever given; a new type is added to it, and a type deleted moves to
 * [RETIRED], its id kept there. Neither is edited otherwise.
 *
 * contract: the type bytes of a file format, which mutation cannot see.
 */
class RecordTypeIdsTest {

    @Test
    fun eachTypeHasItsOwnIdAsAllocated() {
        val now = constants(LearnRecords) + constants(LearnBackup, "backup.") + ("backup.ORIGIN" to BackupMerge.ORIGIN.toInt())
        // distinct: a byte names one type of the log or of a backup (a log also holds ORIGIN)
        val byId = now.entries.groupBy({ it.value }, { it.key })
        assertEquals("ids given twice", emptyMap<Int, List<String>>(), byId.filterValues { it.size > 1 })
        for ((name, id) in now) {
            assertEquals("$name is not the id allocated to it (or not allocated: add it to ALLOCATED)", ALLOCATED[name], id)
            assertTrue("$name takes the retired id $id", id !in RETIRED.values)
        }
        // each allocated id still a type, or retired: none dropped from both
        for ((name, id) in ALLOCATED) assertTrue("$name ($id) neither a type nor retired", name in now || RETIRED[name] == id)
        assertEquals("retired twice or reallocated", RETIRED.size, RETIRED.values.toSet().size)
    }

    // the Byte constants of [holder] (the companion-less objects keep them as static fields), by name:
    // a type id is a Byte by convention, an Int constant there a limit (ABSORBED_ROWS), not a type.
    // The holders are listed by hand: a type id in another object is to be added to the list above
    private fun constants(holder: Any, prefix: String = ""): Map<String, Int> =
        holder.javaClass.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == java.lang.Byte.TYPE }
            .associate { f -> prefix + f.name to f.apply { isAccessible = true }.getByte(null).toInt() }

    private companion object {
        val ALLOCATED = mapOf(
            "COMMIT" to 1, "UNDO" to 2, "PIN" to 3, "FORGET" to 4, "NO_PREDICT" to 5, "TABLE_ORDER_RESET" to 6,
            "EN_COMMIT" to 7, "EN_FORGET" to 8, "EN_ADD" to 9, "RESTORED" to 10, "IMPORT_BATCH" to 11,
            "IMPORTED" to 12, "IMPORT_UNDO" to 13, "ABSORBED" to 14, "TABLE_BLOCK" to 15, "EN_CORRECT" to 28,
            "HEAD" to 32, "KIND" to 33, "STAT_ROW" to 34, "FIRST_OF" to 35, "PENDING_ROW" to 36, "MINE_ROW" to 37,
            "PAIR_ROW" to 38, "PIN_ROW" to 39, "BAR_ROW" to 40, "EN_WORD_ROW" to 41, "EN_PAIR_ROW" to 42,
            "TABLE_WORD_ROW" to 43, "NO_PREDICT_ROW" to 44, "CUT" to 45, "BATCH_ROW" to 46, "IMPORTED_ROW" to 47,
            "WORD_USE_ROW" to 48, "TABLE_BLOCK_ROW" to 65, "DATE_ROW" to 80,
            // a backup's own (70–79, never a log's but ORIGIN)
            "backup.HEAD" to 70, "backup.ADDED" to 71, "backup.BLOCKED" to 72, "backup.ORIGIN" to 73,
            // LAST: not a type, the bound of a backup's range, kept as fixed as one
            "backup.END" to 74, "backup.ADDRESS_COUNTS" to 75, "backup.ADDRESSES" to 76, "backup.LAST" to 79,
        )

        // a type deleted: its name and id, never given again
        val RETIRED = emptyMap<String, Int>()
    }
}
