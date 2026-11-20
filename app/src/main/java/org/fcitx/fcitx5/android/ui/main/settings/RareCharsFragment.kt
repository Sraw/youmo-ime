/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.annotation.SuppressLint
import android.content.Context
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
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MaterialR
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import java.io.IOException
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.pinyin.RareCharsManager
import org.fcitx.fcitx5.android.engine.data.CharLevels.Level
import org.fcitx.fcitx5.android.ui.cards.CardColumns
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.settings.RareCharsViewModel.Page
import org.fcitx.fcitx5.android.ui.main.settings.RareCharsViewModel.State
import org.fcitx.fcitx5.android.ui.main.settings.cards.CardGrid
import org.fcitx.fcitx5.android.ui.main.settings.cards.CheckStrip
import org.fcitx.fcitx5.android.ui.main.settings.cards.GroupCard
import org.fcitx.fcitx5.android.ui.main.settings.search.PageEntry
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchAnchors
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchHighlight
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchablePage
import org.fcitx.fcitx5.android.ui.rare.DragRange
import org.fcitx.fcitx5.android.ui.rare.RareEdits
import org.fcitx.fcitx5.android.ui.rare.RareEdits.Box
import org.fcitx.fcitx5.android.ui.rare.RareEdits.Found
import org.fcitx.fcitx5.android.ui.rare.RareLayout
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.letGoOfFocusedField
import org.fcitx.fcitx5.android.utils.materialTextInput
import org.fcitx.fcitx5.android.utils.styledColor
import org.fcitx.fcitx5.android.utils.toast
import splitties.dimensions.dp
import timber.log.Timber

/** Changes stay on the page and are saved on [onStop]; a later version's file is shown, not changed. */
class RareCharsFragment : Fragment(), SearchAnchors {

    private val mainViewModel: MainViewModel by activityViewModels()
    private val viewModel: RareCharsViewModel by viewModels()

    private lateinit var root: LinearLayout
    private lateinit var switch: MaterialSwitch
    private lateinit var note: TextView
    private lateinit var warning: TextView
    private lateinit var searchLayout: View
    private lateinit var message: TextView
    private lateinit var status: TextView
    private lateinit var cardScroll: ScrollView
    private val cards = LinkedHashMap<Level, GroupCard>()
    private lateinit var head: LinearLayout
    private lateinit var headStrip: CheckStrip
    private lateinit var headNotes: TextView
    private lateinit var list: RecyclerView
    private lateinit var scroller: LetterScroller
    private lateinit var back: OnBackPressedCallback
    private var snackbar: Snackbar? = null

    private var page: Page? = null
    private var layout = RareLayout(emptyList())
    private var found = emptyMap<Level, Int>()
    private var drag: DragRange? = null
    // a long press with no finger down (a screen reader's, a held Enter) is no drag, which only a lift ends
    private var fingerDown = false
    private val adapter = CellAdapter()
    private val number = NumberFormat.getIntegerInstance()

