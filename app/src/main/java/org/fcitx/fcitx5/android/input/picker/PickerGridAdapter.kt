/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.data.RecentlyUsed
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.OnGestureListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.HostKeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener.Source
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.keyRef
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.scene.layout.Face
import org.fcitx.fcitx5.android.scene.layout.KeyAttrs
import org.fcitx.fcitx5.android.scene.layout.Text
import org.fcitx.fcitx5.android.utils.alpha
import splitties.dimensions.dp

/**
 * The sections of [PickerGridView]: recently used first, then [rawData]'s categories; or [only]
 * one of them, as the symbol panel shows it.
 */
class PickerGridAdapter(
    private val grid: PickerGridView,
    private val keyActionListener: KeyActionListener,
    private val popupActionListener: PopupActionListener,
    private val rawData: List<Pair<PickerData.Category, Array<String>>>,
    recentlyUsedFileName: String,
    private val bordered: Boolean,
    private val policy: PickerPolicy
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    val categories = listOf(PickerData.RecentlyUsedCategory) + rawData.map { it.first }

    private var sections = PickerSections.Empty

    /** the one category shown, from the next [rebuild]; null for all of them */
    var only: Int? = null

    /** U-93's 「用我的输入习惯」: off, the recently used are neither shown nor kept (U-67) */
    var habits = true

    val recentEmpty get() = recentlyUsed.items(habits).isEmpty()

    private val recentlyUsed = RecentlyUsed(recentlyUsedFileName, grid.density.recentLimit)

    private var filtered: List<List<String>> = emptyList()
    private var filteredKey: Any? = null

    // what each item commits as under filteredKey: a skin tone's (code points, RGI set, glyph check) is too dear for every bind
    private val transforms = HashMap<String, String>()

    private val popupOnKeyPress by AppPrefs.getInstance().keyboard.popupOnKeyPress

    init {
        grid.gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) =
                if (sections.rows[position].header) grid.density.columnCount else 1
        }
    }

    fun insertRecent(text: String) {
        if (text.length == 1 && text[0].code.let { it in Digit || it in FullWidthDigit }) return
        recentlyUsed.insert(text, habits)
    }

    /**
     * Laid out again with what was recently used: each time the picker opens, not as a symbol is
     * picked, which would move the list under the finger picking the next.
     */
    @SuppressLint("NotifyDataSetChanged")
    fun rebuild() {
        val key = policy.invalidateKey()
        if (filteredKey != key || filtered.isEmpty()) {
            filteredKey = key
            filtered = rawData.map { (_, items) -> items.filter(policy::filter) }
            transforms.clear()
        }
        sections = PickerSections(listOf(recentlyUsed.items(habits)) + filtered, only)
        notifyDataSetChanged()
    }

    /** where [category] starts; past the end (the last ones all hidden), where the last with items does */
    fun startOf(category: Int): Int {
        val start = sections.startOf(category)
        return if (start < sections.rows.size) start else sections.startOf(sections.categoryAt(sections.rows.lastIndex))
    }

    fun categoryAt(position: Int) = sections.categoryAt(position)

    override fun getItemCount() = sections.rows.size

    override fun getItemViewType(position: Int) = if (sections.rows[position].header) HEADER else KEY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        val view = if (viewType == HEADER) TextView(ctx).apply {
            textSize = 12f
            setTextColor(grid.theme.keyTextColor.alpha(0.6f))
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(12), 0, dp(12), 0)
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, grid.headerHeight)
        } else SceneKeyCell(ctx, grid.theme, ownSize = true).apply {
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, grid.rowHeight)
        }
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val entry = sections.rows[position]
        if (entry.header) {
            val category = categories[entry.category]
            (holder.itemView as TextView).text =
                if (category.name != 0) holder.itemView.context.getString(category.name) else category.label
            return
        }
        val key = holder.itemView as SceneKeyCell
        key.layoutParams.let { if (it.height != grid.rowHeight) key.layoutParams = it.apply { height = grid.rowHeight } }
        // recently used ones as they were picked, skin tone and all: no popup to pick again
        if (entry.category == 0) bindAsIs(key, entry.text) else bind(key, entry.text)
    }

    /**
     * A symbol of two characters at a single's size reads as a bar across the panel's key: the
     * Chinese dash —— (two em dashes, drawn joined) and ellipsis …… are each two wide.
     */
    private fun label(key: SceneKeyCell, text: String) {
        val long = grid.density == PickerGridView.Density.Panel && text.codePointCount(0, text.length) > 1
        val size = grid.density.textSize * if (long) LONG else 1f
        // a scene of the one key (060-render-p2 S6): its text, its size, a face where the grid has borders
        key.attrs = cellAttrs(text, size)
        // what TalkBack reads
        key.contentDescription = text
    }

    private fun cellAttrs(text: String, size: Float) = KeyAttrs(
        label = Text.Literal(text),
        kind = "letter",
        textSize = size.toDouble(),
        face = if (bordered) Face.Default else Face.None,
        margin = bordered,
        a11y = Text.Literal(text),
    )

    private fun bindAsIs(key: SceneKeyCell, item: String) = key.apply {
        label(this, item)
        setOnClickListener { commit(item) }
        setOnLongClickListener(null)
        swipeEnabled = false
        onGestureListener = null
    }

    private fun bind(key: SceneKeyCell, item: String) = key.apply {
        val transformed = transforms.getOrPut(item) { policy.transform(item) }
        label(this, transformed)
        setOnClickListener { commit(transformed) }
        setOnLongClickListener longClick@{ view ->
            val popup = policy.popup(item) ?: return@longClick false
            showKeyboard(view, popup)
            false
        }
        swipeEnabled = true
        onGestureListener = OnGestureListener { view, event ->
            when (event.type) {
                CustomGestureView.GestureType.Down -> {
                    showPreview(view, item)
                    // never "consume" the gesture on touch down
                    false
                }
                CustomGestureView.GestureType.Move -> changeFocus(view.keyRef, event.x, event.y)
                CustomGestureView.GestureType.Up -> (!event.cancelled && trigger(view.keyRef)).also {
                    popupActionListener.onPopupAction(PopupAction.DismissAction(view.keyRef))
                }
            }
        }
    }

    private fun commit(text: String) {
        keyActionListener.onKeyAction(CommitAction(text), Source.Keyboard)
    }

    // where the key's face is now (the list may have scrolled since it was laid out)
    private fun boundsOf(view: View): Rect = (view as SceneKeyCell).bounds

    private fun showKeyboard(view: View, popup: KeyDef.Popup.Keyboard) {
        popupActionListener.onPopupAction(PopupAction.ShowKeyboardAction(view.keyRef, popup, boundsOf(view)))
    }

    private fun showPreview(view: View, item: String) {
        if (!popupOnKeyPress) return
        popupActionListener.onPopupAction(PopupAction.PreviewAction(view.keyRef, item, boundsOf(view)))
    }

    private fun changeFocus(key: KeyRef, x: Float, y: Float): Boolean {
        val action = PopupAction.ChangeFocusAction(key, x, y)
        popupActionListener.onPopupAction(action)
        return action.outResult
    }

    private fun trigger(key: KeyRef): Boolean {
        val action = PopupAction.TriggerAction(key)
        popupActionListener.onPopupAction(action)
        // a candidate longer than one character (user-entered) arrives as a CommitAction
        val text = when (val picked = action.outAction) {
            is HostKeyAction -> picked.act
            is CommitAction -> picked.text
            else -> return false
        }
        commit(text)
        popupActionListener.onPopupAction(PopupAction.DismissAction(key))
        return true
    }

    companion object {
        private const val KEY = 0
        private const val HEADER = 1
        private const val LONG = 0.6f
        private val Digit = IntRange('0'.code, '9'.code)
        private val FullWidthDigit = IntRange('０'.code, '９'.code)
    }
}
