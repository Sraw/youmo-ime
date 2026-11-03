/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.clipboard.ClipboardChunks.Chunk
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.imeScope
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.theme.ThemeColors
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.setPaddingDp

/**
 * 「切词」 on a clipboard entry: its pieces ([ClipboardChunks]) as a grid, a tap picking one (again, dropping
 * it), 「上屏」 committing those picked in the order tapped or swept (a sweep's appended in text order, [ClipboardChunks.join]), 「重选」 dropping them
 * all; ← back to the clipboard list it came from (user 2026-11-01).
 */
class ClipboardChunksWindow(private val text: String) : InputWindow.ExtendedInputWindow<ClipboardChunksWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()
    private val fcitx by manager.fcitx()
    private val imeScope by manager.imeScope()

    private val returnAfterPaste by AppPrefs.getInstance().clipboard.clipboardReturnAfterPaste

    // the start of a very long entry, between clusters: the model's split runs on the engine's thread, which typing waits on
    private val shown = ClipboardChunks.cut(text, MAX_CHARS)

    private val ui by lazy {
        ClipboardChunksUi(context, theme, ThemeColors.entryRadius(theme.look.keys), shown, cut = shown.length < text.length) {
            service.commitOverEngine(it)
            if (returnAfterPaste) windowManager.attachWindow(KeyboardWindow)
        }
    }

    private var splitJob: Job? = null

    override fun onCreateView(): View = ui.root

    override fun onAttached() {
        // pieces at once, cluster by cluster where Chinese; the model's words when it answers
        ui.show(ClipboardChunks.chunks(shown))
        splitJob = imeScope.launch {
            modelChunks(shown) { fcitx.runOnReady { wordBoundaries(it) } }?.let(ui::show)
        }
    }

    override fun onDetached() {
        splitJob?.cancel()
    }

    override val title: String by lazy { context.getString(R.string.clipboard_split) }

    override val returnTo: () -> InputWindow = { ClipboardWindow() }
    override val returnLabel = R.string.back_to_clipboard

    private val extension by lazy {
        context.horizontalLayout {
            gravity = Gravity.END
            add(ui.resetButton, lParams(wrapContent, dp(KawaiiBarComponent.heightDp())))
            add(ui.commitButton, lParams(wrapContent, dp(KawaiiBarComponent.heightDp())))
        }
    }

    override fun onCreateBarExtension(): View = extension

    companion object {
        const val MAX_CHARS = 5000
        private const val WORDS_MS = 1000L

        /** Each of [picked] as the one of [pieces] now holding it, in order, once (北 → 北京; 北 and 京 → 北京). */
        internal fun remap(picked: List<Chunk>, pieces: List<Chunk>): List<Chunk> =
            picked.mapNotNull { p -> pieces.firstOrNull { it.from <= p.from && p.to <= it.to } }.distinct()

        /**
         * [text]'s pieces with the model's words from [boundaries]; null with no Chinese, or when it takes
         * over [WORDS_MS] (the engine busy or not ready): the pieces shown, cluster by cluster, stay.
         */
        internal suspend fun modelChunks(text: String, boundaries: suspend (List<String>) -> Map<String, IntArray>): List<Chunk>? {
            val runs = ClipboardChunks.hanRuns(text)
            if (runs.isEmpty()) return null
            val words = withTimeoutOrNull(WORDS_MS) { boundaries(runs) } ?: return null
            return ClipboardChunks.chunks(text, words)
        }
    }
}

/**
 * The pieces of [text] as cells wrapped in rows. A tap picks one or drops it, a sideways sweep a run of them
 * ([SwipePick]), each picked showing its place in the order; [commitButton] gives [onCommit] them joined, [resetButton] drops them. [cut]: [text] is the
 * entry's start only, said at the end.
 */
