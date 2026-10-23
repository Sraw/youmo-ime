/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.SourceNames
import org.fcitx.fcitx5.android.data.pinyin.dict.TextDictionary
import org.fcitx.fcitx5.android.engine.user.DictionaryCompiler
import org.fcitx.fcitx5.android.engine.user.DictionaryFormat
import org.fcitx.fcitx5.android.engine.user.WordListFiles
import org.fcitx.fcitx5.android.ui.main.settings.readAtMost
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorArg
import org.fcitx.fcitx5.android.utils.withLowercaseExtension
import timber.log.Timber
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

object PinyinDictManager {

    internal val pinyinDicDir = File(
        appContext.getExternalFilesDir(null)!!, "data/pinyin/dictionaries"
    ).also { it.mkdirs() }

    // only the Sougou conversion needs it, and a test's application has none
    private val nativeDir by lazy { File(appContext.applicationInfo.nativeLibraryDir) }

    private val scel2org5 by lazy { File(nativeDir, scel2org5Name) }

    /** The dictionaries the user imported, on or off, by name. */
    fun listDictionaries(): List<TextDictionary> {
        ImportedDictionaries.migrate(pinyinDicDir)
        // each made under the sources lock: a conversion's commit deletes a text listed, which TextDictionary refuses (F3602)
        return synchronized(ImportedDictionaries) { ImportedDictionaries.list(pinyinDicDir).map(::TextDictionary) }
    }

    /** The dictionaries libime's migration put aside unread, until [forgetUnread] is told of them. */
    fun unread() = ImportedDictionaries.unread(pinyinDicDir)

    /** Forgets [told], the dictionaries put aside unread the user was told of. */
    fun forgetUnread(told: List<ImportedDictionaries.Unread>) = runCatching { ImportedDictionaries.forgetUnread(pinyinDicDir, told) }

    /**
     * Deletes [dictionary], by its name: whichever of its files is there now (the page may have
     * listed a text since converted); whether it went. The merged file is made again without it.
     */
    fun delete(dictionary: TextDictionary): Boolean =
        ImportedDictionaries.delete(pinyinDicDir, dictionary.name).also { if (it) MergedDictionary.refresh(pinyinDicDir) }

    /**
     * Turns [dictionary] on or off; whether it is so now. The merged file is made again for it.
     * One turned on that would take the merged words past what the compiler takes is refused
     * ([Refusal], TOO_MANY), left off: its compile would fail at every start (review S5). [max]
     * is the compiler's, a test's smaller.
     */
    fun turn(dictionary: TextDictionary, on: Boolean, max: Int = DictionaryCompiler.MAX_WORDS): Boolean {
        // counted by its name, as it is turned: the file the page listed may be a text converted since (F3591)
        if (on && mergedWords() + wordCount(dictionary) > max) {
            refuse(DictionaryFormat.Reason.TOO_MANY)
        }
        // by its name, as delete: not the file the page listed
        return ImportedDictionaries.turn(pinyinDicDir, dictionary.name, on).also { if (it) MergedDictionary.refresh(pinyinDicDir) }
    }

    /**
     * The words of the dictionaries on: each as [wordCount] has it, from the merged file's header
     * or, for one not in it yet (an older build's text still upgrading, or its compile failed),
     * from its text. The header alone counted those as none, and one more could be turned on past
     * the limit.
     */
    private fun mergedWords(): Int {
        // a file each by name, listed at once: a text and its gzip of one name (a kill, a restore) counted once (F3595)
        val header = MergedDictionary.sourceWords(pinyinDicDir)
        return ImportedDictionaries.list(pinyinDicDir).filter { SourceNames.isOn(it.name) }.sumOf { wordCount(SourceNames.nameOf(it.name), header) }
    }

    /**
     * A file picked to import, as text in the cache ([text], its charset whatever it was) and
     * what [DictionaryFormat] made of it; null [analysis] for the user's own export, which is
     * no other input method's list and is read as it is. [close] deletes the copy.
     */
    class Prepared(val text: File, val analysis: DictionaryFormat.Analysis?, val lines: File? = null) : Closeable {
        override fun close() {
            text.delete()
            lines?.delete()
        }
    }

