/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.data.pinyin.dict.SourceNames
import timber.log.Timber
import java.io.File

/** One line `name\tlength\tmodified`; a name may hold a tab, so the last two fields are the numbers. */
internal data class Signature(val fileName: String, val length: Long, val modified: Long) {

    val line get() = "$fileName\t$length\t$modified"

    companion object {
        fun of(f: File) = Signature(f.name, f.length(), f.lastModified())

        fun lineOf(f: File) = of(f).line

        fun fileNameOf(line: String) = line.substringBeforeLast('\t').substringBeforeLast('\t')
    }
}

/**
 * Kept in memory and in [fileName] so a start does not retry them. File and set share one lock, so a compile's [note]
 * never writes back what a [forget] removed.
 */
internal class Notes(val fileName: String, private val key: (File) -> String = { Signature.lineOf(it) }) {

    private val signatures = HashSet<String>()

    /** In this process and in the file. */
    fun notedIn(dir: File): Set<String> = synchronized(this) {
        signatures.toSet() + runCatching { File(dir, fileName).readLines() }.getOrDefault(emptyList())
    }

    /** With those before whose files are unchanged. */
    fun note(dir: File, noted: Collection<String>) {
        if (noted.isEmpty()) return
        synchronized(this) {
            signatures += noted
            val there = dir.listFiles().orEmpty().mapTo(HashSet(), key)
            runCatching { File(dir, fileName).writeText(notedIn(dir).filter { it in there }.joinToString("") { "$it\n" }) }
                .onFailure { Timber.w(it, "%s", fileName) }
        }
    }

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

    /** In memory and in the file. */
    fun replace(dir: File, kept: Collection<String>) {
        synchronized(this) {
            signatures.clear()
            File(dir, fileName).delete()
            note(dir, kept)
        }
    }

    /** Of those noted whose files are unchanged. */
    fun namesIn(dir: File): Set<String> {
        val there = dir.listFiles().orEmpty().mapTo(HashSet()) { Signature.lineOf(it) }
        return notedIn(dir).filter { it in there }.mapTo(HashSet()) { SourceNames.nameOf(Signature.fileNameOf(it)) }
    }

    /** As at the process's start. */
    fun clear() = synchronized(this) { signatures.clear() }
}
