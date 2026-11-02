/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ClipboardDao {
    @Insert
    suspend fun insert(clipboardEntry: ClipboardEntry): Long

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET pinned=:pinned WHERE id=:id")
    suspend fun updatePinStatus(id: Int, pinned: Boolean)

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET text=:text WHERE id=:id")
    suspend fun updateText(id: Int, text: String)

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET timestamp=:timestamp WHERE id=:id")
    suspend fun updateTime(id: Int, timestamp: Long)

    // a pinned entry keeps its place among the pinned
    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET timestamp=:timestamp WHERE id=:id AND pinned=0")
    suspend fun updateTimeIfUnpinned(id: Int, timestamp: Long)

    @Query("SELECT COUNT(*) FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0")
    suspend fun itemCount(): Int

    // the marked too, whose text is on the disk till purged
    @Query("SELECT COUNT(*) FROM ${ClipboardEntry.TABLE_NAME}")
    suspend fun countAll(): Int

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE id=:id AND deleted=0 LIMIT 1")
    suspend fun get(id: Int): ClipboardEntry?

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE rowId=:rowId AND deleted=0 LIMIT 1")
    suspend fun get(rowId: Long): ClipboardEntry?

    @Query("SELECT EXISTS(SELECT 1 FROM ${ClipboardEntry.TABLE_NAME} WHERE pinned=0 AND deleted=0)")
    suspend fun haveUnpinned(): Boolean

    // by time, not id: an entry copied again is new, its id old
    @Query("SELECT timestamp FROM ${ClipboardEntry.TABLE_NAME} WHERE pinned=0 AND deleted=0 ORDER BY timestamp DESC LIMIT 1 OFFSET :index")
    suspend fun unpinnedTimestampAt(index: Int): Long?

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0 ORDER BY pinned DESC, timestamp DESC")
    fun allEntries(): PagingSource<Int, ClipboardEntry>

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE text=:text AND sensitive=:sensitive AND deleted=0 LIMIT 1")
    suspend fun find(text: String, sensitive: Boolean = false): ClipboardEntry?

    @Query("SELECT id FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0")
    suspend fun findAllIds(): IntArray

    @Query("SELECT id FROM ${ClipboardEntry.TABLE_NAME} WHERE pinned=0 AND deleted=0")
    suspend fun findUnpinnedIds(): IntArray

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET deleted=1 WHERE id in (:ids)")
    suspend fun markAsDeleted(vararg ids: Int)

    @Query("DELETE FROM ${ClipboardEntry.TABLE_NAME} WHERE timestamp<:timestamp AND pinned=0 AND deleted=0")
    suspend fun deleteUnpinnedEarlierThan(timestamp: Long)

    @Query("SELECT COUNT(*) FROM ${ClipboardEntry.TABLE_NAME} WHERE timestamp<:timestamp AND pinned=0 AND deleted=0")
    suspend fun countUnpinnedEarlierThan(timestamp: Long): Int

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET deleted=0 WHERE id in (:ids) AND deleted=1")
    suspend fun undoDelete(vararg ids: Int)

    @Query("DELETE FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=1")
    suspend fun purgeAllMarked()

    @Query("DELETE FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=1 AND id in (:ids)")
    suspend fun purgeMarked(vararg ids: Int)
}