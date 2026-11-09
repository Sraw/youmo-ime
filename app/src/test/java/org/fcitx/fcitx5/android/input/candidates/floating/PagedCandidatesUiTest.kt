/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates.floating

import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.host.HostEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A candidate of the floating bar, once recycled, keeps nothing of the candidate it showed. */
@RunWith(RobolectricTestRunner::class)
class PagedCandidatesUiTest {

    /**
     * Splitties' recyclerView inflates its scrollbars layout, which :app's unit tests do not have
     * (isIncludeAndroidResources): a plain RecyclerView stands in for it.
     */
    private class PlainRecyclerViews(base: Context) : ContextWrapper(base) {
        private val inflater = object : LayoutInflater(this) {
            override fun cloneInContext(newContext: Context): LayoutInflater = this
            override fun inflate(resource: Int, root: ViewGroup?, attachToRoot: Boolean): View =
                RecyclerView(this@PlainRecyclerViews)
        }

        override fun getSystemService(name: String): Any? =
            if (name == LAYOUT_INFLATER_SERVICE) inflater else super.getSystemService(name)
    }

    @Test
    // View.hasOnLongClickListeners is API 30: the check, not the code under test; the app for AppPrefs (ThemeManager)
    @Config(sdk = [35], application = FcitxApplication::class)
    fun aRecycledCandidateHoldsNeitherItsTapNorItsLongPress() {
        val ui = PagedCandidatesUi(
            PlainRecyclerViews(RuntimeEnvironment.getApplication()), TestThemes.PixelDark, {},
            onCandidateClick = { _, _ -> }, onCandidateAction = { _, _, _ -> }, onPrevPage = {}, onNextPage = {}
        )
        // no page before or after: the list is the candidate alone
        val data = HostEvent.Paged(
            listOf(CandidateWord("1", "你", "")), 0, hasPrev = false, hasNext = false
        )
        ui.update(data, FloatingCandidatesOrientation.Horizontal)
        @Suppress("UNCHECKED_CAST")
        val adapter = ui.root.adapter as RecyclerView.Adapter<PagedCandidatesUi.UiHolder>
        val holder = adapter.createViewHolder(ui.root, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        assertTrue(holder.itemView.hasOnLongClickListeners())
        adapter.onViewRecycled(holder)
        assertFalse(holder.itemView.hasOnClickListeners())
        assertFalse(holder.itemView.hasOnLongClickListeners())
    }
}
