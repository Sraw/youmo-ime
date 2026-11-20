/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.data.pinyin.dict.SourceNames
import org.fcitx.fcitx5.android.data.pinyin.dict.TextDictionary
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.platform.jvm.replaceFile
import org.fcitx.fcitx5.android.engine.user.imports.LibimeFiles
import org.fcitx.fcitx5.android.engine.user.imports.WordText
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Its monitor is the sources lock: every dictionary file is made, renamed or deleted under it. Long work is written to
 * a `.tmp` outside it; never wait on the compile thread while holding it ([MergedDictionary.refreshNow]).
 */
object ImportedDictionaries {

    /** Whole or not at all; throws IOException if one of its name came meanwhile. */
    fun write(lines: Sequence<String>, dest: File): Int {
        var words = 0
        val temp = File(dest.path + ".tmp")
        try {
            SourceNames.writeGzip(temp) { gz ->
                val out = gz.bufferedWriter()
                for (line in WordText.dictionaryText(lines.withoutByteOrderMark())) {
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

    /** Refused if [temp] is gone (another write of its path deleted it: never rename an empty file into place). */
    @Synchronized
    fun commit(temp: File, dest: File) {
        if (hasDictionary(dest.parentFile!!, SourceNames.nameOf(dest.name))) throw NameTaken(dest.name)
        if (!temp.isFile || !temp.renameTo(dest)) throw IOException("could not write $dest from $temp")
    }

    class NameTaken(fileName: String) : IOException("$fileName: one of its name is there")

    @Synchronized
    fun fileOf(dir: File, name: String): File? = filesOf(dir, name).firstOrNull { it.isFile }

    @Synchronized
    fun hasDictionary(dir: File, name: String): Boolean =
        filesOf(dir, name).any { it.exists() }

    // an older build's text first: it wins over a kept one
    private fun filesOf(dir: File, name: String) = listOf(true, false).map { File(dir, SourceNames.textName(name, it)) } +
        listOf(true, false).map { File(dir, SourceNames.fileName(name, it)) }

    /** One kept both on and off is [settle]d first, so the page never shows two. */
    @Synchronized
    fun list(dir: File): List<File> {
        settle(dir)
        return dir.listFiles { f -> f.isFile && (SourceNames.isSource(f.name) || SourceNames.isText(f.name)) }.orEmpty()
            .groupBy { SourceNames.nameOf(it.name) }
            .map { (name, files) -> filesOf(dir, name).first { it in files } }
            .sortedBy { it.name }
    }

    /**
     * Each kept both on and off (a backup restored over the other state): the newer stays, the on one on a tie. Texts
     * are settled before either is converted, which would leave the off one whatever the times.
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

    /** Whichever of its files is there now (the page may have listed a text a conversion has since replaced). */
    @Synchronized
    fun turn(dir: File, name: String, on: Boolean): Boolean {
        settle(dir)
        // all its files together, text first: a text turned alone split them and was reconverted; a refused turn is undone
        val files = filesOf(dir, name).filter { it.isFile }
        if (files.isEmpty()) return false
        val turned = ArrayList<TextDictionary>()
        fun turn(dictionary: TextDictionary, to: Boolean) = if (to) dictionary.enable() else dictionary.disable()
        for (dictionary in files.map(::TextDictionary)) {
            if (dictionary.isEnabled == on || dictionary.file.resolveSibling(twin(dictionary.file.name)) in files) continue
            if (!turn(dictionary, on)) {
                // one refused back leaves a text and its gzip parted: logged, to diagnose on a device
                for (back in turned) {
                    if (!turn(back, !on)) Timber.w("%s: cannot turn it back", back.file.name)
                }
                return false
            }
            turned += dictionary
        }
        return true
    }

    private fun twin(fileName: String): String {
        val name = SourceNames.nameOf(fileName)
        val on = !SourceNames.isOn(fileName)
        return if (SourceNames.isText(fileName)) SourceNames.textName(name, on) else SourceNames.fileName(name, on)
    }

    /** Text first, so the two never part. */
    @Synchronized
    fun delete(dir: File, name: String): Boolean {
        val (texts, kept) = filesOf(dir, name).partition { SourceNames.isText(it.name) }
        // each tried, not stopping at the first that fails
        if (!texts.map { !it.exists() || it.delete() }.all { it }) return false
        return kept.map { !it.exists() || it.delete() }.all { it }
    }

    /** False, [temp] dropped, if the text went or changed since. [Committed.left] is set if the text's delete was refused. */
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
        // a courtesy: sdcardfs often refuses it; an upgrade is never detected by times
        dest.setLastModified(modified)
        File(dir, SourceNames.fileName(name, !on)).delete()
        // a text left is noted and its delete retried at each compile; one a kill left here is reconverted (a text is always whole)
        if (text.delete()) return Committed(null)
        Timber.w("%s: cannot delete it, tried again at each compile", text.name)
        return Committed(LegacyTextConversion.leftKey(text))
    }

    internal class Committed(val left: String?)

    /**
     * One that cannot be read is only moved and noted for the settings to tell ([unread]); one with an I/O error is left
     * for a retry. Called from both the settings and the engine, on their own threads.
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
            // retried at the next migration
            Timber.w("word pack %s: cannot delete it", file.name)
        }
    }

    // the built-in new words show as part of the base; a separate new-words dictionary was a third to explain
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
            // left in place it would be read again, running the heap out, at every start
            Timber.w(e, "libime's dictionary %s", file.name)
            Unread.Reason.TOO_LARGE
        }
        if (putAside(file) && unread != null) note(file.resolveSibling(LIBIME), Unread(file.name, unread))
    }

    private fun putAside(file: File): Boolean {
        val aside = File(file.parentFile, LIBIME)
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

    private fun noteOlder(dir: File, read: (ByteArray) -> List<String>) {
        val aside = File(dir, LIBIME)
        val done = File(aside, OLDER_NOTED)
        if (!aside.isDirectory || done.exists()) return
        try {
            val unread = readUnread(aside)
            val noted = unread.map { it.fileName }.toSet()
            // each name putAside gives holds .dict; the notes kept with them do not
            val older = aside.listFiles().orEmpty()
                .filter { it.isFile && ".dict" in it.name && it.name !in noted }
                .filter { file -> !hasDictionary(dir, file.name.substringBeforeLast(".dict")) }
                // one that reads is no news: turned into text before, its text since deleted
                .mapNotNull { file -> whyUnread(file, read)?.let { Unread(file.name, it) } }
                .sortedBy { it.fileName }
            if (older.isNotEmpty()) writeUnread(aside, older + unread)
            done.createNewFile()
        } catch (e: IOException) {
            // not marked done: retried at the next migration
            Timber.w(e, "dictionaries an older migration put aside unread")
        }
    }

    private fun whyUnread(file: File, read: (ByteArray) -> List<String>): Unread.Reason? = try {
        val lines = read(file.readBytes())
        if (WordText.dictionaryText(lines.asSequence()).none()) {
            Unread.Reason.NO_WORDS
        } else {
            Timber.i("libime's dictionary %s: it reads, not noted", file.name)
            null
        }
    } catch (e: IOException) {
        // noted, not retried: that would reread all the others at the next migration
        Timber.w(e, "libime's dictionary %s", file.name)
        Unread.Reason.UNKNOWN
    } catch (e: DataFormatException) {
        Timber.w(e, "libime's dictionary %s", file.name)
        Unread.Reason.DAMAGED
    } catch (e: OutOfMemoryError) {
        Timber.w(e, "libime's dictionary %s", file.name)
        Unread.Reason.TOO_LARGE
    }

    data class Unread(val fileName: String, val reason: Reason) {
        /** UNKNOWN: an older migration put it aside unnoted and it could not be read again. */
        enum class Reason { TOO_LARGE, DAMAGED, NO_WORDS, UNKNOWN }
    }

    // a failed note only means the settings do not tell of it
    private fun note(aside: File, unread: Unread) {
        try {
            writeUnread(aside, readUnread(aside) + unread)
        } catch (e: IOException) {
            Timber.w(e, "libime's dictionary %s: cannot note it was put aside unread", unread.fileName)
        }
    }

    fun unread(dir: File): List<Unread> = try {
        readUnread(File(dir, LIBIME))
    } catch (e: IOException) {
        Timber.w(e, "dictionaries put aside unread")
        emptyList()
    }

    /** One put aside since [told] was read is kept, to tell of. */
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

    const val LIBIME = ".libime"

    /** A dot file: no dictionary. */
    const val UNREAD = ".unread"

    private const val OLDER_NOTED = ".unread-older"

    /** An older build's list; [migrate] deletes it. */
    const val INTO_NEW = ".new-words"
}