    @VisibleForTesting
    internal val shownLayout: RareLayout get() = layout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val pad = ctx.dp(16)
        val config = resources.configuration
        val widthDp = minOf(config.screenWidthDp, MAX_WIDTH_DP)
        switch = MaterialSwitch(ctx).apply {
            setText(R.string.rare_chars_switch)
            tag = SWITCH
            setPadding(pad, ctx.dp(8), pad, 0)
            setOnCheckedChangeListener { _, on ->
                val page = page ?: return@setOnCheckedChangeListener
                if (page.edits.choice.on == on) return@setOnCheckedChangeListener
                page.edits.setOn(on)
                // not a step of its own: ↶ or 撤销 would turn it back with the step before
                dropUndo()
                snackbar?.dismiss()
                showState()
            }
        }
        note = TextView(ctx).apply { setPadding(pad, ctx.dp(4), pad, ctx.dp(8)) }
        warning = TextView(ctx).apply {
            setPadding(pad, ctx.dp(4), pad, ctx.dp(4))
            visibility = View.GONE
        }
        val (searchLayout, searchField) = ctx.materialTextInput { hint = getString(R.string.rare_chars_search_hint) }
        this.searchLayout = searchLayout
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
                    showScreen()
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
        cardScroll = groupCards(ctx, widthDp, config.fontScale)
        head = groupHead(ctx)
        list = cellList(ctx, widthDp, config.screenHeightDp, config.fontScale)
        scroller = LetterScroller(ctx).apply {
            onLetter = { c ->
                val top = (list.layoutManager as GridLayoutManager).findFirstVisibleItemPosition()
                val at = layout.positionOf(c, top)
                if (at >= 0) (list.layoutManager as GridLayoutManager).scrollToPositionWithOffset(at, 0)
            }
            visibility = View.GONE
        }
        val content = FrameLayout(ctx).apply {
            addView(cardScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
            addView(head, wrap)
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return FrameLayout(ctx).apply {
            val across = if (config.screenWidthDp > MAX_WIDTH_DP) ctx.dp(MAX_WIDTH_DP) else ViewGroup.LayoutParams.MATCH_PARENT
            addView(root, FrameLayout.LayoutParams(across, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        }
    }

    private fun groupCards(ctx: Context, widthDp: Int, fontScale: Float): ScrollView {
        val pad = ctx.dp(16)
        val grid = CardGrid(ctx, CardColumns.columns(widthDp.toFloat(), fontScale, CardColumns.GROUP_MIN_DP, CardColumns.GROUP)).apply {
            // its node holds the list a reader counts the cards in
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        var before: GroupCard? = null
        for (level in RareLayout.ORDER) {
            val card = GroupCard(ctx)
            card.onOpen = { open(level) }
            card.strip.onCheck = { checkGroup(level) }
            card.view.tag = GROUP + level.name
            before?.let(card::readAfter)
            before = card
            cards[level] = card
            grid.addView(card.view)
        }
        return ScrollView(ctx).apply {
            setPadding(pad, ctx.dp(8), pad, pad)
            clipToPadding = false
            addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            visibility = View.GONE
        }
    }

    private fun groupHead(ctx: Context): LinearLayout {
        val pad = ctx.dp(16)
        headStrip = CheckStrip(ctx, edgeDp = 16).apply { onCheck = { viewModel.opened?.let(::checkGroup) } }
        headNotes = TextView(ctx).apply {
            textSize = NOTES_SP
            setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant))
            setPadding(pad, 0, pad, ctx.dp(8))
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(headStrip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(headNotes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(View(ctx).apply {
                setBackgroundColor(ctx.styledColor(MaterialR.attr.colorOutlineVariant))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1)))
            visibility = View.GONE
        }
    }

    private fun cellList(ctx: Context, widthDp: Int, heightDp: Int, fontScale: Float): RecyclerView {
        val columns = RareCharCell.columns(widthDp - SCROLLER_DP, fontScale)
        return RecyclerView(ctx).apply {
            setHasFixedSize(true)
            itemAnimator = null
            // a letter jump lays out a whole new screen: the default pool of 5 had cells remade each jump, janking the drag
            recycledViewPool.setMaxRecycledViews(
                RareLayout.Kind.CELL.ordinal,
                columns * (RareCharCell.rows(heightDp, fontScale) + 2),
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
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        back = object : OnBackPressedCallback(viewModel.opened != null) {
            override fun handleOnBackPressed() = closeGroup()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        viewModel.load(mainViewModel.fcitx)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::show)
            }
        }
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                val page = page
                val can = page != null && !page.readOnly
                val tint = requireContext().styledColor(android.R.attr.colorControlNormal)
                menu.item(R.string.undo, R.drawable.ic_baseline_undo_24, tint, showAsAction = true) { undoLast() }.apply {
                    isEnabled = can && viewModel.undo != null
                    // a toolbar draws a disabled icon as is: grayed here
                    icon = icon?.mutate()?.apply { alpha = if (isEnabled) OPAQUE else DISABLED_ICON_ALPHA }
                }
                menu.add(R.string.rare_chars_reset).setEnabled(can).setOnMenuItemClickListener {
                    reset()
                    true
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem) = false
        }, viewLifecycleOwner, Lifecycle.State.STARTED)
    }

    override fun onResume() {
        super.onResume()
        // after the navigation's own title, which a recreated page is given too
        showTitle()
    }

    private fun show(state: State) {
        status.visibility = if (state is State.Ready) View.GONE else View.VISIBLE
        when (state) {
            State.Loading -> status.setText(R.string.loading)
            State.NoPack -> status.setText(R.string.pack_text_needed_short)
            State.Unavailable -> status.setText(R.string.rare_chars_unavailable)
            is State.Ready -> if (page !== state.page) {
                page = state.page
                relayout(state.page)
                val opened = viewModel.opened
                if (opened != null) {
                    restoreScroll(opened)
                } else if (viewModel.query.isNotBlank()) {
                    viewModel.scroll?.let { list.layoutManager?.onRestoreInstanceState(it) }
                }
                requireActivity().invalidateMenu()
            }
        }
        if (state !is State.Ready) {
            switch.isEnabled = false
            scroller.letters = emptyList()
        }
        showScreen()
        showState()
        if (state !is State.Loading) SearchHighlight.ready(this)
    }

    // never into a grid
    override fun reveal(id: String, found: (View) -> Unit) {
        if (viewModel.opened != null) closeGroup()
        if (id == SWITCH) return found(switch)
        val level = RareLayout.ORDER.firstOrNull { GROUP + it.name == id } ?: return
        if (cardScroll.isVisible) cards[level]?.view?.let(found)
    }

    private fun showScreen() {
        val ready = page != null
        val opened = viewModel.opened
        val searching = opened == null && viewModel.query.isNotBlank()
        val top = if (opened == null) View.VISIBLE else View.GONE
        switch.visibility = top
        note.visibility = top
        searchLayout.visibility = top
        if (!searching) message.visibility = View.GONE
        head.visibility = if (ready && opened != null) View.VISIBLE else View.GONE
        cardScroll.visibility = if (ready && opened == null && !searching) View.VISIBLE else View.GONE
        list.visibility = if (ready && (opened != null || searching)) View.VISIBLE else View.GONE
        scroller.visibility = if (ready && opened != null) View.VISIBLE else View.GONE
        back.isEnabled = opened != null
        showTitle()
    }

    private fun showTitle() {
        mainViewModel.setToolbarTitle(getString(viewModel.opened?.let(::groupName) ?: R.string.rare_chars))
    }

    private fun showState() {
        val page = page
        val on = page?.edits?.choice?.on == true
        switch.isChecked = on
        switch.isEnabled = page != null && !page.readOnly
        note.text = if (on) getString(R.string.rare_chars_note, getString(R.string.rare_chars_held, number.format(page.edits.heldCount()))) else getString(R.string.rare_chars_note_off)
        warning.visibility = if (page?.readOnly == true) View.VISIBLE else View.GONE
        if (page?.readOnly == true) warning.setText(if (page.unreadable) R.string.rare_chars_unreadable else R.string.rare_chars_newer)
        list.alpha = if (on || page == null) 1f else OFF_ALPHA
        if (page != null) {
            val samples = CardColumns.samples(resources.configuration.screenHeightDp.toFloat())
            for ((level, card) in cards) {
                val name = getString(groupName(level))
                val held = page.edits.heldIn(level)
                card.bind(
                    name, getString(groupAbout(level)), if (samples) page.samples[level].orEmpty() else emptyList(),
                    getString(R.string.rare_chars_card_a11y, name, number.format(page.total(level)), number.format(held)),
                    getString(R.string.rare_chars_card_open),
                )
                showStrip(card.strip, page, level, held)
            }
            viewModel.opened?.let { showHead(page, it) }
        }
        adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_STATE)
    }

