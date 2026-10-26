/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.data.pinyin.dict.SourceNames
import timber.log.Timber
import java.io.File

/**
 * A dictionary's file as the notes keep it: its name, length and time, one line `name\tlength\tmodified`,
 * the one place that format is written and read. A name may hold a tab: the last two fields are the numbers.
 */
internal data class Signature(val fileName: String, val length: Long, val modified: Long) {

    val line get() = "$fileName\t$length\t$modified"

    companion object {
        fun of(f: File) = Signature(f.name, f.length(), f.lastModified())

        fun lineOf(f: File) = of(f).line

        /** The file name of a [line]. */
        fun fileNameOf(line: String) = line.substringBeforeLast('\t').substringBeforeLast('\t')
    }
}

/**
 * Signatures of [MergedDictionary]'s dictionaries noted for a reason, kept in memory and in [fileName]
 * beside them, so a start does not try them again. [key] is how a file there is noted ([Signature] by default).
 * The file's read, change and write are under the same lock as the set: a [forget] (the page's retry, on
 * its thread) is not written back by a [note] of the compile's that read the file before it (F4101).
 */
internal class Notes(val fileName: String, private val key: (File) -> String = { Signature.lineOf(it) }) {

    private val signatures = HashSet<String>()

    /** Those noted, in this process and in the file. */
    fun notedIn(dir: File): Set<String> = synchronized(this) {
        signatures.toSet() + runCatching { File(dir, fileName).readLines() }.getOrDefault(emptyList())
    }

    /** [noted] kept, with those before whose files are still as they were. */
    fun note(dir: File, noted: Collection<String>) {
        if (noted.isEmpty()) return
        synchronized(this) {
            signatures += noted
            val there = dir.listFiles().orEmpty().mapTo(HashSet(), key)
            runCatching { File(dir, fileName).writeText(notedIn(dir).filter { it in there }.joinToString("") { "$it\n" }) }
                .onFailure { Timber.w(it, "%s", fileName) }
        }
    }

    /** [noted] no longer kept. */
    fun forget(dir: File, noted: Collection<String>) {
        if (noted.isEmpty()) return
        val gone = noted.toSet()
        synchronized(this) {
            signatures -= gone
            val file = File(dir, fileName)
            val kept = runCatching { file.readLines() }.getOrDefault(emptyList()) - gone
            runCatching { if (kept.isEmpty()) file.delete() else file.writeText(kept.joinToString("") { "$it\n" }) }
                .onFailure { Timber.w(it, "%s", fileName) }
        }
    }

    /** Only [kept] noted, in memory and in the file. */
    fun replace(dir: File, kept: Collection<String>) {
        synchronized(this) {
            signatures.clear()
            File(dir, fileName).delete()
            note(dir, kept)
        }
    }

    /** The dictionaries' names of those noted whose files are still as they were. */
    fun namesIn(dir: File): Set<String> {
        val there = dir.listFiles().orEmpty().mapTo(HashSet()) { Signature.lineOf(it) }
        return notedIn(dir).filter { it in there }.mapTo(HashSet()) { SourceNames.nameOf(Signature.fileNameOf(it)) }
    }

    /** None noted in memory: as at the process's start. */
    fun clear() = synchronized(this) { signatures.clear() }
}
