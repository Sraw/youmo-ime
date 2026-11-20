/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.text.inSpans
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries
import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries.Unread
import org.fcitx.fcitx5.android.data.pinyin.MergedDictionary
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.data.pinyin.countWordLines
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.TextDictionary
import org.fcitx.fcitx5.android.engine.learn.backup.LearnBackup
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.ImportLayer
import org.fcitx.fcitx5.android.engine.user.DictionaryFormat
import org.fcitx.fcitx5.android.engine.user.ImportFiles
import org.fcitx.fcitx5.android.engine.user.WordListFiles
import org.fcitx.fcitx5.android.engine.user.WordLists
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.ProgressBarDialog
import org.fcitx.fcitx5.android.ui.common.ProgressBarDialogIndeterminate
import org.fcitx.fcitx5.android.ui.common.withLoading
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fcitx.fcitx5.android.ui.main.launchOutlivingPage
import org.fcitx.fcitx5.android.ui.main.settings.search.PageEntry
import org.fcitx.fcitx5.android.ui.main.settings.search.ReadyLater
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchHighlight
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchablePage
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.queryFileName
import org.fcitx.fcitx5.android.utils.toast
import org.fcitx.fcitx5.android.utils.withLowercaseExtension
import splitties.dimensions.dp
import splitties.resources.resolveThemeAttribute
import splitties.views.dsl.core.textView
import splitties.views.textAppearance
import timber.log.Timber

/** Imported dictionaries merge into the base; a word list read into the user dictionary is a batch, undone whole. */
class PinyinDictionaryFragment : PaddingPreferenceFragment(), ReadyLater {

    private val args by lazyRoute<SettingsRoute.PinyinDict>()

    private val viewModel: MainViewModel by activityViewModels()

    // reread after a restore so the imports merged in are listed
    private val restore by lazy { LearningRestore(this, { viewModel.fcitx }) { rebuild() } }

    private lateinit var launcher: ActivityResultLauncher<String>

    /** the view made anew from My words has no saved state, so this tells whether the opened file was asked about */
    private var asked = false

    private var toldUnread = false

    private class Listed(val dictionary: TextDictionary, val words: Int, val missing: Missing = Missing.NONE)

    private var status: Preference? = null
    private val upgrading = ArrayList<Preference>()

