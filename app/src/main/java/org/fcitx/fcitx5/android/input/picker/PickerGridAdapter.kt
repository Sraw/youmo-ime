/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.data.RecentlyUsed
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.OnGestureListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.FcitxKeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener.Source
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Border
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.utils.alpha
import splitties.dimensions.dp

/** The sections of [PickerGridView]: recently used first, then [rawData]'s categories. */
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

    private val recentlyUsed = RecentlyUsed(recentlyUsedFileName, grid.density.recentLimit)

    private var filtered: List<List<String>> = emptyList()
    private var filteredKey: Any? = null

    private val popupOnKeyPress by AppPrefs.getInstance().keyboard.popupOnKeyPress

    private val keyAppearance = Appearance.Text(
        displayText = "",
        textSize = grid.density.textSize,
        variant = Variant.Normal,
        border = if (bordered) Border.On else Border.Off
    )

    init {
        grid.gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) =
                if (sections.rows[position].header) grid.density.columnCount else 1
        }
    }

    fun insertRecent(text: String) {
        if (text.length == 1 && text[0].code.let { it in Digit || it in FullWidthDigit }) return
        recentlyUsed.insert(text)
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
        }
        sections = PickerSections(listOf(recentlyUsed.items) + filtered)
        notifyDataSetChanged()
    }

    fun startOf(category: Int) = sections.startOf(category)

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
        } else TextKeyView(ctx, grid.theme, keyAppearance).apply {
            if (grid.density.autoScale) {
                mainText.apply {
                    scaleMode = AutoScaleTextView.Mode.Proportional
                    setPadding(hMargin, vMargin, hMargin, vMargin)
                }
            }
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
        val key = holder.itemView as TextKeyView
        key.layoutParams.let { if (it.height != grid.rowHeight) key.layoutParams = it.apply { height = grid.rowHeight } }
        // recently used ones as they were picked, skin tone and all: no popup to pick again
        if (entry.category == 0) bindAsIs(key, entry.text) else bind(key, entry.text)
    }

    private fun bindAsIs(key: TextKeyView, item: String) = key.apply {
        mainText.text = item
        setOnClickListener { commit(item) }
        setOnLongClickListener(null)
        swipeEnabled = false
        onGestureListener = null
    }

    private fun bind(key: TextKeyView, item: String) = key.apply {
        val transformed = policy.transform(item)
        mainText.text = transformed
        setOnClickListener { commit(transformed) }
        setOnLongClickListener longClick@{ view ->
            if (view !is KeyView) return@longClick false
            val popup = policy.popup(item) ?: return@longClick false
            showKeyboard(view, popup)
            false
        }
        swipeEnabled = true
        onGestureListener = OnGestureListener { view, event ->
            view as KeyView
            when (event.type) {
                CustomGestureView.GestureType.Down -> {
                    showPreview(view, item)
                    // never "consume" the gesture on touch down
                    false
                }
                CustomGestureView.GestureType.Move -> changeFocus(view.id, event.x, event.y)
                CustomGestureView.GestureType.Up -> trigger(view.id).also {
                    popupActionListener.onPopupAction(PopupAction.DismissAction(view.id))
                }
            }
        }
    }

    private fun commit(text: String) {
        keyActionListener.onKeyAction(CommitAction(text), Source.Keyboard)
    }

    private fun showKeyboard(view: KeyView, popup: KeyDef.Popup.Keyboard) {
        // the list may have scrolled since the key was laid out: where it is now
        if (!popupOnKeyPress) view.updateBounds()
        popupActionListener.onPopupAction(PopupAction.ShowKeyboardAction(view.id, popup, view.bounds))
    }

    private fun showPreview(view: KeyView, item: String) {
        if (!popupOnKeyPress) return
        view.updateBounds()
        popupActionListener.onPopupAction(PopupAction.PreviewAction(view.id, item, view.bounds))
    }

    private fun changeFocus(viewId: Int, x: Float, y: Float): Boolean {
        val action = PopupAction.ChangeFocusAction(viewId, x, y)
        popupActionListener.onPopupAction(action)
        return action.outResult
    }

    private fun trigger(viewId: Int): Boolean {
        val action = PopupAction.TriggerAction(viewId)
        popupActionListener.onPopupAction(action)
        // a candidate longer than one character (user-entered) arrives as a CommitAction
        val text = when (val picked = action.outAction) {
            is FcitxKeyAction -> picked.act
            is CommitAction -> picked.text
            else -> return false
        }
        commit(text)
        popupActionListener.onPopupAction(PopupAction.DismissAction(viewId))
        return true
    }

    companion object {
        private const val KEY = 0
        private const val HEADER = 1
        private val Digit = IntRange('0'.code, '9'.code)
        private val FullWidthDigit = IntRange('０'.code, '９'.code)
    }
}
