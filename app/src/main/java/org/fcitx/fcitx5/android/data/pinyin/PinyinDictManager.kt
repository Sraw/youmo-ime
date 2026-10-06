/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.TextDictionary
import org.fcitx.fcitx5.android.engine.user.WordPack
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorArg
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
            .filter { PinyinDictionary.Type.fromFileName(it.name).let { t -> t == PinyinDictionary.Type.Text || t == PinyinDictionary.Type.Words } }
            .map(::TextDictionary)
            .sortedBy { it.name }
    }

    /** Whether dictionary [name] is merged into the new words dictionary rather than the base one. */
    fun isIntoNew(name: String) = name in ImportedDictionaries.intoNew(pinyinDicDir)

    /** Merges dictionary [name] into the new words dictionary ([into]) or the base one. */
    fun setIntoNew(name: String, into: Boolean) = runCatching { ImportedDictionaries.setIntoNew(pinyinDicDir, name, into) }

    /** Deletes [dictionary], and what was kept of where it was merged; whether both went. */
    fun delete(dictionary: TextDictionary): Boolean =
        dictionary.file.delete() && setIntoNew(dictionary.name, false).isSuccess

    /**
     * Imports [file], of any [PinyinDictionary.Type], as text the engine reads, named as it is,
     * merged into the new words dictionary ([intoNew]) or the base one.
     */
    fun importFromFile(file: File, intoNew: Boolean = false): Result<TextDictionary> = runCatching {
        val raw =
            PinyinDictionary.new(file) ?: errorArg(R.string.exception_dict_filename, file.path)
        val text = File.createTempFile("import", ".${PinyinDictionary.Type.Text.ext}", appContext.cacheDir)
        try {
            raw.toTextDictionary(text)
            // a word pack is told by its first line, whatever the file was called
            val pack = text.useLines { WordPack.isPack(it.firstOrNull().orEmpty()) }
            val type = if (pack) PinyinDictionary.Type.Words else PinyinDictionary.Type.Text
            val dest = File(pinyinDicDir, TextDictionary.fileName(raw.name, true, type))
            // one of that name, on or off, a pack or not, is the user's: the list may not have shown it yet
            if (hasDictionary(raw.name)) errorArg(R.string.dict_already_exists)
            val words = text.useLines { if (pack) ImportedDictionaries.writePack(it, dest) else ImportedDictionaries.write(it, dest) }
            if (words == 0) errorArg(R.string.exception_dict_no_words, file.name)
            // not kept, a failed import, but for the file: one tried again would be "already there"
            try {
                ImportedDictionaries.setIntoNew(pinyinDicDir, raw.name, intoNew)
            } catch (e: IOException) {
                dest.delete()
                throw e
            }
            TextDictionary(dest).also { Timber.d("Imported $raw as $it") }
        } finally {
            text.delete()
        }
    }

    /**
     * Keeps [text], the word pack the server hands out as [name], in place of the one of that name
     * fetched before, on or off as the user left it. A plain dictionary of the name is not touched.
     */
    fun importPack(name: String, text: String): Result<TextDictionary> = runCatching {
        if (!WordPack.validName(name)) errorArg(R.string.exception_dict_filename, name)
        if (listOf(true, false).any { File(pinyinDicDir, TextDictionary.fileName(name, it)).exists() }) errorArg(R.string.dict_already_exists)
        val off = File(pinyinDicDir, TextDictionary.fileName(name, false, PinyinDictionary.Type.Words))
        val on = File(pinyinDicDir, TextDictionary.fileName(name, true, PinyinDictionary.Type.Words))
        val dest = if (off.exists()) off else on
        // new words, as the server hands them out: merged into the new words dictionary when
        // first fetched, then where the user left it
        val first = !off.exists() && !on.exists()
        if (ImportedDictionaries.writePack(text.lineSequence(), dest) == 0) errorArg(R.string.exception_dict_no_words, name)
        if (first) ImportedDictionaries.setIntoNew(pinyinDicDir, name, true)
        TextDictionary(dest)
    }

    /** Whether the pack called [name] is already there, on or off, as [text] has it: nothing to import then. */
    fun hasSamePack(name: String, text: String): Boolean {
        val file = listOf(true, false).map { File(pinyinDicDir, TextDictionary.fileName(name, it, PinyinDictionary.Type.Words)) }.firstOrNull { it.exists() }
            ?: return false
        val lines = text.lineSequence().mapIndexed { i, l -> if (i == 0) l.removePrefix("\uFEFF") else l }.toList().dropLastWhile { it.isEmpty() }
        return runCatching { file.useLines { it.toList().dropLastWhile { l -> l.isEmpty() } } }.getOrNull() == lines
    }

    private fun hasDictionary(name: String) = listOf(PinyinDictionary.Type.Text, PinyinDictionary.Type.Words).any { type ->
        listOf(true, false).any { File(pinyinDicDir, TextDictionary.fileName(name, it, type)).exists() }
    }

    fun importFromInputStream(stream: InputStream, name: String, intoNew: Boolean = false): Result<TextDictionary> {
        val tempFile = File(appContext.cacheDir, name)
        tempFile.outputStream().use {
            stream.copyTo(it)
        }
        val new = importFromFile(tempFile, intoNew)
        tempFile.delete()
        return new
    }

    /**
     * The words of [stream], a dictionary of any [PinyinDictionary.Type] called [name], as lines
     * for the user dictionary to import: nothing is kept of the file.
     */
    fun readWords(stream: InputStream, name: String): Result<List<String>> = runCatching {
        // the name a provider gives is no path to trust: only its extension, which tells the type
        val raw = File.createTempFile("import", ".${name.substringAfterLast('.', "")}", appContext.cacheDir)
        val text = File.createTempFile("import", ".${PinyinDictionary.Type.Text.ext}", appContext.cacheDir)
        try {
            raw.outputStream().use { stream.copyTo(it) }
            val dictionary = PinyinDictionary.new(raw) ?: errorArg(R.string.exception_dict_filename, name)
            text.delete()
            dictionary.toTextDictionary(text)
            text.readLines()
        } finally {
            raw.delete()
            text.delete()
        }
    }

    /** How many words [dictionary] has: its lines, but for comments and blank ones. */
    fun wordCount(dictionary: TextDictionary): Int = runCatching {
        dictionary.file.useLines { lines -> lines.count { it.isNotBlank() && !it.startsWith("#") } }
    }.getOrDefault(0)

    fun sougouDictConv(src: String, dest: String) {
        val process = Runtime.getRuntime()
            .exec(
                arrayOf(scel2org5.absolutePath, "-o", dest, src),
                arrayOf("LD_LIBRARY_PATH=${nativeDir.absolutePath}")
            )
        process.waitFor()
        if (process.exitValue() != 0) {
            throw IOException(process.errorStream.bufferedReader().readText())
        }
    }

    private const val scel2org5Name = "libscel2org5.so"
}