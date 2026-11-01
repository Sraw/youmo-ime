/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.search.SettingsSearch
import org.fcitx.fcitx5.android.utils.materialTextInput
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import splitties.views.setPaddingDp

/** Finds a setting by its name, or by what it says, on any page; a result opens its page at it. */
class SettingsSearchFragment : Fragment() {

    private val search by lazy { SettingsSearch(SettingsIndex.build(requireContext())) }

    private var results: List<SettingsSearch.Entry<SettingsIndex.Target>> = emptyList()

    // kept while the fragment is on the back stack: coming back shows the same results
    private var query = ""

    private var none: TextView? = null

    private var list: RecyclerView? = null

    private class ResultHolder(row: View, val title: TextView, val detail: TextView) : RecyclerView.ViewHolder(row)

    private val adapter = object : RecyclerView.Adapter<ResultHolder>() {
        override fun getItemCount() = results.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultHolder {
            val ctx = parent.context
            val title = ctx.textView {
                textSize = 16f
                setTextColor(ctx.styledColor(android.R.attr.textColorPrimary))
            }
            val detail = ctx.textView {
                textSize = 13f
                setTextColor(ctx.styledColor(android.R.attr.textColorSecondary))
            }
            val row = ctx.verticalLayout {
                setPaddingDp(20, 12, 20, 12)
                isClickable = true
                isFocusable = true
                background = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run {
                    getDrawable(0).also { recycle() }
                }
                add(title, lParams(matchParent))
                add(detail, lParams(matchParent))
                layoutParams = RecyclerView.LayoutParams(matchParent, wrapContent)
            }
            return ResultHolder(row, title, detail)
        }

        override fun onBindViewHolder(holder: ResultHolder, position: Int) {
            val entry = results[position]
            holder.title.text = entry.title
            holder.detail.apply {
                // where it is, then what it says
                text = listOf(entry.path.joinToString(" › "), entry.summary).filter { it.isNotEmpty() }.joinToString("\n")
                isVisible = text.isNotEmpty()
            }
            holder.itemView.setOnClickListener { open(entry.target) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val (fieldLayout, field) = ctx.materialTextInput { hint = getString(R.string.search_settings_hint) }
        fieldLayout.endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
        field.apply {
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(query)
            doAfterTextChanged { show(it.toString()) }
        }
        val none = ctx.textView {
            setPaddingDp(20, 16, 20, 16)
            setText(R.string.search_settings_none)
            setTextColor(ctx.styledColor(android.R.attr.textColorSecondary))
            isVisible = false
        }.also { none = it }
        val list = RecyclerView(ctx).also { list = it }.apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = this@SettingsSearchFragment.adapter
            addItemDecoration(DividerItemDecoration(ctx, DividerItemDecoration.VERTICAL))
        }
        show(query)
        // the keyboard at once only the first time: back from a result, the list is what is wanted
        if (query.isEmpty()) field.post {
            field.requestFocus()
            ctx.getSystemService<InputMethodManager>()?.showSoftInput(field, 0)
        }
        return ctx.verticalLayout {
            backgroundColor = ctx.styledColor(android.R.attr.colorBackground)
            add(fieldLayout, lParams(matchParent) { setMargins(dp(16), dp(8), dp(16), 0) })
            add(none, lParams(matchParent))
            add(list, lParams(matchParent, 0) { weight = 1f })
        }
    }

    // the adapter and these fields outlive the view on the back stack: let go of the old views, or they keep it
    override fun onDestroyView() {
        list?.adapter = null
        list = null
        none = null
        super.onDestroyView()
    }

    private fun show(query: String) {
        this.query = query
        results = search.find(query)
        @Suppress("NotifyDataSetChanged") // a new list each key, nothing to diff against
        adapter.notifyDataSetChanged()
        none?.isVisible = results.isEmpty() && query.isNotBlank()
    }

    private fun open(target: SettingsIndex.Target) {
        view?.let { requireContext().getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(it.windowToken, 0) }
        navigateWithAnim(target.route)
        // the result's entry, on the back stack before its fragment is made: its page reads the key there
        findNavController().currentBackStackEntry?.let { SearchHighlight.point(it, target.key, target.via) }
    }
}
