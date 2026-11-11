/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.graphics.Color
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.CandidateSpacing
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.input.candidates.expanded.decoration.FlexboxVerticalDecoration
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AlwaysFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AutoFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.NeverFillWidth
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import kotlin.math.max

class HorizontalCandidateComponent :
    UniqueViewComponent<HorizontalCandidateComponent, RecyclerView>(), InputBroadcastReceiver {

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val inputView by manager.inputView()
    private val bar: KawaiiBarComponent by manager.must()

    private val fillStyle by AppPrefs.getInstance().keyboard.horizontalCandidateStyle

    // English is typed: its words in three slots, as Gboard has them (the word typed, then the likeliest two)
    private var english = false

    override fun onImeUpdate(ime: InputMethodEntry) {
        english = ime.uniqueName == InputMethodNames.KEYBOARD
    }

    // Since expanded candidate window is created once the expand button was clicked,
    // we need to replay the last offset
    private val _expandedCandidateOffset = MutableSharedFlow<Int>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val expandedCandidateOffset = _expandedCandidateOffset.asSharedFlow()

    private fun refreshExpanded(childCount: Int) {
        _expandedCandidateOffset.tryEmit(childCount)
        bar.expandButtonStateMachine.push(
            ExpandedCandidatesUpdated,
            ExpandedCandidatesEmpty to (adapter.total == childCount)
        )
    }

    val adapter: HorizontalCandidateViewAdapter by lazy {
        object : HorizontalCandidateViewAdapter(theme, barLook) {
            override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                holder.itemView.setOnClickListener {
                    fcitx.launchOnReady(holder.pick())
                }
                holder.itemView.setOnLongClickListener {
                    inputView.showCandidateActionMenu(holder.idx, holder.candidate.text, holder.ui.root)
                    true
                }
            }

            override fun onViewRecycled(holder: CandidateViewHolder) {
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
                super.onViewRecycled(holder)
            }
        }
    }

    val layoutManager by lazy {
        CandidateRowLayoutManager().apply { onLaidOut = ::refreshExpanded }
    }

    // the theme's candidates (T6a): the line between them, the room beside it
    private val barLook by lazy { SceneBar.of(context, theme) }

    private val gap by lazy { SceneBar.px(context, barLook.gapDp) }

    private val dividerDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val intrinsicSize = max(1, context.dp(1))
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            // none drawn: its room kept
            paint.color = barLook.divider?.value ?: Color.TRANSPARENT
        }
    }

    override val view by lazy {
        RecyclerView(context).apply {
            id = R.id.candidate_view
            itemAnimator = null
            adapter = this@HorizontalCandidateComponent.adapter
            layoutManager = this@HorizontalCandidateComponent.layoutManager
            addItemDecoration(FlexboxVerticalDecoration(dividerDrawable, gap))
        }
    }

    override fun onCandidateUpdate(data: HostEvent.Candidates) {
        val candidates = data.candidates
        val total = data.total
        if (english) {
            layoutManager.minWidth = view.width / ENGLISH_SLOTS - dividerDrawable.intrinsicWidth - gap
            // fewer words keep their slots, not the whole bar
            val grows = candidates.size >= ENGLISH_SLOTS
            layoutManager.grows = { grows }
        } else {
            layoutManager.minWidth = 0
            layoutManager.grows = when (fillStyle) {
                NeverFillWidth -> { _ -> false }
                // each word its natural width, as many as fit, stretched over the small leftover beside ⌄
                // only when more wait behind it: a short list that fits whole stays packed
                AutoFillWidth -> { shown -> CandidateSpacing.stretches(shown, total) }
                AlwaysFillWidth -> { _ -> true }
            }
        }
        adapter.updateCandidates(candidates, total)
        // an empty list told at once, as before: the layout after it may wait for the next frame
        if (candidates.isEmpty()) {
            refreshExpanded(0)
        }
    }

    companion object {
        private const val ENGLISH_SLOTS = 3
    }
}
