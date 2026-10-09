/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.R as MaterialR
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.getPunctuationConfig
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.materialTextInput
import org.fcitx.fcitx5.android.utils.onPositiveButtonClick
import org.fcitx.fcitx5.android.utils.str
import org.fcitx.fcitx5.android.utils.styledColor
import org.fcitx.fcitx5.android.utils.toast
import splitties.dimensions.dp
import splitties.resources.drawable
import timber.log.Timber

/**
 * The Chinese punctuation map as cards, the key drawn as a key and what it types beside it.
 * A key with several cards offers their marks as candidates; a card opens to change or delete
 * it, and each change is saved as it is made.
 */
class PunctuationEditorFragment : ProgressFragment() {

    private val args by lazyRoute<SettingsRoute.Punctuation>()

    private lateinit var lang: String

    private val entries = mutableListOf<PunctuationMapEntry>()

    private lateinit var adapter: Adapter

    private lateinit var list: RecyclerView

    // one save at a time, the newest last, on the app's scope: separate saves waiting for fcitx
    // could land in any order, and fcitx's own scope drops what waits on it when it stops
    private val saves = LatestWriter(FcitxApplication.getInstance().coroutineScope) {
        Timber.w(it, "punctuation")
        // the fragment may be gone by now
        appContext.toast(it)
    }

    override suspend fun initialize(): View {
        lang = args.lang ?: DEFAULT_LANG
        val raw = fcitx.runOnReady { getPunctuationConfig(lang) }
        entries.clear()
        entries.addAll(PunctuationManager.parseRawConfig(raw))
        viewModel.toolbarButton.value = ButtonMode.NONE
        val ctx = requireContext()
        adapter = Adapter()
        val columns = 3
        list = RecyclerView(ctx).apply {
            layoutManager = GridLayoutManager(ctx, columns).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) = if (position == 0) columns else 1
                }
            }
            adapter = this@PunctuationEditorFragment.adapter
            clipToPadding = false
            val pad = ctx.dp(12)
            // room under the last row for the add button
            setPadding(pad, pad, pad, ctx.dp(88))
        }
        val fab = FloatingActionButton(ctx).apply {
            setImageDrawable(ctx.drawable(R.drawable.ic_baseline_plus_24))
            contentDescription = getString(R.string.punctuation_add)
            setOnClickListener { edit(null) }
        }
        return FrameLayout(ctx).apply {
            setBackgroundColor(ctx.styledColor(android.R.attr.colorBackground))
            addView(list, FrameLayout.LayoutParams(-1, -1))
            val margin = ctx.dp(16)
            addView(fab, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply {
                setMargins(margin, margin, margin, margin)
            })
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                fab.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = margin + bottom }
                list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, ctx.dp(88) + bottom)
                insets
            }
        }
    }

    private fun save() {
        val snapshot = entries.toList()
        val connection = fcitx
        val language = lang
        saves.submit { connection.runOnReady { PunctuationManager.save(this, language, snapshot) } }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun edit(index: Int?) {
        val ctx = requireContext()
        val entry = index?.let { entries[it] }
        val (keyLayout, keyField) = ctx.materialTextInput { hint = getString(R.string.punctuation_key) }
        val (mappingLayout, mappingField) = ctx.materialTextInput { hint = getString(R.string.punctuation_mapping) }
        val (altLayout, altField) = ctx.materialTextInput { hint = getString(R.string.punctuation_alt_mapping) }
        altLayout.helperText = getString(R.string.punctuation_alt_mapping_helper)
        keyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        entry?.let {
            keyField.setText(it.key)
            mappingField.setText(it.mapping)
            altField.setText(it.altMapping)
        }
        // a key's cards are its candidates in order: this puts one before the others
        val first = MaterialCheckBox(ctx).apply {
            setText(R.string.punctuation_first)
            // checked for the card that is first already, so that unchecking it hands the place on
            isChecked = entries.firstBoxChecked(index, keyField.str)
        }
        // the place it holds is its own key's: a changed key, the box left alone, does not give it the new one's
        var firstSetByHand = false
        first.setOnClickListener { firstSetByHand = true }
        keyField.doAfterTextChanged {
            if (!firstSetByHand) first.isChecked = entries.firstBoxChecked(index, keyField.str)
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(10), ctx.dp(20), 0)
            listOf(keyLayout, mappingLayout, altLayout, first).forEach { addView(it, LinearLayout.LayoutParams(-1, -2)) }
        }
        val builder = AlertDialog.Builder(ctx)
            .setTitle(if (entry == null) getString(R.string.punctuation_add) else args.title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (index != null) {
            builder.setNeutralButton(R.string.delete) { _, _ ->
                entries.removeAt(index)
                adapter.notifyItemRemoved(index + 1)
                adapter.notifyItemRangeChanged(1, entries.size)
                save()
            }
        }
        // what was typed is not lost to a tap beside the dialog
        builder.show().apply { setCanceledOnTouchOutside(false) }.onPositiveButtonClick onClick@{
            val key = keyField.str.trim()
            if (key.isBlank()) {
                keyField.error = getString(R.string._cannot_be_empty, getString(R.string.punctuation_key))
                keyField.requestFocus()
                return@onClick false
            }
            if (!isPunctuationKey(key)) {
                keyField.error = getString(R.string.invalid_value)
                keyField.requestFocus()
                return@onClick false
            }
            val mapping = mappingField.str
            if (mapping.isBlank()) {
                mappingField.error = getString(R.string._cannot_be_empty, getString(R.string.punctuation_mapping))
                mappingField.requestFocus()
                return@onClick false
            }
            // a key may have several cards: typing it offers their marks as candidates
            val new = PunctuationMapEntry(key, mapping, altField.str)
            if (index != null) entries.removeAt(index)
            val at = punctuationCardPosition(entries, key, index ?: entries.size, first.isChecked)
            entries.add(at, new)
            // whether a key has choices shows on each of its cards, and cards may have moved
            adapter.notifyDataSetChanged()
            list.scrollToPosition(at + 1)
            save()
            true
        }
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = entries.size + 1

        override fun getItemViewType(position: Int) = if (position == 0) HEADER else CARD

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val view = if (viewType == HEADER) TextView(ctx).apply {
                setText(R.string.punctuation_page_hint)
                textSize = 14f
                setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant))
                setPadding(ctx.dp(4), 0, ctx.dp(4), ctx.dp(12))
            } else card(ctx)
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (position == 0) return
            val entry = entries[position - 1]
            val card = holder.itemView as MaterialCardView
            card.findViewById<TextView>(R.id.punctuation_key).text = entry.key
            card.findViewById<TextView>(R.id.punctuation_mapping).text =
                if (entry.altMapping.isEmpty()) entry.mapping else "${entry.mapping} ${entry.altMapping}"
            val caption = when {
                entries.count { it.key == entry.key } > 1 -> R.string.punctuation_choice
                entry.altMapping.isNotEmpty() -> R.string.punctuation_pair
                else -> 0
            }
            card.findViewById<TextView>(R.id.punctuation_pair).apply {
                visibility = if (caption == 0) View.GONE else View.VISIBLE
                if (caption != 0) setText(caption)
            }
            card.setOnClickListener { holder.bindingAdapterPosition.takeIf { it > 0 }?.let { edit(it - 1) } }
        }

        private fun card(ctx: Context) = MaterialCardView(ctx).apply {
            radius = ctx.dp(12).toFloat()
            cardElevation = 0f
            strokeWidth = ctx.dp(1)
            strokeColor = ctx.styledColor(MaterialR.attr.colorOutlineVariant)
            setCardBackgroundColor(ctx.styledColor(MaterialR.attr.colorSurfaceContainerLow))
            layoutParams = ViewGroup.MarginLayoutParams(-1, ctx.dp(72)).apply {
                val m = ctx.dp(4)
                setMargins(m, m, m, m)
            }
            // the key, the arrow and the mark on one line whatever the caption: it sits in the
            // corner, out of the line, which it pushed up when under the mark
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER
                addView(KeyCaps.label(ctx, "").apply {
                    id = R.id.punctuation_key
                    background = KeyCaps.background(ctx, radius = 6)
                }, LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)))
                addView(TextView(ctx).apply {
                    text = "→"
                    setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant))
                }, LinearLayout.LayoutParams(-2, -2).apply {
                    marginStart = ctx.dp(8)
                    marginEnd = ctx.dp(8)
                })
                addView(TextView(ctx).apply {
                    id = R.id.punctuation_mapping
                    textSize = 22f
                    maxLines = 1
                    setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurface))
                })
            }, FrameLayout.LayoutParams(-1, -1))
            addView(TextView(ctx).apply {
                id = R.id.punctuation_pair
                textSize = 10f
                setTextColor(ctx.styledColor(android.R.attr.colorPrimary))
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, ctx.dp(8), ctx.dp(4))
            })
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(args.title)
    }

    override fun onDestroy() {
        saves.close()
        super.onDestroy()
    }

    companion object {
        const val DEFAULT_LANG = "zh_CN"
        private const val HEADER = 0
        private const val CARD = 1
    }
}

