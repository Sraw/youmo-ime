/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates.expanded.decoration

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.ShapeDrawable
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GridDecorationTest {

    private val ctx: Context = RuntimeEnvironment.getApplication()

    private val drawn = mutableListOf<Rect>()

    private val divider = object : ShapeDrawable() {
        override fun draw(canvas: Canvas) {
            drawn += Rect(bounds)
        }
    }.apply { intrinsicWidth = 2 }

    // two to a row, 100 high: the last of 21 alone on its row, and only some rows on screen
    private val recyclerView = RecyclerView(ctx).apply {
        layoutManager = GridLayoutManager(ctx, 2)
        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 21

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
                object : RecyclerView.ViewHolder(View(parent.context).apply {
                    layoutParams = GridLayoutManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100)
                }) {}

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }
        layOut()
    }

    private fun RecyclerView.layOut() {
        measure(
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(250, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, 200, 250)
    }

    @Test
    fun noDividerIsDrawnAfterTheLastCandidateOnceScrolledTo() {
        recyclerView.scrollToPosition(20)
        recyclerView.layOut()
        val last = recyclerView.findViewHolderForAdapterPosition(20)!!.itemView
        GridDecoration(divider).onDraw(Canvas(), recyclerView, RecyclerView.State())
        assertTrue(drawn.isNotEmpty())
        assertTrue(drawn.toString(), drawn.none { it.top >= last.top && it.bottom <= last.bottom })
    }
}
