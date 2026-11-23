/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.app.Activity
import android.os.Looper
import android.widget.FrameLayout
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.canvas.ScenePanels
import org.fcitx.fcitx5.android.until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A card bound again, to an entry or to a placeholder of a page not loaded yet, keeps nothing of the entry it showed. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ClipboardAdapterBindTest {

    // one entry, then one counted but never loaded: a placeholder, as Room's count gives past the pages loaded
    private val source = object : PagingSource<Int, ClipboardEntry>() {
        override fun getRefreshKey(state: PagingState<Int, ClipboardEntry>): Int? = null

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ClipboardEntry> =
            if (params.key == null) {
                LoadResult.Page(listOf(ClipboardEntry(id = 1, text = "公园")), prevKey = null, nextKey = 1, itemsBefore = 0, itemsAfter = 1)
            } else {
                awaitCancellation()
            }
    }

    @Test
    fun aPlaceholderBoundOverACardTakesItsActionsOff() {
        val ctx = Robolectric.buildActivity(Activity::class.java).setup().get()
        val theme = ThemeManager.activeTheme
        val look = ScenePanels.clipboard(ctx, theme)
        val pasted = mutableListOf<String>()
        val adapter = object : ClipboardAdapter(theme, look, ScenePanels.clipboardRadius(ctx, look), false) {
            override fun onPaste(entry: ClipboardEntry) {
                pasted += entry.text
            }
            override fun onFavorite(id: Int) = Unit
            override fun onUnfavorite(id: Int) = Unit
            override fun onMoveFirst(id: Int) = Unit
            override fun onEdit(id: Int) = Unit
            override fun onSplit(entry: ClipboardEntry) = Unit
            override fun onShare(entry: ClipboardEntry) = Unit
            override fun onSaveQuickPhrase(entry: ClipboardEntry) = Unit
            override fun onDelete(id: Int) = Unit
        }
        val job = CoroutineScope(Dispatchers.Main).launch {
            Pager(PagingConfig(pageSize = 1, enablePlaceholders = true)) { source }.flow.collect { adapter.submitData(it) }
        }
        try {
            until("an entry and a placeholder", { adapter.itemCount }, step = { shadowOf(Looper.getMainLooper()).idle() }) { adapter.itemCount == 2 }
            val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
            val root = holder.entryUi.root
            fun actions() = AccessibilityNodeInfoCompat.wrap(root.createAccessibilityNodeInfo()).actionList.mapNotNull { it.label?.toString() }
            adapter.onBindViewHolder(holder, 0)
            val bound = actions()
            adapter.onBindViewHolder(holder, 1)
            val placeholder = actions()
            val shown = holder.entryUi.textView.text.toString()
            root.performClick()
            val favorite = ctx.getString(R.string.clipboard_favorite)
            assertRows(
                "the entry's card offers 收藏" to { assertTrue(bound.toString(), favorite in bound) },
                "the placeholder offers it no more" to { assertFalse(placeholder.toString(), favorite in placeholder) },
                "nor pastes the entry the card showed" to { assertEquals(emptyList<String>(), pasted) },
                "nor shows (nor TalkBack reads) its text" to { assertEquals("", shown) },
            )
        } finally {
            job.cancel()
        }
    }
}