    /**
     * [stream], a file called [name] of any [PinyinDictionary.Type] (or of none, a word list for
     * the user dictionary), copied into the cache, a binary dictionary converted to text, and
     * analysed through; [stream] is closed. Before anything is imported: the confirm dialog
     * shows what it is, or that it is refused.
     */
    fun prepare(stream: InputStream, name: String, size: Long = 0, progress: (Float) -> Unit = {}): Prepared {
        // the name a provider gives is no path to trust: only its extension, which tells the type
        val type = PinyinDictionary.Type.fromFileName(withLowercaseExtension(File(name).name))
        val raw = File.createTempFile("import", ".${type?.ext ?: PinyinDictionary.Type.Text.ext}", appContext.cacheDir)
        var made: File? = null
        var lines: File? = null
        var done = false
        try {
            // the copy a tenth of the way, as [size] (the provider's, 0 for none known) says
            stream.use { input -> raw.outputStream().use { copy(input, it, size) { share -> progress(COPY_SHARE * share) } } }
            val text = if (type == null || type == PinyinDictionary.Type.Text) {
                raw
            } else {
                val dictionary = PinyinDictionary.new(raw) ?: errorArg(R.string.exception_dict_filename, name)
                File.createTempFile("import", ".${PinyinDictionary.Type.Text.ext}", appContext.cacheDir).also {
                    it.delete()
                    dictionary.toTextDictionary(it)
                }
            }
            made = text
            val prepared = if (isOwnExport(text)) {
                Prepared(ownExportAsText(text), null)
            } else {
                // the words written as the base keeps them while it is analysed: no pass more to import it (frost's 85 MB took 29 s in three)
                val out = File.createTempFile("import", ".lines", appContext.cacheDir).also { lines = it }
                val analysis = out.bufferedWriter().use { w ->
                    DictionaryFormat.analyse({ text.inputStream() }, text.length(), { w.write(it); w.write('\n'.code) }, knows()) { progress(COPY_SHARE + (1 - COPY_SHARE) * it) }
                }
                Prepared(text, analysis, out)
            }
            done = true
            return prepared
        } finally {
            if (made != raw) raw.delete()
            if (!done) {
                made?.delete()
                lines?.delete()
            }
        }
    }