    private enum class Target(@StringRes val title: Int, @StringRes val hint: Int) {
        BASE(R.string.layer_base_recommended, R.string.import_into_base),
        USER(R.string.layer_user, R.string.import_into_user),
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let(::askTarget) }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }

    private var retried = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.toolbarButton.value = ButtonMode.NONE
        toldUnread = false
        rebuild()
        // once a visit, not on rotation: retry failed compiles, clear import files a killed page left
        if (savedInstanceState == null && !retried) {
            retried = true
            lifecycleScope.launch(Dispatchers.IO) {
                PinyinDictManager.retryMerged()
                PinyinDictManager.sweepCache()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            MergedDictionary.done.collect { (kind, ok) ->
                if (!ok && kind == MergedDictionary.Kind.UPDATE) {
                    context?.let { AlertDialog.Builder(it).setMessage(R.string.dict_update_failed).setPositiveButton(android.R.string.ok, null).show() }
                }
            }
        }
        // relisted only as a compile starts or ends, not at each percent
        viewLifecycleOwner.lifecycleScope.launch {
            MergedDictionary.work.map { it?.kind }.distinctUntilChanged().drop(1).collect { rebuild() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            MergedDictionary.work.filterNotNull().collect { work ->
                said(work).let { text -> status?.summary = text; upgrading.forEach { it.summary = text } }
            }
        }
        // the home page passes "" for no file
        if (savedInstanceState == null && !asked) args.uri?.takeIf { it.isNotEmpty() }?.let { askTarget(Uri.parse(it)) }
        asked = true
    }

    private var listing: Job? = null

    private fun rebuild() {
        if (view == null) return
        listing?.cancel()
        val tell = !toldUnread
        listing = viewLifecycleOwner.lifecycleScope.launch {
            // waits for changes a replaced page (rotation) made
            changes.awaitMade()
            val listed = withContext(Dispatchers.IO) {
                val dictionaries = PinyinDictManager.listDictionaries()
                val dir = PinyinDictManager.pinyinDicDir
                val unreadable = MergedDictionary.unreadable(dir)
                val unconverted = MergedDictionary.unconverted(dir)
                dictionaries.zip(wordCounts.of(dictionaries)) { dictionary, words ->
                    val missing = when (dictionary.name) {
                        in unreadable -> Missing.UNREADABLE
                        in unconverted -> Missing.UNCONVERTED
                        else -> Missing.NONE
                    }
                    Listed(dictionary, words, missing)
                }
            }
            // read after the list: listing runs the migration when the engine has not yet
            val unread = if (tell) withContext(Dispatchers.IO) { PinyinDictManager.unread() } else emptyList()
            show(listed)
            if (tell) {
                toldUnread = true
                tellUnread(unread)
            }
        }
    }

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
            // only OK forgets them: Back would leave them to be told again
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { PinyinDictManager.forgetUnread(unread) }
            }
            .show()
    }

    private fun show(listed: List<Listed>) {
        val ctx = context ?: return
        val screen = preferenceScreen.apply { removeAll() }
        screen.addPreference(hint(ctx, R.string.word_packs_hint))
        val user = category(R.string.layer_user)
        user.addPreference(R.string.my_words, R.string.layer_user_summary) {
            navigateWithAnim(SettingsRoute.UserWords)
        }
        user.addPreference(R.string.imported_lists, R.string.imported_lists_summary, key = IMPORTED_KEY) { showImports() }
        val base = category(R.string.layer_base)
        base.addPreference(builtIn(R.string.builtin_base_title, R.string.builtin_base_summary))
        val work = MergedDictionary.work.value
        status = work?.let {
            Preference(ctx).apply {
                setTitle(if (it.kind == MergedDictionary.Kind.UPGRADE) R.string.dict_upgrading_title else R.string.dict_updating_title)
                summary = said(it)
                isSelectable = false
                isIconSpaceReserved = false
            }.also(base::addPreference)
        }
        upgrading.clear()
        listed.forEach { item ->
            val row = row(item)
            if (work?.kind == MergedDictionary.Kind.UPGRADE && item.dictionary.isEnabled) {
                row.summary = said(work)
                upgrading += row
            }
            base.addPreference(row)
        }
        val files = category(R.string.import_section)
        files.addPreference(R.string.import_dict, R.string.import_dict_summary, key = IMPORT_KEY) { launcher.launch("*/*") }
        val phrases = category(R.string.section_phrases)
        phrases.addPreference(R.string.custom_phrases, R.string.custom_phrases_summary) {
            navigateWithAnim(SettingsRoute.PinyinCustomPhrase)
        }
        phrases.addPreference(R.string.common_words, R.string.common_words_summary) {
            navigateWithAnim(SettingsRoute.CommonWords)
        }
        rareRow = Preference(ctx).apply {
            setTitle(R.string.rare_chars)
            summary = rareSummary
            isIconSpaceReserved = false
            setOnPreferenceClickListener { navigateWithAnim(SettingsRoute.RareChars); true }
        }.also(phrases::addPreference)
        phrases.addPreference(R.string.quick_phrases, R.string.quick_phrases_summary) {
            navigateWithAnim(SettingsRoute.QuickPhrase())
        }
        screen.addPreference(hint(ctx, R.string.words_page_hint))
        view?.post { SearchHighlight.ready(this) }
    }

    private fun hint(ctx: Context, @StringRes text: Int) = Preference(ctx).apply {
        setSummary(text)
        isSelectable = false
        isIconSpaceReserved = false
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

    private fun said(work: MergedDictionary.Work) =
        getString(if (work.kind == MergedDictionary.Kind.UPGRADE) R.string.dict_upgrading else R.string.dict_updating, work.percent)

    private fun row(item: Listed) = Preference(requireContext()).apply {
        val dictionary = item.dictionary
        title = dictionary.name
        val state = getString(if (dictionary.isEnabled) R.string.dict_on else R.string.dict_off)
        val count = resources.getQuantityString(R.plurals.word_count, item.words, item.words)
        val words = when (wordsShown(item.missing, item.words)) {
            WordsShown.DAMAGED -> getString(R.string.dict_damaged, count)
            WordsShown.UNREADABLE -> getString(R.string.dict_unreadable)
            WordsShown.UNCONVERTED -> getString(R.string.dict_unconverted)
            WordsShown.COUNT -> count
        }
        summary = getString(R.string.dict_summary, state, words)
        isIconSpaceReserved = false
        setOnPreferenceClickListener { act(item); true }
    }

    private fun act(item: Listed) {
        val dictionary = item.dictionary
        val actions = listOf(
            (if (dictionary.isEnabled) R.string.dict_turn_off else R.string.dict_turn_on) to {
                change {
                    runCatching { check(PinyinDictManager.turn(dictionary, !dictionary.isEnabled)) { "cannot rename ${dictionary.file.name}" } }
                }
            },
            R.string.delete to { confirmDelete(dictionary) },
        )
        AlertDialog.Builder(requireContext())
            .setTitle(dictionary.name)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun change(edit: suspend () -> Result<Unit>) = changes.change(edit, ::rebuild)

    private fun confirmDelete(dictionary: TextDictionary) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.dict_delete_confirm, dictionary.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                change { runCatching { check(PinyinDictManager.delete(dictionary)) { "cannot delete ${dictionary.file.name}" } } }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** A learning backup asks nothing and is restored. */
    private fun askTarget(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.launch {
            try {
                val incoming = inspect(ctx, uri) ?: return@launch
                if (incoming.backup) {
                    restore.from(uri)
                    return@launch
                }
                if (incoming.brokenBackup) {
                    ctx.toast(R.string.learning_backup_failed)
                    return@launch
                }
                // asked before reading the whole file for nothing (a large one took 42 s, then 已存在)
                val userOnly = mergesIntoBase(incoming.fileName) &&
                    withContext(Dispatchers.IO) { ImportedDictionaries.hasDictionary(PinyinDictManager.pinyinDicDir, incoming.name) }
                if (userOnly && !askUserOnly(ctx, incoming.name)) return@launch
                // read through first so the dialog shows what it is; bare words' pinyin is guessed only where the base may take it
                val prepared = analyse(ctx, uri, incoming, guess = offersBase(incoming) && !userOnly)
                val analysis = prepared.analysis
                // refused for both only when no text is read; otherwise the user dictionary still takes it line by line
                if (analysis is DictionaryFormat.Refused && analysis.reason in UNREADABLE_TEXT) {
                    // the copy can be as large as the file: closed off the main thread
                    withContext(NonCancellable + Dispatchers.IO) { prepared.close() }
                    ctx.importErrorDialog(PinyinDictManager.refusal(analysis.reason))
                    return@launch
                }
                if (userOnly) import(uri, incoming, Target.USER, prepared) else showTargets(ctx, uri, incoming, prepared)
            } catch (e: CancellationException) {
                // the page left: nothing to show the error on
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // a provider's error comes back through the binder as any type
                ctx.importErrorDialog(e)
            } catch (@Suppress("SwallowedException") e: OutOfMemoryError) {
                // read whole and larger than the heap: told, not an OOM
                ctx.importErrorDialog(R.string.dict_refused_too_large)
            }
        }
    }

    private suspend fun askUserOnly(ctx: Context, name: String): Boolean = suspendCancellableCoroutine { cont ->
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.import_dict)
            .setMessage(getString(R.string.dict_exists_user_only, name))
            .setPositiveButton(R.string.import_into_user_short) { _, _ -> if (cont.isActive) cont.resume(true) }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (cont.isActive) cont.resume(false) }
            .show()
        cont.invokeOnCancellation { dialog.dismiss() }
    }

    private suspend fun analyse(ctx: Context, uri: Uri, incoming: Incoming, guess: Boolean): PinyinDictManager.Prepared =
        withProgress(ctx, R.string.dict_analysing_title) { said, cancelled ->
            // not cancellable itself: what it made is closed here if the page went
            withContext(NonCancellable + Dispatchers.IO) {
                val size = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
                } ?: 0L
                val guessing = if (guess) guessing(cancelled) else null
                PinyinDictManager.prepare(ctx.contentResolver.openInputStreamOrThrow(uri), incoming.fileName, size, guessing) { share ->
                    if (cancelled.get()) throw CancellationException("cancelled")
                    said.value = ctx.getString(R.string.dict_progress_analysing, (share * 100).toInt()) to (share * 100).toInt()
                }.also { made ->
                    if (cancelled.get()) {
                        made.close()
                        throw CancellationException("cancelled")
                    }
                }
            }
        }

    /** Null for a file without a name; any text is fine for the user dictionary. */
    private suspend fun inspect(ctx: Context, uri: Uri): Incoming? {
        val cr = ctx.contentResolver
        val fileName = withContext(Dispatchers.IO) { cr.queryFileName(uri) } ?: return null
        val head = withContext(Dispatchers.IO) { cr.openInputStreamOrThrow(uri).use { it.readAtMost(PinyinDictManager.EXPORT_PEEK) } }
        return incoming(fileName, head)
    }

    private fun showTargets(ctx: Context, uri: Uri, incoming: Incoming, prepared: PinyinDictManager.Prepared) {
        // a file the base refuses by name is offered only the user dictionary
        val targets = if (offersBase(incoming)) Target.entries else listOf(Target.USER)
        val items = targets.map { target ->
            SpannableStringBuilder()
                .inSpans(StyleSpan(Typeface.BOLD)) { append(getString(target.title)) }
                .append("\n")
                .inSpans(RelativeSizeSpan(SMALL)) { append(getString(target.hint)) }
                // without a gap, items of a few lines each read as one block
                .inSpans(RelativeSizeSpan(GAP)) { append("\n") }
        }.toTypedArray<CharSequence>()
        var picked = targets.indexOf(if (startsInUser(incoming)) Target.USER else Target.BASE)
        var importing = false
        val title = getString(R.string.import_into_title)
        AlertDialog.Builder(ctx)
            // a message would replace the items
            .apply {
                val detected = prepared.analysis as? DictionaryFormat.Detected
                if (detected == null) {
                    setTitle(title)
                } else {
                    // the standard title is cut at two lines, losing the word count
                    setCustomTitle(detectedTitle(ctx, title, describe(detected)))
                }
            }
            .setSingleChoiceItems(items, picked) { _, which -> picked = which }
            .setPositiveButton(R.string.import_dict) { _, _ ->
                importing = true
                import(uri, incoming, targets[picked], prepared)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (!importing) lifecycleScope.launch(NonCancellable + Dispatchers.IO) { prepared.close() } }
            .show()
    }

    private fun detectedTitle(ctx: Context, title: String, detected: String) = ctx.textView {
        text = SpannableStringBuilder()
            .inSpans(RelativeSizeSpan(TITLE)) { append(title) }
            .append("\n")
            .inSpans(RelativeSizeSpan(SMALL)) { append(detected) }
        textAppearance = ctx.resolveThemeAttribute(android.R.attr.textAppearanceListItem)
        setPadding(dp(24), dp(20), dp(24), dp(4))
    }

    /** e.g. `Rime 词库 · 拼音用空格分开 · 第三列是词频 · 1914417 个词`. */
    private fun describe(detected: DictionaryFormat.Detected): String {
        val parts = ArrayList<String>()
        parts += getString(if (detected.source == DictionaryFormat.Source.RIME) R.string.dict_detected_rime else R.string.dict_detected_plain)
        if (detected.charset != Charsets.UTF_8) {
            val name = if (detected.charset.name() == "GB18030") "GBK" else detected.charset.name().removeSuffix("LE").removeSuffix("BE")
            parts += getString(R.string.dict_detected_charset, name)
        }
        if (detected.order == DictionaryFormat.Order.PINYIN_FIRST) parts += getString(R.string.dict_detected_pinyin_first)
        parts += getString(
            when (detected.reading) {
                DictionaryFormat.Reading.APOSTROPHES -> R.string.dict_detected_apostrophes
                DictionaryFormat.Reading.SPACES -> R.string.dict_detected_spaces
                DictionaryFormat.Reading.RUN_TOGETHER -> R.string.dict_detected_run_together
                DictionaryFormat.Reading.NONE -> R.string.dict_detected_no_pinyin
            },
        )
        parts += getString(
            when (detected.weight) {
                DictionaryFormat.Weight.PROBABILITY -> R.string.dict_detected_probability
                DictionaryFormat.Weight.COUNT -> R.string.dict_detected_count
                DictionaryFormat.Weight.LOG -> R.string.dict_detected_log
                DictionaryFormat.Weight.NONE -> R.string.dict_detected_no_weight
            },
        )
        parts += resources.getQuantityString(R.plurals.word_count, detected.words, detected.words)
        if (detected.guessed > 0) parts += resources.getQuantityString(R.plurals.dict_detected_guessed, detected.guessed, detected.guessed)
        val textOnly = detected.textOnly - detected.unguessed
        if (textOnly > 0) parts += resources.getQuantityString(R.plurals.dict_detected_text_only, textOnly, textOnly)
        if (detected.unguessed > 0) parts += resources.getQuantityString(R.plurals.dict_detected_unguessed, detected.unguessed, detected.unguessed)
        if (detected.unread > 0) parts += resources.getQuantityString(R.plurals.dict_detected_unread, detected.unread, detected.unread)
        return parts.joinToString(" · ")
    }

    private fun import(uri: Uri, incoming: Incoming, target: Target, prepared: PinyinDictManager.Prepared) {
        val ctx = requireContext()
        lifecycleScope.launch {
            try {
                if (target == Target.USER) {
                    // imported user words live in the heap: counted with earlier imports first
                    val words = withContext(Dispatchers.IO) { wordsForUser(prepared.analysis) { countLines(prepared.text) } }
                    val already = viewModel.fcitx.runOnReady { importBatches() }.sumOf { it.left }
                    if (tooLargeForUser(words, already)) {
                        ctx.importErrorDialog(userCapMessage(words))
                        return@launch
                    }
                    withLoading(LOADING_AFTER, { ctx.ProgressBarDialogIndeterminate(R.string.importing).show() }, { it.dismiss() }) {
                        importIntoUser(uri, incoming.fileName)
                    }
                    return@launch
                }
                if (!offersBase(incoming)) {
                    ctx.importErrorDialog(R.string.invalid_dict)
                    return@launch
                }
                // compiled before this returns, so the reloaded engine has the words
                withProgress(ctx, R.string.importing) { said, cancelled ->
                    coroutineScope {
                        val shown = launch {
                            MergedDictionary.work.collect { work ->
                                if (work != null) said.value = getString(stageText(work.stage), work.percent) to work.percent
                            }
                        }
                        try {
                            withContext(NonCancellable + Dispatchers.IO) {
                                PinyinDictManager.importPrepared(prepared, incoming.name, cancelled) { share ->
                                    val percent = (share * 100).toInt()
                                    said.value = getString(R.string.dict_progress_copying, percent) to percent
                                }
                            }.getOrThrow()
                        } finally {
                            shown.cancel()
                        }
                    }
                }
                // the compile reloads only if fcitx was ready then
                viewModel.fcitx.runOnReady { reloadEngine() }
                rebuild()
            } catch (e: CancellationException) {
                // the page left: nothing to show the error on
                throw e
            } catch (e: Exception) {
                ctx.importErrorDialog(e)
            } catch (@Suppress("SwallowedException") e: OutOfMemoryError) {
                ctx.importErrorDialog(R.string.dict_refused_too_large)
            } finally {
                // also when the page is gone, or about twice the file stays in the cache
                withContext(NonCancellable + Dispatchers.IO) { prepared.close() }
            }
        }
    }

    @StringRes
    private fun stageText(stage: MergedDictionary.Step) = when (stage) {
        MergedDictionary.Step.CONVERT -> R.string.dict_progress_converting
        MergedDictionary.Step.READ -> R.string.dict_progress_compiling
        MergedDictionary.Step.MERGE -> R.string.dict_progress_merging
        MergedDictionary.Step.WRITE -> R.string.dict_progress_writing
    }

    /** Only the wait for the host is timed: past it all later batches stay unread, so the dialog never sticks. */
    private fun guessing(cancelled: AtomicBoolean): (List<String>) -> Map<String, String?> {
        var gaveUp = false
        return { texts ->
            check(!gaveUp) { "the engine did not get ready" }
            val fcitx = viewModel.fcitx
            val ready = CompletableDeferred<Unit>()
            // not a child of the wait: a cancel returns at once
            val asked = CoroutineScope(Dispatchers.IO).async {
                fcitx.runOnReady {
                    ready.complete(Unit)
                    pinyinsOf(texts)
                }
            }
            runBlocking {
                val until = System.nanoTime() + GUESS_WAIT_MS * 1_000_000
                while (!asked.isCompleted) {
                    if (cancelled.get()) {
                        asked.cancel()
                        throw CancellationException("cancelled")
                    }
                    if (!ready.isCompleted && System.nanoTime() - until >= 0) {
                        gaveUp = true
                        asked.cancel()
                        break
                    }
                    withTimeoutOrNull(CANCEL_POLL_MS) { asked.join() }
                }
                check(!gaveUp) { "the engine did not get ready" }
                asked.await()
            }
        }
    }

    /** A cancel is not an error to show. */
    private suspend fun <T> withProgress(
        ctx: Context,
        @StringRes title: Int,
        block: suspend (MutableStateFlow<Pair<String, Int>>, AtomicBoolean) -> T,
    ): T = coroutineScope {
        val cancelled = AtomicBoolean()
        // leaving the page cancels too; the scope waits for the NonCancellable work, so a cancelled child tells it
        var finished = false
        val watching = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished) cancelled.set(true)
            }
        }
        val dialog = ProgressBarDialog(ctx, title) { cancelled.set(true) }
        val said = MutableStateFlow("" to 0)
        // a dialog for a few-word file only flashed
        val showing = launch {
            delay(LOADING_AFTER)
            dialog.show()
            said.collect { (text, percent) -> dialog.set(text, percent) }
        }
        try {
            block(said, cancelled)
        } finally {
            finished = true
            watching.cancel()
            showing.cancel()
            dialog.dismiss()
        }
    }

    private suspend fun importIntoUser(uri: Uri, fileName: String) {
        val ctx = requireContext()
        val fcitx = viewModel.fcitx
        val cr = ctx.contentResolver
        val imported = importWordsFrom(fileName, { cr.openInputStreamOrThrow(uri) }) { lines ->
            fcitx.runOnReady { importUserWords(lines, fileName) }
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.import_dict)
            .setMessage(getString(R.string.import_words_done, imported.imported, imported.added + imported.learned, imported.blocked, imported.unread))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showImports() {
        val fcitx = viewModel.fcitx
        viewLifecycleOwner.lifecycleScope.launch {
            val batches = shownBatches(fcitx.runOnReady { importBatches() })
            val ctx = context ?: return@launch
            val builder = AlertDialog.Builder(ctx).setTitle(R.string.imported_lists)
            if (batches.isEmpty()) {
                builder.setMessage(R.string.imported_lists_none).setPositiveButton(android.R.string.ok, null).show()
                return@launch
            }
            val items = batches.map {
                val left = resources.getQuantityString(R.plurals.imported_left, it.left, it.left)
                getString(R.string.imported_list_line, it.file.ifEmpty { getString(R.string.imported_list_unnamed) }, formatLocalDay(it.localDay), left)
            }.toTypedArray<CharSequence>()
            builder.setItems(items) { _, which -> confirmDrop(batches[which]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun confirmDrop(batch: ImportLayer.Batch) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.import_drop)
            .setMessage(getString(R.string.import_drop_confirm, batch.file.ifEmpty { getString(R.string.imported_list_unnamed) }, batch.left))
            .setPositiveButton(R.string.import_drop) { _, _ -> drop(batch) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun drop(batch: ImportLayer.Batch) {
        val fcitx = viewModel.fcitx
        viewLifecycleOwner.lifecycleScope.launch {
            val dropped = fcitx.runOnReady { dropImport(batch.id) } ?: return@launch
            val view = view ?: return@launch
            Snackbar.make(view, resources.getQuantityString(R.plurals.import_dropped, dropped, dropped), Snackbar.LENGTH_LONG)
                // on the connection's scope: the page may be left as it is tapped
                .setAction(R.string.undo) {
                    val app = view.context.applicationContext
                    fcitx.lifecycleScope.launch {
                        // the record was reread since (a fold, a clear): nothing to put back
                        if (!fcitx.runOnReady { restoreImport(batch.id) }) app.toast(R.string.import_restore_failed)
                    }
                }
                .show()
        }
    }

    private var rareRow: Preference? = null
    private var rareSummary: String? = null

    override fun onStart() {
        super.onStart()
        val fcitx = viewModel.fcitx
        lifecycleScope.launch {
            val said = rareSummary(requireContext()) { fcitx.runOnReady { rareGrid() } } ?: return@launch
            rareSummary = said
            rareRow?.summary = said
        }
    }

    override fun onStop() {
        changes.left()?.let { reload ->
            val fcitx = viewModel.fcitx
            // the connection checked now, in onStop, while the page still has it
            fcitx.lifecycleScope.launch(Dispatchers.Main.immediate) { fcitx.runOnReady(reload) }
        }
        super.onStop()
    }

    // rows that open a page of their own are found as that page
    companion object : SearchablePage {
        private const val IMPORTED_KEY = "imported_lists"
        private const val IMPORT_KEY = "import_dict"

        override fun searchEntries(context: Context) = listOf(
            PageEntry(context.getString(R.string.imported_lists), SearchHighlight.PREF + IMPORTED_KEY, summary = context.getString(R.string.imported_lists_summary)),
            PageEntry(context.getString(R.string.import_dict), SearchHighlight.PREF + IMPORT_KEY, summary = context.getString(R.string.import_dict_summary)),
        )

        private const val SMALL = 0.85f
        private const val GAP = 0.6f
        private const val TITLE = 1.15f

        private val UNREADABLE_TEXT = setOf(DictionaryFormat.Reason.ENCODING, DictionaryFormat.Reason.BINARY, DictionaryFormat.Reason.NO_WORDS)

        private const val LOADING_AFTER = 200L

        // also how often Cancel is checked meanwhile
        private const val GUESS_WAIT_MS = 15_000L
        private const val CANCEL_POLL_MS = 100L

        // process-wide: a recreated page rereads only changed dictionaries
        private val wordCounts = WordCounts { PinyinDictManager.wordCount(it) }

        // process-wide: a recreated page runs its changes after the old page's
        private val changes = DictionaryChanges({ launchOutlivingPage("dictionaries", it) }) {
            Timber.w(it, "dictionaries")
            // the page may be gone by now
            if (it is PinyinDictManager.Refusal) appContext.toast(it.message) else appContext.toast(R.string.dict_change_failed)
        }
    }
}

/** Any file but a binary dictionary (.dict, .scel), which are converted. */
internal fun readAsWordList(fileName: String) =
    PinyinDictionary.Type.fromFileName(withLowercaseExtension(fileName)).let {
        it != PinyinDictionary.Type.LibIME && it != PinyinDictionary.Type.Sougou
    }

/** Its own export, or a file the base would refuse anyway. */
internal fun startsInUser(incoming: Incoming) = incoming.userExport || !offersBase(incoming)

internal fun offersBase(incoming: Incoming) = mergesIntoBase(incoming.fileName) && incoming.namedForBase

internal fun isRimeDictionary(fileName: String) = PinyinDictManager.isRimeDictionary(fileName)

internal fun mergesIntoBase(fileName: String) =
    PinyinDictionary.Type.fromFileName(withLowercaseExtension(fileName)) != null || isRimeDictionary(fileName)

internal suspend fun importWordsFrom(
    fileName: String,
    open: () -> InputStream,
    import: suspend (Sequence<String>) -> Engines.Imported,
): Engines.Imported =
    // a word list is read whole to decode UTF-16 or GB18030, a .scel whole; a .dict a line at a time
    when (PinyinDictionary.Type.fromFileName(withLowercaseExtension(fileName))) {
        PinyinDictionary.Type.Sougou -> {
            val lines = withContext(Dispatchers.IO) { scelLines(open().use { it.readImported(PinyinDictManager.MAX_SCEL.toInt()) }) }
            import(lines.asSequence())
        }
        PinyinDictionary.Type.LibIME -> withContext(Dispatchers.IO) { PinyinDictManager.readWords(open(), fileName, import) }
        else -> {
            val lines = withContext(Dispatchers.IO) { WordLists.decode(open().use { it.readImported() }).lineSequence() }
            import(lines)
        }
    }

internal fun scelLines(bytes: ByteArray): List<String> = ArrayList<String>().also { out -> ImportFiles.Scel.read(bytes) { out += it.line() } }

internal enum class Missing {
    NONE,

    // a damaged gzip keeps what was read before the damage
    UNREADABLE,

    // for want of space: nothing wrong with it, not to be deleted
    UNCONVERTED,
}

internal enum class WordsShown { COUNT, DAMAGED, UNREADABLE, UNCONVERTED }

/** An unreadable one with words counted is a gzip damaged past them. */
internal fun wordsShown(missing: Missing, words: Int) = when (missing) {
    Missing.UNREADABLE -> if (words > 0) WordsShown.DAMAGED else WordsShown.UNREADABLE
    Missing.UNCONVERTED -> WordsShown.UNCONVERTED
    Missing.NONE -> WordsShown.COUNT
}

/** One all the user's, or all deleted, has nothing to undo. */
internal fun shownBatches(batches: List<ImportLayer.Batch>) = batches.filter { it.left > 0 }.sortedByDescending { it.id }

internal fun incoming(fileName: String, head: ByteArray?) = Incoming(
    fileName,
    userExport = readAsWordList(fileName) && head?.let(WordListFiles::isUserExport) == true,
    // whatever its name: a chat app or cloud drive may rename it
    backup = head?.let(LearnBackup::isBackup) == true,
)

/** [userExport]: an earlier build's export (header or `!` line); [backup]: a learning backup or bare log. */
internal class Incoming(val fileName: String, val userExport: Boolean = false, val backup: Boolean = false) {
    /** Rime's `luna_pinyin.dict.yaml` is kept as luna_pinyin */
    val name = PinyinDictManager.keptName(fileName)

    val namedForBase get() = PinyinDictManager.dictionaryName(fileName) != null

    /** Cut short or damaged: read as text it is noise. */
    val brokenBackup get() = !backup && withLowercaseExtension(fileName).endsWith(LearnBackup.EXTENSION)
}

/** Imported user words are held in the heap (~200 B each); more go to the base only. */
internal const val USER_MAX_WORDS = 100_000

/** For a file refused for the base its [lines]; 0 for the user's own export. */
internal fun wordsForUser(analysis: DictionaryFormat.Analysis?, lines: () -> Int): Int = when (analysis) {
    null -> 0
    is DictionaryFormat.Detected -> analysis.words + analysis.textOnly
    is DictionaryFormat.Refused -> lines()
}

internal fun countLines(file: File): Int = countWordLines(file)

/** On all imports there; the user's own export is never refused (older builds had no cap). */
internal fun tooLargeForUser(words: Int, already: Int) = words > 0 && already.toLong() + words > USER_MAX_WORDS

internal fun userCapMessage(words: Int) =
    if (words > USER_MAX_WORDS) R.string.dict_too_large_for_user else R.string.dict_user_imports_full

/** Rereads only changed files: the list is rebuilt after each change. */
internal class WordCounts(private val count: (TextDictionary) -> Int) {
    private class Counted(val length: Long, val modified: Long, val words: Int)

    // by name and type: turning one off or on renames its file
    private val counted = HashMap<String, Counted>()

    @Synchronized
    fun of(dictionaries: List<TextDictionary>): List<Int> {
        counted.keys.retainAll(dictionaries.mapTo(HashSet()) { keyOf(it) })
        return dictionaries.map { dictionary ->
            val length = dictionary.file.length()
            val modified = dictionary.file.lastModified()
            counted[keyOf(dictionary)]?.takeIf { it.length == length && it.modified == modified }?.words
                ?: count(dictionary).also { counted[keyOf(dictionary)] = Counted(length, modified, it) }
        }
    }

    private fun keyOf(dictionary: TextDictionary) = TextDictionary.fileName(dictionary.name)
}

/** Started on a scope outliving the page: on the page's own, leaving cancelled a pending edit. Main thread. */
internal class DictionaryChanges(
    private val start: (suspend () -> Unit) -> Job,
    private val io: CoroutineContext = Dispatchers.IO,
    private val onFailure: (Throwable) -> Unit,
) {
    private var changed = false

    // the next change, the engine reload and a page listing all wait for it
    private var last: Job? = null

    fun change(edit: suspend () -> Result<Unit>, then: () -> Unit) {
        // set now: the page may be left before the change is made
        changed = true
        val before = last
        last = start {
            before?.join()
            withContext(io) { edit() }.onFailure(onFailure)
            then()
        }
    }

    suspend fun awaitMade() {
        last?.join()
    }

    /** Null if nothing changed since the page was last left. */
    fun left(): (suspend HostApi.() -> Unit)? {
        if (!changed) return null
        changed = false
        val made = last
        val reload: suspend HostApi.() -> Unit = {
            made?.join()
            reloadEngine()
        }
        return reload
    }
}

internal fun unreadNotice(unread: List<Unread>, intro: String, outro: String, line: (Unread) -> String): String? =
    if (unread.isEmpty()) null else (listOf(intro) + unread.map(line) + outro).joinToString("\n")
