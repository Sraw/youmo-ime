/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.search

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
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
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
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
import timber.log.Timber

/** Finds a setting by its name, or by what it says, on any page; a result opens its page at it. */
class SettingsSearchFragment : Fragment() {

    // null until the index lands (SettingsIndexModel, off the main thread): what is typed meanwhile is searched then
    private var search: SettingsSearch<SettingsIndex.Target>? = null

    private val index: SettingsIndexModel by viewModels()

    private val viewModel: MainViewModel by activityViewModels()

    // the input methods added, to say so under the options of one not: none said until fcitx answers
    private var added: Set<String>? = null

    private var results: List<SettingsSearch.Found<SettingsIndex.Target>> = emptyList()

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
            val found = results[position]
            val entry = found.entry
            holder.title.text = entry.title
            holder.detail.apply {
                // where it is and what it is, then what it says
                val where = (listOf(entry.path.joinToString(" › ")) + notes(found)).filter { it.isNotEmpty() }.joinToString(" · ")
                text = listOf(where, entry.summary).filter { it.isNotEmpty() }.joinToString("\n")
                isVisible = text.isNotEmpty()
            }
            holder.itemView.setOnClickListener { open(found.entry) }
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
        // the keyboard at once only the first time: back from a result, the list is what is wanted
        if (query.isEmpty()) field.post {
            field.requestFocus()
            ctx.getSystemService<InputMethodManager>()?.showSoftInput(field, 0)
        }
        show(query)
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // started with the view, built on Dispatchers.Default while the page slides in; waited for only while the
        // view lives, the build itself kept over a rotation and cancelled with the page (SettingsIndexModel). A build
        // that throws throws here, at await(), on the main thread, as it did when it was built there
        if (search == null) viewLifecycleOwner.lifecycleScope.launch {
            search = index.index(requireContext()).await()
            show(query)
        }
    }

    // each time the page is back: an input method may have been added or removed on a result's page
    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch {
            val now = viewModel.fcitx.runOnReady { enabledIme().map { it.uniqueName }.toSet() }
            if (now == added) return@launch
            added = now
            @Suppress("NotifyDataSetChanged") // a word added under some rows, any of them
            adapter.notifyDataSetChanged()
        }
    }

    private fun notes(found: SettingsSearch.Found<SettingsIndex.Target>) = notes(requireContext(), found, added)

    private fun show(query: String) {
        this.query = query
        val search = search ?: return
        val start = SystemClock.elapsedRealtime()
        results = search.find(query)
        Timber.d("settings search %d ms, %d found", SystemClock.elapsedRealtime() - start, results.size)
        @Suppress("NotifyDataSetChanged") // a new list each key, nothing to diff against
        adapter.notifyDataSetChanged()
        none?.isVisible = results.isEmpty() && query.isNotBlank()
    }

    private fun open(entry: SettingsSearch.Entry<SettingsIndex.Target>) {
        val target = entry.target
        view?.let { requireContext().getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(it.windowToken, 0) }
        val nav = findNavController()
        // the home page's own row: back to the home page under every page, not one more of it
        if (target.route == SettingsRoute.Index) nav.popBackStack(SettingsRoute.Index, false) else navigateWithAnim(target.route)
        // the result's entry, on the back stack before its fragment is made: its page reads the id there
        nav.currentBackStackEntry?.let { SearchHighlight.point(it, target.highlight, target.highlightVia) }
        if (entry.kind == SettingsSearch.Kind.COMMAND) {
            // where a command is set is a key's swipe, which no row points at: said how instead
            val name = if (entry.id == SettingsIndex.STROKES) getString(R.string.search_strokes_one) else entry.title
            // stays until let go, it says what to do on the page it opened; but on android.R.id.content it would
            // outlive that page: gone at the first page change too
            val shownOn = nav.currentBackStackEntry
            val bar = Snackbar.make(requireActivity().findViewById(android.R.id.content), getString(R.string.search_command_hint, name), Snackbar.LENGTH_INDEFINITE)
            val leave = object : NavController.OnDestinationChangedListener {
                override fun onDestinationChanged(controller: NavController, destination: NavDestination, arguments: Bundle?) {
                    // called at once when added, on the page it is shown on
                    if (controller.currentBackStackEntry === shownOn) return
                    controller.removeOnDestinationChangedListener(this)
                    bar.dismiss()
                }
            }
            bar.setAction(R.string.search_got_it) {}
                .addCallback(object : Snackbar.Callback() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) = nav.removeOnDestinationChangedListener(leave)
                })
                .show()
            nav.addOnDestinationChangedListener(leave)
        }
    }

    companion object {
        /**
         * Said after where a result is: a choice, a command, an input method not in [added] (none said while it is
         * not known), the other name it was found by.
         */
        fun notes(context: Context, found: SettingsSearch.Found<SettingsIndex.Target>, added: Set<String>?): List<String> {
            val entry = found.entry
            val im = (entry.target.route as? SettingsRoute.InputMethodConfig)?.uniqueName
            return listOfNotNull(
                when (entry.kind) {
                    SettingsSearch.Kind.VALUE -> context.getString(R.string.search_is_choice)
                    SettingsSearch.Kind.COMMAND -> context.getString(R.string.search_is_command)
                    else -> null
                },
                context.getString(R.string.search_not_added).takeIf { im != null && added?.contains(im) == false },
                found.alias?.let { context.getString(R.string.search_also_called, it) },
            )
        }
    }
}
