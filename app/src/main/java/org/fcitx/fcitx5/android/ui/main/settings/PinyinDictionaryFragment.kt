/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.text.inSpans
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.reloadPinyinDict
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries.Unread
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.TextDictionary
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.openUrl
import org.fcitx.fcitx5.android.utils.queryFileName

/**
 * The three layers of the dictionary, as they rank: the user dictionary, the new words, the
 * base. A dictionary the user imports is merged into one of them, as asked when it is imported;
 * those merged into a layer rank alike, and can be turned off, moved or deleted.
 */
class PinyinDictionaryFragment : PaddingPreferenceFragment() {

    private val args by lazyRoute<SettingsRoute.PinyinDict>()

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var launcher: ActivityResultLauncher<String>

    /** a dictionary turned on or off, moved or deleted: the engine reads them again on leaving */
    private var changed = false

    /** the file opened with the app asked about: the view, made anew back from My words, has no saved state to tell */
    private var asked = false

    /** whether this view told of the dictionaries put aside unread: the list is built again after each change */
    private var toldUnread = false

    private class Listed(val dictionary: TextDictionary, val intoNew: Boolean, val words: Int)

    /** A file to import: a word pack or not, told by its first line whatever the file is called, as the import tells one. */
    private class Incoming(val fileName: String, val pack: Boolean) {
        /** what it is kept as, and replaces one of if a pack */
        val name = if (pack) PackImport.name(fileName) else PinyinDictionary.nameOf(fileName)

        val keptAs get() = if (pack) TextDictionary.fileName(name, type = PinyinDictionary.Type.Words) else fileName
    }

