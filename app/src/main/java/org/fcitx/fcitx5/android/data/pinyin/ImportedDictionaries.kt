/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.data.pinyin.dict.SourceNames
import org.fcitx.fcitx5.android.data.pinyin.dict.TextDictionary
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.libime.LibimeFiles
import org.fcitx.fcitx5.android.engine.store.replaceFile
import org.fcitx.fcitx5.android.engine.user.LibimeImport
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * The pinyin dictionaries the user imported, kept in a directory as text the engine's compile
 * reads ([TextDictionary]), a word a line, gzip-compressed ([SourceNames]). libime kept them in its
 * binary format (`.dict`, and `.dict.disable` if turned off), which [migrate] turns into text,
 * keeping the originals aside in [LIBIME]: a word today's reader drops is not lost to a reader
 * fixed later.
 *
 * Its monitor is the sources lock: every file of a dictionary is made, renamed or deleted under
 * it (an import's commit, a turn, a delete, the compile thread's conversion and sweep), so the
 * page and the compile thread never see one half moved. Long work (an import's or a conversion's
 * deflate) is written to a `.tmp` outside it, renamed under it. Never wait on the compile thread
 * holding it ([MergedDictionary.refreshNow]): the compile thread takes it too.
 */
object ImportedDictionaries {

    /**
     * Writes the words of [lines], a dictionary in libime's text format, to [dest] as the engine
     * keeps them ([SourceNames.fileName]), whole or not at all.
     *
     * @return how many words it has; none, and nothing is written
     * @throws IOException if it could not be written, or one of its name came meanwhile
     */
    fun write(lines: Sequence<String>, dest: File): Int {
        var words = 0
        val temp = File(dest.path + ".tmp")
        try {
            SourceNames.writeGzip(temp) { gz ->
                val out = gz.bufferedWriter()
                for (line in LibimeImport.dictionaryText(lines.withoutByteOrderMark())) {
                    out.write(line)
                    out.write('\n'.code)
                    words++
                }
                out.flush()
            }
            if (words == 0) return 0
            commit(temp, dest)
        } finally {
            temp.delete()
        }
        return words
    }

    private fun Sequence<String>.withoutByteOrderMark() = mapIndexed { i, l -> if (i == 0) l.removePrefix("\uFEFF") else l }

    /**
     * [temp], a dictionary written whole and synced ([SourceNames.writeGzip]), renamed to [dest]
     * under the sources lock; refused if one of its name came since its caller looked, or if
     * [temp] is gone (another's write of its path deleted it: never an empty file renamed into place, F3594).
     *
     * @throws NameTaken if one of its name is there
     * @throws IOException if it could not be renamed
     */
    @Synchronized
    fun commit(temp: File, dest: File) {
        if (hasDictionary(dest.parentFile!!, SourceNames.nameOf(dest.name))) throw NameTaken(dest.name)
        if (!temp.isFile || !temp.renameTo(dest)) throw IOException("could not write $dest from $temp")
    }

    /** A dictionary of [fileName]'s name came while one was written: the user is told it exists (F3603). */
    class NameTaken(fileName: String) : IOException("$fileName: one of its name is there")

    /** The file of the dictionary called [name] in [dir] now, as [list] would list it; null if none. */
    @Synchronized
    fun fileOf(dir: File, name: String): File? = filesOf(dir, name).firstOrNull { it.isFile }

    /** Whether [dir] holds a dictionary called [name], on or off, kept or as an older build kept it. */
    @Synchronized
    fun hasDictionary(dir: File, name: String): Boolean =
        filesOf(dir, name).any { it.exists() }

    // the four a dictionary may be: an older build's text first, which wins over a kept one (it is always
    // whole, converted next; or, its delete refused after its conversion, deleted again at the next compile)
    private fun filesOf(dir: File, name: String) = listOf(true, false).map { File(dir, SourceNames.textName(name, it)) } +
        listOf(true, false).map { File(dir, SourceNames.fileName(name, it)) }

    /**
     * The dictionaries in [dir], on or off, a file each by name, by name: an older build's text if
     * one is there (it wins, converted at the next compile, or deleted then if it was already), else the one kept. One kept both on
     * and off is [settle]d first: the page never shows two of one name.
     */
    @Synchronized
    fun list(dir: File): List<File> {
        settle(dir)
        return dir.listFiles { f -> f.isFile && (SourceNames.isSource(f.name) || SourceNames.isText(f.name)) }.orEmpty()
            .groupBy { SourceNames.nameOf(it.name) }
            .map { (name, files) -> filesOf(dir, name).first { it in files } }
            .sortedBy { it.name }
    }