/** fcitx's punctuation map keeps a key only when it is one character, and typing looks one up. */
internal fun isPunctuationKey(key: String) = key.codePointCount(0, key.length) == 1

/** Whether the card at [index] is the first of several for its key, the one the key types. */
internal fun List<PunctuationMapEntry>.isFirstOfSeveral(index: Int): Boolean {
    val key = this[index].key
    return indexOfFirst { it.key == key } == index && count { it.key == key } > 1
}

/**
 * Whether the "first" box of the card at [index] (null for a new one), left alone, is checked
 * while [typed] is in its key field: for the first of several cards of a key, and only while that
 * key is typed, since a changed key's first card is another one.
 */
internal fun List<PunctuationMapEntry>.firstBoxChecked(index: Int?, typed: String) =
    index != null && isFirstOfSeveral(index) && typed.trim() == this[index].key

/**
 * Where a card for [key] goes among [others], the cards without it, [at] being where it was (the
 * end for a new one). It goes first of its key only when [first] asks for it; left where it was
 * ahead of the key's other cards (a changed key can put it there), it goes after them instead.
 */
internal fun punctuationCardPosition(others: List<PunctuationMapEntry>, key: String, at: Int, first: Boolean): Int {
    val firstOfKey = others.indexOfFirst { it.key == key }
    return when {
        firstOfKey < 0 -> at
        first -> minOf(at, firstOfKey)
        at <= firstOfKey -> others.indexOfLast { it.key == key } + 1
        else -> at
    }
}
