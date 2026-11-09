/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.floating

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.LayoutHint
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import splitties.views.dsl.core.Ui
import splitties.views.dsl.recyclerview.recyclerView

class PagedCandidatesUi(
    override val ctx: Context,
    val theme: Theme,
    private val setupTextView: TextView.() -> Unit,
    private val onCandidateClick: (Int, String) -> Unit,
    private val onCandidateAction: (Int, String, View) -> Unit,
    private val onPrevPage: () -> Unit,
    private val onNextPage: () -> Unit
) : Ui {

    private var data = HostEvent.Paged.EMPTY

    // the theme's candidates (T6a), worked out once for every holder
    private val bar = SceneBar.of(ctx, theme)

    private var isVertical = false

    sealed class UiHolder(open val ui: Ui) : RecyclerView.ViewHolder(ui.root) {
        class Candidate(override val ui: LabeledCandidateItemUi) : UiHolder(ui)
        class Pagination(override val ui: PaginationUi) : UiHolder(ui)
    }

    private val candidatesAdapter = object : RecyclerView.Adapter<UiHolder>() {
        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long =
            data.candidates.getOrNull(position).hashCode().toLong()

        override fun getItemCount() =
            data.candidates.size + (if (data.hasPrev || data.hasNext) 1 else 0)

        override fun getItemViewType(position: Int) = if (position < data.candidates.size) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UiHolder {
            return when (viewType) {
                0 -> UiHolder.Candidate(LabeledCandidateItemUi(ctx, theme, setupTextView, bar))
                else -> UiHolder.Pagination(PaginationUi(ctx, theme)).apply {
                    ui.prevIcon.setOnClickListener {
                        onPrevPage.invoke()
                    }
                    ui.nextIcon.setOnClickListener {
                        onNextPage.invoke()
                    }
                }
            }.apply {
                // assign default LayoutParams, otherwise updateLayoutParams won't work
                ui.root.layoutParams = FlexboxLayoutManager.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            }
        }

        override fun onBindViewHolder(holder: UiHolder, position: Int) {
            when (holder) {
                is UiHolder.Candidate -> {
                    val candidate = data.candidates[position]
                    holder.ui.update(candidate, active = position == data.cursorIndex, first = position == 0)
                    holder.ui.root.setOnClickListener {
                        onCandidateClick.invoke(position, candidate.text)
                    }
                    holder.ui.root.setOnLongClickListener { v ->
                        onCandidateAction.invoke(position, candidate.text, v)
                        true
                    }
                    holder.ui.root.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                        width = if (isVertical) MATCH_PARENT else WRAP_CONTENT
                    }
                }
                is UiHolder.Pagination -> {
                    holder.ui.update(data)
                    holder.ui.root.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                        flexGrow = 1f
                        width = if (isVertical) MATCH_PARENT else WRAP_CONTENT
                        alignSelf = if (isVertical) AlignItems.STRETCH else AlignItems.CENTER
                    }
                }
            }
        }

        override fun onViewRecycled(holder: UiHolder) {
            if (holder is UiHolder.Candidate) {
                holder.ui.root.setOnClickListener(null)
                holder.ui.root.setOnLongClickListener(null)
            }
            super.onViewRecycled(holder)
        }
    }

    private val candidatesLayoutManager = FlexboxLayoutManager(ctx).apply {
        flexWrap = FlexWrap.WRAP
    }

    override val root = recyclerView {
        isFocusable = false
        adapter = candidatesAdapter
        layoutManager = candidatesLayoutManager
        overScrollMode = View.OVER_SCROLL_NEVER
        itemAnimator = null
    }

    @SuppressLint("NotifyDataSetChanged")
    fun update(
        data: HostEvent.Paged,
        orientation: FloatingCandidatesOrientation
    ) {
        this.data = data
        this.isVertical = when (orientation) {
            FloatingCandidatesOrientation.Automatic -> data.layoutHint == LayoutHint.Vertical
            else -> orientation == FloatingCandidatesOrientation.Vertical
        }
        candidatesLayoutManager.apply {
            if (isVertical) {
                flexDirection = FlexDirection.COLUMN
                alignItems = AlignItems.STRETCH
            } else {
                flexDirection = FlexDirection.ROW
                alignItems = AlignItems.BASELINE
            }
        }
        candidatesAdapter.notifyDataSetChanged()
    }
}