    private fun copy(input: InputStream, out: OutputStream, size: Long, progress: (Float) -> Unit) {
        val buffer = ByteArray(COPY_BUFFER)
        var copied = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return
            out.write(buffer, 0, n)
            copied += n
            if (size > 0) progress(minOf(1f, copied.toFloat() / size))
        }
    }

    /**
     * Deletes what an import left in the cache when its page was killed (the copy and its lines,
     * about twice the file): those older than an hour, never one an import under way still uses.
     */
    fun sweepCache() {
        val old = System.currentTimeMillis() - STALE_MS
        appContext.cacheDir.listFiles { f -> f.name.startsWith("import") && f.lastModified() < old }?.forEach { it.delete() }
    }

    // the base dictionary's readings, a file's characters checked against: none in a test (no assets)
    private fun knows(): ((String, IntArray) -> Boolean)? = MergedDictionary.base()?.has?.invoke()

    private fun isOwnExport(text: File) = text.inputStream().use { WordListFiles.isOwnExport(it.readAtMost(EXPORT_PEEK)) }

    // the user's own export put into the base: its learned words too, as WordListFiles.baseLines reads it
    private fun ownExportAsText(export: File): File {
        val text = File.createTempFile("import", ".${PinyinDictionary.Type.Text.ext}", appContext.cacheDir)
        var done = false
        try {
            export.useLines(WordListFiles.charset { export.inputStream() }) { lines ->
                text.bufferedWriter().use { out -> WordListFiles.baseLines(lines).forEach { out.write(it); out.write('\n'.code) } }
            }
            done = true
        } finally {
            export.delete()
            if (!done) text.delete()
        }
        return text
    }

    /**
     * Imports [prepared] into the base dictionary, named [name] (a file's name, its extension
     * dropped), as gzip'd text the engine's compile reads, then compiles the merged file with it and
     * waits for that: the words are there once this returns. Refused as [DictionaryFormat] said,
     * or for no pinyin, or where one of the name is there, or where it would take the merged
     * words past what the compiler takes.
     */
    fun importPrepared(
        prepared: Prepared,
        name: String,
        cancel: AtomicBoolean = AtomicBoolean(),
        progress: (Float) -> Unit = {},
    ): Result<TextDictionary> = runCatching {
        val dest = File(pinyinDicDir, TextDictionary.fileName(name, true))
        // one of that name, on or off, is the user's: the list may not have shown it yet
        if (ImportedDictionaries.hasDictionary(pinyinDicDir, name)) errorArg(R.string.dict_already_exists)
        // made, it is the dictionary's: deleted on any way out but success, none left on that the user was told failed (review M2);
        // set once it is renamed into place, never for one of its name that came meanwhile (the commit refuses it)
        var made = false
        try {
            val words = try {
                when (val analysis = prepared.analysis) {
                    null -> prepared.text.useLines { ImportedDictionaries.write(it, dest) }.also { made = true }
                    is DictionaryFormat.Refused -> refuse(analysis.reason)
                    is DictionaryFormat.Detected -> {
                        analysis.refusedForBase?.let(::refuse)
                        if (mergedWords() + analysis.words > DictionaryCompiler.MAX_WORDS) refuse(DictionaryFormat.Reason.TOO_MANY)
                        val lines = prepared.lines ?: error("analysed without its lines")
                        // already as the sources keep them: deflated, not read again (review S10)
                        copyLines(lines, dest, cancel, progress)
                        made = true
                        analysis.words
                    }
                }
            } catch (e: ImportedDictionaries.NameTaken) {
                // one of its name came as it was written: told as the check above tells it (F3603)
                Timber.d(e)
                errorArg(R.string.dict_already_exists)
            }
            if (words == 0) errorArg(R.string.exception_dict_no_words, name)
            // out of the sources lock (the commit's): the compile thread takes it
            MergedDictionary.refreshNow(pinyinDicDir, cancel)
            made = false
        } finally {
            if (made) {
                // cancelled, or failed: not imported, the merged file as it was
                dest.delete()
                MergedDictionary.refresh(pinyinDicDir)
            }
        }
        TextDictionary(dest).also { Timber.d("Imported %s as %s", name, it) }
    }

    // [lines] into [dest], gzip'd through a temporary file outside the sources lock, [cancel]
    // checked as it goes (the bytes read tell how far, the deflate with them); committed under it
    private fun copyLines(lines: File, dest: File, cancel: AtomicBoolean, progress: (Float) -> Unit) {
        val temp = File(dest.path + ".tmp")
        try {
            val size = lines.length().coerceAtLeast(1)
            lines.inputStream().use { input ->
                SourceNames.writeGzip(temp) { out ->
                    copy(input, out, size) {
                        if (cancel.get()) throw CancellationException("cancelled")
                        progress(it)
                    }
                }
            }
            ImportedDictionaries.commit(temp, dest)
        } finally {
            temp.delete()
        }
    }

    /** What the user is told of a file refused for [reason]: 格式不支持, and why. */
    fun refusal(reason: DictionaryFormat.Reason): String = appContext.getString(R.string.dict_refused, appContext.getString(reasonText(reason)))

    private fun refuse(reason: DictionaryFormat.Reason): Nothing = throw Refusal(reason)

    /** A file refused for [reason] ([DictionaryFormat]), its message the user's. */
    class Refusal(val reason: DictionaryFormat.Reason) : IllegalArgumentException() {
        override val message: String get() = refusal(reason)
    }

    @StringRes
    private fun reasonText(reason: DictionaryFormat.Reason): Int = when (reason) {
        DictionaryFormat.Reason.ENCODING -> R.string.dict_refused_encoding
        DictionaryFormat.Reason.BINARY -> R.string.dict_refused_binary
        DictionaryFormat.Reason.NO_WORDS -> R.string.dict_refused_no_words
        DictionaryFormat.Reason.MIXED -> R.string.dict_refused_mixed
        DictionaryFormat.Reason.INITIALS -> R.string.dict_refused_initials
        DictionaryFormat.Reason.UNREADABLE -> R.string.dict_refused_unreadable
        DictionaryFormat.Reason.MIXED_WEIGHTS -> R.string.dict_refused_mixed_weights
        DictionaryFormat.Reason.TOO_MANY -> R.string.dict_refused_too_many
        DictionaryFormat.Reason.NO_PINYIN -> R.string.dict_refused_no_pinyin
        DictionaryFormat.Reason.MISMATCH -> R.string.dict_refused_mismatch
    }

    /**
     * Imports [file], of any [PinyinDictionary.Type], into the base dictionary, named as it is:
     * [prepare]d, then [importPrepared]. One imported into the user dictionary is read word by
     * word by the settings page, not kept as a file. The page itself prepares and imports in two
     * steps, its confirm dialog between.
     */
    @VisibleForTesting
    fun importFromFile(file: File): Result<TextDictionary> = importFromInputStream(file.inputStream(), file.name)

    @VisibleForTesting
    fun importFromInputStream(stream: InputStream, name: String): Result<TextDictionary> {
        // the name a provider gives is no path to trust: its last part alone, which names the dictionary
        val fileName = withLowercaseExtension(File(name).name)
        // one that is no dictionary's is refused unread: "." or ".." would be the cache or what holds it
        if (PinyinDictionary.Type.fromFileName(fileName) == null) {
            stream.close()
            return runCatching { errorArg(R.string.exception_dict_filename, name) }
        }
        // a stream that fails as it is copied throws, as it did before the analysis
        return prepare(stream, fileName).use { importPrepared(it, PinyinDictionary.nameOf(fileName)) }
    }

    /**
     * [block] run on the words of [stream], a dictionary of any [PinyinDictionary.Type] called
     * [name], as lines for the user dictionary to import: read as it takes them, once and only
     * while it runs. [stream] is copied and closed first, on the caller's thread: [block] reads
     * the lines of a local file, on whichever thread takes them (fcitx's, for the engine).
     * Nothing is kept of the file.
     */
    suspend fun <T> readWords(stream: InputStream, name: String, block: suspend (Sequence<String>) -> T): T {
        val text = stream.use { textOf(it, name) }
        try {
            return text.useLines { block(it) }
        } finally {
            text.delete()
        }
    }

    /** [input], a dictionary called [name], as text in a file of the cache, the caller's to delete. */
    private fun textOf(input: InputStream, name: String): File {
        // the name a provider gives is no path to trust: only its extension, which tells the type
        val raw = File.createTempFile("import", ".${withLowercaseExtension(name).substringAfterLast('.', "")}", appContext.cacheDir)
        val text = File.createTempFile("import", ".${PinyinDictionary.Type.Text.ext}", appContext.cacheDir)
        var made = false
        try {
            raw.outputStream().use { input.copyTo(it) }
            val dictionary = PinyinDictionary.new(raw) ?: errorArg(R.string.exception_dict_filename, name)
            text.delete()
            dictionary.toTextDictionary(text)
            made = true
            return text
        } finally {
            raw.delete()
            if (!made) text.delete()
        }
    }

    /**
     * How many words [dictionary] has: as the merged file's header has it for one on, compiled
     * with it; else its lines, but for comments and blank ones, read a line at a time.
     */
    fun wordCount(dictionary: TextDictionary): Int = wordCount(dictionary.name, MergedDictionary.sourceWords(pinyinDicDir))

    // the dictionary called [name] as its file is now, not as a page listed it (a text converted since
    // counted none, and one more turned on past the limit, F3591); [header] the merged file's counts.
    // One replaced as it is counted (a conversion's commit) is counted again as it is then
    // (no test: the rename cannot be timed into a count without a hook in this code, F3656)
    private fun wordCount(name: String, header: Map<String, Int>): Int {
        repeat(COUNT_TRIES) {
            val file = ImportedDictionaries.fileOf(pinyinDicDir, name) ?: return 0
            if (SourceNames.isOn(file.name)) header[name]?.let { return it }
            try {
                return countWordLines(file)
            } catch (e: FileNotFoundException) {
                Timber.d(e, "%s: replaced as it was counted", file.name)
            } catch (e: IOException) {
                Timber.w(e, "%s: not counted", file.name)
                return 0
            }
        }
        return 0
    }

    fun sougouDictConv(src: String, dest: String) {
        runConverter(listOf(scel2org5.absolutePath, "-o", dest, src), nativeDir)
    }

    /**
     * Runs [command] with [libraryDir] as its library path, the only variable of its environment.
     *
     * @throws IOException if it fails, with what it printed
     */
    internal fun runConverter(command: List<String>, libraryDir: File) {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply { environment().run { clear(); put("LD_LIBRARY_PATH", libraryDir.absolutePath) } }
            .start()
        try {
            // read through before the wait: one that fills the pipe stops until it is read
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor() != 0) throw IOException(output)
        } finally {
            process.destroy()
        }
    }

    private const val scel2org5Name = "libscel2org5.so"

    /** How much of a file is read to tell it: the export's header is its first line, and a blocked word's `!` is soon there if it has one. */
    internal const val EXPORT_PEEK = 64 shl 10

    private const val COPY_BUFFER = 1 shl 16

    private const val COUNT_TRIES = 3

    private const val STALE_MS = 60 * 60 * 1000L

    // the share of an import's reading that is the copy into the cache: a fast pass beside the analysis
    private const val COPY_SHARE = 0.1f
}

/** The lines of [file] that may be words: not blank, not a comment. Read a line at a time; one rule for the page's count and the import's cap. */
internal fun countWordLines(file: File): Int = SourceNames.open(file).bufferedReader().useLines { lines -> lines.count { it.isNotBlank() && !it.startsWith("#") } }
