/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded.window

import android.graphics.Color
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Slide
import androidx.transition.Transition
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.pack.PackStore
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesAttached
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesDetached
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.input.candidates.expanded.CandidatesPagingSource
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateLayout
import org.fcitx.fcitx5.android.input.candidates.expanded.PagingCandidateViewAdapter
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.imeScope
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognition
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDrawing
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.PageStrokeTouch
import org.fcitx.fcitx5.android.input.motion.Motion
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.pack.PackKind
import org.fcitx.fcitx5.android.utils.interpolator
import org.fcitx.fcitx5.android.utils.motionSettings
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.recyclerview.verticalLayoutManager
import kotlin.math.max
import kotlin.math.min

abstract class BaseExpandedCandidateWindow<T : BaseExpandedCandidateWindow<T>> :
    InputWindow.SimpleInputWindow<T>(), InputBroadcastReceiver {

    protected val imeScope by manager.imeScope()
    protected val theme by manager.theme()
    protected val fcitx by manager.fcitx()
    protected val inputView by manager.inputView()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val bar: KawaiiBarComponent by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()
    private val preeditEmptyState: PreeditEmptyStateComponent by manager.must()
    private val service by manager.inputMethodService()
    private val prefs = AppPrefs.getInstance()

    // the pages' scroll too: 移除动画 does not scale RecyclerView's scroller, as it does an animator
    private val animates: Boolean get() = motionSettings().animates

    private lateinit var candidateLayout: ExpandedCandidateLayout

    // the theme's candidates (T6a), the bar's: their look, the lines between them, the panel under them
    private val barLook by lazy { SceneBar.of(context, theme) }

    protected val dividerDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val intrinsicSize = max(1, context.dp(1))
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            // none drawn: its room kept
            paint.color = barLook.divider?.value ?: Color.TRANSPARENT
        }
    }

    /** Draws the dividers between candidates, as the layout places them. */
    protected abstract fun newDivider(): RecyclerView.ItemDecoration

    // the two layout managers have these alike but share no type for them
    protected abstract fun firstFullyVisible(): Int
    protected abstract fun lastFullyVisible(): Int

    // opens down out of the bar and closes back up into it: the keyboard's whole area, Motion's Long
    override fun enterAnimation(lastWindow: InputWindow): Transition = Slide(Gravity.TOP).apply {
        duration = Motion.Long.enter(motionSettings())
        interpolator = Motion.Curve.Enter.interpolator
    }

    override fun exitAnimation(nextWindow: InputWindow): Transition = Slide(Gravity.TOP).apply {
        duration = Motion.Long.exit(motionSettings())
        interpolator = Motion.Curve.Exit.interpolator
    }

    final override fun onCreateView(): View {
        adapter.bar = barLook
        candidateLayout = ExpandedCandidateLayout(context, theme, barLook).apply {
            recyclerView.apply {
                adapter = this@BaseExpandedCandidateWindow.adapter
                layoutManager = this@BaseExpandedCandidateWindow.layoutManager
                addItemDecoration(newDivider())
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        val count = this@BaseExpandedCandidateWindow.layoutManager.itemCount
                        embeddedKeyboard.showPages(up = firstFullyVisible() > 0, down = lastFullyVisible() < count - 1)
                    }
                })
                draws = ::drawsStrokes
                lift = { context.dp(prefs.keyboard.strokeLift.getValue().dp) }
                longPressMillis = { prefs.keyboard.longPressDelay.getValue().toLong() }
                stroked = { commonKeyActionListener.listener.onKeyAction(KeyAction.SymAction.stroke(it), KeyActionListener.Source.Keyboard) }
            }
        }
        return candidateLayout
    }

    // as the keys draw 笔画筛选's strokes (PageStrokeTouch.Draws, the keys' rule; BaseKeyboard.drawsStrokes), read as a
    // finger comes down: the setting first, so a page of one who never chose it reads nothing more
    private fun drawsStrokes(): Boolean {
        if (prefs.keyboard.keyDrawing.getValue() != KeyDrawing.StrokeFilter) return false
        val (entry, panel) = fcitx.runImmediately { inputMethodEntryCached to inputPanelCached }
        val im = entry.uniqueName
        val handwriting = prefs.internal.handwritingOpen.getValue()
        return PageStrokeTouch.Draws().apply {
            chosen = true
            password = service.inPasswordField
            address = service.inAddressField
            composing = !preeditEmptyState.isEmpty
            tempMode = panel.auxUp.isNotEmpty()
            this.im = im
            // the keyboard under the page: its keys draw none (注音's), the page none either
            filters = (windowManager.getEssentialWindow(KeyboardWindow) as KeyboardWindow).filtersStrokes()
            handwritingOpen = handwriting
            textPack = PackStore.ready(PackKind.TEXT)
            // asked only if it writes: a keyboard of one who never writes makes no recognizer
            recognizerLoadable = handwriting && HandwritingRecognition.shared.loadable
            exploring = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        }.on
    }

    private val keyActionListener = KeyActionListener { it, source ->
        if (it is KeyAction.LayoutSwitchAction) {
            when (it.act) {
                ExpandedCandidateLayout.Keyboard.UpBtnLabel -> prevPage()
                ExpandedCandidateLayout.Keyboard.DownBtnLabel -> nextPage()
            }
        } else {
            commonKeyActionListener.listener.onKeyAction(it, source)
        }
    }

    abstract val adapter: PagingCandidateViewAdapter
    abstract val layoutManager: RecyclerView.LayoutManager

    private var offsetJob: Job? = null

    private val candidatesPager by lazy {
        Pager(
            config = PagingConfig(
                pageSize = 48,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                CandidatesPagingSource(
                    fcitx,
                    total = horizontalCandidate.adapter.total,
                    offset = adapter.offset
                )
            }
        )
    }
    private var candidatesSubmitJob: Job? = null

    private fun prevPage() = smoothScrollTo(max(firstFullyVisible() - 1, 0), LinearSmoothScroller.SNAP_TO_END)

    private fun nextPage() {
        val count = layoutManager.itemCount
        // the first page not loaded yet (Down is enabled till then): -1 would throw
        if (count == 0) return
        smoothScrollTo(min(lastFullyVisible() + 1, count - 1), LinearSmoothScroller.SNAP_TO_START)
    }

    private fun smoothScrollTo(position: Int, snap: Int) {
        layoutManager.startSmoothScroll(object : LinearSmoothScroller(context) {
            override fun getVerticalSnapPreference() = snap
            override fun calculateSpeedPerPixel(dm: DisplayMetrics?) =
                if (!animates) Float.MIN_VALUE else super.calculateSpeedPerPixel(dm)
        }.apply { targetPosition = position })
    }

    override fun onAttached() {
        bar.expandButtonStateMachine.push(ExpandedCandidatesAttached)
        // the first times the page opens drawing strokes: that a finger draws here, the pages turned by ↑↓
        val shown = prefs.internal.strokePageHintShown
        if (PageStrokeTouch.Hint.shows(drawsStrokes(), shown.getValue())) {
            shown.setValue(shown.getValue() + 1)
            candidateLayout.showHint(PageStrokeTouch.Hint.MILLIS, animates)
        }
        candidateLayout.embeddedKeyboard.also {
            it.onReturnDrawableUpdate(returnKeyDrawable.appearance)
            it.keyActionListener = keyActionListener
        }
        offsetJob = imeScope.launch {
            horizontalCandidate.expandedCandidateOffset.collect {
                // none left past the bar's: closed, unless strokes are drawn here, pinyin typed still (笔画筛选 filtered
                // them all away: the page stays, its ⌫ takes the stroke back)
                if (it <= 0 && (preeditEmptyState.isEmpty || !drawsStrokes())) {
                    windowManager.attachWindow(KeyboardWindow)
                } else {
                    candidateLayout.resetPosition()
                    adapter.refreshWithOffset(max(it, 0))
                }
            }
        }
        candidatesSubmitJob = imeScope.launch {
            candidatesPager.flow.collectLatest {
                adapter.submitData(it)
            }
        }
    }

    fun bindCandidateUiViewHolder(holder: CandidateViewHolder) {
        holder.itemView.setOnClickListener {
            fcitx.launchOnReady(holder.pick())
        }
        holder.itemView.setOnLongClickListener {
            inputView.showCandidateActionMenu(holder.idx, holder.candidate.text, holder.ui.root)
            true
        }
    }

    fun recycleCandidateViewHolder(holder: CandidateViewHolder) {
        holder.itemView.setOnClickListener(null)
        holder.itemView.setOnLongClickListener(null)
    }

    override fun onDetached() {
        bar.expandButtonStateMachine.push(
            ExpandedCandidatesDetached,
            ExpandedCandidatesEmpty to (horizontalCandidate.adapter.total == adapter.offset)
        )
        candidatesSubmitJob?.cancel()
        offsetJob?.cancel()
        candidateLayout.embeddedKeyboard.keyActionListener = null
        candidateLayout.hideHint()
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        if (empty) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

}