    /**
     * Each dictionary both on and off (a backup restored over the other state): the newer stays,
     * the on one if they are of one time (a second's granularity); the other is deleted. Texts an
     * older build kept first, before either is converted, which would leave the off one whatever
     * the times (F3596); kept ones with no text of their name (with one, its conversion settles them).
     */
    @Synchronized
    fun settle(dir: File) {
        val names = dir.listFiles { f -> f.isFile && (SourceNames.isSource(f.name) || SourceNames.isText(f.name)) }.orEmpty()
            .mapTo(HashSet()) { SourceNames.nameOf(it.name) }
        for (name in names) {
            val texts = listOf(true, false).map { File(dir, SourceNames.textName(name, it)) }
            if (texts.any { it.exists() }) {
                if (texts.all { it.exists() }) keepNewer(texts[0], texts[1])
                continue
            }
            val kept = listOf(true, false).map { File(dir, SourceNames.fileName(name, it)) }
            if (kept.all { it.exists() }) keepNewer(kept[0], kept[1])
        }
    }

    private fun keepNewer(on: File, off: File) {
        val older = if (off.lastModified() > on.lastModified()) on else off
        if (!older.delete()) Timber.w("%s: cannot delete it", older.name)
    }

    /**
     * Turns the dictionary called [name] in [dir] on or off, whichever of its files is there now
     * (the page may have listed a text a conversion has since replaced); whether it is so now.
     */
    @Synchronized
    fun turn(dir: File, name: String, on: Boolean): Boolean {
        settle(dir)
        // all its files together: a text left beside its gzip (its delete refused) turned alone split
        // them, and the text was converted again, an upgrade told (F3686). Text first; its gzip after,
        // or the text turned back if that is refused, so they never part
        val files = filesOf(dir, name).filter { it.isFile }
        if (files.isEmpty()) return false
        val turned = ArrayList<TextDictionary>()
        for (dictionary in files.map(::TextDictionary)) {
            // one of its kind already in the state asked (a backup's overlaid beside a text): that one is it
            if (dictionary.isEnabled == on || dictionary.file.resolveSibling(twin(dictionary.file.name)) in files) continue
            if (if (on) dictionary.enable() else dictionary.disable()) {
                turned += dictionary
            } else {
                // one refused back leaves a text and its gzip parted (F3686): logged, to tell it on a device (F3701)
                turned.filterNot { if (on) it.disable() else it.enable() }.forEach { Timber.w("%s: cannot turn it back", it.file.name) }
                return false
            }
        }
        return true
    }

    // [fileName] in the other state
    private fun twin(fileName: String): String {
        val name = SourceNames.nameOf(fileName)
        val on = !SourceNames.isOn(fileName)
        return if (SourceNames.isText(fileName)) SourceNames.textName(name, on) else SourceNames.fileName(name, on)
    }

    /**
     * Deletes the dictionary called [name] in [dir], each of its files there now; whether none is
     * left. Its text first: one refused leaves its gzip too, the two never parted (F3686).
     */
    @Synchronized
    fun delete(dir: File, name: String): Boolean {
        val (texts, kept) = filesOf(dir, name).partition { SourceNames.isText(it.name) }
        // each tried, not stopping at the first that fails (F3598)
        if (!texts.map { !it.exists() || it.delete() }.all { it }) return false
        return kept.map { !it.exists() || it.delete() }.all { it }
    }

    /**
     * [temp], the gzip of the text of the dictionary called [name] as it was read ([length],
     * [modified]), put in its place, on or off as the text is now (a turn renames it meanwhile,
     * its length and time kept), the text deleted after: false, [temp] dropped, if the text went
     * or changed since (deleted, or a backup restored over it), converted again then if it is there.
     * [Committed.left]: the text's [MergedDictionary.leftKey] if its delete was refused, taken here under the lock.
     *
     * @throws IOException if it could not be renamed
     */
    @Synchronized
    internal fun commitConversion(dir: File, name: String, temp: File, length: Long, modified: Long): Committed? {
        val text = listOf(true, false).map { File(dir, SourceNames.textName(name, it)) }.firstOrNull { it.isFile } ?: run {
            temp.delete()
            return null
        }
        if (text.length() != length || text.lastModified() != modified || !temp.isFile) {
            temp.delete()
            return null
        }
        val on = SourceNames.isOn(text.name)
        val dest = File(dir, SourceNames.fileName(name, on))
        if (!temp.renameTo(dest)) throw IOException("could not write $dest")
        // the text's time kept, a courtesy (design S3): sdcardfs often refuses it; an upgrade is never told by times (F3644)
        dest.setLastModified(modified)
        File(dir, SourceNames.fileName(name, !on)).delete()
        // the gzip is in place now, no failure to tell of (F3649). A text left (its delete refused) is
        // noted by the caller and deleted again at each compile (F3660, F3674); one a kill here left is
        // converted again over the kept one at the next start: a text is always whole
        if (text.delete()) return Committed(null)
        Timber.w("%s: cannot delete it, tried again at each compile", text.name)
        return Committed(MergedDictionary.leftKey(text))
    }