class ClipboardChunksUi(
    override val ctx: Context,
    private val theme: Theme,
    radiusDp: Float,
    private val text: String,
    private val cut: Boolean,
    private val onCommit: (String) -> Unit,
) : Ui {

    private var pieces: List<Chunk> = emptyList()

    /** The pieces picked, in the order tapped or swept (a sweep's appended in text order). */
    val picked = ArrayList<Chunk>()

    private val radius = ctx.dp(radiusDp)

    private fun barButton(label: Int, onClick: () -> Unit) = TextView(ctx).apply {
        text = ctx.getString(label)
        textSize = 15f
        gravity = Gravity.CENTER
        setPaddingDp(12, 0, 12, 0)
        setTextColor(theme.altKeyTextColor)
        setOnClickListener { onClick() }
    }

    /** Off till something is picked. */
    val commitButton = barButton(R.string.clipboard_split_commit) {
        if (picked.isEmpty()) return@barButton
        val joined = ClipboardChunks.join(text, pieces, picked)
        pick(emptyList())
        onCommit(joined)
    }

    val resetButton = barButton(R.string.clipboard_split_reset) { pick(emptyList()) }

    private class Holder(val cell: FrameLayout, val label: TextView, val badge: TextView) : RecyclerView.ViewHolder(cell)

    private fun background(color: Int) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    private val adapter = object : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = pieces.size + if (cut) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val label = TextView(ctx).apply {
                textSize = 16f
                minWidth = dp(40)
                gravity = Gravity.CENTER
                setPaddingDp(10, 6, 10, 6)
                setTextColor(theme.keyTextColor)
            }
            val badge = TextView(ctx).apply {
                textSize = 10f
                gravity = Gravity.CENTER
                minWidth = dp(14)
                setPaddingDp(3, 0, 3, 0)
                setTextColor(theme.accentKeyTextColor)
                background = GradientDrawable().apply {
                    cornerRadius = dp(7).toFloat()
                    setColor(theme.accentKeyBackgroundColor)
                }
                isVisible = false
            }
            val cell = FrameLayout(ctx).apply {
                addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(14), Gravity.TOP or Gravity.END))
                layoutParams = FlexboxLayoutManager.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(dp(MARGIN_DP), dp(MARGIN_DP), dp(MARGIN_DP), dp(MARGIN_DP)) }
            }
            return Holder(cell, label, badge)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            if (position >= pieces.size) {
                // what was not split: nothing to tap
                holder.label.text = ctx.getString(R.string.clipboard_split_cut, ClipboardChunksWindow.MAX_CHARS)
                holder.label.alpha = 0.6f
                holder.badge.isVisible = false
                holder.cell.background = null
                holder.cell.setOnClickListener(null)
                holder.cell.isClickable = false
                return
            }
            val piece = pieces[position]
            val order = picked.indexOf(piece)
            holder.label.text = piece.text
            holder.label.alpha = 1f
            holder.badge.isVisible = order >= 0
            holder.badge.text = (order + 1).toString()
            holder.cell.background = background(if (order >= 0) theme.genericActiveBackgroundColor else theme.clipboardEntryColor)
            holder.cell.setOnClickListener { pick(if (order >= 0) picked - piece else picked + piece) }
        }
    }

    private val sweep = object : RecyclerView.OnItemTouchListener {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
        private val edge = ctx.dp(32)
        // the cells' margin: a finger clamped into the grid lands on the edge row's cells, not the gap around them
        private val margin = ctx.dp(MARGIN_DP)
        private val step = ctx.dp(8)
        private var anchor = -1
        private var downX = 0f
        private var downY = 0f
        // the highest and lowest the finger went since it came down: toward an edge is measured from the far side
        private var minY = 0f
        private var maxY = 0f
        private var x = 0f
        private var y = 0f
        private var swipe: SwipePick<Chunk>? = null

        // held after dragging toward the top or bottom edge: the grid scrolls on and the range follows the piece under it
        private val scroll = object : Runnable {
            override fun run() {
                if (swipe == null) return
                val by = step * SwipePick.scrollDirection(y, minY, maxY, root.height, edge, slop)
                if (by == 0) return
                // at the end already: no frames spent (and a grid shorter than two bands never spins)
                if (!root.canScrollVertically(by)) return
                root.scrollBy(0, by)
                follow()
                root.postOnAnimation(this)
            }
        }

        fun end() {
            anchor = -1
            swipe = null
            root.removeCallbacks(scroll)
        }

        private fun position(rv: RecyclerView, e: MotionEvent): Int? =
            rv.findChildViewUnder(e.x, e.y)?.let(rv::getChildAdapterPosition)?.takeIf { it != RecyclerView.NO_POSITION }

        // over a gap the range stays; over the cut note, as the last piece; a finger past the grid's
        // edge as over the row at that edge, so the range follows the auto-scroll (a grid too small for the
        // insets: its top-left inset corner)
        private fun follow() {
            val s = swipe ?: return
            val left = (root.paddingLeft + margin).toFloat()
            val top = (root.paddingTop + margin).toFloat()
            val cx = x.coerceIn(left, maxOf(left, (root.width - root.paddingRight - margin - 1).toFloat()))
            val cy = y.coerceIn(top, maxOf(top, (root.height - root.paddingBottom - margin - 1).toFloat()))
            val at = root.findChildViewUnder(cx, cy)?.let(root::getChildAdapterPosition) ?: return
            if (at == RecyclerView.NO_POSITION) return
            val now = s.to(at)
            if (now != picked) pick(now)
        }

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    end()
                    anchor = position(rv, e)?.takeIf { it < pieces.size } ?: -1
                    downX = e.x
                    downY = e.y
                    minY = e.y
                    maxY = e.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (anchor < 0) return false
                    // the farthest the finger went either way counts from the down, before the sweep is taken too
                    minY = minOf(minY, e.y)
                    maxY = maxOf(maxY, e.y)
                    when (SwipePick.startsSwipe(e.x - downX, e.y - downY, slop)) {
                        null -> {}
                        false -> anchor = -1
                        true -> {
                            // taking the gesture cancels the cell's click and keeps a parent from scrolling
                            rv.parent?.requestDisallowInterceptTouchEvent(true)
                            swipe = SwipePick(pieces, picked.toList(), anchor)
                            onTouchEvent(rv, e)
                            return true
                        }
                    }
                }
                // a second finger: the sweep ends where it is, the picks kept, and no other finger moves it
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> end()
            }
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    x = e.x
                    y = e.y
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)
                    follow()
                    rv.removeCallbacks(scroll)
                    if (swipe != null && SwipePick.scrollDirection(y, minY, maxY, rv.height, edge, slop) != 0) rv.postOnAnimation(scroll)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> end()
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            // a cell asking its parents: nothing of the sweep's
        }
    }

    override val root: RecyclerView = recyclerView {
        backgroundColor = theme.barColor
        setPaddingDp(4, 4, 4, 4)
        clipToPadding = false
        layoutManager = FlexboxLayoutManager(ctx).apply { flexWrap = FlexWrap.WRAP }
        adapter = this@ClipboardChunksUi.adapter
        itemAnimator = null
        addOnItemTouchListener(sweep)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun pick(now: List<Chunk>) {
        val keep = now.toList()
        picked.clear()
        picked += keep
        commitButton.isEnabled = picked.isNotEmpty()
        commitButton.alpha = if (commitButton.isEnabled) 1f else DISABLED_ALPHA
        adapter.notifyDataSetChanged()
    }

    /** The pieces to show; the model's split replacing the first keeps each pick as the piece now holding it (北 → 北京). */
    fun show(pieces: List<Chunk>) {
        // a sweep's anchor and range index the pieces replaced: it ends, its picks remapped like any
        sweep.end()
        this.pieces = pieces
        pick(ClipboardChunksWindow.remap(picked, pieces))
    }

    private companion object {
        const val DISABLED_ALPHA = 0.4f
        const val MARGIN_DP = 3
    }
}
