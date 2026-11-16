/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.pinyin.RareCharsManager
import org.fcitx.fcitx5.android.engine.data.CharLevels.Level
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.settings.RareCharsViewModel.Page
import org.fcitx.fcitx5.android.ui.main.settings.RareCharsViewModel.State
import org.fcitx.fcitx5.android.ui.rare.DragRange
import org.fcitx.fcitx5.android.ui.rare.RareEdits
import org.fcitx.fcitx5.android.ui.rare.RareEdits.Box
import org.fcitx.fcitx5.android.ui.rare.RareEdits.Found
import org.fcitx.fcitx5.android.ui.rare.RareLayout
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.materialTextInput
import org.fcitx.fcitx5.android.utils.styledColor
import org.fcitx.fcitx5.android.utils.toast
import splitties.dimensions.dp
import timber.log.Timber
import java.io.IOException
import java.text.NumberFormat

/**
 * 生僻字屏蔽 (dev/design/061-rare-char-block.md §6): the switch, the four groups' cells to hold back or
 * let through one by one, by a drag or a group's box, and a search. Changes stay on the page and are
 * saved as it is left ([onStop]), the engine then reading them; a later version's file is shown, not changed.
 */
class RareCharsFragment : Fragment() {

    private val mainViewModel: MainViewModel by activityViewModels()
    private val viewModel: RareCharsViewModel by viewModels()

    private lateinit var root: LinearLayout
    private lateinit var switch: MaterialSwitch
    private lateinit var note: TextView
    private lateinit var warning: TextView
    private lateinit var message: TextView
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private lateinit var scroller: LetterScroller

    private var page: Page? = null
    private var layout = RareLayout(emptyList())
    private var drag: DragRange? = null
    private val adapter = CellAdapter()
    private val number = NumberFormat.getIntegerInstance()

