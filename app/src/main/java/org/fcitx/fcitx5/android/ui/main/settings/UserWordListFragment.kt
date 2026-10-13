/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.res.Resources
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
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
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
import org.fcitx.fcitx5.android.engine.english.EnglishUser
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
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * One list of the user dictionary ([UserWord.Kind]): searched as the user types, a word acted on
 * by a tap, several deleted at once after a long press. A list may run to thousands: a
 * [RecyclerView] makes views for the rows on screen only, and the search runs off the main
 * thread, the list shown whole again rather than diffed, as a search changes most of it.
 *
 * With [SettingsRoute.UserWordList.english], the English words the user typed instead (the
 * English page's list): no pinyin, a word added as typed twice, forgotten as a learned one is.
 * What differs between the lists is the [WordSource] the route picks, once.
 */
class UserWordListFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private val args by lazyRoute<SettingsRoute.UserWordList>()
    private val source: WordSource by lazy {
        if (args.english) EnglishWords else PinyinWords(UserWord.Kind.valueOf(args.kind))
    }

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
        val (searchLayout, searchField) = ctx.materialTextInput {
            hint = getString(source.searchHint)
        }
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
        val fab = source.addLabel?.let { label ->
            FloatingActionButton(ctx).apply {
                this@UserWordListFragment.fab = this
                imageDrawable = ctx.getDrawable(R.drawable.ic_baseline_plus_24)
                contentDescription = getString(label)
                setOnClickListener { addWord(label) }
            }
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
            if (selected.isEmpty()) getString(source.title)
            else getString(R.string.selected_count, selected.size)
        )
    }

    private fun load() {
        lifecycleScope.launch {
            all = onFcitx("Reading user words failed") { source.words(this) }.getOrElse { return@launch }
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
            val q = searchKey(query)
            val words = all
            shown = if (q.isEmpty()) words else withContext(Dispatchers.Default) { searchWords(words, q) }
            adapter.notifyDataSetChanged()
            countView.text = when {
                words.isEmpty() -> getString(R.string.word_list_empty)
                q.isEmpty() -> resources.getQuantityString(R.plurals.word_list_count, words.size, words.size)
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

    private fun confirmRemove(words: List<UserWord>) {
        if (words.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setMessage(resources.getQuantityString(source.confirmRemove, words.size, words.size))
            .setPositiveButton(source.removeLabel) { _, _ -> remove(words) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun remove(words: List<UserWord>) {
        val ctx = requireContext()
        // thousands at once take a moment: the added words' model is made again without them
        lifecycleScope.withLoadingDialog(ctx) {
            try {
                viewModel.fcitx.runOnReady { source.remove(this, words) }
                selected.removeAll(words.toSet())
                all = words()
                filter(immediately = true)
                selectionChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // a word list that could not be written: shown, where uncaught it would close the app
                Timber.w(e, "Removing user words failed")
                ctx.toast(e)
            }
        }
    }

    private suspend fun words() = viewModel.fcitx.runOnReady { source.words(this) }

    /**
     * [block] on fcitx. Where it failed (a word list that could not be read or written, fcitx
     * stopping), [failed] is logged and the error shown, where uncaught it would close the app, as
     * [remove] shows it. A [Result], not null for a failure: what [block] gives may itself be null
     * (a word of no known pinyin).
     */
    private suspend fun <T> onFcitx(failed: String, block: suspend FcitxAPI.() -> T): Result<T> {
        val ctx = requireContext()
        return try {
            Result.success(viewModel.fcitx.runOnReady(block))
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, failed)
            ctx.toast(e)
            Result.failure(e)
        }
    }

    /** What can be done to [word]: edited where its list lets it be, taken off its list. */
    private fun act(word: UserWord) {
        val actions = buildList {
            if (source.editable) add(R.string.edit)
            add(source.removeLabel)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("${word.text}  ${word.pinyin}".trim())
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

    private fun addWord(@StringRes title: Int) = when (val list = source) {
        EnglishWords -> addEnglish(title)
        is PinyinWords -> edit(title, null) { text, pinyin -> list.add(this, text, pinyin) }
    }

    /** Asks for an English word, kept open with an error under it while it is none. */
    private fun addEnglish(@StringRes title: Int) {
        val ctx = requireContext()
        val (layout, field) = ctx.materialTextInput { hint = getString(R.string.word_text) }
        field.apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val padded = ctx.verticalLayout {
            setPaddingDp(20, 10, 20, 0)
            add(layout, lParams(matchParent))
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(padded)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .onPositiveButtonClick onClick@{
                val dialog = this
                val text = field.str.trim()
                lifecycleScope.launch {
                    val added = onFcitx(SAVING_FAILED) { addEnglishWord(text) }.getOrElse { return@launch }
                    if (added) {
                        dialog.dismiss()
                        load()
                    } else {
                        field.error = getString(R.string.english_word_invalid)
                    }
                }
                false
            }
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
                        val guessed = onFcitx("Reading a word's pinyin failed") { pinyinOf(text) }.getOrElse { return@launch }
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
                    val saved = onFcitx(SAVING_FAILED) { save(text, pinyin) }.getOrElse { return@launch }
                    if (saved) {
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
            holder.detail.text = source.detail(resources, word)
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
        private const val SAVING_FAILED = "Saving a user word failed"
    }
}

/**
 * What differs between the lists [UserWordListFragment] shows: its title and labels, where its
 * words come from and go, and what a row says of one. [addLabel] is null for a list nothing is
 * added to by hand; [editable], whether a word's text and pinyin may be edited.
 */
private sealed class WordSource(
    @StringRes val title: Int,
    @StringRes val searchHint: Int,
    @StringRes val addLabel: Int?,
    @StringRes val removeLabel: Int,
    @PluralsRes val confirmRemove: Int,
    val editable: Boolean,
) {
    abstract suspend fun words(fcitx: FcitxAPI): List<UserWord>

    abstract suspend fun remove(fcitx: FcitxAPI, words: List<UserWord>)

    abstract fun detail(res: Resources, word: UserWord): String
}

/** One list of pinyin's user dictionary: the words learned come from typing alone, a blocked one is unblocked, not edited. */
private class PinyinWords(private val kind: UserWord.Kind) : WordSource(
    title = UserWordsFragment.title(kind),
    searchHint = R.string.search_words_hint,
    addLabel = when (kind) {
        UserWord.Kind.ADDED -> R.string.add_word
        UserWord.Kind.LEARNED -> null
        UserWord.Kind.BLOCKED -> R.string.block_word
    },
    removeLabel = when (kind) {
        UserWord.Kind.ADDED -> R.string.delete
        UserWord.Kind.LEARNED -> R.string.forget_word
        UserWord.Kind.BLOCKED -> R.string.unblock_word
    },
    confirmRemove = when (kind) {
        UserWord.Kind.ADDED -> R.plurals.confirm_delete_words
        UserWord.Kind.LEARNED -> R.plurals.confirm_forget_words
        UserWord.Kind.BLOCKED -> R.plurals.confirm_unblock_words
    },
    editable = kind != UserWord.Kind.BLOCKED,
) {
    override suspend fun words(fcitx: FcitxAPI) = fcitx.userWords(kind)

    override suspend fun remove(fcitx: FcitxAPI, words: List<UserWord>) = fcitx.removeUserWords(words)

    /** A word typed in, put on this list: added, or blocked. */
    suspend fun add(fcitx: FcitxAPI, text: String, pinyin: String) =
        if (kind == UserWord.Kind.BLOCKED) fcitx.blockUserWord(text, pinyin) else fcitx.addUserWord(text, pinyin)

    override fun detail(res: Resources, word: UserWord): String = if (word.kind == UserWord.Kind.LEARNED) {
        val times = word.count.roundToInt().coerceAtLeast(1)
        res.getQuantityString(R.plurals.word_typed_times, times, word.pinyin, times)
    } else {
        word.pinyin
    }
}

/** The English words the user typed or added: no pinyin to edit, forgotten as a learned word is. */
private object EnglishWords : WordSource(
    title = R.string.english_words,
    searchHint = R.string.search_english_words_hint,
    addLabel = R.string.add_word,
    removeLabel = R.string.forget_word,
    confirmRemove = R.plurals.confirm_forget_words,
    editable = false,
) {
    override suspend fun words(fcitx: FcitxAPI) = fcitx.englishWords()

    override suspend fun remove(fcitx: FcitxAPI, words: List<UserWord>) = fcitx.forgetEnglishWords(words.map { it.text })

    // whether it is still corrected, not a count: an added word was never typed, and below LEARNED,
    // where it is corrected, a count halved from more may round up to 2
    override fun detail(res: Resources, word: UserWord): String =
        if (word.count >= EnglishUser.LEARNED) res.getString(R.string.english_word_learned)
        else res.getQuantityString(R.plurals.english_word_typed_times, 1, 1)
}

// spaces and the `'` that parts syllables: a pinyin is found however the user spaces it
private val SEARCH_IGNORED = Regex("[\\s']")

/** [query] as [searchWords] matches it: in lower case, spaces and `'` aside. */
internal fun searchKey(query: String): String = query.trim().lowercase().replace(SEARCH_IGNORED, "")

/**
 * The [words] with [key] (a [searchKey]) in the word or in its pinyin, each read as a key is
 * (an English don't as dont, a pinyin however spaced): those that start with it first, as typing a
 * word's start should find it at the top, each part in the order of [words].
 */
internal fun searchWords(words: List<UserWord>, key: String): List<UserWord> {
    val (starting, within) = words.mapNotNull { word ->
        val text = searchKey(word.text)
        val pinyin = searchKey(word.pinyin)
        when {
            text.startsWith(key) || pinyin.startsWith(key) -> word to true
            key in text || key in pinyin -> word to false
            else -> null
        }
    }.partition { it.second }
    return starting.map { it.first } + within.map { it.first }
}
