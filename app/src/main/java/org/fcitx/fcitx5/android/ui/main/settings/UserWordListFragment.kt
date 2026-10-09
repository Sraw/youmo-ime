/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.fcitx.fcitx5.android.engine.user.WordLists
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.materialTextInput
import org.fcitx.fcitx5.android.utils.onPositiveButtonClick
import org.fcitx.fcitx5.android.utils.str
import org.fcitx.fcitx5.android.utils.styledColor
import org.fcitx.fcitx5.android.utils.toast
import splitties.dimensions.dp
import splitties.resources.styledDrawable
import splitties.views.backgroundColor
import splitties.views.bottomPadding
import splitties.views.dsl.coordinatorlayout.coordinatorLayout
import splitties.views.dsl.coordinatorlayout.defaultLParams
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityBottomEnd
import splitties.views.gravityCenterVertical
import splitties.views.imageDrawable
import splitties.views.setPaddingDp
import kotlin.math.roundToInt

/**
 * One list of the user dictionary ([UserWord.Kind]): searched as the user types, a word acted on
 * by a tap, several deleted at once after a long press. A list may run to thousands: a
 * [RecyclerView] makes views for the rows on screen only, and the search runs off the main
 * thread, the list shown whole again rather than diffed, as a search changes most of it.
 */
class UserWordListFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private val args by lazyRoute<SettingsRoute.UserWordList>()
    private val kind by lazy { UserWord.Kind.valueOf(args.kind) }

    private var all = emptyList<UserWord>()
    private var shown = emptyList<UserWord>()
    private var query = ""
    private val selected = LinkedHashSet<UserWord>()
    private var filtering: Job? = null

    private lateinit var countView: TextView
    private var fab: FloatingActionButton? = null
    private val adapter = WordAdapter()

    private val backToList = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            selected.clear()
            selectionChanged()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val (searchLayout, searchField) = ctx.materialTextInput { hint = getString(R.string.search_words_hint) }
        searchLayout.endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
        searchField.apply {
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            doAfterTextChanged {
                query = it.toString()
                filter(immediately = false)
            }
        }
        countView = ctx.textView {
            setPaddingDp(20, 4, 20, 8)
            setTextColor(ctx.styledColor(android.R.attr.textColorSecondary))
        }
        val recycler = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = this@UserWordListFragment.adapter
            addItemDecoration(DividerItemDecoration(ctx, DividerItemDecoration.VERTICAL))
            clipToPadding = false
        }
        val fab = if (kind == UserWord.Kind.LEARNED) null else FloatingActionButton(ctx).apply {
            this@UserWordListFragment.fab = this
            imageDrawable = ctx.getDrawable(R.drawable.ic_baseline_plus_24)
            contentDescription = getString(if (kind == UserWord.Kind.BLOCKED) R.string.block_word else R.string.add_word)
            setOnClickListener { add() }
        }
        val root = ctx.coordinatorLayout {
            backgroundColor = ctx.styledColor(android.R.attr.colorBackground)
            add(ctx.verticalLayout {
                add(searchLayout, lParams(matchParent) { setMargins(dp(16), dp(8), dp(16), 0) })
                add(countView, lParams(matchParent))
                add(recycler, lParams(matchParent, 0) { weight = 1f })
            }, defaultLParams(matchParent, matchParent))
            fab?.let {
                add(it, defaultLParams(wrapContent, wrapContent) {
                    gravity = gravityBottomEnd
                    setMargins(dp(16), dp(16), dp(16), dp(16))
                })
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            // the last rows clear of the button, which clears the navigation bar
            recycler.bottomPadding = bottom + if (fab != null) ctx.dp(88) else 0
            fab?.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = bottom + ctx.dp(16) }
            insets
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backToList)
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                if (selected.isEmpty()) return
                val tint = requireContext().styledColor(android.R.attr.colorControlNormal)
                menu.item(R.string.select_all, R.drawable.ic_baseline_select_all_24, tint, showAsAction = true) {
                    selected.addAll(shown)
                    selectionChanged()
                }
                menu.item(R.string.delete_selected, R.drawable.ic_baseline_delete_24, tint, showAsAction = true) {
                    confirmRemove(selected.toList())
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem) = false
        }, viewLifecycleOwner, Lifecycle.State.STARTED)
        load()
    }

    override fun onStart() {
        super.onStart()
        setTitle()
    }

    private fun setTitle() {
        viewModel.setToolbarTitle(
            if (selected.isEmpty()) getString(UserWordsFragment.title(kind))
            else getString(R.string.selected_count, selected.size)
        )
    }

    private fun load() {
        lifecycleScope.launch {
            all = viewModel.fcitx.runOnReady { userWords() }.filter { it.kind == kind }
            // by word and reading: a learned word's count may have changed since it was picked
            val picked = selected.mapTo(HashSet()) { it.text to it.pinyin }
            selected.clear()
            all.filterTo(selected) { (it.text to it.pinyin) in picked }
            filter(immediately = true)
            selectionChanged()
        }
    }

    /** [all] narrowed to [query]: a word with it in, or a pinyin with it in, spaces and `'` aside. */
    private fun filter(immediately: Boolean) {
        filtering?.cancel()
        // a load that ends after the page is left has nothing to show
        val owner = viewLifecycleOwnerLiveData.value ?: return
        filtering = owner.lifecycleScope.launch {
            // a search as the user types, not one a keystroke
            if (!immediately) delay(SEARCH_DELAY)
            val q = query.trim().lowercase().replace(Regex("[\\s']"), "")
            val words = all
            shown = if (q.isEmpty()) words else withContext(Dispatchers.Default) {
                // those that start with it first: typing a word's start finds it at the top
                val (starting, within) = words.mapNotNull { word ->
                    val text = word.text.lowercase()
                    val pinyin = word.pinyin.replace(" ", "").lowercase()
                    when {
                        text.startsWith(q) || pinyin.startsWith(q) -> word to true
                        q in text || q in pinyin -> word to false
                        else -> null
                    }
                }.partition { it.second }
                starting.map { it.first } + within.map { it.first }
            }
            adapter.notifyDataSetChanged()
            countView.text = when {
                words.isEmpty() -> getString(R.string.word_list_empty)
                q.isEmpty() -> getString(R.string.word_list_count, words.size)
                else -> getString(R.string.word_list_found, shown.size, words.size)
            }
        }
    }

    private fun selectionChanged() {
        backToList.isEnabled = selected.isNotEmpty()
        // adding while choosing what to delete would be a muddle
        fab?.let { if (selected.isEmpty()) it.show() else it.hide() }
        requireActivity().invalidateMenu()
        setTitle()
        adapter.notifyDataSetChanged()
    }

    private fun toggle(word: UserWord) {
        if (!selected.remove(word)) selected += word
        selectionChanged()
    }

    private fun removeLabel() = when (kind) {
        UserWord.Kind.ADDED -> R.string.delete
        UserWord.Kind.LEARNED -> R.string.forget_word
        UserWord.Kind.BLOCKED -> R.string.unblock_word
    }

    private fun confirmRemove(words: List<UserWord>) {
        if (words.isEmpty()) return
        val message = when (kind) {
            UserWord.Kind.ADDED -> R.string.confirm_delete_words
            UserWord.Kind.LEARNED -> R.string.confirm_forget_words
            UserWord.Kind.BLOCKED -> R.string.confirm_unblock_words
        }
        AlertDialog.Builder(requireContext())
            .setMessage(getString(message, words.size))
            .setPositiveButton(removeLabel()) { _, _ -> remove(words) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun remove(words: List<UserWord>) {
        val ctx = requireContext()
        // thousands at once take a moment: the added words' model is made again without them
        lifecycleScope.withLoadingDialog(ctx) {
            try {
                viewModel.fcitx.runOnReady { removeUserWords(words) }
                selected.removeAll(words.toSet())
                all = viewModel.fcitx.runOnReady { userWords() }.filter { it.kind == kind }
                filter(immediately = true)
                selectionChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // a word list that could not be written: shown, where uncaught it would close the app
                ctx.toast(e)
            }
        }
    }

    /** What can be done to [word]: edited (not a blocked one), taken off its list. */
    private fun act(word: UserWord) {
        val actions = buildList {
            if (kind != UserWord.Kind.BLOCKED) add(R.string.edit)
            add(removeLabel())
        }
        AlertDialog.Builder(requireContext())
            .setTitle("${word.text}  ${word.pinyin}")
            .setItems(actions.map { getString(it) }.toTypedArray()) { _, which ->
                if (actions[which] == R.string.edit) {
                    edit(R.string.edit, word) { text, pinyin ->
                        // added before the old is taken off: a word that does not read so leaves both be
                        addUserWord(text, pinyin).also { if (it) removeUserWords(listOf(word)) }
                    }
                } else {
                    remove(listOf(word))
                }
            }
            .show()
    }

    private fun add() = if (kind == UserWord.Kind.BLOCKED) {
        edit(R.string.block_word, null) { text, pinyin -> blockUserWord(text, pinyin) }
    } else {
        edit(R.string.add_word, null) { text, pinyin -> addUserWord(text, pinyin) }
    }

    /**
     * Asks for a word and its pinyin, prefilled with [word]'s, and hands them to [save]; kept
     * open, with an error under the pinyin, while they do not read together. Left blank, the
     * pinyin is filled in as the dictionary reads the word, for the user to check and save.
     */
    private fun edit(title: Int, word: UserWord?, save: suspend FcitxAPI.(String, String) -> Boolean) {
        val ctx = requireContext()
        val (textLayout, textField) = ctx.materialTextInput { hint = getString(R.string.word_text) }
        textField.apply {
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        val (pinyinLayout, pinyinField) = ctx.materialTextInput { hint = getString(R.string.word_pinyin) }
        pinyinField.apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        word?.let {
            textField.setText(it.text)
            pinyinField.setText(it.pinyin)
        }
        val layout = ctx.verticalLayout {
            setPaddingDp(20, 10, 20, 0)
            add(textLayout, lParams(matchParent))
            add(pinyinLayout, lParams(matchParent))
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .onPositiveButtonClick onClick@{
                val text = textField.str.trim()
                val pinyin = pinyinField.str
                if (text.isEmpty()) {
                    textField.error = getString(R.string._cannot_be_empty, getString(R.string.word_text))
                    return@onClick false
                }
                val dialog = this
                if (pinyin.isBlank()) {
                    // read as the dictionary reads it, shown for the user to check before saving
                    lifecycleScope.launch {
                        val guessed = viewModel.fcitx.runOnReady { pinyinOf(text) }
                        if (guessed == null) {
                            pinyinField.error = getString(R.string.word_pinyin_unknown)
                        } else {
                            pinyinField.setText(guessed)
                        }
                    }
                    return@onClick false
                }
                val entry = WordLists.entry(text, pinyin)
                if (entry == null) {
                    pinyinField.error = getString(R.string.word_pinyin_invalid)
                    return@onClick false
                }
                // unchanged: nothing to do
                if (word != null && WordLists.entry(word.text, word.pinyin) == entry) return@onClick true
                lifecycleScope.launch {
                    if (viewModel.fcitx.runOnReady { save(text, pinyin) }) {
                        dialog.dismiss()
                        load()
                    } else {
                        pinyinField.error = getString(R.string.word_pinyin_invalid)
                    }
                }
                false
            }
    }

    private inner class WordHolder(val root: View, val text: TextView, val detail: TextView, val check: CheckBox) :
        RecyclerView.ViewHolder(root)

    private inner class WordAdapter : RecyclerView.Adapter<WordHolder>() {
        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WordHolder {
            val ctx = parent.context
            val text = ctx.textView { textSize = 18f }
            val detail = ctx.textView { setTextColor(ctx.styledColor(android.R.attr.textColorSecondary)) }
            // the row takes the tap: the box only shows it
            val check = CheckBox(ctx).apply {
                isClickable = false
                isFocusable = false
            }
            val root = ctx.horizontalLayout {
                gravity = gravityCenterVertical
                setPaddingDp(20, 10, 12, 10)
                background = ctx.styledDrawable(android.R.attr.selectableItemBackground)
                add(ctx.verticalLayout {
                    add(text, lParams(matchParent))
                    add(detail, lParams(matchParent))
                }, lParams(0, wrapContent) { weight = 1f })
                add(check, lParams(wrapContent, wrapContent))
                layoutParams = RecyclerView.LayoutParams(matchParent, wrapContent)
            }
            return WordHolder(root, text, detail, check)
        }

        override fun onBindViewHolder(holder: WordHolder, position: Int) {
            val word = shown[position]
            holder.text.text = word.text
            holder.detail.text = if (word.kind == UserWord.Kind.LEARNED) {
                val times = word.count.roundToInt().coerceAtLeast(1)
                resources.getQuantityString(R.plurals.word_typed_times, times, word.pinyin, times)
            } else {
                word.pinyin
            }
            holder.check.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
            holder.check.isChecked = word in selected
            holder.root.setOnClickListener { if (selected.isEmpty()) act(word) else toggle(word) }
            holder.root.setOnLongClickListener {
                toggle(word)
                true
            }
        }
    }

    companion object {
        private const val SEARCH_DELAY = 150L
    }
}
