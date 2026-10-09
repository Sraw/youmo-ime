/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.TextDictionary
import org.fcitx.fcitx5.android.data.replaceFile
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
        replaceFile(dest, File(dest.path + ".tmp")) { temp ->
            temp.bufferedWriter().use { out ->
                for (line in LibimeImport.dictionaryText(lines.mapIndexed { i, l -> if (i == 0) l.removePrefix("\uFEFF") else l })) {
                    out.write(line)
                    out.write('\n'.code)
                    words++
                }
            }
            words > 0
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
        replaceFile(dest, File(dest.path + ".tmp")) { temp ->
            temp.bufferedWriter().use { out -> kept.forEach { out.write(it); out.write('\n'.code) } }
            true
        }
        return pack.words.size
    }

    /**
     * Turns each of libime's dictionaries in [dir] into text, on or off as it was, and moves it
     * to [LIBIME]; one with no word the engine can read, or that is no libime dictionary, or too
     * large for the heap, or whose name a text one has, is only moved. One that could not be read
     * (an I/O error) is left to try again. Those only moved, but for one whose name a text one
     * has, are noted for the settings to tell of ([unread]); so are, once, those an older one put
     * aside without noting them, if they still cannot be read. Both the settings and the engine
     * call it, on their own threads.
     */
    @Synchronized
    fun migrate(dir: File) {
        // before any is put aside: one put aside under a taken name is noted by the name it had
        noteOlder(dir)
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
        val unread = try {
            if (listOf(true, false).any { file.resolveSibling(TextDictionary.fileName(name, it)).exists() }) {
                Timber.w("libime's dictionary %s: a dictionary of its name is there", file.name)
                null
            } else {
                val lines = LibimeFiles.pinyinDictionary(file.readBytes())
                val words = write(lines.asSequence(), file.resolveSibling(TextDictionary.fileName(name, enabled)))
                Timber.i("libime's dictionary %s: %d of %d words kept", file.name, words, lines.size)
                if (words == 0) Unread.Reason.NO_WORDS else null
            }
        } catch (e: IOException) {
            Timber.w(e, "libime's dictionary %s", file.name)
            return
        } catch (e: DataFormatException) {
            Timber.w(e, "libime's dictionary %s", file.name)
            Unread.Reason.DAMAGED
        } catch (e: OutOfMemoryError) {
            // left where it is, it would be read again, and run the heap out, at every start
            Timber.w(e, "libime's dictionary %s", file.name)
            Unread.Reason.TOO_LARGE
        }
        if (putAside(file) && unread != null) note(file.resolveSibling(LIBIME), Unread(file.name, unread))
    }

    private fun putAside(file: File): Boolean {
        val aside = File(file.parentFile, LIBIME)
        // a new one holds none an older migration put there unnoted
        if (aside.mkdirs()) {
            try {
                File(aside, OLDER_NOTED).createNewFile()
            } catch (e: IOException) {
                Timber.w(e, "cannot mark %s as holding none an older migration put there", aside)
            }
        }
        var dest = File(aside, file.name)
        var n = 1
        while (dest.exists()) dest = File(aside, "${file.name}.${n++}")
        val moved = file.renameTo(dest)
        if (!moved) Timber.w("libime's dictionary %s: cannot move it to %s", file.name, dest)
        return moved
    }

    // an older migration put aside what it could not read without a note: each there that is
    // not noted and has no text dictionary of its name is read again, and noted once if it
    // still cannot be
    private fun noteOlder(dir: File) {
        val aside = File(dir, LIBIME)
        val done = File(aside, OLDER_NOTED)
        // none aside, none an older one left: what is put aside from now on is noted as it goes
        if (!aside.isDirectory || done.exists()) return
        try {
            val unread = readUnread(aside)
            val noted = unread.map { it.fileName }.toSet()
            // each name putAside gives holds .dict, the notes kept with them do not
            val older = aside.listFiles().orEmpty()
                .filter { it.isFile && ".dict" in it.name && it.name !in noted }
                .filter { file ->
                    val name = file.name.substringBeforeLast(".dict")
                    listOf(true, false).none { File(dir, TextDictionary.fileName(name, it)).exists() }
                }
                // one that reads is no news: turned into text before, its text since deleted
                .mapNotNull { file -> whyUnread(file)?.let { Unread(file.name, it) } }
                .sortedBy { it.fileName }
            // put aside before any noted
            if (older.isNotEmpty()) writeUnread(aside, older + unread)
            done.createNewFile()
        } catch (e: IOException) {
            // not marked done: tried again at the next migration
            Timber.w(e, "dictionaries an older migration put aside unread")
        }
    }

    // why [file], put aside by an older migration, cannot be turned into text as migrate would
    // turn it; none if it can
    private fun whyUnread(file: File): Unread.Reason? = try {
        val lines = LibimeFiles.pinyinDictionary(file.readBytes())
        if (LibimeImport.dictionaryText(lines.asSequence()).none()) Unread.Reason.NO_WORDS else null
    } catch (e: IOException) {
        // noted, not tried again: that would read each of the others again at the next migration
        Timber.w(e, "libime's dictionary %s", file.name)
        Unread.Reason.UNKNOWN
    } catch (e: DataFormatException) {
        Timber.w(e, "libime's dictionary %s", file.name)
        Unread.Reason.DAMAGED
    } catch (e: OutOfMemoryError) {
        Timber.w(e, "libime's dictionary %s", file.name)
        Unread.Reason.TOO_LARGE
    }

    /** A dictionary [migrate] put aside unread: the name of its file, and why. */
    data class Unread(val fileName: String, val reason: Reason) {
        /**
         * too large for the heap; no libime dictionary, or a damaged one; no word the engine can
         * read; not known, an older migration put it aside without a note and it could not be
         * read again
         */
        enum class Reason { TOO_LARGE, DAMAGED, NO_WORDS, UNKNOWN }
    }

    // not noted, the dictionary is aside all the same: only the settings do not tell of it
    private fun note(aside: File, unread: Unread) {
        try {
            writeUnread(aside, readUnread(aside) + unread)
        } catch (e: IOException) {
            Timber.w(e, "libime's dictionary %s: cannot note it was put aside unread", unread.fileName)
        }
    }

    /**
     * The dictionaries [migrate] put aside unread from [dir], in the order it did, until
     * [forgetUnread] is told of them; none if it cannot be read.
     */
    fun unread(dir: File): List<Unread> = try {
        readUnread(File(dir, LIBIME))
    } catch (e: IOException) {
        Timber.w(e, "dictionaries put aside unread")
        emptyList()
    }

    /** Forgets [told], the user told of them; one put aside since they were read is kept, to tell of. */
    @Synchronized
    fun forgetUnread(dir: File, told: List<Unread>) {
        val aside = File(dir, LIBIME)
        val left = readUnread(aside).toMutableList()
        told.forEach { left.remove(it) }
        writeUnread(aside, left)
    }

    private fun readUnread(aside: File): List<Unread> {
        val file = File(aside, UNREAD)
        if (!file.isFile) return emptyList()
        return file.readLines().mapNotNull { line ->
            val reason = Unread.Reason.entries.firstOrNull { line.startsWith("${it.name}\t") } ?: return@mapNotNull null
            Unread(line.substring(reason.name.length + 1), reason)
        }
    }

    private fun writeUnread(aside: File, unread: List<Unread>) {
        val file = File(aside, UNREAD)
        if (unread.isEmpty()) {
            if (!file.delete() && file.exists()) throw IOException("cannot delete $UNREAD")
            return
        }
        replaceFile(file, File(aside, "$UNREAD.tmp")) { temp ->
            // the reason first: a name may hold a tab
            temp.writeText(unread.joinToString("") { "${it.reason.name}\t${it.fileName}\n" })
            true
        }
    }

    /** Where libime's dictionaries go once [migrate] went through them, among the text ones. */
    const val LIBIME = ".libime"

    /**
     * Which of the dictionaries in [LIBIME] [migrate] put there unread, and why, a line each,
     * until the user is told. A dot file among them: no dictionary.
     */
    const val UNREAD = ".unread"

    /**
     * Kept in [LIBIME] once those an older migration put there unread are noted in [UNREAD], or
     * from when [migrate] made it, none being there.
     */
    private const val OLDER_NOTED = ".unread-older"

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
        dir.listFiles().orEmpty().filter { PinyinDictionary.Type.fromFileName(it.name, ignoreCase = false) == PinyinDictionary.Type.Words }
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
        replaceFile(file, File(dir, "$INTO_NEW.tmp")) { temp ->
            temp.writeText(now.sorted().joinToString("") { "$it\n" })
            true
        }
    }

    private const val LAYER_KEY = "# layer:"
    private const val HEADER_LINES = 3
}
