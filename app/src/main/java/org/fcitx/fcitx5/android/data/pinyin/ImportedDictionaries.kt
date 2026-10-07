/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.TextDictionary
import org.fcitx.fcitx5.android.engine.data.WordLayers
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.libime.LibimeFiles
import org.fcitx.fcitx5.android.engine.user.LibimeImport
import org.fcitx.fcitx5.android.engine.user.WordPack
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
     * Writes [lines], a word pack, to [dest] as it is (but for a byte order mark), whole or not
     * at all, once it is read through: the engine reads it again as it is kept.
     *
     * @return how many words it has; none, and nothing is written
     * @throws SourceException if it is no word pack, saying where
     * @throws IOException if it could not be written
     */
    fun writePack(lines: Sequence<String>, dest: File): Int {
        val kept = ArrayList<String>()
        val pack = WordPack.parse(lines.mapIndexed { i, l -> (if (i == 0) l.removePrefix("\uFEFF") else l).also { kept += it } }, dest.name)
        if (pack.words.isEmpty()) return 0
        val temp = File(dest.path + ".tmp")
        try {
            temp.bufferedWriter().use { out -> kept.forEach { out.write(it); out.write('\n'.code) } }
            if (!temp.renameTo(dest)) throw IOException("cannot write ${dest.name}")
        } finally {
            temp.delete()
        }
        return pack.words.size
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

    /**
     * The names of the dictionaries and packs merged into the new words dictionary, one a line;
     * the rest are merged into the base dictionary. A dot file: no dictionary to list.
     */
    const val INTO_NEW = ".new-words"

    /** The names [INTO_NEW] in [dir] lists; none if it cannot be read, all then in the base. */
    fun intoNew(dir: File): Set<String> = try {
        readIntoNew(dir)
    } catch (e: IOException) {
        Timber.w(e, "dictionaries merged into the new words")
        emptySet()
    }

    private fun readIntoNew(dir: File): Set<String> {
        val file = File(dir, INTO_NEW)
        if (!file.isFile) return packsOfTheirOwnLayer(dir)
        return file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /**
     * Before [INTO_NEW] was kept, a pack was weighed as the layer its header named (the cloud's
     * new words as `new`), only `base` as the base: those are the new words still, until the
     * user moves one.
     */
    private fun packsOfTheirOwnLayer(dir: File): Set<String> =
        dir.listFiles().orEmpty().filter { PinyinDictionary.Type.fromFileName(it.name) == PinyinDictionary.Type.Words }
            .filter { file ->
                val layer = file.useLines { lines -> lines.take(HEADER_LINES).firstOrNull { it.startsWith(LAYER_KEY) } }
                layer?.substring(LAYER_KEY.length)?.trim() != WordLayers.BASE
            }
            .map { PinyinDictionary.nameOf(it.name) }.toSet()

    /**
     * Merges dictionary [name] into the new words dictionary ([into]) or the base one. The
     * settings write it, from any thread.
     */
    @Synchronized
    fun setIntoNew(dir: File, name: String, into: Boolean) {
        // a list that does not read is not written over with one name: the rest would be lost
        val names = readIntoNew(dir)
        val now = if (into) names + name else names - name
        val file = File(dir, INTO_NEW)
        if (now == names && file.isFile) return
        val temp = File(dir, "$INTO_NEW.tmp")
        temp.writeText(now.sorted().joinToString("") { "$it\n" })
        if (!temp.renameTo(file)) throw IOException("cannot write $INTO_NEW")
    }

    private const val LAYER_KEY = "# layer:"
    private const val HEADER_LINES = 3
}
