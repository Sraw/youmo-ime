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

    // the column is 收藏 (ClipboardEntry.favorite); the time orders the favourites too
    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET pinned=:favorite, timestamp=:timestamp WHERE id=:id")
    suspend fun setFavorite(id: Int, favorite: Boolean, timestamp: Long)

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET text=:text WHERE id=:id")
    suspend fun updateText(id: Int, text: String)

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET timestamp=:timestamp WHERE id=:id")
    suspend fun updateTime(id: Int, timestamp: Long)

    // a favourite keeps its place among the favourites
    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET timestamp=:timestamp WHERE id=:id AND pinned=0")
    suspend fun updateTimeIfRecent(id: Int, timestamp: Long)

    @Query("SELECT COUNT(*) FROM ${ClipboardEntry.TABLE_NAME} WHERE pinned=0 AND deleted=0")
    suspend fun recentCount(): Int

    @Query("SELECT COUNT(*) FROM ${ClipboardEntry.TABLE_NAME} WHERE pinned=1 AND deleted=0")
    suspend fun favoriteCount(): Int

    // the marked too, whose text is on the disk till purged
    @Query("SELECT COUNT(*) FROM ${ClipboardEntry.TABLE_NAME}")
    suspend fun countAll(): Int

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE id=:id AND deleted=0 LIMIT 1")
    suspend fun get(id: Int): ClipboardEntry?

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE rowId=:rowId AND deleted=0 LIMIT 1")
    suspend fun get(rowId: Long): ClipboardEntry?

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE id=:id LIMIT 1")
    suspend fun getEvenMarked(id: Int): ClipboardEntry?

    // by time, not id: an entry copied again is new, its id old
    @Query("SELECT timestamp FROM ${ClipboardEntry.TABLE_NAME} WHERE pinned=0 AND deleted=0 ORDER BY timestamp DESC LIMIT 1 OFFSET :index")
    suspend fun recentTimestampAt(index: Int): Long?

    // a marked one too: its undo must not land above a favourite made since
    @Query("SELECT MAX(timestamp) FROM ${ClipboardEntry.TABLE_NAME} WHERE pinned=1")
    suspend fun newestFavoriteTimestamp(): Long?

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE pinned=0 AND deleted=0 ORDER BY timestamp DESC")
    fun recentEntries(): PagingSource<Int, ClipboardEntry>

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE pinned=1 AND deleted=0 ORDER BY timestamp DESC")
    fun favoriteEntries(): PagingSource<Int, ClipboardEntry>

    // a copy of a favourite's text goes to 最近 as any copy, the favourite left as it is
    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE text=:text AND sensitive=:sensitive AND pinned=0 AND deleted=0 LIMIT 1")
    suspend fun findRecent(text: String, sensitive: Boolean = false): ClipboardEntry?

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE text=:text AND sensitive=:sensitive AND pinned=1 AND deleted=0 LIMIT 1")
    suspend fun findFavorite(text: String, sensitive: Boolean = false): ClipboardEntry?

    @Query("SELECT id FROM ${ClipboardEntry.TABLE_NAME} WHERE pinned=0 AND deleted=0")
    suspend fun findRecentIds(): IntArray

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET deleted=1 WHERE id in (:ids)")
    suspend fun markAsDeleted(vararg ids: Int)

    @Query("DELETE FROM ${ClipboardEntry.TABLE_NAME} WHERE timestamp<:timestamp AND pinned=0 AND deleted=0")
    suspend fun deleteRecentEarlierThan(timestamp: Long)

    @Query("SELECT COUNT(*) FROM ${ClipboardEntry.TABLE_NAME} WHERE timestamp<:timestamp AND pinned=0 AND deleted=0")
    suspend fun countRecentEarlierThan(timestamp: Long): Int

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET deleted=0 WHERE id in (:ids) AND deleted=1")
    suspend fun undoDelete(vararg ids: Int)

    @Query("DELETE FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=1")
    suspend fun purgeAllMarked()

    @Query("DELETE FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=1 AND id in (:ids)")
    suspend fun purgeMarked(vararg ids: Int)
}