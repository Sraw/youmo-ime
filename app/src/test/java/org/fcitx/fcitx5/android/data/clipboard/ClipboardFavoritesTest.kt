/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.ClipData
import android.os.Looper
import android.os.PersistableBundle
import androidx.paging.PagingSource
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.Favorited
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import timber.log.Timber
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** A favourite is a copy of an entry of 最近, each page apart: limits, the trash and an unfavourite's undo. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ClipboardFavoritesTest {

    @Before
    fun fresh() = runBlocking {
        settle()
        ClipboardManager.resetDuplicateCheck()
        ClipboardManager.nukeTable()
        Unit
    }

    private suspend fun settle() = ClipboardManager.coroutineContext.job.children.forEach { it.join() }

    private fun copy(text: String, sensitive: Boolean = false) = runBlocking {
        settle()
        val clip = ClipData.newPlainText(null, text)
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        ClipboardManager.resetDuplicateCheck()
        // each at a time of its own, before what comes next: the pages go by time
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        appContext.clipboardManager.setPrimaryClip(clip)
        settle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    }

    private fun page(favorites: Boolean): List<ClipboardEntry> = runBlocking {
        settle()
        val source = if (favorites) ClipboardManager.favoriteEntries() else ClipboardManager.recentEntries()
        (source.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page).data
    }

    private fun recent() = page(false).map { it.text }

    private fun favorites() = page(true).map { it.text }

    private fun recentId(text: String) = page(false).first { it.text == text }.id

    private fun favoriteId(text: String) = page(true).first { it.text == text }.id

    @Test
    fun aFavouriteIsACopyWithTheEntrysKindAndSensitivity() = runBlocking {
        copy("公园")
        copy("mima1234", sensitive = true)
        val added = ClipboardManager.favorite(recentId("公园"))
        val addedSensitive = ClipboardManager.favorite(recentId("mima1234"))
        val again = ClipboardManager.favorite(recentId("公园"))
        val copyOfSensitive = page(true).first { it.text == "mima1234" }
        val itsRecent = page(false).first { it.text == "mima1234" }
        assertRows(
            "a plain one" to { assertEquals(Favorited.Added, added) },
            "a sensitive one says it stays out of backups" to { assertEquals(Favorited.AddedSensitive, addedSensitive) },
            "the same text again: already there, no second" to { assertEquals(Favorited.Already, again) },
            "the copy keeps the sensitivity, shown as dots and left out of a backup" to { assertEquals(true, copyOfSensitive.sensitive) },
            "the copy keeps the kind" to { assertEquals(itsRecent.type, copyOfSensitive.type) },
            "the newest favourite first" to { assertEquals(listOf("mima1234", "公园"), favorites()) },
            "最近 keeps its entries" to { assertEquals(listOf("mima1234", "公园"), recent()) },
            "each page counted on its own" to { assertEquals(2 to 2, ClipboardManager.recentCount to ClipboardManager.favoriteCount) },
        )
    }

    @Test
    fun aFavouriteOfAnEntryGoneOrOnlyMarkedSaysSoAndAMarkedFavouriteIsNoDuplicate() = runBlocking {
        copy("公园")
        copy("散步")
        val walk = recentId("散步")
        ClipboardManager.delete(walk)
        val gone = ClipboardManager.favorite(walk)
        val park = recentId("公园")
        ClipboardManager.favorite(park)
        // unfavourited, its undo pending: marked, so not there
        val unfavored = ClipboardManager.unfavorite(favoriteId("公园"))
        val againWhileMarked = ClipboardManager.favorite(park)
        assertRows(
            "deleted between the long press and the pick" to { assertEquals(Favorited.Gone, gone) },
            "unfavourited and awaiting its undo: a favourite again" to { assertEquals(Favorited.Added, againWhileMarked) },
            "an original in 最近: the favourite was marked, not moved" to { assertNull(unfavored?.timestamp) },
        )
    }

    @Test
    fun unfavouritingWithTheTextIn最近TakesTheCopyAndItsUndoBringsItBack() = runBlocking {
        copy("公园")
        ClipboardManager.favorite(recentId("公园"))
        val u = ClipboardManager.unfavorite(favoriteId("公园"))!!
        val after = favorites() to recent()
        ClipboardManager.undoUnfavorite(u)
        val undone = favorites() to recent()
        assertRows(
            "gone from 收藏, 最近 as it was" to { assertEquals(emptyList<String>() to listOf("公园"), after) },
            "the undo brings it back" to { assertEquals(listOf("公园") to listOf("公园"), undone) },
        )
    }

    @Test
    fun unfavouritingOneWithNoCopyIn最近MovesItThereFirstAndItsUndoBackInPlace() = runBlocking {
        copy("北京")
        copy("上海")
        ClipboardManager.favorite(recentId("北京"))
        ClipboardManager.favorite(recentId("上海"))
        copy("今天")
        // its recent copy gone: as an upgrade's pin, which has none
        recentId("北京").let { ClipboardManager.delete(it); ClipboardManager.purgeMarked(it) }
        settle()
        val u = ClipboardManager.unfavorite(favoriteId("北京"))!!
        val after = favorites() to recent()
        ClipboardManager.undoUnfavorite(u)
        val undone = favorites() to recent()
        assertRows(
            "moved to 最近, first: not lost" to { assertEquals(listOf("上海") to listOf("北京", "今天", "上海"), after) },
            "said moved, not deleted" to { assertEquals(false, u.dropped) },
            "the undo puts it back where it was among the favourites" to {
                assertEquals(listOf("上海", "北京") to listOf("今天", "上海"), undone)
            },
        )
    }

    // 最近 would hide it, then trim it: unfavourited, it goes as a delete does
    @Test
    fun unfavouritingTheOnlyCopyWithTheHistoryOffOrALimitOf0DeletesItAndItsUndoBringsItBack() = runBlocking {
        val clipboard = AppPrefs.getInstance().clipboard
        val rows = mutableListOf<Pair<String, () -> Unit>>()
        for ((case, off) in listOf<Pair<String, () -> Unit>>(
            "history off" to { clipboard.clipboardListening.setValue(false) },
            "a limit of 0" to { clipboard.clipboardHistoryLimit.setValue(0) },
        )) {
            ClipboardManager.nukeTable()
            copy("北京")
            ClipboardManager.favorite(recentId("北京"))
            recentId("北京").let { ClipboardManager.delete(it); ClipboardManager.purgeMarked(it) }
            try {
                off()
                settle()
                val u = ClipboardManager.unfavorite(favoriteId("北京"))!!
                val after = favorites() to recent()
                ClipboardManager.undoUnfavorite(u)
                val undone = favorites() to recent()
                rows += "$case: said deleted" to { assertEquals(true to null, u.dropped to u.timestamp) }
                rows += "$case: not moved to 最近" to { assertEquals(emptyList<String>() to emptyList<String>(), after) }
                rows += "$case: the undo brings it back" to { assertEquals(listOf("北京") to emptyList<String>(), undone) }
            } finally {
                clipboard.clipboardListening.setValue(clipboard.clipboardListening.defaultValue)
                clipboard.clipboardHistoryLimit.setValue(clipboard.clipboardHistoryLimit.defaultValue)
                settle()
            }
        }
        assertRows(*rows.toTypedArray())
    }

    @Test
    fun anUndoAfterTheSameTextWasFavouritedAgainMakesNoSecond() = runBlocking {
        copy("公园")
        ClipboardManager.favorite(recentId("公园"))
        val marked = ClipboardManager.unfavorite(favoriteId("公园"))!!
        ClipboardManager.favorite(recentId("公园"))
        ClipboardManager.undoUnfavorite(marked)
        val oneMarked = favorites()
        // the moved kind: no copy left in 最近
        recentId("公园").let { ClipboardManager.delete(it); ClipboardManager.purgeMarked(it) }
        val moved = ClipboardManager.unfavorite(favoriteId("公园"))!!
        ClipboardManager.favorite(recentId("公园"))
        ClipboardManager.undoUnfavorite(moved)
        assertRows(
            "a marked one undone over a new favourite" to { assertEquals(listOf("公园"), oneMarked) },
            "a moved one undone over a new favourite stays in 最近" to { assertEquals(listOf("公园") to listOf("公园"), favorites() to recent()) },
        )
    }

    @Test
    fun movedFirstPastedTrimmedOrClearedTheFavouritesStay() = runBlocking {
        for (w in listOf("甲", "乙", "丙")) copy(w)
        for (w in listOf("甲", "乙", "丙")) ClipboardManager.favorite(recentId(w))
        ClipboardManager.moveFavoriteFirst(favoriteId("甲"))
        val moved = favorites()
        ClipboardManager.used(listOf(favoriteId("乙")))
        val pasted = favorites()
        val ids = ClipboardManager.deleteAllRecent()
        val cleared = favorites() to recent()
        ClipboardManager.purgeMarked(*ids)
        copy("甲")
        val copiedAgain = favorites() to recent()
        assertRows(
            "移到最前" to { assertEquals(listOf("甲", "丙", "乙"), moved) },
            "pasting a favourite moves none" to { assertEquals(moved, pasted) },
            "the trash takes 最近 alone" to { assertEquals(listOf("甲", "丙", "乙") to emptyList<String>(), cleared) },
            "a copy of a favourite's text lands in 最近, 收藏 as it was" to {
                assertEquals(listOf("甲", "丙", "乙") to listOf("甲"), copiedAgain)
            },
        )
    }

    @Test
    fun theCopyCountGoesUpWithCopiesAloneTheSameTextToo() = runBlocking {
        val before = ClipboardManager.copies
        copy("公园")
        copy("公园")
        ClipboardManager.used(listOf(recentId("公园")))
        ClipboardManager.favorite(recentId("公园"))
        assertEquals(before + 2, ClipboardManager.copies)
    }

    // a restore's: the phone's favourites, back in once the backup's database opens
    @Test
    fun theFavouritesKeptAtARestoreAreMergedAtTheNextOpen() = runBlocking {
        settle()
        ClipboardManager.closeDatabase()
        val kept = favoritesToMerge(appContext.getDatabasePath("clbdb").parentFile!!)
        kept.writeText("""[{"text":"公园","type":"text/plain","sensitive":true,"timestamp":1}]""")
        ClipboardManager.reopenDatabase()
        assertRows(
            "merged" to { assertEquals(listOf("公园") to true, favorites() to page(true).single().sensitive) },
            "the file gone once merged" to { assertFalse(kept.exists()) },
        )
    }

    @Test
    fun aKeptFileNeverReadableIsDroppedNotTriedAtEveryStart() = runBlocking {
        settle()
        ClipboardManager.closeDatabase()
        val kept = favoritesToMerge(appContext.getDatabasePath("clbdb").parentFile!!).apply { writeText("not json") }
        val logged = CopyOnWriteArrayList<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logged += message.lineSequence().first()
            }
        }
        Timber.plant(tree)
        try {
            ClipboardManager.reopenDatabase()
        } finally {
            Timber.uproot(tree)
        }
        assertRows(
            "dropped" to { assertFalse(kept.exists()) },
            "said in the log" to { assertTrue(logged.toString(), "Favourites kept at a restore unreadable: dropped" in logged) },
            "nothing merged" to { assertEquals(0, ClipboardManager.favoriteCount) },
        )
    }

    // else the favourites the user just cleared would come back at the next open
    @Test
    fun clearingTheDatabaseDropsTheFavouritesKeptAtARestore() = runBlocking {
        settle()
        val kept = favoritesToMerge(appContext.getDatabasePath("clbdb").parentFile!!)
        kept.writeText("""[{"text":"公园","type":"text/plain","sensitive":false,"timestamp":1}]""")
        val half = java.io.File(kept.parentFile, "${kept.name}.tmp").apply { writeText("[]") }
        ClipboardManager.nukeTable()
        val gone = !kept.exists() && !half.exists()
        ClipboardManager.closeDatabase()
        ClipboardManager.reopenDatabase()
        assertRows(
            "the file gone with the rows" to { assertTrue(gone) },
            "none back at the next open" to { assertEquals(0, ClipboardManager.favoriteCount) },
        )
    }
}
