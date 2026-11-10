/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.pinyin.CommonWordsManager
import org.fcitx.fcitx5.android.engine.phrase.CommonWords
import org.fcitx.fcitx5.android.engine.phrase.CommonWords.Problem
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.WordLists
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.common.OnItemChangedListener
import org.fcitx.fcitx5.android.ui.main.EditDeleteMenuProvider
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.materialTextInput
import org.fcitx.fcitx5.android.utils.onPositiveButtonClick
import org.fcitx.fcitx5.android.utils.str
import org.fcitx.fcitx5.android.utils.toast
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.setPaddingDp
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * 常用词 (dev/design/060-common-words.md): a word, its pinyin and the replacements typing it offers
 * first. The list is the file's; saved, over what the keyboard deleted meanwhile, as the page is left.
 */
class CommonWordsFragment : Fragment(), OnItemChangedListener<CommonWords.Word> {

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var ui: BaseDynamicListUi<CommonWords.Word>

    // false till the file is read, and when it could not be: nothing saved over it (as the custom phrases')
    private var readable = false

    // the read; an import or export delivered before it ends waits on it
    private var read: Job? = null

    // the file as last loaded or saved here: what the keyboard changed since is kept on save
    private var loaded = CommonWords.EMPTY

    private var dirty = false

    private lateinit var importLauncher: ActivityResultLauncher<String>
    private lateinit var exportLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let(::importWords) }
        exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(::exportWords) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        readable = false
        ui = object : BaseDynamicListUi<CommonWords.Word>(
            requireContext(),
            Mode.FreeAdd("", converter = { CommonWords.Word("", IntArray(0), emptyList()) }),
            emptyList(),
        ) {
            override fun updateFAB() {
                if (readable) super.updateFAB() else fab.hide()
            }

            override fun showEntry(x: CommonWords.Word) = "${x.text} → ${x.replacements.joinToString("、")}"

            override fun onBindViewHolder(holder: ViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                val word = entries[position]
                // the keys it is typed by in full pinyin, under it
                val keys = CommonWords.keys(word, Syllables::spelling).joinToString(" · ") { it.second }
                holder.nameText.text = "${showEntry(word)}\n$keys"
                tapRowToEdit(holder.nameText, holder.editButton, multiselect)
            }

            override fun showEditDialog(title: String, entry: CommonWords.Word?, block: (CommonWords.Word) -> Unit) {
                edit(title, entry, block)
            }
        }
        ui.addOnItemChangedListener(this)
        // a swipe left deletes, undone from the snackbar
        ui.addTouchCallback()
        ui.setViewModel(viewModel)
        return ui.root
    }

    /**
     * Asks for a word, its pinyin and its replacements, one a line in their order; kept open, with
     * an error under the field at fault, until they make a word ([CommonWords.problem]). The pinyin
     * left blank is filled in as the dictionary reads the word, for the user to check (as 我的词's).
     */
    private fun edit(title: String, entry: CommonWords.Word?, block: (CommonWords.Word) -> Unit) {
        val ctx = requireContext()
        val (textLayout, textField) = ctx.materialTextInput { hint = getString(R.string.common_word_text) }
        textField.apply {
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        val (pinyinLayout, pinyinField) = ctx.materialTextInput { hint = getString(R.string.word_pinyin) }
        pinyinField.apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val (replacementsLayout, replacementsField) = ctx.materialTextInput { hint = getString(R.string.common_word_replacements) }
        replacementsField.apply {
            isSingleLine = false
            minLines = 2
            maxLines = 9
        }
        entry?.let {
            textField.setText(it.text)
            pinyinField.setText(it.pinyin)
            replacementsField.setText(it.replacements.joinToString("\n"))
        }
        val layout = ctx.verticalLayout {
            setPaddingDp(20, 10, 20, 0)
            add(textLayout, lParams(matchParent))
            add(pinyinLayout, lParams(matchParent))
            add(replacementsLayout, lParams(matchParent))
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .apply {
                if (entry != null) setNeutralButton(R.string.delete) { _, _ ->
                    ui.indexItem(entry).takeIf { it >= 0 }?.let { ui.removeItem(it) }
                }
            }
            .show()
            .onPositiveButtonClick onClick@{
                val text = textField.str.trim()
                val pinyin = pinyinField.str
                val replacements = replacementsField.str.lines()
                if (text.isEmpty()) {
                    textField.error = getString(R.string._cannot_be_empty, getString(R.string.common_word_text))
                    return@onClick false
                }
                if (pinyin.isBlank()) {
                    lifecycleScope.launch {
                        val guessed = onFcitx { pinyinOf(text) } ?: return@launch
                        if (guessed.isEmpty()) pinyinField.error = getString(R.string.word_pinyin_unknown) else pinyinField.setText(guessed)
                    }
                    return@onClick false
                }
                val syllables = CommonWords.syllables(text, pinyin)
                when (val problem = CommonWords.problem(text, syllables, replacements)) {
                    null -> {}
                    Problem.NO_PINYIN, Problem.BAD_PINYIN -> {
                        pinyinField.error = getString(R.string.word_pinyin_invalid)
                        return@onClick false
                    }
                    Problem.TOO_LONG -> {
                        (if (text.length > CommonWords.MAX_WORD_CHARS) textField else replacementsField).error = said(problem)
                        return@onClick false
                    }
                    else -> {
                        replacementsField.error = said(problem)
                        return@onClick false
                    }
                }
                val word = CommonWords.Word(text, checkNotNull(syllables), CommonWords.clean(text, replacements))
                // one text, one word: edit the one listed
                if (ui.entries.any { it.text == text && it !== entry }) {
                    textField.error = getString(R.string.common_word_listed)
                    return@onClick false
                }
                block(word)
                true
            }
    }

    private fun said(problem: Problem) = getString(
        when (problem) {
            Problem.NO_REPLACEMENT -> R.string.common_word_no_replacement
            Problem.SAME_AS_WORD -> R.string.common_word_same_as_word
            Problem.NO_PINYIN -> R.string.word_pinyin_unknown
            Problem.BAD_PINYIN -> R.string.word_pinyin_invalid
            Problem.TOO_LONG -> R.string.common_word_too_long
            Problem.TOO_MANY -> R.string.common_word_too_many
        }
    )

    // [block] on fcitx; null, said, where it failed or fcitx stopped. "" for a word of no known pinyin.
    private suspend fun onFcitx(block: suspend org.fcitx.fcitx5.android.core.HostApi.() -> String?): String? = try {
        viewModel.fcitx.runOnReady(block).orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Timber.w(e, "common words: a word's pinyin")
        requireContext().toast(e)
        null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        read = viewLifecycleOwner.lifecycleScope.launch {
            val words = try {
                withContext(Dispatchers.IO) { load() }
            } catch (e: IOException) {
                Timber.w(e, "common words")
                requireContext().toast(e)
                findNavController().popBackStack()
                return@launch
            }
            loaded = words
            ui.addItems(words.words)
            dirty = false
            readable = true
            ui.updateFAB()
            requireActivity().invalidateMenu()
            viewModel.toolbarButton.value = if (ui.entries.isNotEmpty()) ButtonMode.EDIT else ButtonMode.NONE
        }
        viewModel.toolbarButton.value = ButtonMode.NONE
        requireActivity().addMenuProvider(
            EditDeleteMenuProvider(
                buttonMode = viewModel.toolbarButton,
                editButtonAction = { ui.enterMultiSelect(requireActivity().onBackPressedDispatcher) },
                deleteButtonAction = { ui.deleteSelected(); ui.exitMultiSelect() },
                menuHost = requireActivity(),
                lifecycleOwner = viewLifecycleOwner,
            ),
            viewLifecycleOwner,
            Lifecycle.State.STARTED,
        )
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menu.add(R.string.import_file).setEnabled(readable).setOnMenuItemClickListener {
                    importLauncher.launch("text/*")
                    true
                }
                menu.add(R.string.export_file).setEnabled(readable).setOnMenuItemClickListener {
                    exportLauncher.launch("youmo-common-words-${LocalDate.now()}.txt")
                    true
                }
                menu.add(R.string.delete_all).setEnabled(readable).setOnMenuItemClickListener {
                    deleteAll()
                    true
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem) = false
        }, viewLifecycleOwner, Lifecycle.State.STARTED)
    }

    private fun deleteAll() {
        if (ui.entries.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.common_words_delete_all, ui.entries.size))
            .setPositiveButton(R.string.delete_all) { _, _ -> ui.removeItems(0, ui.entries.size) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Reads a file the user picks (an export, or a list typed by hand: [CommonWords.parse]), the
     * readings it lacks as the dictionary reads them, and says what it would add, update and skip;
     * on the user's yes, lays it over the list ([CommonWords.importing]).
     */
    @VisibleForTesting
    internal fun importWords(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.launch {
            read?.join()
            if (!readable) return@launch
            ctx.showingFileErrors("common words import") {
                val text = withContext(Dispatchers.IO) {
                    WordLists.decode(ctx.contentResolver.openInputStreamOrThrow(uri).use { it.readImported() })
                }
                val asked = ArrayList<String>()
                withContext(Dispatchers.Default) { CommonWords.parse(text) { asked += it; emptyMap() } }
                val guessed = if (asked.isEmpty()) emptyMap() else try {
                    viewModel.fcitx.runOnReady { pinyinsOf(asked) }
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // read without them: those lines said to lack their pinyin
                    Timber.w(e, "common words: the import's pinyin")
                    emptyMap()
                }
                val read = withContext(Dispatchers.Default) { CommonWords.parse(text) { guessed } }
                val imported = CommonWords.of(ui.entries.toList()).importing(read.words)
                confirmImport(read, imported)
            }
        }
    }

    private fun confirmImport(read: CommonWords.Read, imported: CommonWords.Imported) {
        val skipped = read.skipped.size + imported.full.size
        val message = buildString {
            append(getString(R.string.common_words_import_counts, imported.added, imported.updated, skipped))
            // the first few reasons, a line each
            val reasons = read.skipped.map { getString(R.string.common_words_import_line, it.line, it.text.take(LINE_SHOWN), said(it.problem)) } +
                imported.full.map { getString(R.string.common_words_import_full, it.text) }
            reasons.take(REASONS_SHOWN).forEach { append('\n').append(it) }
            if (reasons.size > REASONS_SHOWN) append("\n…")
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.import_file)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (imported.added + imported.updated == 0) return@setPositiveButton
                val now = imported.words.words
                ui.removeItems(0, ui.entries.size)
                ui.addItems(now)
                dirty = true
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Writes the list to a file the user picks, once the file is read. */
    @VisibleForTesting
    internal fun exportWords(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.launch {
            read?.join()
            if (!readable) return@launch
            val words = CommonWords.of(ui.entries.toList())
            ctx.showingFileErrors("common words export") {
                withContext(Dispatchers.IO) {
                    val temp = File(ctx.cacheDir, "commonwords.export")
                    try {
                        CommonWordsManager.write(words, temp)
                        ctx.contentResolver.openOutputStreamOrThrow(uri).use { out -> temp.inputStream().use { it.copyTo(out) } }
                    } finally {
                        temp.delete()
                    }
                }
            }
        }
    }

    override fun onItemAdded(idx: Int, item: CommonWords.Word) {
        dirty = true
    }

    override fun onItemRemoved(idx: Int, item: CommonWords.Word) {
        dirty = true
    }

    override fun onItemUpdated(idx: Int, old: CommonWords.Word, new: CommonWords.Word) {
        dirty = true
    }

    override fun onItemRemovedBatch(indexed: List<Pair<Int, CommonWords.Word>>) {
        dirty = true
    }

    override fun onItemAddedBatch(indexed: List<Pair<Int, CommonWords.Word>>) {
        dirty = true
    }

    private fun save() {
        if (!readable || !dirty) return
        dirty = false
        val words = CommonWords.of(ui.entries.toList())
        val base = loaded
        // outlives the page, whose scope leaving cancels; begun at once: a page made anew reads the file next
        val saved = FcitxApplication.getInstance().coroutineScope.async(Dispatchers.Main.immediate) {
            try {
                loaded = withContext(Dispatchers.IO) { CommonWordsManager.saveOver(base, words) }
                true
            } catch (e: IOException) {
                Timber.w(e, "common words")
                appContext.toast(e)
                // not saved: tried again at the next stop
                dirty = true
                false
            }
        }
        val fcitx = viewModel.fcitx
        fcitx.lifecycleScope.launch(Dispatchers.Main.immediate) {
            fcitx.runOnReady<Unit> { if (saved.await()) reloadEngine() }
        }
    }

    override fun onStop() {
        save()
        ui.exitMultiSelect()
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeItemChangedListener()
        super.onDestroy()
    }

    companion object {
        private const val REASONS_SHOWN = 8
        private const val LINE_SHOWN = 24

        // the file's read; a test holds it to see the page while it waits
        @VisibleForTesting
        internal var load: () -> CommonWords = { CommonWordsManager.load() }
    }
}
