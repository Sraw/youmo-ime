/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
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
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.data.pinyin.RareCharsManager
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.LexiconView
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.engine.user.CharGate
import org.fcitx.fcitx5.android.engine.user.WordLists
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.materialTextInput
import org.fcitx.fcitx5.android.utils.navigateWithAnim
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
import java.io.IOException
import kotlin.math.roundToInt

/** Thousands of rows: a search shows the list whole again rather than diffing (a search changes most of it). */
class UserWordListFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private val args by lazyRoute<SettingsRoute.UserWordList>()
    private val source: WordSource by lazy {
        if (args.english) EnglishWords else PinyinWords(UserWord.Kind.valueOf(args.kind))
    }

    private var noPredict: TextView? = null
    private var tableBlocks: TextView? = null

    private var all = emptyList<UserWord>()
    private var rare = CharGate.NONE
    private val marksRare get() = (source as? PinyinWords)?.kind?.let { it != UserWord.Kind.BLOCKED } == true
    // a word's 「五笔：与 X 同码」 lines, per table that cannot take it
    private var sameCode = emptyMap<String, String>()
    private var shown = emptyList<UserWord>()
    private var query = ""
    private val selected = LinkedHashSet<UserWord>()
    private var filtering: Job? = null
    private var recentFirst = false
    private var unusedOnly = false

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
        if (!args.english && args.kind == UserWord.Kind.BLOCKED.name) {
            noPredict = entryRow(ctx, LearningOp.Listed.NO_PREDICT)
            tableBlocks = entryRow(ctx, LearningOp.Listed.TABLE_BLOCKS).apply { visibility = View.GONE }
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
                noPredict?.let { add(it, lParams(matchParent)) }
                tableBlocks?.let { add(it, lParams(matchParent)) }
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
                if (selected.isEmpty()) {
                    if (source.dated) {
                        menu.check(R.string.learning_sort_recent, recentFirst) { recentFirst = it }
                        menu.check(R.string.learning_filter_unused, unusedOnly) { unusedOnly = it }
                    }
                    return
                }
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
        countEntryRows()
    }

    private fun setTitle() {
        viewModel.setToolbarTitle(
            if (selected.isEmpty()) getString(source.title)
            else getString(R.string.selected_count, selected.size)
        )
    }

    private fun entryRow(ctx: Context, what: LearningOp.Listed) = ctx.textView {
        setPaddingDp(20, 12, 20, 12)
        minHeight = dp(48)
        gravity = gravityCenterVertical
        background = ctx.styledDrawable(android.R.attr.selectableItemBackground)
        setOnClickListener { navigateWithAnim(SettingsRoute.LearnedItems(what.name)) }
    }

    // each time the page shows: back from that page, some may be predicted or shown again
    private fun countEntryRows() {
        val row = noPredict ?: return
        lifecycleScope.launch {
            val overview = onFcitx("Reading the predictions turned off failed") { learning(LearningOp.Overview) }.getOrNull()
            val n = (overview as? LearningResult.Overview)?.noPredict ?: 0
            row.text = getString(R.string.learning_no_predict_entry, n)
            val blocks = tableBlocks ?: return@launch
            val listed = onFcitx("Reading the tables' blocks failed") { learning(LearningOp.ListItems(LearningOp.Listed.TABLE_BLOCKS)) }.getOrNull()
            val m = (listed as? LearningResult.Listed)?.items?.size ?: 0
            blocks.text = getString(R.string.learning_table_blocks_entry, m)
            blocks.visibility = if (m == 0) View.GONE else View.VISIBLE
        }
    }

    private fun load() {
        lifecycleScope.launch {
            all = onFcitx("Reading user words failed") { source.words(this) }.getOrElse { return@launch }
            // off the main thread (runOnReady runs on the caller's)
            if (marksRare) rare = withContext(Dispatchers.IO) { RareCharsManager.gate() }
            // by word and reading: a learned word's count may have changed since it was picked
            val picked = selected.mapTo(HashSet()) { it.text to it.pinyin }
            selected.clear()
            all.filterTo(selected) { (it.text to it.pinyin) in picked }
            filter(immediately = true)
            selectionChanged()
            loadSameCodes()
        }
    }

    // the user's own words only: a blocked word is offered nowhere anyway
    private suspend fun loadSameCodes() {
        if (args.english || args.kind == UserWord.Kind.BLOCKED.name) return
        val texts = all.map { it.text }
        val found = onFcitx("Reading the tables' codes failed") {
            val tables = enabledIme().filter { InputMethodCatalog.hasTableKeys(it.uniqueName) }
            tables to if (tables.isEmpty()) emptyMap() else sameCodes(texts, tables.map { it.uniqueName })
        }.getOrElse { return }
        val ctx = context ?: return
        val names = found.first.associate { it.uniqueName to InputMethodNames.of(ctx, it) }
        val codes = found.second
        sameCode = codes.mapValues { (_, words) ->
            words.joinToString("\n") { (im, word) -> getString(R.string.same_code_as, names[im] ?: im, word) }
        }
        adapter.notifyDataSetChanged()
    }

    private fun Menu.check(@StringRes title: Int, checked: Boolean, set: (Boolean) -> Unit) {
        add(title).apply {
            isCheckable = true
            isChecked = checked
            setOnMenuItemClickListener {
                set(!checked)
                requireActivity().invalidateMenu()
                filter(immediately = true)
                true
            }
        }
    }

    private fun filter(immediately: Boolean) {
        filtering?.cancel()
        // a load ending after the page is left has nothing to show
        val owner = viewLifecycleOwnerLiveData.value ?: return
        filtering = owner.lifecycleScope.launch {
            if (!immediately) delay(SEARCH_DELAY)
            val q = searchKey(query)
            val words = if (source.dated) LexiconView.arrange(all, recentFirst, unusedOnly) else all
            shown = if (q.isEmpty()) words else withContext(Dispatchers.Default) { searchWords(words, q) }
            adapter.notifyDataSetChanged()
            countView.text = when {
                words.isEmpty() && all.isNotEmpty() -> getString(R.string.learning_filter_unused_none, all.size)
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
        lifecycleScope.withLoadingDialog(ctx) {
            try {
                val undo = viewModel.fcitx.runOnReady { source.remove(this, words) }
                selected.removeAll(words.toSet())
                all = words()
                filter(immediately = true)
                selectionChanged()
                if (undo != LearningResult.NO_UNDO) offerUndo(words.size, undo)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // shown, where uncaught it would close the app
                Timber.w(e, "Removing user words failed")
                ctx.toast(e)
            }
        }
    }

    private suspend fun words() = viewModel.fcitx.runOnReady { source.words(this) }

    // back together while nothing else was learned since
    private fun offerUndo(n: Int, token: Long) {
        val root = view ?: return
        Snackbar.make(root, resources.getQuantityString(R.plurals.learning_removed, n, n), Snackbar.LENGTH_LONG)
            .setAction(R.string.learning_undo) {
                val fcitx = viewModel.fcitx
                val app = root.context.applicationContext
                // on the connection's scope: the page may be left as it is tapped
                fcitx.lifecycleScope.launch {
                    val undone = try {
                        fcitx.runOnReady { learning(LearningOp.Undo(token)) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                        Timber.w(e, "Taking a removal back failed")
                        app.toast(e)
                        return@launch
                    }
                    when (undone) {
                        LearningResult.Unavailable -> app.toast(R.string.learning_undo_gone)
                        LearningResult.Failed -> app.toast(R.string.learning_undo_failed)
                        else -> {}
                    }
                    if (isAdded) load()
                }
            }
            .show()
    }

    private fun restoreCorrection(word: UserWord) {
        lifecycleScope.launch {
            onFcitx(SAVING_FAILED) { learning(LearningOp.RestoreCorrection(listOf(word.text))) }.getOrElse { return@launch }
            // a failure is shown, not thrown
            load()
        }
    }

    /** Logged and shown where uncaught it would close the app; a [Result] as [block] may itself give null. */
    private suspend fun <T> onFcitx(failed: String, block: suspend HostApi.() -> T): Result<T> {
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

    private fun act(word: UserWord) {
        val held = rare.heldIn(word.text)
        val actions = buildList {
            if (held.isNotEmpty()) add(if (held.size == 1) R.string.rare_allow_one else R.string.rare_allow_these)
            if (source.editable) add(R.string.edit)
            if (source == EnglishWords && word.neverCorrected) add(R.string.english_restore_correction)
            add(source.removeLabel)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("${word.text}  ${word.pinyin}".trim())
            .setItems(actions.map { if (it == R.string.rare_allow_one) getString(it, held.first()) else getString(it) }.toTypedArray()) { _, which ->
                when (actions[which]) {
                    R.string.rare_allow_one, R.string.rare_allow_these -> allowRare(held) { load() }
                    R.string.edit -> edit(R.string.edit, word) { text, pinyin ->
                        // added before the old is removed: a word that does not read so leaves both be
                        addUserWord(text, pinyin).also { if (it) removeUserWords(listOf(word)) }
                    }
                    R.string.english_restore_correction -> restoreCorrection(word)
                    else -> remove(listOf(word))
                }
            }
            .show()
    }

    private fun allowRare(chars: List<String>, then: () -> Unit) {
        val levels = RareCharsManager.levels ?: return
        val ctx = requireContext().applicationContext
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { RareCharsManager.allow(chars, levels) }
            } catch (e: IOException) {
                Timber.w(e, "rare characters: let through")
                if (e is RareCharsManager.Newer) ctx.toast(R.string.rare_chars_newer) else ctx.toast(e)
                return@launch
            }
            onFcitx("Reloading the engine failed") { reloadEngine() }
            rare = withContext(Dispatchers.IO) { RareCharsManager.gate() }
            then()
        }
    }

    private fun addWord(@StringRes title: Int) = when (val list = source) {
        EnglishWords -> addEnglish(title)
        is PinyinWords -> edit(title, null) { text, pinyin -> list.add(this, text, pinyin) }
    }

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

    /** Blank pinyin is filled in for the user to check. */
    private fun edit(title: Int, word: UserWord?, save: suspend HostApi.(String, String) -> Boolean) {
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
        // a held character said at once, with a button to let it through; saving not stopped
        val allow = MaterialButton(ctx, null, MaterialR.attr.materialButtonOutlinedStyle).apply { visibility = View.GONE }
        fun sayRare() {
            val held = rare.heldIn(textField.str)
            textLayout.helperText = held.takeIf { it.isNotEmpty() }?.let { getString(R.string.rare_add_hint, it.joinToString("、")) }
            allow.visibility = if (held.isEmpty()) View.GONE else View.VISIBLE
            allow.setText(if (held.size > 1) R.string.rare_allow_these else R.string.rare_allow_this)
            // the list behind is reread too: its words with the character are no longer marked
            allow.setOnClickListener {
                allowRare(held) {
                    sayRare()
                    load()
                }
            }
        }
        textField.doAfterTextChanged { sayRare() }
        sayRare()
        val layout = ctx.verticalLayout {
            setPaddingDp(20, 10, 20, 0)
            add(textLayout, lParams(matchParent))
            add(allow, lParams(wrapContent))
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
            val held = rare.heldIn(word.text)
            holder.detail.text = listOfNotNull(
                source.detail(resources, word),
                sameCode[word.text],
                held.takeIf { it.isNotEmpty() }?.let { getString(R.string.rare_word_has, it.joinToString("、")) },
            ).joinToString("\n")
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

internal sealed class WordSource(
    @StringRes val title: Int,
    @StringRes val searchHint: Int,
    @StringRes val addLabel: Int?,
    @StringRes val removeLabel: Int,
    @PluralsRes val confirmRemove: Int,
    val editable: Boolean,
    val dated: Boolean = false,
) {
    abstract suspend fun words(fcitx: HostApi): List<UserWord>

    /** Returns the undo token, or [LearningResult.NO_UNDO]. */
    abstract suspend fun remove(fcitx: HostApi, words: List<UserWord>): Long

    abstract fun detail(res: Resources, word: UserWord): String
}

/** A blocked one is unblocked, not edited. */
internal class PinyinWords(val kind: UserWord.Kind) : WordSource(
    title = UserWordsFragment.title(kind),
    searchHint = R.string.search_words_hint,
    addLabel = when (kind) {
        UserWord.Kind.ADDED -> R.string.add_word
        UserWord.Kind.LEARNED -> null
        UserWord.Kind.BLOCKED -> R.string.block_word
    },
    removeLabel = when (kind) {
        UserWord.Kind.ADDED -> R.string.delete
        UserWord.Kind.LEARNED -> R.string.learning_delete
        UserWord.Kind.BLOCKED -> R.string.unblock_word
    },
    confirmRemove = when (kind) {
        UserWord.Kind.ADDED -> R.plurals.confirm_delete_words
        UserWord.Kind.LEARNED -> R.plurals.confirm_delete_words
        UserWord.Kind.BLOCKED -> R.plurals.confirm_unblock_words
    },
    editable = kind != UserWord.Kind.BLOCKED,
    dated = kind != UserWord.Kind.BLOCKED,
) {
    override suspend fun words(fcitx: HostApi) = fcitx.userWords(kind)

    override suspend fun remove(fcitx: HostApi, words: List<UserWord>): Long =
        (fcitx.learning(LearningOp.RemoveWords(words)) as? LearningResult.Done)?.undo ?: LearningResult.NO_UNDO

    suspend fun add(fcitx: HostApi, text: String, pinyin: String) =
        if (kind == UserWord.Kind.BLOCKED) fcitx.blockUserWord(text, pinyin) else fcitx.addUserWord(text, pinyin)

    override fun detail(res: Resources, word: UserWord): String {
        val said = if (word.kind == UserWord.Kind.LEARNED) {
            val times = word.count.roundToInt().coerceAtLeast(1)
            res.getQuantityString(R.plurals.word_typed_times, times, word.pinyin, times)
        } else if (word.kind == UserWord.Kind.BLOCKED && word.pinyin.isEmpty()) {
            // all-lower-case matches any case, any other (iPhone) as written
            res.getString(if (word.text == word.text.lowercase()) R.string.english_block_detail else R.string.english_block_detail_exact)
        } else {
            word.pinyin
        }
        if (word.lastUsed == UserWord.NEVER) return said
        // a day, not a time: the record keeps no more
        return res.getString(R.string.learning_last_used, said, formatLocalDay(word.lastUsed))
    }
}

internal object EnglishWords : WordSource(
    title = R.string.english_words,
    searchHint = R.string.search_english_words_hint,
    addLabel = R.string.add_word,
    removeLabel = R.string.forget_word,
    confirmRemove = R.plurals.confirm_forget_words,
    editable = false,
) {
    override suspend fun words(fcitx: HostApi) = fcitx.englishWords()

    override suspend fun remove(fcitx: HostApi, words: List<UserWord>): Long {
        fcitx.forgetEnglishWords(words.map { it.text })
        return LearningResult.NO_UNDO
    }

    // whether it is still corrected first: an added word may never have been typed
    override fun detail(res: Resources, word: UserWord): String {
        if (word.neverCorrected) return res.getString(R.string.english_word_learned)
        val times = word.count.roundToInt().coerceAtLeast(1)
        return res.getQuantityString(R.plurals.english_word_typed_times, times, times)
    }
}

private val SEARCH_IGNORED = Regex("[\\s']")

internal fun searchKey(query: String): String = query.trim().lowercase().replace(SEARCH_IGNORED, "")

/** Read as a key is (don't as dont, pinyin however spaced); prefix matches first. */
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
