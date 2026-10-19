/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.TextDictionary
import org.fcitx.fcitx5.android.engine.user.WordListFiles
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorArg
import org.fcitx.fcitx5.android.utils.withLowercaseExtension
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream

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
        return pinyinDicDir.listFiles().orEmpty()
            .filter { PinyinDictionary.Type.fromFileName(it.name) == PinyinDictionary.Type.Text }
            .map(::TextDictionary)
            .sortedBy { it.name }
    }

    /** The dictionaries libime's migration put aside unread, until [forgetUnread] is told of them. */
    fun unread() = ImportedDictionaries.unread(pinyinDicDir)

    /** Forgets [told], the dictionaries put aside unread the user was told of. */
    fun forgetUnread(told: List<ImportedDictionaries.Unread>) = runCatching { ImportedDictionaries.forgetUnread(pinyinDicDir, told) }

    /** Deletes [dictionary]; whether it went. */
    fun delete(dictionary: TextDictionary): Boolean = dictionary.file.delete()

    /**
     * Imports [file], of any [PinyinDictionary.Type], as text the engine reads, named as it is,
     * merged into the base dictionary. A text file is read as the user dictionary's import reads
     * one (see [WordListFiles.baseLine]). One imported into the user dictionary is read word by
     * word by the settings page, not kept as a file.
     */
    fun importFromFile(file: File): Result<TextDictionary> = runCatching {
        val raw =
            PinyinDictionary.new(file) ?: errorArg(R.string.exception_dict_filename, file.path)
        val text = File.createTempFile("import", ".${PinyinDictionary.Type.Text.ext}", appContext.cacheDir)
        try {
            val dest = File(pinyinDicDir, TextDictionary.fileName(raw.name, true))
            // one of that name, on or off, is the user's: the list may not have shown it yet
            if (ImportedDictionaries.hasDictionary(pinyinDicDir, raw.name)) errorArg(R.string.dict_already_exists)
            val words = if (raw is TextDictionary) {
                // read where it is, not copied as UTF-8 first: Sogou's export is UTF-16, older lists GB18030
                raw.file.useLines(WordListFiles.charset { raw.file.inputStream() }) { lines ->
                    ImportedDictionaries.write(WordListFiles.baseLines(lines), dest)
                }
            } else {
                raw.toTextDictionary(text)
                text.useLines { ImportedDictionaries.write(it, dest) }
            }
            if (words == 0) errorArg(R.string.exception_dict_no_words, file.name)
            TextDictionary(dest).also { Timber.d("Imported $raw as $it") }
        } finally {
            text.delete()
        }
    }

    fun importFromInputStream(stream: InputStream, name: String): Result<TextDictionary> {
        // the name a provider gives is no path to trust: its last part alone, which names the dictionary
        val fileName = withLowercaseExtension(File(name).name)
        // one that is no dictionary's is refused unread: "." or ".." would be the cache or what holds it
        if (PinyinDictionary.Type.fromFileName(fileName) == null) {
            stream.close()
            return runCatching { errorArg(R.string.exception_dict_filename, name) }
        }
        val tempFile = File(appContext.cacheDir, fileName)
        return try {
            stream.use { input -> tempFile.outputStream().use { input.copyTo(it) } }
            importFromFile(tempFile)
        } finally {
            tempFile.delete()
        }
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

    /** How many words [dictionary] has: its lines, but for comments and blank ones. */
    fun wordCount(dictionary: TextDictionary): Int = runCatching {
        dictionary.file.useLines { lines -> lines.count { it.isNotBlank() && !it.startsWith("#") } }
    }.getOrDefault(0)

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
}