    private fun showStrip(strip: CheckStrip, page: Page, level: Level, held: Int) {
        val total = page.total(level)
        val box = when (held) {
            0 -> Box.NONE
            total -> Box.ALL
            else -> Box.SOME
        }
        strip.state = when (box) {
            Box.ALL -> MaterialCheckBox.STATE_CHECKED
            Box.SOME -> MaterialCheckBox.STATE_INDETERMINATE
            Box.NONE -> MaterialCheckBox.STATE_UNCHECKED
        }
        strip.count.text = getString(R.string.rare_chars_card_held, number.format(held), number.format(total))
        val boxText = getString(
            when (box) {
                Box.ALL -> R.string.rare_chars_box_all
                Box.SOME -> R.string.rare_chars_box_some
                Box.NONE -> R.string.rare_chars_box_none
            }
        )
        strip.contentDescription = getString(R.string.rare_chars_group_a11y, getString(groupName(level)), number.format(total), boxText)
        strip.setUsable(editable(page), if (page.readOnly) CheckStrip.DISABLED_ALPHA else OFF_ALPHA)
    }

    private fun showHead(page: Page, level: Level) {
        showStrip(headStrip, page, level, page.edits.heldIn(level))
        val unread = page.grid.unread[Level.GRID.indexOf(level)]
        val unshown = page.unshown.getValue(level)
        val notes = listOfNotNull(
            unread.takeIf { it > 0 }?.let { getString(R.string.rare_chars_unread, number.format(it)) },
            unshown.takeIf { it > 0 }?.let { getString(R.string.rare_chars_unshown, number.format(it)) },
            getString(R.string.rare_chars_view_only).takeIf { !page.readOnly && !page.edits.choice.on },
        )
        headNotes.text = notes.joinToString("\n")
        headNotes.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun editable(page: Page) = !page.readOnly && page.edits.choice.on

    private fun checkGroup(level: Level) {
        val page = page ?: return
        if (!editable(page)) return
        val before = held(page)
        changed(page, before, page.edits.setGroup(level, page.edits.box(level) != Box.ALL))
    }

    @VisibleForTesting
    internal fun open(level: Level) {
        val page = page ?: return
        if (viewModel.opened != null) return
        // with the keyboard on the hidden search, typing would go on searching unseen
        searchLayout.letGoOfFocusedField()
        viewModel.opened = level
        relayout(page)
        restoreScroll(level)
        showScreen()
        showState()
    }

    private fun closeGroup() {
        val page = page ?: return
        keepScroll()
        drag = null
        viewModel.opened = null
        relayout(page)
        showScreen()
        showState()
    }

    private fun keepScroll() {
        val level = viewModel.opened ?: return
        val manager = list.layoutManager as GridLayoutManager
        val first = manager.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        viewModel.scrolls[level] = first to (manager.findViewByPosition(first)?.top ?: 0) - list.paddingTop
    }

    private fun restoreScroll(level: Level) {
        val (at, offset) = viewModel.scrolls[level] ?: (0 to 0)
        (list.layoutManager as GridLayoutManager).scrollToPositionWithOffset(at.coerceIn(0, maxOf(0, layout.size - 1)), offset)
    }

    private fun relayout(page: Page) {
        // a drag's positions are the old rows'
        drag = null
        val query = viewModel.query
        val opened = viewModel.opened
        message.visibility = View.GONE
        found = emptyMap()
        val groups = when {
            opened != null -> listOf(RareLayout.Group(opened, page.shown.getValue(opened), page::letterOf, open = true, head = false))
            query.isBlank() -> emptyList()
            else -> when (val result = RareEdits.search(query, page.grid, page.levels, page.sorted)) {
                is Found.Cells -> {
                    if (result.cells.isEmpty()) say(getString(R.string.rare_chars_none_found, query.trim()))
                    RareLayout.ORDER.mapNotNull { level ->
                        result.cells.filter { page.grid.level(it) == level }.takeIf { it.isNotEmpty() }
                            ?.let { RareLayout.Group(level, it.toIntArray(), page::letterOf, true) }
                    }.also { groups -> found = groups.associate { it.level to it.cells.size } }
                }
                is Found.Common -> emptyList<RareLayout.Group>().also { say(getString(R.string.rare_chars_common, text(result.codePoint))) }
                is Found.NotOurs -> emptyList<RareLayout.Group>().also { say(getString(R.string.rare_chars_not_ours, text(result.codePoint))) }
                is Found.NoCell -> emptyList<RareLayout.Group>().also {
                    say(getString(R.string.rare_chars_no_cell, text(result.codePoint), getString(groupName(result.level))))
                }
            }
        }
        layout = RareLayout(groups)
        scroller.letters = if (opened != null) layout.letters else emptyList()
        adapter.notifyDataSetChanged()
    }

    private fun say(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun changed(page: Page, before: Int, undo: RareEdits.Undo) {
        stepTaken(undo)
        val after = held(page)
        showState()
        if (after == before) return
        val text = if (after > before) getString(R.string.rare_chars_changed_held, number.format(after - before))
        else getString(R.string.rare_chars_changed_allowed, number.format(before - after))
        snackbar = Snackbar.make(root, text, Snackbar.LENGTH_LONG).setAction(R.string.undo) {
            if (page.edits.undo(undo)) showState()
            // a step after it went back with it
            dropUndo()
        }.also { it.show() }
    }

    private fun stepTaken(undo: RareEdits.Undo) {
        viewModel.undo = undo
        requireActivity().invalidateMenu()
    }

    private fun dropUndo() {
        if (viewModel.undo == null) return
        viewModel.undo = null
        activity?.invalidateMenu()
    }

    @VisibleForTesting
    internal fun undoLast() {
        val page = page ?: return
        val undo = viewModel.undo ?: return
        dropUndo()
        snackbar?.dismiss()
        if (page.edits.undo(undo)) showState()
    }

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
        keepScroll()
        viewModel.scroll = list.layoutManager?.onSaveInstanceState()
        snackbar = null
        super.onDestroyView()
    }

    override fun onStop() {
        save()
        super.onStop()
    }

    private fun save() {
        val page = page ?: return
        // no undo past a save: ↶ grayed at once
        dropUndo()
        if (page.readOnly || page.edits.changes.isEmpty()) return
        val before = RareCharsViewModel.saving
        // outlives the page, whose scope leaving cancels
        val saved = FcitxApplication.getInstance().coroutineScope.async(Dispatchers.Main.immediate) {
            // the last stop's save still writing: its changes are taken off first, else they are sent again and later ones dropped
            before?.join()
            if (page.edits.changes.isEmpty()) return@async false
            val changes = page.edits.changes.toList()
            try {
                val file = withContext(Dispatchers.IO) { RareCharsManager.update(changes, page.levels) }
                page.edits.saved(file, changes.size)
                true
            } catch (e: IOException) {
                Timber.w(e, "rare characters")
                // not saved: the changes stay, retried at the next stop
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
                RareLayout.Kind.HEAD -> TextView(ctx).apply {
                    textSize = NOTES_SP
                    setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant))
                    setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(4))
                }
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
                is TextView -> view.text = if (layout.kind(position) == RareLayout.Kind.HEAD) {
                    val level = layout.level(position)
                    getString(R.string.rare_chars_found_in, getString(groupName(level)), number.format(found[level] ?: 0))
                } else {
                    layout.letter(position).uppercase()
                }
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
            }
        }
    }

    private fun shown(page: Page, position: Int): Boolean = drag?.shown(position) ?: page.edits.held(layout.cell(position))

    private fun toggle(holder: RecyclerView.ViewHolder) {
        val page = page ?: return
        val position = holder.bindingAdapterPosition
        if (!editable(page) || position == RecyclerView.NO_POSITION) return
        // one cell, seen changing under the finger: no snackbar, but a step ↶ takes back
        stepTaken(page.edits.toggle(layout.cell(position)))
        showState()
    }

    private fun startDrag(holder: RecyclerView.ViewHolder): Boolean {
        val page = page ?: return false
        val position = holder.bindingAdapterPosition
        if (!editable(page) || position == RecyclerView.NO_POSITION) return false
        // no finger to drag with: the cell changed, as a long press lifted where it began would
        if (!fingerDown) {
            toggle(holder)
            return true
        }
        drag = DragRange(position, { page.edits.held(layout.cell(it)) }, { layout.kind(it) == RareLayout.Kind.CELL })
        list.parent?.requestDisallowInterceptTouchEvent(true)
        adapter.notifyItemChanged(position, PAYLOAD_STATE)
        return true
    }

    /** Begun by a long press; on lift, all covered cells change, one undo. */
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
                // stop at the end (a move restarts it): else it runs a frame at a time till the lift
                if (by == 0 || !list.canScrollVertically(by)) return
                list.scrollBy(0, by)
                moveTo(x, y)
                list.postOnAnimation(this)
            }
        }

        // the event the list is taken over by is not also handed to onTouchEvent: handled here
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> fingerDown = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fingerDown = false
            }
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
            fingerDown = false
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

    @VisibleForTesting
    internal fun moveTo(x: Float, y: Float) {
        val range = drag ?: return
        val child = list.findChildViewUnder(x, y) ?: return
        val position = list.getChildAdapterPosition(child)
        if (position == RecyclerView.NO_POSITION) return
        range.moveTo(position).forEach { adapter.notifyItemChanged(it, PAYLOAD_STATE) }
    }

    companion object : SearchablePage {
        private const val PAYLOAD_STATE = "state"
        private const val SCROLLER_DP = 24
        private const val EDGE_DP = 48
        private const val SCROLL_DP = 12
        private const val NOTES_SP = 13f
        private const val OFF_ALPHA = 0.5f
        private const val LETTER_IDS = 1000L
        private const val MAX_WIDTH_DP = 600
        private const val OPAQUE = 255
        private const val DISABLED_ICON_ALPHA = 97 // 38%

        // ↶ and ⋮'s 恢复默认 have no View, said where they are
        private const val SWITCH = "view:rare/switch"
        private const val GROUP = "view:rare/group/"

        override fun searchEntries(context: Context): List<PageEntry> =
            listOf(PageEntry(context.getString(R.string.rare_chars_switch), SWITCH)) +
            RareLayout.ORDER.map { PageEntry(context.getString(groupName(it)), GROUP + it.name) } + listOf(
                PageEntry(context.getString(R.string.undo), "view:rare/undo", where = R.string.search_where_undo),
                PageEntry(context.getString(R.string.rare_chars_reset), "view:rare/reset", where = R.string.search_where_menu),
            )

        fun groupName(level: Level) = when (level) {
            Level.L3 -> R.string.rare_group_l3
            Level.TRAD -> R.string.rare_group_trad
            Level.OUT -> R.string.rare_group_out
            else -> R.string.rare_group_ext
        }

        fun groupAbout(level: Level) = when (level) {
            Level.L3 -> R.string.rare_group_l3_about
            Level.TRAD -> R.string.rare_group_trad_about
            Level.OUT -> R.string.rare_group_out_about
            else -> R.string.rare_group_ext_about
        }
    }
}
