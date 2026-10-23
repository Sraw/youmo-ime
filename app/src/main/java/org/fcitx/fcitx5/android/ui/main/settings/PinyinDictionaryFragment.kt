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
import android.widget.Toast
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries
import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries.Unread
import org.fcitx.fcitx5.android.data.pinyin.MergedDictionary
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.data.pinyin.countWordLines
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.TextDictionary
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.ImportLayer
import org.fcitx.fcitx5.android.engine.learn.backup.LearnBackup
import org.fcitx.fcitx5.android.engine.user.DictionaryCompiler
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
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/**
 * The words and phrases (词库与短语): the user dictionary, then the base one, as they rank, and
 * the custom phrases. The user sees two dictionaries: the engine's built-in new words, which in
 * pinyin start level with the base, are shown as part of it, and every imported dictionary is
 * merged into the base (ImportedDictionaries.migrate moves those an older build merged into the
 * new words). A file imported goes into the base or, read word by word, the user dictionary, as
 * asked; one merged can be turned off or deleted. Another input method's list read into the user
 * dictionary is imported as a batch (U-101), listed under 导入的词 to be undone whole (U-102).
 */
class PinyinDictionaryFragment : PaddingPreferenceFragment() {

    private val args by lazyRoute<SettingsRoute.PinyinDict>()

    private val viewModel: MainViewModel by activityViewModels()

    // the user dictionary's export, a backup, restored as one (WD-10)
    // the rows read again after a restore: the imports merged in are listed (review R11)
    private val restore by lazy { LearningRestore(this, { viewModel.fcitx }) { rebuild() } }

    private lateinit var launcher: ActivityResultLauncher<String>

    /** the file opened with the app asked about: the view, made anew back from My words, has no saved state to tell */
    private var asked = false

    /** whether this view told of the dictionaries put aside unread: the list is built again after each change */
    private var toldUnread = false

    private class Listed(val dictionary: TextDictionary, val words: Int)

