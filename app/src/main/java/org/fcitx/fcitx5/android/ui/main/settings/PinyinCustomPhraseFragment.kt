/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import java.io.File
import java.io.IOException
import kotlin.math.absoluteValue
import kotlin.math.min
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
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fcitx.fcitx5.android.ui.main.addEditDeleteMenu
import org.fcitx.fcitx5.android.ui.main.addOverflowMenu
import org.fcitx.fcitx5.android.ui.main.settings.search.PageEntry
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchablePage
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

class PinyinCustomPhraseFragment : Fragment(), OnItemChangedListener<PinyinCustomPhrase> {

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var ui: BaseDynamicListUi<PinyinCustomPhrase>

    private val dustman = NaiveDustman<PinyinCustomPhrase>()

    // false till read (the first read may wait on the data move), or when unreadable: nothing saved over it
    private var readable = false

    // an import or export delivered before it ends (activity recreated under the picker) waits on it
    private var read: Job? = null

    // what the keyboard changed since is kept on save
    private var loaded = emptyList<PinyinCustomPhrase>()

    // the dustman, reset as the save began, no longer tells of what it was to save
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
            // nothing added before the read: it would be taken for the file's
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

    // lower case, as the engine matches; null when unchanged, so the IME's composing span is kept
    private fun keyTyped(source: CharSequence, start: Int, end: Int): CharSequence? {
        val typed = source.subSequence(start, end)
        val kept = typed.filter { it.code in 'A'.code..'Z'.code || it.code in 'a'.code..'z'.code }.toString().lowercase()
        return when {
            kept == typed.toString() -> null
            // without the composing span, an IME that composes then appends would type the letters twice
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
            // shown, not added: an add offered an undo that deleted the phrases read
            ui.showAll(items)
            // what was read is not a change to save
            dustman.reset(items.associateBy { it.serialize() })
            readable = true
            ui.updateFAB()
            // import and export too: one before the read would export nothing, or be lost
            requireActivity().invalidateMenu()
            viewModel.toolbarButton.value = if (ui.entries.isNotEmpty()) ButtonMode.EDIT else ButtonMode.NONE
        }
        viewModel.toolbarButton.value = ButtonMode.NONE
        addEditDeleteMenu(viewModel.toolbarButton, ui)
        addOverflowMenu(
            R.string.import_file to { importLauncher.launch("text/*") },
            R.string.export_file to { exportLauncher.launch("youmo-phrases.txt") },
            enabled = { readable },
        )
    }

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
                // thousands of phrases would hold the main thread for seconds
                val phrases = withContext(Dispatchers.Default) {
                    CustomPhraseManager.items(CustomPhrases.parse(WordLists.decode(bytes))).filter { have.add(it.serialize()) }
                }
                // the whole list is written at each save and read by the engine at each start
                val kept = phrases.take((MAX_PHRASES - ui.entries.size).coerceAtLeast(0))
                val done = getString(R.string.import_phrases_done, kept.size)
                val over = phrases.size - kept.size
                val said = if (over == 0) done else getString(R.string.import_over_limit, done, over, MAX_PHRASES)
                // one bar with the count and this import's undo (a toast went unseen under it)
                if (kept.isEmpty()) ctx.toast(said) else ui.addItems(kept, said)
            }
        }
    }

    @VisibleForTesting
    internal fun exportPhrases(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.launch {
            // one delivered before the read ends would write an empty file
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
        // outlives the page, whose scope leaving cancels; begun at once: a recreated page reads the file next
        val saved = FcitxApplication.getInstance().coroutineScope.async(Dispatchers.Main.immediate) {
            try {
                loaded = withContext(Dispatchers.IO) { CustomPhraseManager.saveOver(base, items) }
                true
            } catch (e: IOException) {
                Timber.w(e, "custom phrases")
                // the fragment may be gone by now
                appContext.toast(e)
                unsaved = true
                false
            }
        }
        val fcitx = viewModel.fcitx
        // the connection checked now, in onStop, while the page has it
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

    companion object : SearchablePage {
        // ⋮'s items: no View to point at, said where they are
        override fun searchEntries(context: Context) = listOf(
            PageEntry(context.getString(R.string.import_file), "view:phrases/import", where = R.string.search_where_menu),
            PageEntry(context.getString(R.string.export_file), "view:phrases/export", where = R.string.search_where_menu),
        )

        @VisibleForTesting
        internal var load: () -> List<PinyinCustomPhrase> = { CustomPhraseManager.load() }

        const val MAX_PHRASES = 5000
    }

}

/** The list's own tap only ticked the hidden box. In multi-select it still ticks. */
internal fun tapRowToEdit(row: View, pen: View, multiselect: Boolean) {
    if (!multiselect) row.setOnClickListener { pen.performClick() }
}
