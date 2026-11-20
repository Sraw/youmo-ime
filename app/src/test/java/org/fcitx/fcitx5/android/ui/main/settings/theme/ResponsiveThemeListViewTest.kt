/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** A resize changing the grid's columns once left the spacing worked out for the columns it had first. */
@RunWith(RobolectricTestRunner::class)
class ResponsiveThemeListViewTest {

    private class Holder(view: View) : RecyclerView.ViewHolder(view)

    private class Themes(private val count: Int) : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(View(parent.context))
        override fun onBindViewHolder(holder: Holder, position: Int) = Unit
        override fun getItemCount() = count
    }

    @Test
    fun theSpacingFollowsTheColumnsAfterAResize() {
        // sizes of its own, whatever the density: 2 columns of 128 in 400 leave 3 gaps of 48
        val list = ResponsiveThemeListView(RuntimeEnvironment.getApplication()).apply {
            itemWidth = 128
            itemHeight = 92
            minMargin = 16
            adapter = Themes(4)
        }
        list.layout(0, 0, 2000, 800)
        val wide = (list.layoutManager as GridLayoutManager).spanCount
        list.layout(0, 0, 400, 800)
        val narrow = (list.layoutManager as GridLayoutManager).spanCount
        assertNotEquals(wide, narrow)
        val row = (0 until narrow).map { list.findViewHolderForAdapterPosition(it)!!.itemView }
        val gaps = listOf(row.first().left) + row.zipWithNext { a, b -> b.left - a.right } + (list.width - row.last().right)
        assertEquals(listOf(48, 48, 48), gaps)
    }
}
