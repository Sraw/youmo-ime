/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.pinyin.CustomPhraseManager
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.user.WordLists
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.common.OnItemChangedListener
import org.fcitx.fcitx5.android.ui.main.EditDeleteMenuProvider
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fcitx.fcitx5.android.utils.NaiveDustman
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
import kotlin.math.absoluteValue
import kotlin.math.min

class PinyinCustomPhraseFragment : Fragment(), OnItemChangedListener<PinyinCustomPhrase> {

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var ui: BaseDynamicListUi<PinyinCustomPhrase>

    private val dustman = NaiveDustman<PinyinCustomPhrase>()

    // false till the file is read (on IO: the first read may wait on the move of fcitx's data), and when it
    // could not be: nothing is saved over it, and a failed read leaves the page
    private var readable = false

    // the read of the view's list: an import or export delivered before it ends (the activity made anew
    // under the file picker) waits on it, else it is added to a list the read then takes for the file's,
    // or writes an empty one
    private var read: Job? = null

    // the file as last loaded or saved here: what the keyboard changed since is kept on save
    private var loaded = emptyList<PinyinCustomPhrase>()

    // the last save failed: the dustman, reset as it began, no longer tells of what it was to save
    private var unsaved = false

    private lateinit var importLauncher: ActivityResultLauncher<String>
    private lateinit var exportLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let(::importPhrases) }
        exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(::exportPhrases) }
    }

    private val keyLabel by lazy { getString(R.string.custom_phrase_key) }
    private val orderLabel by lazy { getString(R.string.custom_phrase_order) }
    private val phraseLabel by lazy { getString(R.string.custom_phrase_phrase) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // a view made anew reads the file anew
        readable = false
        ui = object : BaseDynamicListUi<PinyinCustomPhrase>(
            requireContext(),
            Mode.FreeAdd("", converter = { PinyinCustomPhrase("", 1, "") }),
            emptyList(),
            enableOrder = true,
            initCheckBox = { entry ->
                isChecked = entry.enabled
                setOnCheckedChangeListener { _, checked ->
                    ui.updateItem(ui.indexItem(entry), entry.copyEnabled(checked))
                }
            }
        ) {
            // nothing added before the file is read: the read's list would take it for the file's, and
            // a page left before the read ends saves nothing
            override fun updateFAB() {
                if (readable) super.updateFAB() else fab.hide()
            }

            override fun showEntry(x: PinyinCustomPhrase): String {
                val s = x.serialize()
                val firstLF = s.indexOf('\n')
                val endIndex = min(if (firstLF > 0) firstLF else s.length, 20)
                return if (endIndex == s.length) {
                    s
                } else {
                    s.take(endIndex) + "…"
                }
            }

            override fun onBindViewHolder(holder: ViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                tapRowToEdit(holder.nameText, holder.editButton, multiselect)
            }

            override fun showEditDialog(
                title: String,
                entry: PinyinCustomPhrase?,
                block: (PinyinCustomPhrase) -> Unit
            ) {
                val (keyLayout, keyField) = materialTextInput {
                    hint = keyLabel
                }
                keyField.apply {
                    isSingleLine = true
                    filters = arrayOf(InputFilter { source, start, end, _, _, _ -> keyTyped(source, start, end) })
                    imeOptions = EditorInfo.IME_ACTION_NEXT
                    inputType =
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                val (orderLayout, orderField) = materialTextInput {
                    hint = orderLabel
                }
                orderField.apply {
                    isSingleLine = true
                    inputType =
                        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_NORMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                    imeOptions = EditorInfo.IME_ACTION_NEXT
                }
                val (phraseLayout, phraseField) = materialTextInput {
                    hint = phraseLabel
                }
                phraseField.apply {
                    isSingleLine = false
                    maxLines = 8
                }
                entry?.apply {
                    keyField.setText(key)
                    orderField.setText(order.absoluteValue.toString(10))
                    phraseField.setText(value)
                }
                val layout = verticalLayout {
                    setPaddingDp(20, 10, 20, 0)
                    add(keyLayout, lParams(matchParent))
                    add(orderLayout, lParams(matchParent))
                    add(phraseLayout, lParams(matchParent))
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setView(layout)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .apply {
                        // editing one (a tap on its row or its pen): it can be deleted here too (WD-18)
                        if (entry != null) setNeutralButton(R.string.delete) { _, _ ->
                            ui.indexItem(entry).takeIf { it >= 0 }?.let { ui.removeItem(it) }
                        }
                    }
                    .show()
                    .onPositiveButtonClick onClick@{
                        val key = keyField.str
                        if (key.isBlank()) {
                            keyField.error = getString(R.string._cannot_be_empty, keyLabel)
                            keyField.requestFocus()
                            return@onClick false
                        } else {
                            keyField.error = null
                        }
                        // a negative order is a phrase turned off; 0 would be neither, and vanish
                        val order = orderField.str.toIntOrNull()?.takeIf { it != 0 } ?: 1
                        val phrase = phraseField.str
                        if (phrase.isEmpty()) {
                            phraseField.error = getString(R.string._cannot_be_empty, phraseLabel)
                            phraseField.requestFocus()
                            return@onClick false
                        } else {
                            phraseField.error = null
                        }
                        block(PinyinCustomPhrase(key, order, phrase))
                        return@onClick true
                    }
                    .setCanceledOnTouchOutside(false)
            }
        }
        ui.addOnItemChangedListener(this)
        ui.addTouchCallback()
        resetDustman()
        ui.setViewModel(viewModel)
        return ui.root
    }

    // kept lower case, as the engine matches them (CustomPhrases.parse); null when nothing changes, so
    // an input method's composing span is kept
    private fun keyTyped(source: CharSequence, start: Int, end: Int): CharSequence? {
        val typed = source.subSequence(start, end)
        val kept = typed.filter { it.code in 'A'.code..'Z'.code || it.code in 'a'.code..'z'.code }.toString().lowercase()
        return when {
            kept == typed.toString() -> null
            // the input method's composing span kept on what is left: without it, one that composes and
            // then appends would type the letters twice
            source is Spanned -> SpannableString(kept).also { TextUtils.copySpansFrom(source, start, start + kept.length, null, it, 0) }
            else -> kept
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        read = viewLifecycleOwner.lifecycleScope.launch {
            val items = try {
                withContext(Dispatchers.IO) { load() }
            } catch (e: IOException) {
                Timber.w(e, "custom phrases")
                requireContext().toast(e)
                findNavController().popBackStack()
                return@launch
            }
            loaded = items
            ui.addItems(items)
            // what was read is not a change to save
            dustman.reset(items.associateBy { it.serialize() })
            readable = true
            ui.updateFAB()
            // import and export too: one before the read would export nothing, or be lost as the add is
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
            Lifecycle.State.STARTED
        )
        // in the overflow: a file of phrases, as fcitx writes them, in and out
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menu.add(R.string.import_file).setEnabled(readable).setOnMenuItemClickListener {
                    importLauncher.launch("text/*")
                    true
                }
                menu.add(R.string.export_file).setEnabled(readable).setOnMenuItemClickListener {
                    exportLauncher.launch("youmo-phrases.txt")
                    true
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem) = false
        }, viewLifecycleOwner, Lifecycle.State.STARTED)
    }

    /** Adds the phrases of a file the user picks, those the list has not already; saved as the page is left. */
    @VisibleForTesting
    internal fun importPhrases(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.launch {
            read?.join()
            // the read failed and the page is left, or its view went with the read
            if (!readable) return@launch
            ctx.showingFileErrors("custom phrases import") {
                val bytes = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStreamOrThrow(uri).use { it.readImported() }
                }
                val have = ui.entries.mapTo(HashSet()) { it.serialize() }
                // off the main thread: a file of thousands of phrases would hold it for seconds
                val phrases = withContext(Dispatchers.Default) {
                    CustomPhraseManager.items(CustomPhrases.parse(WordLists.decode(bytes))).filter { have.add(it.serialize()) }
                }
                ui.addItems(phrases)
                ctx.toast(getString(R.string.import_phrases_done, phrases.size))
            }
        }
    }

    /** Writes the list to a file the user picks, once the file is read. */
    @VisibleForTesting
    internal fun exportPhrases(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.launch {
            // as an import: one delivered before the read ends would write an empty file
            read?.join()
            if (!readable) return@launch
            // a copy: written on another thread while the list may change
            val items = ui.entries.toList()
            ctx.showingFileErrors("custom phrases export") {
                withContext(Dispatchers.IO) {
                    val temp = File(ctx.cacheDir, "customphrase.export")
                    try {
                        CustomPhraseManager.save(items, temp)
                        ctx.contentResolver.openOutputStreamOrThrow(uri).use { out -> temp.inputStream().use { it.copyTo(out) } }
                    } finally {
                        temp.delete()
                    }
                }
            }
        }
    }

    override fun onItemAdded(idx: Int, item: PinyinCustomPhrase) {
        dustman.addOrUpdate(item.serialize(), item)
    }

    override fun onItemRemoved(idx: Int, item: PinyinCustomPhrase) {
        dustman.remove(item.serialize())
    }

    override fun onItemRemovedBatch(indexed: List<Pair<Int, PinyinCustomPhrase>>) {
        batchRemove(indexed)
    }

    override fun onItemUpdated(idx: Int, old: PinyinCustomPhrase, new: PinyinCustomPhrase) {
        dustman.remove(old.serialize())
        dustman.addOrUpdate(new.serialize(), new)
    }

    private fun saveConfig() {
        if (!readable || !(dustman.dirty || unsaved)) return
        unsaved = false
        resetDustman()
        val items = ui.entries.toList()
        val base = loaded
        // outlives the page, whose scope leaving cancels; begun at once: a page made anew reads the file next
        val saved = FcitxApplication.getInstance().coroutineScope.async(Dispatchers.Main.immediate) {
            try {
                loaded = withContext(Dispatchers.IO) { CustomPhraseManager.saveOver(base, items) }
                true
            } catch (e: IOException) {
                Timber.w(e, "custom phrases")
                // the fragment may be gone by now
                appContext.toast(e)
                // not saved: the next save is tried, changed since or not
                unsaved = true
                false
            }
        }
        val fcitx = viewModel.fcitx
        // the connection checked now, in onStop, while the page has it; dropped if fcitx stops before the save ends
        fcitx.lifecycleScope.launch(Dispatchers.Main.immediate) {
            fcitx.runOnReady<Unit> { if (saved.await()) reloadEngine() }
        }
    }

    private fun resetDustman() {
        dustman.reset(ui.entries.associateBy { it.serialize() })
    }

    override fun onStop() {
        saveConfig()
        ui.exitMultiSelect()
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeItemChangedListener()
        super.onDestroy()
    }

    companion object {
        // the file's read; a test holds it to see the page while it waits
        @VisibleForTesting
        internal var load: () -> List<PinyinCustomPhrase> = { CustomPhraseManager.load() }
    }

}

/**
 * A tap on a phrase's row edits it, as its pen does (WD-18: docs/words.md says 点一条可以编辑或删除);
 * the list's own tap only ticked the row's box, hidden outside multi-select. In multi-select the
 * tap still ticks it, as the list set.
 */
internal fun tapRowToEdit(row: View, pen: View, multiselect: Boolean) {
    if (!multiselect) row.setOnClickListener { pen.performClick() }
}