    /** A conversion committed: [left], the [MergedDictionary.leftKey] of its text if that could not be deleted. */
    internal class Committed(val left: String?)

    /**
     * Turns each of libime's dictionaries in [dir] into text, on or off as it was, and moves it
     * to [LIBIME]; one with no word the engine can read, or that is no libime dictionary, or too
     * large for the heap, or whose name a text one has, is only moved. One that
     * could not be read (an I/O error) is left to try again. Those only moved, but for one whose
     * name another has, are noted for the settings to tell of ([unread]); so are, once, those an
     * older one put aside without noting them, if they still cannot be read. Each is read by
     * [read], libime's reader but in a test. Both the settings and the engine call it, on their
     * own threads.
     *
     * The word packs an older build imported (`.words`, and `.words.disable` if turned off) are
     * deleted first: their words are built in now. So is [INTO_NEW], the list of those an older
     * build merged into the new words dictionary: every one is merged into the base now.
     */
    @Synchronized
    fun migrate(dir: File, read: (ByteArray) -> List<String> = LibimeFiles::pinyinDictionary) {
        // before any is put aside: one put aside under a taken name is noted by the name it had
        noteOlder(dir, read)
        val files = dir.listFiles().orEmpty()
        files.filter { isWordPack(it.name) }.forEach { deleteWordPack(it) }
        forgetIntoNew(dir)
        for (file in files) {
            val enabled = when {
                file.name.endsWith(".dict") -> true
                file.name.endsWith(".dict.${TextDictionary.DISABLE}") -> false
                else -> null
            }
            if (enabled != null) migrate(file, enabled, read)
        }
    }

    private fun isWordPack(fileName: String) =
        fileName.endsWith(".words") || fileName.endsWith(".words.${TextDictionary.DISABLE}")

    private fun deleteWordPack(file: File) {
        if (file.delete()) {
            Timber.i("word pack %s: deleted, its words are built in", file.name)
        } else {
            // tried again at the next migration
            Timber.w("word pack %s: cannot delete it", file.name)
        }
    }

    // the user is shown two dictionaries, theirs and the base, with the built-in new words in the base:
    // one merged into the new words, apart from the base, was a third to explain; a restored backup
    // brings the list back, gone again at the next migration
    private fun forgetIntoNew(dir: File) {
        for (file in listOf(File(dir, INTO_NEW), File(dir, "$INTO_NEW.tmp"))) {
            if (file.exists() && !file.delete()) Timber.w("%s: cannot delete it", file.name)
        }
    }

    private fun migrate(file: File, enabled: Boolean, read: (ByteArray) -> List<String>) {
        val name = file.name.substringBeforeLast(".dict")
        val unread = try {
            if (hasDictionary(file.parentFile, name)) {
                Timber.w("libime's dictionary %s: a dictionary of its name is there", file.name)
                null
            } else {
                val lines = read(file.readBytes())
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
    // not noted and has no dictionary of its name is read again, and noted once if it still
    // cannot be
    private fun noteOlder(dir: File, read: (ByteArray) -> List<String>) {
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
                .filter { file -> !hasDictionary(dir, file.name.substringBeforeLast(".dict")) }
                // one that reads is no news: turned into text before, its text since deleted
                .mapNotNull { file -> whyUnread(file, read)?.let { Unread(file.name, it) } }
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
    private fun whyUnread(file: File, read: (ByteArray) -> List<String>): Unread.Reason? = try {
        val lines = read(file.readBytes())
        if (LibimeImport.dictionaryText(lines.asSequence()).none()) {
            Unread.Reason.NO_WORDS
        } else {
            Timber.i("libime's dictionary %s: it reads, not noted", file.name)
            null
        }
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
     * Where an older build listed the dictionaries merged into the new words dictionary, one a line;
     * [migrate] deletes it, all of them merged into the base.
     */
    const val INTO_NEW = ".new-words"
}
