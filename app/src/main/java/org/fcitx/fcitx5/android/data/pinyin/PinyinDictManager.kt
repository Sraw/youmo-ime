/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.SourceNames
import org.fcitx.fcitx5.android.data.pinyin.dict.TextDictionary
import org.fcitx.fcitx5.android.engine.user.DictionaryCompiler
import org.fcitx.fcitx5.android.engine.user.DictionaryFormat
import org.fcitx.fcitx5.android.engine.user.ImportFiles
import org.fcitx.fcitx5.android.engine.user.WordListFiles
import org.fcitx.fcitx5.android.host.ConfigHome
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

    // nothing is written while not settled, as it would be lost at the next start
    private val data get() = HostConfigDir.inUse(ConfigHome.DATA)
    internal val pinyinDicDir: File
        get() = data.let { File(it.dir, "pinyin/dictionaries").also { dir -> if (it.writable) dir.mkdirs() } }

    private fun notSettled(): Nothing = errorArg(R.string.user_data_not_settled)

    fun listDictionaries(): List<TextDictionary> {
        if (data.writable) ImportedDictionaries.migrate(pinyinDicDir)
        // under the sources lock: a conversion's commit deletes a listed text, which TextDictionary refuses
        return synchronized(ImportedDictionaries) { ImportedDictionaries.list(pinyinDicDir).map(::TextDictionary) }
    }

    fun unread() = ImportedDictionaries.unread(pinyinDicDir)

    fun forgetUnread(told: List<ImportedDictionaries.Unread>) = runCatching {
        if (!data.writable) notSettled()
        ImportedDictionaries.forgetUnread(pinyinDicDir, told)
    }

    /** By its name: the page may have listed a text since converted. */
    fun delete(dictionary: TextDictionary): Boolean {
        // refused as turn is, so the page says why rather than "cannot delete"
        if (!data.writable) notSettled()
        return ImportedDictionaries.delete(pinyinDicDir, dictionary.name).also { if (it) MergedDictionary.refresh(pinyinDicDir) }
    }

    /** One past what the compiler takes is refused and left off: its compile would fail at every start. */
    fun turn(dictionary: TextDictionary, on: Boolean, max: Int = DictionaryCompiler.MAX_WORDS): Boolean {
        if (!data.writable) notSettled()
        // counted by name: the file the page listed may be a text converted since
        if (on && mergedWords() + wordCount(dictionary) > max) {
            refuse(DictionaryFormat.Reason.TOO_MANY)
        }
        return ImportedDictionaries.turn(pinyinDicDir, dictionary.name, on).also { if (it) MergedDictionary.refresh(pinyinDicDir) }
    }

    /** The text for one not in the merged header yet: the header alone counted those as none, past the limit. */
    private fun mergedWords(): Int {
        // a text and its gzip of one name counted once
        val header = MergedDictionary.sourceWords(pinyinDicDir)
        return ImportedDictionaries.list(pinyinDicDir).filter { SourceNames.isOn(it.name) }.sumOf { wordCount(SourceNames.nameOf(it.name), header) }
    }

    /** [text] is in the cache whatever its charset was; null [analysis] for the user's own export, read as is. */
    class Prepared(val text: File, val analysis: DictionaryFormat.Analysis?, val lines: File? = null) : Closeable {
        override fun close() {
            text.delete()
            lines?.delete()
        }
    }

    /** Analysed before anything is imported, so the confirm dialog shows what it is. [guess]: read bare words for the base. */
    fun prepare(
        stream: InputStream,
        name: String,
        size: Long = 0,
        guess: ((List<String>) -> Map<String, String?>)? = null,
        progress: (Float) -> Unit = {},
    ): Prepared {
        // a provider's name is no path to trust: only its extension, which tells the type
        val type = PinyinDictionary.Type.fromFileName(withLowercaseExtension(File(name).name))
        val raw = File.createTempFile("import", ".${type?.ext ?: PinyinDictionary.Type.Text.ext}", appContext.cacheDir)
        var made: File? = null
        var lines: File? = null
        var done = false
        try {
            // [size] is the provider's, 0 if unknown
            stream.use { input -> raw.outputStream().use { copy(input, it, size, maxImported(type)) { share -> progress(COPY_SHARE * share) } } }
            val text = if (type == null || type == PinyinDictionary.Type.Text) {
                raw
            } else {
                val dictionary = PinyinDictionary.new(raw) ?: errorArg(R.string.exception_dict_filename, name)
                File.createTempFile("import", ".${PinyinDictionary.Type.Text.ext}", appContext.cacheDir).also {
                    it.delete()
                    made = it
                    convert(dictionary, it)
                }
            }
            made = text
            val prepared = if (isOwnExport(text)) {
                Prepared(ownExportAsText(text), null)
            } else {
                // written as the base keeps them while analysed: no further pass to import (85 MB took 29 s in three)
                val out = File.createTempFile("import", ".lines", appContext.cacheDir).also { lines = it }
                val analysis = out.bufferedWriter().use { w ->
                    DictionaryFormat.analyse({ text.inputStream() }, text.length(), { w.write(it); w.write('\n'.code) }, knows(), guess) { progress(COPY_SHARE + (1 - COPY_SHARE) * it) }
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

    private fun copy(input: InputStream, out: OutputStream, size: Long, max: Long = Long.MAX_VALUE, progress: (Float) -> Unit) {
        val buffer = ByteArray(COPY_BUFFER)
        var copied = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return
            copied += n
            if (copied > max) refuse(DictionaryFormat.Reason.TOO_LARGE)
            out.write(buffer, 0, n)
            if (size > 0) progress(minOf(1f, copied.toFloat() / size))
        }
    }

    /** Nothing where fcitx's data is not settled: it would be written there. */
    fun retryMerged() {
        val data = data
        if (data.writable) MergedDictionary.retry(File(data.dir, "pinyin/dictionaries"))
    }

    /** Leftovers of a killed import page, older than an hour, never one in use. */
    fun sweepCache() {
        val old = System.currentTimeMillis() - STALE_MS
        appContext.cacheDir.listFiles { f -> f.name.startsWith("import") && f.lastModified() < old }?.forEach { it.delete() }
    }

    // none in a test (no assets)
    private fun knows(): ((String, IntArray) -> Boolean)? = MergedDictionary.base()?.has?.invoke()

    private fun isOwnExport(text: File) = text.inputStream().use { WordListFiles.isOwnExport(it.readAtMost(EXPORT_PEEK)) }

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

    /** Compiles the merged file and waits, so the words are there on return. */
    fun importPrepared(
        prepared: Prepared,
        name: String,
        cancel: AtomicBoolean = AtomicBoolean(),
        progress: (Float) -> Unit = {},
    ): Result<TextDictionary> = runCatching {
        if (!data.writable) notSettled()
        val dest = File(pinyinDicDir, TextDictionary.fileName(name, true))
        // one of that name, on or off: the list may not have shown it yet
        if (ImportedDictionaries.hasDictionary(pinyinDicDir, name)) errorArg(R.string.dict_already_exists)
        // once in place it is the dictionary's: deleted on any way out but success, so none stays on after a told failure
        var made = false
        try {
            val words = try {
                when (val analysis = prepared.analysis) {
                    null -> prepared.text.useLines { ImportedDictionaries.write(it, dest) }.also { made = it > 0 }
                    is DictionaryFormat.Refused -> refuse(analysis.reason)
                    is DictionaryFormat.Detected -> {
                        analysis.refusedForBase?.let(::refuse)
                        if (mergedWords() + analysis.words > DictionaryCompiler.MAX_WORDS) refuse(DictionaryFormat.Reason.TOO_MANY)
                        val lines = prepared.lines ?: error("analysed without its lines")
                        // already deflated as the sources keep them: not read again
                        copyLines(lines, dest, cancel, progress)
                        made = true
                        analysis.words
                    }
                }
            } catch (e: ImportedDictionaries.NameTaken) {
                // one of its name came as it was written: told as the check above tells it
                Timber.d(e)
                errorArg(R.string.dict_already_exists)
            }
            if (words == 0) errorArg(R.string.exception_dict_no_words, name)
            // out of the sources lock: the compile thread takes it
            MergedDictionary.refreshNow(pinyinDicDir, cancel)
            made = false
        } finally {
            if (made) {
                dest.delete()
                MergedDictionary.refresh(pinyinDicDir)
            }
        }
        TextDictionary(dest).also { Timber.d("Imported %s as %s", name, it) }
    }

    // gzip'd through a temp file outside the sources lock, committed under it
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

    fun refusal(reason: DictionaryFormat.Reason): String = appContext.getString(R.string.dict_refused, appContext.getString(reasonText(reason)))

    private fun refuse(reason: DictionaryFormat.Reason): Nothing = throw Refusal(reason)

    /** Its message is the user's. */
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
        DictionaryFormat.Reason.TOO_LARGE -> R.string.dict_refused_too_large
    }

    /** [prepare] then [importPrepared], which the page does with its confirm dialog between. */
    @VisibleForTesting
    fun importFromFile(file: File): Result<TextDictionary> = importFromInputStream(file.inputStream(), file.name)

    @VisibleForTesting
    fun importFromInputStream(stream: InputStream, name: String): Result<TextDictionary> {
        // a provider's name is no path to trust: its last part alone names the dictionary
        val fileName = withLowercaseExtension(File(name).name)
        // refused unread: "." or ".." would be the cache or what holds it
        if (PinyinDictionary.Type.fromFileName(fileName) == null) {
            stream.close()
            return runCatching { errorArg(R.string.exception_dict_filename, name) }
        }
        val dictionary = dictionaryName(fileName) ?: run {
            stream.close()
            return runCatching { errorArg(R.string.exception_dict_filename, name) }
        }
        // a stream that fails as it is copied throws, as it did before the analysis
        return prepare(stream, fileName).use { importPrepared(it, dictionary) }
    }

    /** Rime's `luna_pinyin.dict.yaml` as `luna_pinyin`, any other without its extension. */
    fun keptName(fileName: String): String =
        if (isRimeDictionary(fileName)) fileName.dropLast(RIME_DICTIONARY.length) else PinyinDictionary.nameOf(fileName)

    /** None for `.dict.yaml` alone, nor for a hidden name (the list would show it untitled). */
    fun dictionaryName(fileName: String): String? = keptName(fileName).takeIf { it.isNotEmpty() && !it.startsWith(".") }

    fun isRimeDictionary(fileName: String) = fileName.lowercase().endsWith(RIME_DICTIONARY)

    private const val RIME_DICTIONARY = ".dict.yaml"

    /** [stream] is copied and closed first on the caller's thread; [block] reads the copy on whichever thread takes it. */
    suspend fun <T> readWords(stream: InputStream, name: String, block: suspend (Sequence<String>) -> T): T {
        val text = stream.use { textOf(it, name) }
        try {
            return text.useLines { block(it) }
        } finally {
            text.delete()
        }
    }

    /** Past it, refused as TOO_LARGE before reading: a mistaken pick must not take down the keyboard. */
    internal fun maxImported(type: PinyinDictionary.Type?): Long = when (type) {
        PinyinDictionary.Type.Sougou -> MAX_SCEL
        PinyinDictionary.Type.LibIME -> MAX_LIBIME
        else -> MAX_TEXT
    }

    // one too large for the heap is refused rather than an OOM ending the keyboard's process
    private fun convert(dictionary: PinyinDictionary, dest: File) {
        try {
            dictionary.toTextDictionary(dest)
        } catch (e: OutOfMemoryError) {
            Timber.w(e, "%s: too large to convert", dictionary.file.name)
            dest.delete()
            refuse(DictionaryFormat.Reason.TOO_LARGE)
        }
    }

    private fun textOf(input: InputStream, name: String): File {
        // a provider's name is no path to trust: only its extension, which tells the type
        val raw = File.createTempFile("import", ".${withLowercaseExtension(name).substringAfterLast('.', "")}", appContext.cacheDir)
        val text = File.createTempFile("import", ".${PinyinDictionary.Type.Text.ext}", appContext.cacheDir)
        var made = false
        try {
            val type = PinyinDictionary.Type.fromFileName(withLowercaseExtension(name))
            raw.outputStream().use { copy(input, it, 0, maxImported(type)) {} }
            val dictionary = PinyinDictionary.new(raw) ?: errorArg(R.string.exception_dict_filename, name)
            text.delete()
            convert(dictionary, text)
            made = true
            return text
        } finally {
            raw.delete()
            if (!made) text.delete()
        }
    }

    /** From the merged header for one on; else its non-comment, non-blank lines. */
    fun wordCount(dictionary: TextDictionary): Int = wordCount(dictionary.name, MergedDictionary.sourceWords(pinyinDicDir))

    // by its file now, not as a page listed it; one replaced mid-count is counted again (untested: no hook to time it)
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

    /** scel2org5's output, read in Kotlin. */
    @Throws(IOException::class)
    fun sougouDictConv(src: File, dest: File) {
        // streamed out: a million words in a String is tens of MB in the keyboard's process
        try {
            dest.bufferedWriter().use { ImportFiles.Scel.writeTextDictionary(src.readBytes(), it) }
        } catch (e: IOException) {
            dest.delete()
            throw e
        }
    }


    /** The header is the first line, and a blocked word's `!` comes soon if there is one. */
    internal const val EXPORT_PEEK = 64 shl 10

    private const val COPY_BUFFER = 1 shl 16

    internal const val MAX_SCEL = 32L shl 20

    private const val MAX_LIBIME = 64L shl 20

    private const val MAX_TEXT = 512L shl 20

    private const val COUNT_TRIES = 3

    private const val STALE_MS = 60 * 60 * 1000L

    private const val COPY_SHARE = 0.1f
}

/** One rule for the page's count and the import's cap. */
internal fun countWordLines(file: File): Int = SourceNames.open(file).bufferedReader().useLines { lines -> lines.count { it.isNotBlank() && !it.startsWith("#") } }
