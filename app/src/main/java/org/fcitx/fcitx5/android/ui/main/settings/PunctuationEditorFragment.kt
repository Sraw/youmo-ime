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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.R as MaterialR
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.getPunctuationConfig
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.materialTextInput
import org.fcitx.fcitx5.android.utils.onPositiveButtonClick
import org.fcitx.fcitx5.android.utils.str
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.resources.drawable

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
        fcitx.launchOnReady { PunctuationManager.save(it, lang, snapshot) }
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
        val first = MaterialCheckBox(ctx).apply { setText(R.string.punctuation_first) }
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
            val mapping = mappingField.str
            if (mapping.isBlank()) {
                mappingField.error = getString(R.string._cannot_be_empty, getString(R.string.punctuation_mapping))
                mappingField.requestFocus()
                return@onClick false
            }
            // a key may have several cards: typing it offers their marks as candidates
            val new = PunctuationMapEntry(key, mapping, altField.str)
            var at = index ?: entries.size
            if (index == null) entries.add(new) else entries[index] = new
            val firstOfKey = entries.indexOfFirst { it.key == key }
            if (first.isChecked && firstOfKey < at) {
                entries.removeAt(at)
                entries.add(firstOfKey, new)
                at = firstOfKey
            }
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
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(TextView(ctx).apply {
                        id = R.id.punctuation_mapping
                        textSize = 22f
                        maxLines = 1
                        setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurface))
                    })
                    addView(TextView(ctx).apply {
                        id = R.id.punctuation_pair
                        setText(R.string.punctuation_pair)
                        textSize = 10f
                        setTextColor(ctx.styledColor(android.R.attr.colorPrimary))
                    })
                })
            }, FrameLayout.LayoutParams(-1, -1))
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(args.title)
    }

    companion object {
        const val DEFAULT_LANG = "zh_CN"
        private const val HEADER = 0
        private const val CARD = 1
    }
}
