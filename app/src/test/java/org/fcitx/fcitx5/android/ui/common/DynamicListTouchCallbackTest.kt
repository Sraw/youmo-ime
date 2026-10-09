/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.common

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** A row the list protects is not swiped away by a vertical move on an ordered list. */
@RunWith(RobolectricTestRunner::class)
class DynamicListTouchCallbackTest {

    private val ctx: Context = RuntimeEnvironment.getApplication()

    private val list = object : DynamicListAdapter<String>(listOf("kept", "free"), enableOrder = true) {
        override fun showEntry(x: String) = x
    }.apply { removable = { it != "kept" } }

    private val callback = DynamicListTouchCallback(ctx, list)

    // rows bound by a plain adapter: the callback reads only the list's entries
    private val recyclerView = RecyclerView(ctx).apply {
        layoutManager = LinearLayoutManager(ctx)
        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = list.entries.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
                object : RecyclerView.ViewHolder(TextView(parent.context)) {}

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                (holder.itemView as TextView).text = list.entries[position]
            }
        }
        val size = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        measure(size, size)
        layout(0, 0, 1000, 1000)
    }

    private fun swipeDirsAt(position: Int) =
        callback.getSwipeDirs(recyclerView, recyclerView.findViewHolderForAdapterPosition(position)!!)

    @Test
    fun protectedRowHasNoSwipeDirection() {
        assertEquals(0, swipeDirsAt(0))
    }

    @Test
    fun removableRowSwipesLeft() {
        assertEquals(ItemTouchHelper.LEFT, swipeDirsAt(1))
    }

    @Test
    fun unboundRowHasNoSwipeDirection() {
        val unbound = object : RecyclerView.ViewHolder(View(ctx)) {}
        assertEquals(0, callback.getSwipeDirs(recyclerView, unbound))
    }
}