    @VisibleForTesting
    internal val shownLayout: RareLayout get() = layout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val pad = ctx.dp(16)
        switch = MaterialSwitch(ctx).apply {
            setText(R.string.rare_chars_switch)
            setPadding(pad, ctx.dp(8), pad, 0)
            setOnCheckedChangeListener { _, on ->
                val page = page ?: return@setOnCheckedChangeListener
                if (page.edits.choice.on == on) return@setOnCheckedChangeListener
                page.edits.setOn(on)
                showState()
            }
        }
        note = TextView(ctx).apply { setPadding(pad, ctx.dp(4), pad, ctx.dp(8)) }
        warning = TextView(ctx).apply {
            setPadding(pad, ctx.dp(4), pad, ctx.dp(4))
            visibility = View.GONE
        }
        val (searchLayout, searchField) = ctx.materialTextInput { hint = getString(R.string.rare_chars_search_hint) }
        searchLayout.setPadding(pad, 0, pad, 0)
        searchField.apply {
            isSingleLine = true
            setText(viewModel.query)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (viewModel.query == s.toString()) return
                    viewModel.query = s.toString()
                    page?.let { relayout(it) }
                }
            })
        }
        message = TextView(ctx).apply {
            setPadding(pad, ctx.dp(4), pad, ctx.dp(4))
            visibility = View.GONE
        }
        status = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setText(R.string.loading)
        }
        val config = resources.configuration
        val columns = RareCharCell.columns(config.screenWidthDp - SCROLLER_DP, config.fontScale)
        list = RecyclerView(ctx).apply {
            setHasFixedSize(true)
            itemAnimator = null
            // a letter's jump lays out a whole new screen of cells: the default pool keeps 5 a type and the
            // rest were made anew each jump (gfxinfo, 2026-11-14: 60% janky frames dragging the letters).
            // A screen's worth and two rows more
            recycledViewPool.setMaxRecycledViews(
                RareLayout.Kind.CELL.ordinal,
                columns * (RareCharCell.rows(config.screenHeightDp, config.fontScale) + 2),
            )
            layoutManager = GridLayoutManager(ctx, columns).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) = layout.spanSize(position, spanCount)
                    override fun getSpanIndex(position: Int, spanCount: Int) = layout.spanIndex(position, spanCount)
                    override fun getSpanGroupIndex(adapterPosition: Int, spanCount: Int) = layout.spanGroupIndex(adapterPosition, spanCount)
                }
            }
            adapter = this@RareCharsFragment.adapter
            addOnItemTouchListener(DragListener())
            visibility = View.GONE
        }
        scroller = LetterScroller(ctx).apply {
            onLetter = { c ->
                // in the group at the list's top: a drag there stays there (review S2)
                val top = (list.layoutManager as GridLayoutManager).findFirstVisibleItemPosition()
                val at = layout.positionOf(c, top)
                if (at >= 0) (list.layoutManager as GridLayoutManager).scrollToPositionWithOffset(at, 0)
            }
        }
        val grid = FrameLayout(ctx).apply {
            addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = ctx.dp(SCROLLER_DP) })
            addView(scroller, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
            addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val wrap = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(switch, wrap)
            addView(note, wrap)
            addView(warning, wrap)
            addView(searchLayout, wrap)
            addView(message, wrap)
            addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.load(mainViewModel.fcitx)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::show)
            }
        }
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                val page = page
                menu.add(R.string.rare_chars_reset).setEnabled(page != null && !page.readOnly).setOnMenuItemClickListener {
                    reset()
                    true
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem) = false
        }, viewLifecycleOwner, Lifecycle.State.STARTED)
    }

    private fun show(state: State) {
        status.visibility = if (state is State.Ready) View.GONE else View.VISIBLE
        list.visibility = if (state is State.Ready) View.VISIBLE else View.GONE
        when (state) {
            State.Loading -> status.setText(R.string.loading)
            State.NoPack -> status.setText(R.string.pack_text_needed_short)
            State.Unavailable -> status.setText(R.string.rare_chars_unavailable)
            is State.Ready -> if (page !== state.page) {
                page = state.page
                relayout(state.page)
                viewModel.scroll?.let { list.layoutManager?.onRestoreInstanceState(it) }
                requireActivity().invalidateMenu()
            }
        }
        if (state !is State.Ready) {
            switch.isEnabled = false
            scroller.letters = emptyList()
        }
        showState()
    }

    /** The switch, the note under it and why the page cannot be changed, as the edits now are. */
    private fun showState() {
        val page = page
        val on = page?.edits?.choice?.on == true
        switch.isChecked = on
        switch.isEnabled = page != null && !page.readOnly
        note.text = if (on) getString(R.string.rare_chars_note, getString(R.string.rare_chars_held, number.format(page.edits.heldCount()))) else getString(R.string.rare_chars_note_off)
        warning.visibility = if (page?.readOnly == true) View.VISIBLE else View.GONE
        if (page?.readOnly == true) warning.setText(if (page.unreadable) R.string.rare_chars_unreadable else R.string.rare_chars_newer)
        // off, the grid grayed but there to look at (§6.2)
        list.alpha = if (on || page == null) 1f else OFF_ALPHA
        adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_STATE)
    }

    private fun editable(page: Page) = !page.readOnly && page.edits.choice.on

    /** The rows anew: the groups as opened, or what the search finds. */
    private fun relayout(page: Page) {
        val query = viewModel.query
        message.visibility = View.GONE
        val groups = if (query.isBlank()) {
            RareLayout.ORDER.map { level -> RareLayout.Group(level, page.shown.getValue(level), page::letterOf, level in viewModel.open) }
        } else {
            when (val found = RareEdits.search(query, page.grid, page.levels, page.sorted)) {
                is Found.Cells -> {
                    if (found.cells.isEmpty()) say(getString(R.string.rare_chars_none_found, query.trim()))
                    RareLayout.ORDER.mapNotNull { level ->
                        found.cells.filter { page.grid.level(it) == level }.takeIf { it.isNotEmpty() }
                            ?.let { RareLayout.Group(level, it.toIntArray(), page::letterOf, true) }
                    }
                }
                is Found.Common -> emptyList<RareLayout.Group>().also { say(getString(R.string.rare_chars_common, text(found.codePoint))) }
                is Found.NotOurs -> emptyList<RareLayout.Group>().also { say(getString(R.string.rare_chars_not_ours, text(found.codePoint))) }
                is Found.NoCell -> emptyList<RareLayout.Group>().also {
                    say(getString(R.string.rare_chars_no_cell, text(found.codePoint), getString(groupName(found.level))))
                }
            }
        }
        layout = RareLayout(groups)
        scroller.letters = layout.letters
        adapter.notifyDataSetChanged()
    }

    private fun say(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    /** Takes [undo] back from the snackbar, the cells changed told: held or let through. */
    private fun changed(page: Page, before: Int, undo: RareEdits.Undo) {
        val after = held(page)
        showState()
        if (after == before) return
        val text = if (after > before) getString(R.string.rare_chars_changed_held, number.format(after - before))
        else getString(R.string.rare_chars_changed_allowed, number.format(before - after))
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).setAction(R.string.undo) {
            if (page.edits.undo(undo)) showState()
        }.show()
    }

    // the cells held, on or off: what a change made is counted by
    private fun held(page: Page) = Level.GRID.sumOf { page.edits.heldIn(it) }

    @VisibleForTesting
    internal fun reset() {
        val page = page ?: return
        if (page.readOnly) return
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.rare_chars_reset_confirm)
            .setPositiveButton(R.string.rare_chars_reset) { _, _ ->
                val before = held(page)
                changed(page, before, page.edits.reset())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        viewModel.scroll = list.layoutManager?.onSaveInstanceState()
        super.onDestroyView()
    }

    override fun onStop() {
        save()
        super.onStop()
    }

    /** The page's changes laid over the file as it is now, the engine then reading it; none, nothing done (§6.2). */
    private fun save() {
        val page = page ?: return
        if (page.readOnly || page.edits.changes.isEmpty()) return
        val changes = page.edits.changes.toList()
        // outlives the page, whose scope leaving cancels
        val saved = FcitxApplication.getInstance().coroutineScope.async(Dispatchers.Main.immediate) {
            try {
                val file = withContext(Dispatchers.IO) { RareCharsManager.update(changes, page.levels) }
                page.edits.saved(file, changes.size)
                true
            } catch (e: IOException) {
                Timber.w(e, "rare characters")
                // not saved: the changes stay, tried again at the next stop
                if (e is RareCharsManager.Newer) appContext.toast(R.string.rare_chars_newer) else appContext.toast(e)
                false
            }
        }
        RareCharsViewModel.saving = saved
        val fcitx = mainViewModel.fcitx
        fcitx.lifecycleScope.launch(Dispatchers.Main.immediate) {
            fcitx.runOnReady<Unit> { if (saved.await()) reloadEngine() }
        }
    }

    private fun text(codePoint: Int) = String(Character.toChars(codePoint))

    private inner class CellAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        // the cells' paints, one set for the grid
        private var paints: RareCharCell.Paints? = null

        init {
            setHasStableIds(true)
        }

        override fun getItemCount() = layout.size

        override fun getItemViewType(position: Int) = layout.kind(position).ordinal

        override fun getItemId(position: Int): Long = when (layout.kind(position)) {
            RareLayout.Kind.CELL -> page!!.grid.chars[layout.cell(position)].toLong()
            RareLayout.Kind.HEAD -> -1L - layout.level(position).ordinal
            RareLayout.Kind.LETTER -> -LETTER_IDS - layout.level(position).ordinal * LETTER_IDS - layout.letter(position).code
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val view = when (RareLayout.Kind.entries[viewType]) {
                RareLayout.Kind.CELL -> RareCharCell(ctx, paints ?: RareCharCell.Paints(ctx).also { paints = it })
                RareLayout.Kind.HEAD -> GroupHead(ctx)
                RareLayout.Kind.LETTER -> TextView(ctx).apply {
                    setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(2))
                    setTextColor(ctx.styledColor(android.R.attr.colorPrimary))
                }
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (PAYLOAD_STATE in payloads) bindState(holder, position) else onBindViewHolder(holder, position)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val page = page ?: return
            when (val view = holder.itemView) {
                is RareCharCell -> {
                    val i = layout.cell(position)
                    val readings = page.grid.readings(i)
                    val char = text(page.grid.chars[i])
                    val first = readings.first().pinyin + if (readings.size > 1) "…" else ""
                    view.show(char, first, shown(page, position)) { getString(R.string.rare_chars_cell_a11y, char, readings.joinToString("、") { it.pinyin }) }
                    view.setOnClickListener { toggle(holder) }
                    view.setOnLongClickListener { startDrag(holder) }
                }
                is GroupHead -> view.bind(layout.level(position))
                is TextView -> view.text = layout.letter(position).uppercase()
            }
            bindState(holder, position)
        }

        private fun bindState(holder: RecyclerView.ViewHolder, position: Int) {
            val page = page ?: return
            when (val view = holder.itemView) {
                is RareCharCell -> {
                    view.setHeld(shown(page, position))
                    view.isEnabled = editable(page)
                }
                is GroupHead -> view.bind(layout.level(position))
            }
        }
    }

    private fun shown(page: Page, position: Int): Boolean = drag?.shown(position) ?: page.edits.held(layout.cell(position))

    private fun toggle(holder: RecyclerView.ViewHolder) {
        val page = page ?: return
        val position = holder.bindingAdapterPosition
        if (!editable(page) || position == RecyclerView.NO_POSITION) return
        // one cell, seen changing under the finger: no undo (§6.2 gives one to a drag and a group's box)
        page.edits.toggle(layout.cell(position))
        showState()
    }

    private fun startDrag(holder: RecyclerView.ViewHolder): Boolean {
        val page = page ?: return false
        val position = holder.bindingAdapterPosition
        if (!editable(page) || position == RecyclerView.NO_POSITION) return false
        drag = DragRange(position, { page.edits.held(layout.cell(it)) }, { layout.kind(it) == RareLayout.Kind.CELL })
        list.parent?.requestDisallowInterceptTouchEvent(true)
        adapter.notifyItemChanged(position, PAYLOAD_STATE)
        return true
    }

    /**
     * The drag begun by a long press (§6.2): the finger's cell each move, the list scrolled at its
     * edges; lifted, the cells it covered changed at once, one undo for them all.
     */
    private inner class DragListener : RecyclerView.OnItemTouchListener {

        private var y = 0f
        private var x = 0f

        private val edgeScroll = object : Runnable {
            override fun run() {
                if (drag == null) return
                val edge = list.context.dp(EDGE_DP)
                val by = when {
                    y < edge -> -list.context.dp(SCROLL_DP)
                    y > list.height - edge -> list.context.dp(SCROLL_DP)
                    else -> 0
                }
                // at the list's end it stops (a move starts it again): else on and on, a frame at a time, till the lift
                if (by == 0 || !list.canScrollVertically(by)) return
                list.scrollBy(0, by)
                moveTo(x, y)
                list.postOnAnimation(this)
            }
        }

        // the event the list is taken over by is not handed to onTouchEvent too: handled here
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (drag == null) return false
            onTouchEvent(rv, e)
            return true
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    x = e.x
                    y = e.y
                    moveTo(x, y)
                    rv.removeCallbacks(edgeScroll)
                    rv.postOnAnimation(edgeScroll)
                }
                MotionEvent.ACTION_UP -> finish(apply = true)
                MotionEvent.ACTION_CANCEL -> finish(apply = false)
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit

        private fun finish(apply: Boolean) {
            val range = drag ?: return
            list.removeCallbacks(edgeScroll)
            drag = null
            val page = page ?: return
            val cells = range.changes().map { layout.cell(it) }
            if (apply && cells.isNotEmpty()) {
                val before = held(page)
                changed(page, before, page.edits.set(cells, range.target))
            } else {
                adapter.notifyItemRangeChanged(range.range.first, range.range.last - range.range.first + 1, PAYLOAD_STATE)
            }
        }
    }

    /** The finger at ([x], [y]) in the list: the cell under it the drag's end. */
    @VisibleForTesting
    internal fun moveTo(x: Float, y: Float) {
        val range = drag ?: return
        val child = list.findChildViewUnder(x, y) ?: return
        val position = list.getChildAdapterPosition(child)
        if (position == RecyclerView.NO_POSITION) return
        range.moveTo(position).forEach { adapter.notifyItemChanged(it, PAYLOAD_STATE) }
    }

    /** A group's head (§6.2): its name and count, opened or shut by a tap; its box; what it holds; what has no cell. */
    private inner class GroupHead(ctx: android.content.Context) : LinearLayout(ctx) {
        private val name = TextView(ctx).apply {
            textSize = HEAD_SP
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(8), ctx.dp(4))
        }
        private val box = MaterialCheckBox(ctx)
        private val counts = TextView(ctx).apply { setPadding(ctx.dp(16), 0, ctx.dp(16), ctx.dp(8)) }
        private var level = Level.L3

        init {
            orientation = VERTICAL
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(box, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(counts, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            name.setOnClickListener {
                if (viewModel.query.isNotBlank()) return@setOnClickListener
                if (!viewModel.open.remove(level)) viewModel.open += level
                page?.let { relayout(it) }
            }
            box.setOnClickListener {
                val page = page ?: return@setOnClickListener
                if (!editable(page)) return@setOnClickListener
                val before = held(page)
                changed(page, before, page.edits.setGroup(level, page.edits.box(level) != Box.ALL))
            }
        }

        fun bind(level: Level) {
            this.level = level
            val page = page ?: return
            val total = page.grid.count(level)
            val open = viewModel.query.isNotBlank() || level in viewModel.open
            name.text = "${if (open) "▾" else "▸"} ${getString(groupName(level))} ${number.format(total)}"
            // read without the arrow, its state said and what a tap does (review N12)
            name.contentDescription = "${getString(groupName(level))} ${number.format(total)}"
            ViewCompat.setStateDescription(name, getString(if (open) R.string.rare_chars_group_open else R.string.rare_chars_group_shut))
            ViewCompat.replaceAccessibilityAction(
                name, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                getString(if (open) R.string.rare_chars_collapse else R.string.rare_chars_expand), null,
            )
            val state = page.edits.box(level)
            box.checkedState = when (state) {
                Box.ALL -> MaterialCheckBox.STATE_CHECKED
                Box.SOME -> MaterialCheckBox.STATE_INDETERMINATE
                Box.NONE -> MaterialCheckBox.STATE_UNCHECKED
            }
            box.isEnabled = editable(page)
            val boxText = getString(
                when (state) {
                    Box.ALL -> R.string.rare_chars_box_all
                    Box.SOME -> R.string.rare_chars_box_some
                    Box.NONE -> R.string.rare_chars_box_none
                }
            )
            box.contentDescription = getString(R.string.rare_chars_group_a11y, getString(groupName(level)), number.format(total), boxText)
            val unread = page.grid.unread[Level.GRID.indexOf(level)]
            val unshown = page.unshown.getValue(level)
            counts.text = listOfNotNull(
                getString(R.string.rare_chars_group_held, number.format(page.edits.heldIn(level))),
                unread.takeIf { it > 0 }?.let { getString(R.string.rare_chars_unread, number.format(it)) },
                unshown.takeIf { it > 0 }?.let { getString(R.string.rare_chars_unshown, number.format(it)) },
            ).joinToString("\n")
        }
    }

    companion object {
        private const val PAYLOAD_STATE = "state"
        private const val SCROLLER_DP = 24
        private const val EDGE_DP = 48
        private const val SCROLL_DP = 12
        private const val HEAD_SP = 16f
        private const val OFF_ALPHA = 0.5f
        private const val LETTER_IDS = 1000L

        fun groupName(level: Level) = when (level) {
            Level.L3 -> R.string.rare_group_l3
            Level.TRAD -> R.string.rare_group_trad
            Level.OUT -> R.string.rare_group_out
            else -> R.string.rare_group_ext
        }
    }
}
