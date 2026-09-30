/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.TextDictionary
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorArg
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream

object PinyinDictManager {

    private val pinyinDicDir = File(
        appContext.getExternalFilesDir(null)!!, "data/pinyin/dictionaries"
    ).also { it.mkdirs() }

    private val nativeDir = File(appContext.applicationInfo.nativeLibraryDir)

    private val scel2org5 by lazy { File(nativeDir, scel2org5Name) }

    /** The dictionaries the user imported, on or off, by name. */
    fun listDictionaries(): List<TextDictionary> {
        ImportedDictionaries.migrate(pinyinDicDir)
        return pinyinDicDir.listFiles().orEmpty()
            .filter { PinyinDictionary.Type.fromFileName(it.name) == PinyinDictionary.Type.Text }
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
            val dest = File(pinyinDicDir, TextDictionary.fileName(raw.name))
            // one of that name, on or off, is the user's: the list may not have shown it yet
            if (dest.exists() || File(pinyinDicDir, TextDictionary.fileName(raw.name, false)).exists()) {
                errorArg(R.string.dict_already_exists)
            }
            if (text.useLines { ImportedDictionaries.write(it, dest) } == 0) {
                errorArg(R.string.exception_dict_no_words, file.name)
            }
            TextDictionary(dest).also { Timber.d("Imported $raw as $it") }
        } finally {
            text.delete()
        }
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