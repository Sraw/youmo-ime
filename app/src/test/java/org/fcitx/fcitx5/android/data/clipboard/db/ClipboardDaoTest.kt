/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard.db

import androidx.paging.PagingSource
import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.data.clipboard.lastKept
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** A purge takes only the rows its snackbar showed; rows past the history limit go outright. */
@RunWith(RobolectricTestRunner::class)
class ClipboardDaoTest {

    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ClipboardDatabase::class.java)
        .build()
    private val dao = db.clipboardDao()

    @After
    fun close() = db.close()

    private suspend fun add(text: String, timestamp: Long = 1000L, favorite: Boolean = false, sensitive: Boolean = false) =
        dao.get(dao.insert(ClipboardEntry(text = text, timestamp = timestamp, favorite = favorite, sensitive = sensitive)))!!.id

    @Test
    fun aPurgeTakesOnlyTheMarkedRowsItIsGiven() = runTest {
        val shown = add("a")
        val markedSince = add("b")
        val kept = add("c")
        dao.markAsDeleted(shown, markedSince)
        dao.purgeMarked(shown, kept)
        dao.undoDelete(shown, markedSince)
        assertNull(dao.get(shown))
        assertNotNull(dao.get(markedSince))
        assertNotNull(dao.get(kept))
    }

    @Test
    fun aPurgeOfNoIdsTakesNoneAndOneOfAllMarkedTakesEvery() = runTest {
        val marked = add("a")
        val kept = add("b")
        dao.markAsDeleted(marked)
        dao.purgeMarked()
        dao.undoDelete(marked)
        assertNotNull(dao.get(marked))
        dao.markAsDeleted(marked)
        dao.purgeAllMarked()
        dao.undoDelete(marked)
        assertNull(dao.get(marked))
        assertNotNull(dao.get(kept))
    }

    @Test
    fun theLastKeptGoesByWhenLastCopied() = runTest {
        // copied at 100, 200, 300, 500, the second again at 450: room for 3 keeps it, the third and fourth
        add("a", timestamp = 100L)
        val copiedAgain = add("b", timestamp = 200L)
        add("c", timestamp = 300L)
        add("d", timestamp = 500L)
        dao.updateTime(copiedAgain, 450L)
        add("favourite", timestamp = 900L, favorite = true)
        dao.markAsDeleted(add("deleted", timestamp = 800L))
        assertEquals(300L, dao.recentTimestampAt(2))
        // room for 5: none outdated
        assertNull(dao.recentTimestampAt(4))
    }

    @Test
    fun anOutdatedEntryGoesOutrightAndOneAwaitingItsUndoStays() = runTest {
        val outdated = add("old", timestamp = 100L)
        val awaitingUndo = add("deleted", timestamp = 200L)
        val favorite = add("favourite", timestamp = 50L, favorite = true)
        val recent = add("new", timestamp = 1000L)
        dao.markAsDeleted(awaitingUndo)
        dao.deleteRecentEarlierThan(500L)
        dao.undoDelete(outdated, awaitingUndo)
        assertNull(dao.get(outdated))
        assertNotNull(dao.get(awaitingUndo))
        assertNotNull(dao.get(favorite))
        assertNotNull(dao.get(recent))
    }

    private suspend fun texts(source: PagingSource<Int, ClipboardEntry>): List<String> {
        val page = source.load(PagingSource.LoadParams.Refresh(null, 50, false))
        return (page as PagingSource.LoadResult.Page).data.map { it.text }
    }

    @Test
    @Uat("TB-50")
    fun aRecentEntryUsedComesFirstOfTheRecentAFavouriteStays() = runTest {
        add("p1", 500L, favorite = true)
        val p2 = add("p2", 400L, favorite = true)
        add("a", 3000L)
        val b = add("b", 2000L)
        add("c", 1000L)
        dao.updateTimeIfRecent(b, 5000L)
        assertEquals(listOf("b", "a", "c"), texts(dao.recentEntries()))
        dao.updateTimeIfRecent(p2, 6000L)
        assertEquals(listOf("p1", "p2"), texts(dao.favoriteEntries()))
        // least recently used goes first: c, not b
        val last = lastKept(2, 0L) { dao.recentTimestampAt(it) }
        assertEquals(3000L, last)
        dao.deleteRecentEarlierThan(last!!)
        assertEquals(listOf("b", "a"), texts(dao.recentEntries()))
        assertEquals(listOf("p1", "p2"), texts(dao.favoriteEntries()))
    }

    @Test
    fun thePagesAndTheirCountsHoldTheirOwnRowsOnly() = runTest {
        add("r1", 100L)
        add("f1", 900L, favorite = true)
        dao.markAsDeleted(add("r gone", 300L))
        dao.markAsDeleted(add("f gone", 950L, favorite = true))
        add("f2", 200L, favorite = true)
        val recent = texts(dao.recentEntries())
        val favorites = texts(dao.favoriteEntries())
        val counts = dao.recentCount() to dao.favoriteCount()
        val newest = dao.newestFavoriteTimestamp()
        val recentIds = dao.findRecentIds().size
        assertRows(
            "最近 lists the recent alone, a marked one not" to { assertEquals(listOf("r1"), recent) },
            "收藏 lists the favourites by their time, a marked one not" to { assertEquals(listOf("f1", "f2"), favorites) },
            "each page counts its own" to { assertEquals(1 to 2, counts) },
            "the newest favourite's time counts a marked one: its undo must not land above a newer" to { assertEquals(950L, newest) },
            "the recent ids, for the trash: no favourite" to { assertEquals(1, recentIds) },
        )
    }

    @Test
    fun theSameTextIsFoundOnItsOwnPageWithTheSameSensitivityAndNotWhenMarked() = runTest {
        val recent = add("同", 100L)
        val favorite = add("同", 200L, favorite = true)
        add("密", 300L, sensitive = true)
        val marked = add("删", 400L, favorite = true)
        dao.markAsDeleted(marked)
        val found = listOf(
            dao.findRecent("同", false)?.id, dao.findFavorite("同", false)?.id, dao.findRecent("密", false)?.id,
            dao.findRecent("密", true)?.text, dao.findFavorite("删", false)?.id, dao.getEvenMarked(marked)?.text, dao.get(marked)?.id,
        )
        assertRows(
            "a recent by text" to { assertEquals(recent, found[0]) },
            "a favourite by text" to { assertEquals(favorite, found[1]) },
            "the same text not sensitive is another entry" to { assertNull(found[2]) },
            "sensitive found as sensitive" to { assertEquals("密", found[3]) },
            "a favourite marked deleted is not there: a second may be made" to { assertNull(found[4]) },
            "an undo still reads the marked row" to { assertEquals("删", found[5]) },
            "the plain read does not" to { assertNull(found[6]) },
        )
    }

    @Test
    fun noFavouriteHasNoNewestTime() = runTest {
        add("r", 100L)
        assertNull(dao.newestFavoriteTimestamp())
    }
}
