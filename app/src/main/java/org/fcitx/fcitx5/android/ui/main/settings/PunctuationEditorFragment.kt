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
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.R as MaterialR
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
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
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
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

    // how many cards each key has, counted at each change rather than at each card's bind
    private var cardsOfKey = emptyMap<String, Int>()

    private lateinit var adapter: Adapter

    private lateinit var list: RecyclerView

    private var undo: Snackbar? = null

    override suspend fun initialize(): View {
        lang = args.lang ?: DEFAULT_LANG
        val loaded = saves.load(fcitx, lang)
        entries.clear()
        entries.addAll(loaded)
        countCards()
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
            add(list, lParams(matchParent, matchParent))
            val margin = ctx.dp(16)
            add(fab, lParams(wrapContent, wrapContent, Gravity.END or Gravity.BOTTOM) {
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
        saves.save(lang, entries.toList())
    }

    private fun countCards() {
        cardsOfKey = entries.groupingBy { it.key }.eachCount()
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
            // a key's only card is its first, with no other to come before
            isVisible = entries.hasOtherCards(index, keyField.str)
        }
        // the place it holds is its own key's: a changed key, the box left alone, does not give it the new one's
        var firstSetByHand = false
        first.setOnClickListener { firstSetByHand = true }
        keyField.doAfterTextChanged {
            if (!firstSetByHand) first.isChecked = entries.firstBoxChecked(index, keyField.str)
            first.isVisible = entries.hasOtherCards(index, keyField.str)
        }
        val layout = ctx.verticalLayout {
            setPadding(ctx.dp(20), ctx.dp(10), ctx.dp(20), 0)
            listOf(keyLayout, mappingLayout, altLayout, first).forEach { add(it, lParams(matchParent)) }
        }
        val builder = AlertDialog.Builder(ctx)
            .setTitle(entry?.key ?: getString(R.string.punctuation_add))
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (index != null) {
            builder.setNeutralButton(R.string.delete) { _, _ -> delete(index) }
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
            val at = entries.putCard(index, PunctuationMapEntry(key, mapping, altField.str), first.isChecked)
            countCards()
            // whether a key has choices shows on each of its cards, and cards may have moved
            adapter.notifyDataSetChanged()
            list.scrollToPosition(at + OFFSET)
            save()
            true
        }
    }

    private fun delete(index: Int) {
        val removed = entries.removeAt(index)
        countCards()
        adapter.notifyItemRemoved(index + OFFSET)
        adapter.notifyItemRangeChanged(OFFSET, entries.size)
        save()
        // one tap takes a card, maybe one of the defaults, which nothing else on the page puts back
        undo = Snackbar.make(list, getString(R.string.removed_x, "${removed.key} → ${removed.marks}"), Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { restore(index, removed) }
            .also { it.show() }
    }

    private fun restore(index: Int, card: PunctuationMapEntry) {
        val at = entries.restoreCard(index, card)
        countCards()
        adapter.notifyItemInserted(at + OFFSET)
        adapter.notifyItemRangeChanged(OFFSET, entries.size)
        list.scrollToPosition(at + OFFSET)
        save()
    }

    private class CardHolder(card: View, val key: TextView, val mapping: TextView, val caption: TextView) :
        RecyclerView.ViewHolder(card)

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = entries.size + OFFSET

        override fun getItemViewType(position: Int) = if (position == 0) HEADER else CARD

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            if (viewType == CARD) return card(ctx)
            val header = TextView(ctx).apply {
                setText(R.string.punctuation_page_hint)
                textSize = 14f
                setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant))
                setPadding(ctx.dp(4), 0, ctx.dp(4), ctx.dp(12))
            }
            return object : RecyclerView.ViewHolder(header) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder !is CardHolder) return
            val entry = entries[position - OFFSET]
            holder.key.text = entry.key
            holder.mapping.text = entry.marks
            val caption = when {
                (cardsOfKey[entry.key] ?: 0) > 1 -> R.string.punctuation_choice
                entry.altMapping.isNotEmpty() -> R.string.punctuation_pair
                else -> 0
            }
            holder.caption.apply {
                visibility = if (caption == 0) View.GONE else View.VISIBLE
                if (caption != 0) setText(caption)
            }
            holder.itemView.setOnClickListener {
                holder.bindingAdapterPosition.takeIf { it >= OFFSET }?.let { edit(it - OFFSET) }
            }
        }

        private fun card(ctx: Context): CardHolder {
            val key = KeyCaps.label(ctx, "").apply { background = KeyCaps.background(ctx, radius = 6) }
            val mapping = TextView(ctx).apply {
                textSize = 22f
                maxLines = 1
                setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurface))
            }
            val caption = TextView(ctx).apply {
                textSize = 10f
                setTextColor(ctx.styledColor(android.R.attr.colorPrimary))
            }
            val card = MaterialCardView(ctx).apply {
                radius = ctx.dp(12).toFloat()
                cardElevation = 0f
                strokeWidth = ctx.dp(1)
                strokeColor = ctx.styledColor(MaterialR.attr.colorOutlineVariant)
                setCardBackgroundColor(ctx.styledColor(MaterialR.attr.colorSurfaceContainerLow))
                layoutParams = ViewGroup.MarginLayoutParams(matchParent, ctx.dp(72)).apply {
                    val m = ctx.dp(4)
                    setMargins(m, m, m, m)
                }
                // the key, the arrow and the mark on one line whatever the caption: it sits in the
                // corner, out of the line, which it pushed up when under the mark
                add(ctx.horizontalLayout {
                    gravity = Gravity.CENTER
                    add(key, lParams(ctx.dp(36), ctx.dp(36)))
                    add(TextView(ctx).apply {
                        text = "→"
                        setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant))
                    }, lParams(wrapContent, wrapContent) {
                        marginStart = ctx.dp(8)
                        marginEnd = ctx.dp(8)
                    })
                    add(mapping, lParams())
                }, lParams(matchParent, matchParent))
                add(caption, lParams(wrapContent, wrapContent, Gravity.BOTTOM or Gravity.END) {
                    setMargins(0, 0, ctx.dp(8), ctx.dp(4))
                })
            }
            return CardHolder(card, key, mapping, caption)
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(args.title ?: getString(R.string.punctuation_map))
    }

    override fun onDestroyView() {
        // its undo puts a card back on this page
        undo?.dismiss()
        undo = null
        super.onDestroyView()
    }

    companion object {
        const val DEFAULT_LANG = "zh_CN"
        private const val HEADER = 0
        private const val CARD = 1
        // the cards come after the page's hint
        private const val OFFSET = 1

        // one save at a time, the newest last, on the app's scope: separate saves waiting for fcitx
        // could land in any order, and fcitx's own scope drops what waits on it when it stops; one
        // queue for all instances of the page, so that one made anew (a rotation) comes after the last
        private val saves by lazy {
            val name = PunctuationSaves::class.java.name
            PunctuationSaves(
                FcitxApplication.getInstance().coroutineScope,
                connect = { FcitxDaemon.connect(name) },
                disconnect = { FcitxDaemon.disconnect(name) }
            ) {
                Timber.w(it, "punctuation")
                // the fragment may be gone by now
                appContext.toast(it)
            }
        }
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
 * Whether the key [typed] has cards besides the one at [index] (null for a new one): only then is
 * there a first place among them to give or hand on.
 */
internal fun List<PunctuationMapEntry>.hasOtherCards(index: Int?, typed: String): Boolean {
    val key = typed.trim()
    return withIndex().any { (i, card) -> i != index && card.key == key }
}

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

/**
 * Puts [card] in place of the card at [index] (null for a new one), where
 * [punctuationCardPosition] says, and returns where it went.
 */
internal fun MutableList<PunctuationMapEntry>.putCard(index: Int?, card: PunctuationMapEntry, first: Boolean): Int {
    if (index != null) removeAt(index)
    return punctuationCardPosition(this, card.key, index ?: size, first).also { add(it, card) }
}

/** Puts back [card], deleted from [index], and returns where it went: the end if fewer cards are left. */
internal fun MutableList<PunctuationMapEntry>.restoreCard(index: Int, card: PunctuationMapEntry): Int =
    minOf(index, size).also { add(it, card) }

private val PunctuationMapEntry.marks
    get() = if (altMapping.isEmpty()) mapping else "$mapping $altMapping"
