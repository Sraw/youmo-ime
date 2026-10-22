/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
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
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.text.inSpans
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries.Unread
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.TextDictionary
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.ImportLayer
import org.fcitx.fcitx5.android.engine.learn.backup.LearnBackup
import org.fcitx.fcitx5.android.engine.user.ImportFiles
import org.fcitx.fcitx5.android.engine.user.WordListFiles
import org.fcitx.fcitx5.android.engine.user.WordLists
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
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
import timber.log.Timber
import java.io.InputStream
import kotlin.coroutines.CoroutineContext

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.toolbarButton.value = ButtonMode.NONE
        toldUnread = false
        rebuild()
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
        listed.forEach { base.addPreference(row(it)) }
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
                    runCatching { check(if (dictionary.isEnabled) dictionary.disable() else dictionary.enable()) { "cannot rename ${dictionary.file.name}" } }
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
        lifecycleScope.withLoadingDialog(ctx) {
            try {
                val incoming = inspect(ctx, uri) ?: return@withLoadingDialog
                if (incoming.backup) {
                    restore.from(uri)
                    return@withLoadingDialog
                }
                if (incoming.brokenBackup) {
                    ctx.toast(R.string.learning_backup_failed)
                    return@withLoadingDialog
                }
                showTargets(ctx, uri, incoming)
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

    /**
     * The file at [uri] to import; null for one without a name. Its type is not checked here: the
     * user dictionary takes any text, only the base wants a dictionary's extension.
     */
    private suspend fun inspect(ctx: Context, uri: Uri): Incoming? {
        val cr = ctx.contentResolver
        val fileName = withContext(Dispatchers.IO) { cr.queryFileName(uri) } ?: return null
        val head = withContext(Dispatchers.IO) { cr.openInputStreamOrThrow(uri).use { it.readAtMost(EXPORT_PEEK) } }
        return incoming(fileName, head)
    }

    /**
     * Asks which dictionary [incoming] goes into, the user dictionary picked to begin with if
     * [startsInUser], else the base, and imports it there.
     */
    private fun showTargets(ctx: Context, uri: Uri, incoming: Incoming) {
        val targets = Target.entries
        val items = targets.map { target ->
            SpannableStringBuilder()
                .inSpans(StyleSpan(Typeface.BOLD)) { append(getString(target.title)) }
                .append("\n")
                .inSpans(RelativeSizeSpan(SMALL)) { append(getString(target.hint)) }
                // a gap under each: two of a few lines each read as one block without
                .inSpans(RelativeSizeSpan(GAP)) { append("\n") }
        }.toTypedArray<CharSequence>()
        var picked = targets.indexOf(if (startsInUser(incoming)) Target.USER else Target.BASE)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.import_into_title)
            .setSingleChoiceItems(items, picked) { _, which -> picked = which }
            .setPositiveButton(R.string.import_dict) { _, _ -> import(uri, incoming, targets[picked]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun import(uri: Uri, incoming: Incoming, target: Target) {
        val ctx = requireContext()
        val cr = ctx.contentResolver
        lifecycleScope.withLoadingDialog(ctx, R.string.importing) {
            try {
                if (target == Target.USER) {
                    importIntoUser(uri, incoming.fileName)
                    return@withLoadingDialog
                }
                if (PinyinDictionary.Type.fromFileName(withLowercaseExtension(incoming.fileName)) == null) {
                    ctx.importErrorDialog(R.string.invalid_dict)
                    return@withLoadingDialog
                }
                val imported = withContext(Dispatchers.IO) {
                    ManagedDictionaries.importFile(incoming) { cr.openInputStreamOrThrow(uri) }
                }
                if (!imported) {
                    ctx.importErrorDialog(R.string.dict_already_exists)
                    return@withLoadingDialog
                }
                viewModel.fcitx.runOnReady { reloadEngine() }
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

        // the export's header is its first line, and a blocked word's `!` is soon there if it has one
        private const val EXPORT_PEEK = 64 shl 10

        // the process's: a page made anew reads again only the dictionaries that changed
        private val wordCounts = WordCounts { PinyinDictManager.wordCount(it) }

        // the process's too: a page made anew (a rotation) makes its changes after those of the one it replaces
        private val changes = DictionaryChanges({ launchOutlivingPage("dictionaries", it) }) {
            Timber.w(it, "dictionaries")
            // the page may be gone by now
            appContext.toast(R.string.dict_change_failed)
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
 * export, and for a file of no dictionary's type, which only it takes.
 */
internal fun startsInUser(incoming: Incoming) =
    incoming.userExport || PinyinDictionary.Type.fromFileName(withLowercaseExtension(incoming.fileName)) == null

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
    /** what it is kept as */
    val name = PinyinDictionary.nameOf(fileName)

    /** Named as a backup, and not one (cut short, or damaged): never offered as words, read as text it is noise. */
    val brokenBackup get() = !backup && withLowercaseExtension(fileName).endsWith(LearnBackup.EXTENSION)
}

/** Where a file the user imports goes: the dictionaries [PinyinDictManager] keeps, or a test's. */
internal interface ImportTarget {
    /** The type of the dictionary of [name] there, if there is one. */
    fun typeOf(name: String): PinyinDictionary.Type?

    fun import(stream: InputStream, fileName: String)
}

/**
 * Imports [incoming], as [open] reads it, into the base dictionary; false, with nothing read or
 * changed, where one of its name is there.
 */
internal fun ImportTarget.importFile(incoming: Incoming, open: () -> InputStream): Boolean {
    if (typeOf(incoming.name) != null) return false
    open().use { import(it, incoming.fileName) }
    return true
}

private object ManagedDictionaries : ImportTarget {
    override fun typeOf(name: String) = PinyinDictManager.listDictionaries().firstOrNull { it.name == name }?.type

    override fun import(stream: InputStream, fileName: String) {
        PinyinDictManager.importFromInputStream(stream, fileName).getOrThrow()
    }
}

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
