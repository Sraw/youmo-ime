/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
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
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.reloadPinyinCustomPhrase
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

    // false when the file could not be read: the fragment leaves, and saves nothing over it
    private var readable = true

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
        val initialItems = try {
            CustomPhraseManager.load().also { loaded = it }
        } catch (e: IOException) {
            Timber.w(e, "custom phrases")
            requireContext().toast(e)
            readable = false
            emptyList()
        }
        ui = object : BaseDynamicListUi<PinyinCustomPhrase>(
            requireContext(),
            Mode.FreeAdd("", converter = { PinyinCustomPhrase("", 1, "") }),
            initialItems,
            enableOrder = true,
            initCheckBox = { entry ->
                isChecked = entry.enabled
                setOnCheckedChangeListener { _, checked ->
                    ui.updateItem(ui.indexItem(entry), entry.copyEnabled(checked))
                }
            }
        ) {
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
                    filters = arrayOf(
                        InputFilter { source, _, _, _, _, _ ->
                            source.filter { it.code in 'A'.code..'Z'.code || it.code in 'a'.code..'z'.code }
                        }
                    )
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!readable) {
            findNavController().popBackStack()
            return
        }
        viewModel.toolbarButton.value =
            if (ui.entries.isNotEmpty()) ButtonMode.EDIT else ButtonMode.NONE
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
                menu.add(R.string.import_file).setOnMenuItemClickListener {
                    importLauncher.launch("text/*")
                    true
                }
                menu.add(R.string.export_file).setOnMenuItemClickListener {
                    exportLauncher.launch("youmo-phrases.txt")
                    true
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem) = false
        }, viewLifecycleOwner, Lifecycle.State.STARTED)
    }

    /** Adds the phrases of a file the user picks, those the list has not already; saved as the page is left. */
    private fun importPhrases(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    (ctx.contentResolver.openInputStream(uri) ?: throw IOException("cannot read $uri")).use { it.readImported() }
                }
                val have = ui.entries.mapTo(HashSet()) { it.serialize() }
                // off the main thread: a file of thousands of phrases would hold it for seconds
                val phrases = withContext(Dispatchers.Default) {
                    CustomPhrases.parse(WordLists.decode(bytes)).all
                        .map { PinyinCustomPhrase(it.key, it.order, it.value) }
                        .filter { have.add(it.serialize()) }
                }
                ui.addItems(phrases)
                ctx.toast(getString(R.string.import_phrases_done, phrases.size))
            } catch (e: IOException) {
                ctx.toast(e)
            } catch (e: SecurityException) {
                // a file the picker gave that its provider will not open after all
                ctx.toast(e)
            }
        }
    }

    private fun exportPhrases(uri: Uri) {
        val ctx = requireContext()
        // a copy: written on another thread while the list may change
        val items = ui.entries.toList()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val temp = File(ctx.cacheDir, "customphrase.export")
                    try {
                        CustomPhraseManager.save(items, temp)
                        (ctx.contentResolver.openOutputStream(uri) ?: throw IOException("cannot write $uri")).use { out -> temp.inputStream().use { it.copyTo(out) } }
                    } finally {
                        temp.delete()
                    }
                }
            } catch (e: IOException) {
                ctx.toast(e)
            } catch (e: SecurityException) {
                // a file the picker gave that its provider will not open after all
                ctx.toast(e)
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
            fcitx.runOnReady<Unit> { if (saved.await()) reloadPinyinCustomPhrase() }
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

}
