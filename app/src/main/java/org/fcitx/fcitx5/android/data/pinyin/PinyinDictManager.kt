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

    /** Imports [file], of any [PinyinDictionary.Type], as text the engine reads, named as it is. */
    fun importFromFile(file: File): Result<TextDictionary> = runCatching {
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
        val dest = if (off.exists()) off else File(pinyinDicDir, TextDictionary.fileName(name, true, PinyinDictionary.Type.Words))
        if (ImportedDictionaries.writePack(text.lineSequence(), dest) == 0) errorArg(R.string.exception_dict_no_words, name)
        TextDictionary(dest)
    }

    private fun hasDictionary(name: String) = listOf(PinyinDictionary.Type.Text, PinyinDictionary.Type.Words).any { type ->
        listOf(true, false).any { File(pinyinDicDir, TextDictionary.fileName(name, it, type)).exists() }
    }

    fun importFromInputStream(stream: InputStream, name: String): Result<TextDictionary> {
        val tempFile = File(appContext.cacheDir, name)
        tempFile.outputStream().use {
            stream.copyTo(it)
        }
        val new = importFromFile(tempFile)
        tempFile.delete()
        return new
    }

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