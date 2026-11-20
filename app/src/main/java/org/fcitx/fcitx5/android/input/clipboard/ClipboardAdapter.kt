/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.scene.engine.PanelLooks
import org.fcitx.fcitx5.android.utils.DeviceUtil
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.styledColor
import kotlin.math.min

abstract class ClipboardAdapter(
    private val theme: Theme,
    private val look: PanelLooks.Clipboard,
    private val entryRadius: Float,
    private val maskSensitive: Boolean,
    val page: ClipboardPage = ClipboardPage.Recent,
) : PagingDataAdapter<ClipboardEntry, ClipboardAdapter.ViewHolder>(diffCallback) {

    companion object {
        private const val PLACE_CHANGED = "place"

        private val A11Y_ACTIONS = setOf(ClipboardAction.Favorite, ClipboardAction.Unfavorite, ClipboardAction.MoveFirst)

        @StringRes
        fun label(action: ClipboardAction) = when (action) {
            ClipboardAction.Favorite -> R.string.clipboard_favorite
            ClipboardAction.Unfavorite -> R.string.clipboard_unfavorite
            ClipboardAction.MoveFirst -> R.string.clipboard_move_first
            ClipboardAction.Split -> R.string.clipboard_split
            ClipboardAction.Edit -> R.string.edit
            ClipboardAction.SaveQuickPhrase -> R.string.clipboard_save_quick_phrase
            ClipboardAction.Share -> R.string.share
            ClipboardAction.Delete -> R.string.delete
        }

        @DrawableRes
        private fun icon(action: ClipboardAction) = when (action) {
            ClipboardAction.Favorite -> R.drawable.ic_baseline_star_24
            ClipboardAction.Unfavorite -> R.drawable.ic_outline_star_border_24
            ClipboardAction.MoveFirst -> R.drawable.ic_baseline_arrow_upward_24
            ClipboardAction.Split -> R.drawable.ic_baseline_content_cut_24
            ClipboardAction.Edit -> R.drawable.ic_baseline_edit_24
            ClipboardAction.SaveQuickPhrase -> R.drawable.ic_quick_phrase
            ClipboardAction.Share -> R.drawable.ic_baseline_share_24
            ClipboardAction.Delete -> R.drawable.ic_baseline_delete_24
        }

        private val diffCallback = object : DiffUtil.ItemCallback<ClipboardEntry>() {
            override fun areItemsTheSame(
                oldItem: ClipboardEntry,
                newItem: ClipboardEntry
            ): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(
                oldItem: ClipboardEntry,
                newItem: ClipboardEntry
            ): Boolean {
                return oldItem == newItem
            }
        }

        /**
         * excerpt text to show on ClipboardEntryUi, to reduce render time of very long text.
         * No more of the text is looked at than [lines] full lines and their breaks.
         */
        fun excerptText(
            str: String,
            mask: Boolean = false,
            lines: Int = 4,
            chars: Int = 128
        ): String = buildString {
            // a very long text is not scanned to its end, yet the lines after a long one still show
            val scanEnd = min(str.length, lines * (chars + 1))
            var lineBreak = -1
            for (i in 1..lines) {
                val start = lineBreak + 1   // skip previous '\n'
                val excerptEnd = min(start + chars, scanEnd)
                lineBreak = (start until scanEnd).firstOrNull { str[it] == '\n' } ?: -1
                if (lineBreak < 0) {
                    if (mask) {
                        append(ClipboardEntry.BULLET.repeat(excerptEnd - start))
                    } else {
                        append(str.substring(start, excerptEnd))
                    }
                    break
                } else {
                    val end = min(excerptEnd, lineBreak)
                    if (mask) {
                        appendLine(ClipboardEntry.BULLET.repeat(end - start))
                    } else {
                        appendLine(str.substring(start, end))
                    }
                }
            }
        }
    }

    private var popupMenu: PopupMenu? = null

    init {
        // 移到最前 is offered by place, and a card moved down by another's coming first keeps its contents: bound again
        if (page == ClipboardPage.Favorites) {
            addOnPagesUpdatedListener { notifyItemRangeChanged(0, min(2, itemCount), PLACE_CHANGED) }
        }
    }

    class ViewHolder(val entryUi: ClipboardEntryUi) : RecyclerView.ViewHolder(entryUi.root) {
        // a recycled card's, taken off before this entry's go on
        val a11yActions = mutableListOf<Int>()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ClipboardEntryUi(parent.context, theme, look, entryRadius))

    private fun act(action: ClipboardAction, entry: ClipboardEntry) = when (action) {
        ClipboardAction.Favorite -> onFavorite(entry.id)
        ClipboardAction.Unfavorite -> onUnfavorite(entry.id)
        ClipboardAction.MoveFirst -> onMoveFirst(entry.id)
        ClipboardAction.Split -> onSplit(entry)
        ClipboardAction.Edit -> onEdit(entry.id)
        ClipboardAction.SaveQuickPhrase -> onSaveQuickPhrase(entry)
        ClipboardAction.Share -> onShare(entry)
        ClipboardAction.Delete -> onDelete(entry.id)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position) ?: return
        val masked = entry.sensitive && maskSensitive
        val actions = ClipboardPages.actions(page, entry.sensitive, masked, first = position == 0)
        with(holder.entryUi) {
            setEntry(excerptText(entry.text, masked), masked)
            holder.a11yActions.forEach { ViewCompat.removeAccessibilityAction(root, it) }
            holder.a11yActions.clear()
            // in TalkBack's actions menu without the long press
            actions.filter { it in A11Y_ACTIONS }.forEach { action ->
                holder.a11yActions += ViewCompat.addAccessibilityAction(root, ctx.getString(label(action))) { _, _ ->
                    act(action, entry)
                    true
                }
            }
            root.setOnClickListener {
                onPaste(entry)
            }
            root.setOnLongClickListener {
                val popup = PopupMenu(ctx, root)
                val menu = popup.menu
                val iconTint = ctx.styledColor(android.R.attr.colorControlNormal)
                actions.forEach { action -> menu.item(label(action), icon(action), iconTint) { act(action, entry) } }
                if (!DeviceUtil.isSamsungOneUI && !DeviceUtil.isFlyme) {
                    popup.setForceShowIcon(true)
                }
                popup.setOnDismissListener {
                    if (it === popupMenu) popupMenu = null
                }
                popupMenu?.dismiss()
                popupMenu = popup
                popup.show()
                true
            }
        }
    }

    fun getEntryAt(position: Int) = getItem(position)

    fun onDetached() {
        popupMenu?.dismiss()
        popupMenu = null
    }

    abstract fun onPaste(entry: ClipboardEntry)

    abstract fun onFavorite(id: Int)

    abstract fun onUnfavorite(id: Int)

    abstract fun onMoveFirst(id: Int)

    abstract fun onEdit(id: Int)

    abstract fun onSplit(entry: ClipboardEntry)

    abstract fun onShare(entry: ClipboardEntry)

    abstract fun onSaveQuickPhrase(entry: ClipboardEntry)

    abstract fun onDelete(id: Int)

}