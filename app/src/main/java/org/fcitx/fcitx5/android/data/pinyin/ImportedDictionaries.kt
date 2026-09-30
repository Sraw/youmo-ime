/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.data.pinyin.dict.TextDictionary
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.libime.LibimeFiles
import org.fcitx.fcitx5.android.engine.user.LibimeImport
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * The pinyin dictionaries the user imported, kept in a directory as text the engine reads
 * ([TextDictionary]), a word a line. libime kept them in its binary format (`.dict`, and
 * `.dict.disable` if turned off), which [migrate] turns into text, keeping the originals aside
 * in [LIBIME]: a word today's reader drops is not lost to a reader fixed later.
 */
object ImportedDictionaries {

    /**
     * Writes the words of [lines], a dictionary in libime's text format, to [dest] as the engine
     * keeps them, whole or not at all.
     *
     * @return how many words it has; none, and nothing is written
     * @throws IOException if it could not be written
     */
    fun write(lines: Sequence<String>, dest: File): Int {
        var words = 0
        val temp = File(dest.path + ".tmp")
        try {
            temp.bufferedWriter().use { out ->
                for (line in LibimeImport.dictionaryText(lines.mapIndexed { i, l -> if (i == 0) l.removePrefix("\uFEFF") else l })) {
                    out.write(line)
                    out.write('\n'.code)
                    words++
                }
            }
            if (words > 0 && !temp.renameTo(dest)) throw IOException("cannot write ${dest.name}")
        } finally {
            temp.delete()
        }
        return words
    }

    /**
     * Turns each of libime's dictionaries in [dir] into text, on or off as it was, and moves it
     * to [LIBIME]; one with no word the engine can read, or that is no libime dictionary, or
     * whose name a text one has, is only moved. One that could not be read (an I/O error) is
     * left to try again. Both the settings and the engine call it, on their own threads.
     */
    @Synchronized
    fun migrate(dir: File) {
        for (file in dir.listFiles().orEmpty()) {
            val enabled = when {
                file.name.endsWith(".dict") -> true
                file.name.endsWith(".dict.${TextDictionary.DISABLE}") -> false
                else -> null
            }
            if (enabled != null) migrate(file, enabled)
        }
    }

    private fun migrate(file: File, enabled: Boolean) {
        val name = file.name.substringBeforeLast(".dict")
        try {
            if (listOf(true, false).any { file.resolveSibling(TextDictionary.fileName(name, it)).exists() }) {
                Timber.w("libime's dictionary %s: a dictionary of its name is there", file.name)
            } else {
                val lines = LibimeFiles.pinyinDictionary(file.readBytes())
                val words = write(lines.asSequence(), file.resolveSibling(TextDictionary.fileName(name, enabled)))
                Timber.i("libime's dictionary %s: %d of %d words kept", file.name, words, lines.size)
            }
        } catch (e: IOException) {
            Timber.w(e, "libime's dictionary %s", file.name)
            return
        } catch (e: DataFormatException) {
            Timber.w(e, "libime's dictionary %s", file.name)
        }
        putAside(file)
    }

    private fun putAside(file: File) {
        val aside = File(file.parentFile, LIBIME).apply { mkdirs() }
        var dest = File(aside, file.name)
        var n = 1
        while (dest.exists()) dest = File(aside, "${file.name}.${n++}")
        if (!file.renameTo(dest)) Timber.w("libime's dictionary %s: cannot move it to %s", file.name, dest)
    }

    /** Where libime's dictionaries go once [migrate] went through them, among the text ones. */
    const val LIBIME = ".libime"
}