    // what says a compile is under way, and the rows of the dictionaries it upgrades: told its percent as it goes
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
        // what failed to compile in this process is compiled again now, as the failure's notice says (review M3);
        // and the import files a killed page left in the cache are cleared (review S3)
        // once a page visit: not at each rotation or return from a sub-page, a minute's compile each (review NIT)
        if (savedInstanceState == null && !retried) {
            retried = true
            lifecycleScope.launch(Dispatchers.IO) {
                MergedDictionary.retry(PinyinDictManager.pinyinDicDir)
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
        // the merged file made anew (an upgrade, a dictionary off or deleted): said till it is in use, then listed as it is.
        // Listed again only as one starts or ends (the list just built has the one now), not at each percent: the
        // status row is set only once the list is read, and each step restarted that read (review NIT)
        viewLifecycleOwner.lifecycleScope.launch {
            MergedDictionary.work.map { it?.kind }.distinctUntilChanged().drop(1).collect { rebuild() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            MergedDictionary.work.filterNotNull().collect { work ->
                said(work).let { text -> status?.summary = text; upgrading.forEach { it.summary = text } }
            }
        }
        // a file opened with the app, once (the home page passes none, as "")
        if (savedInstanceState == null && !asked) args.uri?.takeIf { it.isNotEmpty() }?.let { askTarget(Uri.parse(it)) }
        asked = true
    }

    /** the list being read: one asked for later replaces it, not to be overwritten by it */
    private var listing: Job? = null

    private fun rebuild() {
        if (view == null) return
        listing?.cancel()
        val tell = !toldUnread
        listing = viewLifecycleOwner.lifecycleScope.launch {
            // after a change the page this one replaced (a rotation) made: its list was built again on that page, gone
            changes.awaitMade()
            val listed = withContext(Dispatchers.IO) {
                val dictionaries = PinyinDictManager.listDictionaries()
                dictionaries.zip(wordCounts.of(dictionaries)) { dictionary, words -> Listed(dictionary, words) }
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
        screen.addPreference(hint(ctx, R.string.word_packs_hint))
        val user = category(R.string.layer_user)
        user.addPreference(R.string.my_words, R.string.layer_user_summary) {
            navigateWithAnim(SettingsRoute.UserWords)
        }
        user.addPreference(R.string.imported_lists, R.string.imported_lists_summary) { showImports() }
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
        files.addPreference(R.string.import_dict, R.string.import_dict_summary) { launcher.launch("*/*") }
        val phrases = category(R.string.section_phrases)
        phrases.addPreference(R.string.custom_phrases, R.string.custom_phrases_summary) {
            navigateWithAnim(SettingsRoute.PinyinCustomPhrase)
        }
        screen.addPreference(hint(ctx, R.string.words_page_hint))
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
        summary = getString(R.string.dict_summary, state, resources.getQuantityString(R.plurals.word_count, item.words, item.words))
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

    /**
     * Makes [edit], a change to the dictionaries' files, off the main thread and after the one
     * before it; lists them again then, and tells of one that failed.
     */
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

    /**
     * Which dictionary a file is merged into: the base unless asked otherwise, the safer of the
     * two, or the user dictionary for a file that is its export. The user dictionary's export,
     * a learning backup (WD-10), asks nothing: it is restored, merged as any backup is.
     */
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
                // one of that name in the base already: only the user dictionary is left to it, asked
                // before the whole file is read for nothing (frost's 42 s, then 已存在; review S7)
                val userOnly = mergesIntoBase(incoming.fileName) &&
                    withContext(Dispatchers.IO) { ImportedDictionaries.hasDictionary(PinyinDictManager.pinyinDicDir, incoming.name) }
                if (userOnly && !askUserOnly(ctx, incoming.name)) return@launch
                // the whole file read through before anything is imported (D6): what it is shows
                // in the dialog, and one not surely read is refused here, for either dictionary
                val prepared = analyse(ctx, uri, incoming)
                val analysis = prepared.analysis
                // refused for either dictionary only when its text is not read at all; else the
                // user dictionary takes it line by line as before, the base refusing it then (review OQ1)
                if (analysis is DictionaryFormat.Refused && analysis.reason in UNREADABLE_TEXT) {
                    // a copy as large as the file, maybe two: off the main thread, as the other ways out close it
                    withContext(NonCancellable + Dispatchers.IO) { prepared.close() }
                    ctx.importErrorDialog(PinyinDictManager.refusal(analysis.reason))
                    return@launch
                }
                if (userOnly) import(uri, incoming, Target.USER, prepared) else showTargets(ctx, uri, incoming, prepared)
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

    // whether the user takes the user dictionary for a file whose name the base has already
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

    // the whole file read through, its progress shown; the page gone or Cancel stops it, its files cleared
    private suspend fun analyse(ctx: Context, uri: Uri, incoming: Incoming): PinyinDictManager.Prepared =
        withProgress(ctx, R.string.dict_analysing_title) { said, cancelled ->
            // not cancellable itself (its token is): what it made is closed here if the page went meanwhile
            withContext(NonCancellable + Dispatchers.IO) {
                val size = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
                } ?: 0L
                PinyinDictManager.prepare(ctx.contentResolver.openInputStreamOrThrow(uri), incoming.fileName, size) { share ->
                    if (cancelled.get()) throw CancellationException("cancelled")
                    said.value = ctx.getString(R.string.dict_progress_analysing, (share * 100).toInt()) to (share * 100).toInt()
                }.also { made ->
                    // done as the page went: its result is thrown away by the cancelled scope, its files not left
                    if (cancelled.get()) {
                        made.close()
                        throw CancellationException("cancelled")
                    }
                }
            }
        }

    /**
     * The file at [uri] to import; null for one without a name. Its type is not checked here: the
     * user dictionary takes any text, only the base wants a dictionary's extension.
     */
    private suspend fun inspect(ctx: Context, uri: Uri): Incoming? {
        val cr = ctx.contentResolver
        val fileName = withContext(Dispatchers.IO) { cr.queryFileName(uri) } ?: return null
        val head = withContext(Dispatchers.IO) { cr.openInputStreamOrThrow(uri).use { it.readAtMost(PinyinDictManager.EXPORT_PEEK) } }
        return incoming(fileName, head)
    }

    /**
     * Asks which dictionary [incoming] goes into, the user dictionary picked to begin with if
     * [startsInUser], else the base, and imports it there.
     */
    private fun showTargets(ctx: Context, uri: Uri, incoming: Incoming, prepared: PinyinDictManager.Prepared) {
        // a file the base refuses by its name is offered only the user dictionary, not refused after (review F3444)
        val targets = if (offersBase(incoming)) Target.entries else listOf(Target.USER)
        val items = targets.map { target ->
            SpannableStringBuilder()
                .inSpans(StyleSpan(Typeface.BOLD)) { append(getString(target.title)) }
                .append("\n")
                .inSpans(RelativeSizeSpan(SMALL)) { append(getString(target.hint)) }
                // a gap under each: two of a few lines each read as one block without
                .inSpans(RelativeSizeSpan(GAP)) { append("\n") }
        }.toTypedArray<CharSequence>()
        var picked = targets.indexOf(if (startsInUser(incoming)) Target.USER else Target.BASE)
        var importing = false
        val title = getString(R.string.import_into_title)
        AlertDialog.Builder(ctx)
            // what the file was read as, under the question (a message would take the items' place)
            .apply {
                val detected = prepared.analysis as? DictionaryFormat.Detected
                if (detected == null) {
                    setTitle(title)
                } else {
                    // a custom title: the standard one is cut at two lines, and with it the word count (review S6)
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

    /** [detected] as the confirm dialog says it: `Rime 词库 · 拼音用空格分开 · 第三列是词频 · 1914417 个词`. */
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
        // the words the base takes; those without pinyin only the user dictionary does, said apart (review NIT)
        parts += resources.getQuantityString(R.plurals.word_count, detected.words, detected.words)
        if (detected.textOnly > 0) parts += resources.getQuantityString(R.plurals.dict_detected_text_only, detected.textOnly, detected.textOnly)
        if (detected.unread > 0) parts += resources.getQuantityString(R.plurals.dict_detected_unread, detected.unread, detected.unread)
        return parts.joinToString(" · ")
    }

    private fun import(uri: Uri, incoming: Incoming, target: Target, prepared: PinyinDictManager.Prepared) {
        val ctx = requireContext()
        lifecycleScope.launch {
            try {
                if (target == Target.USER) {
                    // the user dictionary's imported words are in the heap: counted first with those
                    // imported before, never half imported; a file refused for the base by its lines
                    val words = withContext(Dispatchers.IO) { wordsForUser(prepared.analysis) { countLines(prepared.text) } }
                    val already = viewModel.fcitx.runOnReady { importBatches() }.sumOf { it.left }
                    if (tooLargeForUser(words, already)) {
                        // the file alone too large, or the imports there with it: each its own advice (review F3413)
                        ctx.importErrorDialog(userCapMessage(words))
                        return@launch
                    }
                    withLoading(LOADING_AFTER, { ctx.ProgressBarDialogIndeterminate(R.string.importing).show() }, { it.dismiss() }) {
                        importIntoUser(uri, incoming.fileName)
                    }
                    return@launch
                }
                // a guard only: showTargets offers the base for none of these (offersBase)
                if (!offersBase(incoming)) {
                    ctx.importErrorDialog(R.string.invalid_dict)
                    return@launch
                }
                // compiled into the merged file before this returns: the engine reloaded has the words
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
                // the compile told the engine already (MergedDictionary.made), but only if fcitx was ready
                // then (runIfReady); this waits for it: one reload more, the words surely there
                viewModel.fcitx.runOnReady { reloadEngine() }
                rebuild()
            } catch (e: CancellationException) {
                // the page left: nothing to show the error on
                throw e
            } catch (e: Exception) {
                ctx.importErrorDialog(e)
            } finally {
                // the page gone, too: about twice the file in the cache otherwise (review S3)
                withContext(NonCancellable + Dispatchers.IO) { prepared.close() }
            }
        }
    }

    @StringRes
    private fun stageText(stage: DictionaryCompiler.Stage) = when (stage) {
        DictionaryCompiler.Stage.READ -> R.string.dict_progress_compiling
        DictionaryCompiler.Stage.MERGE -> R.string.dict_progress_merging
        DictionaryCompiler.Stage.WRITE -> R.string.dict_progress_writing
    }

    /**
     * [block] under a [ProgressBarDialog] titled [title]: it sets what the dialog says and how far
     * (text to percent); its cancel sets the flag [block] checks and calls [cancel]. A cancel
     * ends it as a CancellationException, as one that is not imported is not an error to show.
     */
    private suspend fun <T> withProgress(
        ctx: Context,
        @StringRes title: Int,
        block: suspend (MutableStateFlow<Pair<String, Int>>, AtomicBoolean) -> T,
    ): T = coroutineScope {
        val cancelled = AtomicBoolean()
        // the page gone (a rotation) stops the work too, at once: run on blind, it left its files in the cache (review S3).
        // Told by a child cancelled with the scope; the scope's own completion waits for the NonCancellable work
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
        // not for a moment's work: a three-word file's dialog only flashed
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

    /** 导入的词: the lists imported, newest first, each to be undone whole (U-102). */
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

    // undone, with 撤销 to put it back for as long as the bar shows
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
                        // the record read afresh since (a fold, a clear): nothing to put back, and said so
                        if (!fcitx.runOnReady { restoreImport(batch.id) }) Toast.makeText(app, R.string.import_restore_failed, Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
    }

    override fun onStop() {
        changes.left()?.let { reload ->
            val fcitx = viewModel.fcitx
            // the connection checked now, in onStop, while the page has it; read again once the last change is made
            fcitx.lifecycleScope.launch(Dispatchers.Main.immediate) { fcitx.runOnReady(reload) }
        }
        super.onStop()
    }

    companion object {
        private const val SMALL = 0.85f
        private const val GAP = 0.6f
        private const val TITLE = 1.15f

        // refused whatever dictionary it is for: no text read in it, or no word in it at all
        private val UNREADABLE_TEXT = setOf(DictionaryFormat.Reason.ENCODING, DictionaryFormat.Reason.BINARY, DictionaryFormat.Reason.NO_WORDS)

        // the spinner of an import into the user dictionary, once it takes this long (ms)
        private const val LOADING_AFTER = 200L

        // the process's: a page made anew reads again only the dictionaries that changed
        private val wordCounts = WordCounts { PinyinDictManager.wordCount(it) }

        // the process's too: a page made anew (a rotation) makes its changes after those of the one it replaces
        private val changes = DictionaryChanges({ launchOutlivingPage("dictionaries", it) }) {
            Timber.w(it, "dictionaries")
            // the page may be gone by now; one refused (too many words) says why
            if (it is PinyinDictManager.Refusal) appContext.toast(it.message) else appContext.toast(R.string.dict_change_failed)
        }
    }
}

/**
 * Whether a file named [fileName], imported into the user dictionary, is read as a word list
 * (WordLists.decode): any but a binary dictionary (.dict, .scel), as the user dictionary's own
 * import read any text file, whatever its name; those two are converted as dictionaries.
 */
internal fun readAsWordList(fileName: String) =
    PinyinDictionary.Type.fromFileName(withLowercaseExtension(fileName)).let {
        it != PinyinDictionary.Type.LibIME && it != PinyinDictionary.Type.Sougou
    }

/**
 * Whether the user dictionary, not the base, is picked to begin with for [incoming]: for its own
 * export, and for a file of no dictionary's type or of a name no base file can have, which only it
 * takes (review F3423: not offered the base first only to be refused after the analysis).
 */
internal fun startsInUser(incoming: Incoming) = incoming.userExport || !offersBase(incoming)

/** Whether the base dictionary is offered for [incoming] at all: a file it takes, of a name a base file can have. */
internal fun offersBase(incoming: Incoming) = mergesIntoBase(incoming.fileName) && incoming.namedForBase

/** Whether a file named [fileName] is Rime's dictionary (`*.dict.yaml`): text the base takes, its header skipped. */
internal fun isRimeDictionary(fileName: String) = fileName.lowercase().endsWith(RIME_DICTIONARY)

private const val RIME_DICTIONARY = ".dict.yaml"

/** Whether the base dictionary takes a file named [fileName]: a dictionary's type, or Rime's. */
internal fun mergesIntoBase(fileName: String) =
    PinyinDictionary.Type.fromFileName(withLowercaseExtension(fileName)) != null || isRimeDictionary(fileName)

/**
 * The words of the file named [fileName], as [open] reads it, imported into the user dictionary
 * by [import], the engine's.
 */
internal suspend fun importWordsFrom(
    fileName: String,
    open: () -> InputStream,
    import: suspend (Sequence<String>) -> Engines.Imported,
): Engines.Imported =
    // three ways, as the formats differ: a word list is read whole (at most readImported's cap,
    // as the user dictionary's own import read it) to be decoded from UTF-16 or GB18030; a .scel
    // read whole too (at most MAX_SCEL), to lines of its words with their counts (ImportFiles.Scel,
    // a few MB at most); a .dict is converted as the base's are, to UTF-8 text read a line at a time
    when (PinyinDictionary.Type.fromFileName(withLowercaseExtension(fileName))) {
        PinyinDictionary.Type.Sougou -> {
            val lines = withContext(Dispatchers.IO) { scelLines(open().use { it.readImported(MAX_SCEL) }) }
            import(lines.asSequence())
        }
        PinyinDictionary.Type.LibIME -> withContext(Dispatchers.IO) { PinyinDictManager.readWords(open(), fileName, import) }
        else -> {
            val lines = withContext(Dispatchers.IO) { WordLists.decode(open().use { it.readImported() }).lineSequence() }
            import(lines)
        }
    }

// a .scel read at most: Sogou's biggest cell dictionaries are some 10 MB
private const val MAX_SCEL = 32 shl 20

/** The words of a .scel file, [bytes], as lines of a word list: `词 pin'yin 12`. */
internal fun scelLines(bytes: ByteArray): List<String> = ArrayList<String>().also { out -> ImportFiles.Scel.read(bytes) { out += it.line() } }

/**
 * The lists imported with words still not the user's, newest first: the one just imported is the
 * one most likely undone; one all theirs, or all deleted, has nothing to undo (review-G NIT 3).
 */
internal fun shownBatches(batches: List<ImportLayer.Batch>) = batches.filter { it.left > 0 }.sortedByDescending { it.id }

/** The file named [fileName], starting with [head] (null if it could not be read), as an import. */
internal fun incoming(fileName: String, head: ByteArray?) = Incoming(
    fileName,
    userExport = readAsWordList(fileName) && head?.let(WordListFiles::isUserExport) == true,
    // whatever its name: a chat app or a cloud drive may have named it .bin or .dict (review-uat-fixes N3)
    backup = head?.let(LearnBackup::isBackup) == true,
)

/**
 * A file to import, named [fileName]; [userExport] if it looks like an earlier build's export of
 * the user dictionary (WordListFiles.isUserExport: its header, or a `!` line); [backup] if it is
 * a learning backup or a bare learning log (this build's export, 另存一份's copy).
 */
internal class Incoming(val fileName: String, val userExport: Boolean = false, val backup: Boolean = false) {
    /** what it is kept as: Rime's `luna_pinyin.dict.yaml` as luna_pinyin */
    val name = if (isRimeDictionary(fileName)) fileName.dropLast(RIME_DICTIONARY.length) else PinyinDictionary.nameOf(fileName)

    /** Whether [name] can name a file of the base: `.dict.yaml` alone names none, a hidden `.txt` the list shows untitled. */
    val namedForBase get() = name.isNotEmpty() && !name.startsWith(".")

    /** Named as a backup, and not one (cut short, or damaged): never offered as words, read as text it is noise. */
    val brokenBackup get() = !backup && withLowercaseExtension(fileName).endsWith(LearnBackup.EXTENSION)
}

/** More words than this go into the base dictionary only: the user dictionary's imported words are held in the heap (~200 B each). */
internal const val USER_MAX_WORDS = 100_000

/**
 * The words a file analysed as [analysis] puts in the user dictionary: those [DictionaryFormat]
 * counted, with pinyin or without; for one it refused for the base (two layouts, initials only…),
 * which the user dictionary still reads line by line, its [lines]; none counted for the user's own
 * export, not analysed, as before.
 */
internal fun wordsForUser(analysis: DictionaryFormat.Analysis?, lines: () -> Int): Int = when (analysis) {
    null -> 0
    is DictionaryFormat.Detected -> analysis.words + analysis.textOnly
    is DictionaryFormat.Refused -> lines()
}

/** The lines of [file] that may be words, counted as the page counts a dictionary's ([countWordLines]). */
internal fun countLines(file: File): Int = countWordLines(file)

/**
 * Whether a file of [words] (as [wordsForUser] counts the file, not the words it would newly add)
 * would take the user dictionary's imported words, [already] there (the lists imported, what is
 * left of each), past [USER_MAX_WORDS]: the total is what the heap holds, not one import's. Only
 * the user's own export (0, not counted) is never refused: 0.3.1 had no cap, so an upgraded user
 * may be past it already (review F3413); counting the file keeps the rule one users can predict.
 */
internal fun tooLargeForUser(words: Int, already: Int) = words > 0 && already.toLong() + words > USER_MAX_WORDS

/** The advice for a file [tooLargeForUser] refused: the file alone too large, or the imports there with it (review F3413). */
internal fun userCapMessage(words: Int) =
    if (words > USER_MAX_WORDS) R.string.dict_too_large_for_user else R.string.dict_user_imports_full

/**
 * How many words each dictionary has, as [count] reads them, read again only for one whose file
 * changed since: the list, built again after each change, read every dictionary whole each time.
 */
internal class WordCounts(private val count: (TextDictionary) -> Int) {
    private class Counted(val length: Long, val modified: Long, val words: Int)

    // by name and type: turning one off or on renames its file, its words as they were
    private val counted = HashMap<String, Counted>()

    /** The words of each of [dictionaries]; one no longer among them is forgotten, counted anew should it return. */
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

/**
 * The page's changes to the dictionaries' files, each made after the one before it, [start]ed on
 * a scope that outlives the page: on the page's own, one still waiting as the page was left was
 * cancelled, and the engine read the dictionaries again without it. Each is made on [io];
 * [onFailure] is told of one that failed. Used on the main thread.
 */
internal class DictionaryChanges(
    private val start: (suspend () -> Unit) -> Job,
    private val io: CoroutineContext = Dispatchers.IO,
    private val onFailure: (Throwable) -> Unit,
) {
    // a dictionary turned on or off or deleted: the engine reads them again on leaving
    private var changed = false

    // the last: the next waits for it, and so do the engine reading them again and a page listing them
    private var last: Job? = null

    /** Makes [edit] once the change before it is made, then [then] (the list built again). */
    fun change(edit: suspend () -> Result<Unit>, then: () -> Unit) {
        // now: the page may be left before the change is made, and the engine waits for it then
        changed = true
        val before = last
        last = start {
            before?.join()
            withContext(io) { edit() }.onFailure(onFailure)
            then()
        }
    }

    /** Returns once the changes started so far are made: what a page lists then has them all. */
    suspend fun awaitMade() {
        last?.join()
    }

    /**
     * As the page is left: what reads the dictionaries again once the last change is made, if
     * one was made since the page was last left; null if none was.
     */
    fun left(): (suspend FcitxAPI.() -> Unit)? {
        if (!changed) return null
        changed = false
        val made = last
        val reload: suspend FcitxAPI.() -> Unit = {
            made?.join()
            reloadEngine()
        }
        return reload
    }
}

/**
 * The notice of [unread], the dictionaries the migration put aside unread: [intro], a line each
 * as [line] words it, and [outro]; none, and no dialog, without one.
 */
internal fun unreadNotice(unread: List<Unread>, intro: String, outro: String, line: (Unread) -> String): String? =
    if (unread.isEmpty()) null else (listOf(intro) + unread.map(line) + outro).joinToString("\n")