    private enum class Layer { USER, NEW, BASE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let(::askLayer) }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.toolbarButton.value = ButtonMode.NONE
        toldUnread = false
        rebuild()
        // a file opened with the app, once (the home page passes none, as "")
        if (savedInstanceState == null && !asked) args.uri?.takeIf { it.isNotEmpty() }?.let { askLayer(Uri.parse(it)) }
        asked = true
    }

    /** the list being read: one asked for later replaces it, not to be overwritten by it */
    private var listing: Job? = null

    private fun rebuild() {
        if (view == null) return
        listing?.cancel()
        val tell = !toldUnread
        listing = viewLifecycleOwner.lifecycleScope.launch {
            val listed = withContext(Dispatchers.IO) {
                PinyinDictManager.listDictionaries().map {
                    Listed(it, PinyinDictManager.isIntoNew(it.name), PinyinDictManager.wordCount(it))
                }
            }
            // read after the list: listing it runs the migration when the engine has not yet
            val unread = if (tell) withContext(Dispatchers.IO) { PinyinDictManager.unread() } else emptyList()
            show(listed)
            if (tell) {
                toldUnread = true
                tellUnread(unread)
            }
        }
    }

    /** Tells of [unread], the dictionaries the migration put aside unread; its OK forgets them. */
    private fun tellUnread(unread: List<Unread>) {
        val message = unreadNotice(unread, getString(R.string.dict_unread_message), getString(R.string.dict_unread_import_again)) {
            val reason = when (it.reason) {
                Unread.Reason.TOO_LARGE -> R.string.dict_unread_too_large
                Unread.Reason.DAMAGED -> R.string.dict_unread_damaged
                Unread.Reason.NO_WORDS -> R.string.dict_unread_no_words
                Unread.Reason.UNKNOWN -> R.string.dict_unread_unknown
            }
            getString(R.string.dict_unread_line, it.fileName, getString(reason))
        } ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dict_unread_title)
            .setMessage(message)
            // only OK forgets them: one sent away by Back would be told again
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // not forgotten, they are told again the next time the page shows
                lifecycleScope.launch(Dispatchers.IO) { PinyinDictManager.forgetUnread(unread) }
            }
            .show()
    }

    private fun show(listed: List<Listed>) {
        val ctx = context ?: return
        val screen = preferenceScreen.apply { removeAll() }
        val user = category(R.string.layer_user)
        user.addPreference(R.string.my_words, R.string.layer_user_summary) {
            navigateWithAnim(SettingsRoute.UserWords)
        }
        val new = category(R.string.layer_new)
        new.addPreference(builtIn(R.string.builtin_new_title, R.string.builtin_new_summary))
        listed.filter { it.intoNew }.forEach { new.addPreference(row(it)) }
        val base = category(R.string.layer_base)
        base.addPreference(builtIn(R.string.builtin_base_title, R.string.builtin_base_summary))
        listed.filter { !it.intoNew }.forEach { base.addPreference(row(it)) }
        val files = category(R.string.import_section)
        files.addPreference(R.string.import_dict, R.string.import_dict_summary) { launcher.launch("*/*") }
        // no network permission: the browser downloads a pack, importing it is as for any file
        files.addPreference(R.string.download_word_packs) { ctx.openUrl(Const.wordPacksUrl) }
        screen.addPreference(Preference(ctx).apply {
            setSummary(R.string.words_page_hint)
            isSelectable = false
            isIconSpaceReserved = false
        })
    }

    private fun category(title: Int) = PreferenceCategory(requireContext()).apply {
        setTitle(title)
        isIconSpaceReserved = false
        preferenceScreen.addPreference(this)
    }

    private fun builtIn(title: Int, summary: Int) = Preference(requireContext()).apply {
        setTitle(title)
        setSummary(summary)
        isSelectable = false
        isIconSpaceReserved = false
    }

    private fun row(item: Listed) = Preference(requireContext()).apply {
        val dictionary = item.dictionary
        title = dictionary.name
        val state = getString(if (dictionary.isEnabled) R.string.dict_on else R.string.dict_off)
        summary = getString(R.string.dict_summary, state, resources.getQuantityString(R.plurals.word_count, item.words, item.words))
        isIconSpaceReserved = false
        setOnPreferenceClickListener { act(item); true }
    }

    private fun act(item: Listed) {
        val dictionary = item.dictionary
        val actions = listOf(
            (if (dictionary.isEnabled) R.string.dict_turn_off else R.string.dict_turn_on) to {
                done(if (dictionary.isEnabled) dictionary.disable() else dictionary.enable())
            },
            (if (item.intoNew) R.string.dict_move_to_base else R.string.dict_move_to_new) to {
                done(PinyinDictManager.setIntoNew(dictionary.name, !item.intoNew).isSuccess)
            },
            R.string.delete to { confirmDelete(dictionary) },
        )
        AlertDialog.Builder(requireContext())
            .setTitle(dictionary.name)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun done(succeeded: Boolean) {
        if (!succeeded) Toast.makeText(requireContext(), R.string.dict_change_failed, Toast.LENGTH_SHORT).show()
        changed = true
        rebuild()
    }

    private fun confirmDelete(dictionary: TextDictionary) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.dict_delete_confirm, dictionary.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                done(PinyinDictManager.delete(dictionary))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Which layer a file is merged into: the base unless asked otherwise, the safest of the three;
     * for a pack replacing one, the layer that one is in.
     */
    private fun askLayer(uri: Uri) {
        val ctx = requireContext()
        val cr = ctx.contentResolver
        lifecycleScope.withLoadingDialog(ctx) {
            try {
                val fileName = withContext(Dispatchers.IO) { cr.queryFileName(uri) } ?: return@withLoadingDialog
                val type = PinyinDictionary.Type.fromFileName(fileName)
                if (type == null) {
                    ctx.importErrorDialog(R.string.invalid_dict)
                    return@withLoadingDialog
                }
                val incoming = withContext(Dispatchers.IO) {
                    val text = type == PinyinDictionary.Type.Text || type == PinyinDictionary.Type.Words
                    Incoming(fileName, text && (cr.openInputStream(uri) ?: throw java.io.IOException("cannot read $uri")).use(PackImport::isPack))
                }
                // a pack replacing one stays where that one is when the import is taken as offered
                val offered = withContext(Dispatchers.IO) {
                    val replaced = PinyinDictManager.listDictionaries().firstOrNull { it.name == incoming.name }
                    val action = PackImport.action(incoming.pack, replaced?.type)
                    if (offersNewLayer(action, PinyinDictManager.isIntoNew(incoming.name))) Layer.NEW else Layer.BASE
                }
                val layers = listOf(
                    Layer.BASE to (R.string.layer_base to R.string.import_into_base),
                    Layer.NEW to (R.string.layer_new to R.string.import_into_new),
                    Layer.USER to (R.string.layer_user to R.string.import_into_user),
                )
                val items = layers.map { (_, text) ->
                    SpannableStringBuilder()
                        .inSpans(StyleSpan(Typeface.BOLD)) { append(getString(text.first)) }
                        .append("\n")
                        .inSpans(RelativeSizeSpan(SMALL)) { append(getString(text.second)) }
                        // a gap under each: three of two lines each read as one block without
                        .inSpans(RelativeSizeSpan(GAP)) { append("\n") }
                }.toTypedArray<CharSequence>()
                var picked = layers.indexOfFirst { it.first == offered }
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.import_into_title)
                    .setSingleChoiceItems(items, picked) { _, which -> picked = which }
                    .setPositiveButton(R.string.import_dict) { _, _ -> import(uri, incoming, layers[picked].first) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } catch (e: CancellationException) {
                // the page left: nothing to show the error on
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // a provider's error comes back through the binder as any kind (a SecurityException for
                // a file the picker gave that it will not open after all): shown, as import() does
                ctx.importErrorDialog(e)
            }
        }
    }

    private fun import(uri: Uri, incoming: Incoming, layer: Layer) {
        val ctx = requireContext()
        val cr = ctx.contentResolver
        lifecycleScope.withLoadingDialog(ctx, R.string.importing) {
            try {
                if (layer == Layer.USER) {
                    importIntoUser(uri, incoming.fileName)
                    return@withLoadingDialog
                }
                val name = incoming.name
                val replaced = withContext(Dispatchers.IO) { PinyinDictManager.listDictionaries() }.firstOrNull { it.name == name }
                // a pack of the same name is its next one (each month's official pack is youmo-new.words): it replaces
                val action = PackImport.action(incoming.pack, replaced?.type)
                if (action == PackImport.Action.REFUSE) {
                    ctx.importErrorDialog(R.string.dict_already_exists)
                    return@withLoadingDialog
                }
                withContext(Dispatchers.IO) {
                    val stream = cr.openInputStream(uri) ?: throw java.io.IOException("cannot read $uri")
                    if (action == PackImport.Action.IMPORT) {
                        PinyinDictManager.importFromInputStream(stream, incoming.keptAs, layer == Layer.NEW).getOrThrow()
                    } else {
                        // the layer first: a pack that then fails to replace is the last one, as asked
                        PinyinDictManager.setIntoNew(name, layer == Layer.NEW).getOrThrow()
                        PinyinDictManager.importPack(name, stream.bufferedReader().use { it.readText() }).getOrThrow()
                    }
                }
                viewModel.fcitx.runOnReady { reloadPinyinDict() }
                rebuild()
            } catch (e: CancellationException) {
                // the page left: nothing to show the error on
                throw e
            } catch (e: Exception) {
                ctx.importErrorDialog(e)
            }
        }
    }

    private suspend fun importIntoUser(uri: Uri, fileName: String) {
        val ctx = requireContext()
        val lines = withContext(Dispatchers.IO) {
            val stream = ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("cannot read $uri")
            PinyinDictManager.readWords(stream, fileName).getOrThrow()
        }
        val imported = viewModel.fcitx.runOnReady { importUserWords(lines) }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.import_dict)
            .setMessage(getString(R.string.import_words_done, imported.added, imported.blocked, imported.unread))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onStop() {
        if (changed) {
            changed = false
            viewModel.fcitx.launchOnReady { it.reloadPinyinDict() }
        }
        super.onStop()
    }

    companion object {
        private const val SMALL = 0.85f
        private const val GAP = 0.6f
    }
}

/**
 * Whether importing a file that does [action] offers the new words rather than the base: a pack
 * replacing one merged into the new words ([intoNew]) stays there (each month's youmo-new.words).
 */
internal fun offersNewLayer(action: PackImport.Action, intoNew: Boolean) = action == PackImport.Action.REPLACE && intoNew

/**
 * The notice of [unread], the dictionaries the migration put aside unread: [intro], a line each
 * as [line] words it, and [outro]; none, and no dialog, without one.
 */
internal fun unreadNotice(unread: List<Unread>, intro: String, outro: String, line: (Unread) -> String): String? =
    if (unread.isEmpty()) null else (listOf(intro) + unread.map(line) + outro).joinToString("\n")
