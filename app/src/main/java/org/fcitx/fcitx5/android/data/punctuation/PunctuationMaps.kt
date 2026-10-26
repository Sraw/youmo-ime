/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.punctuation

import org.fcitx.fcitx5.android.host.PunctuationTable
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * The punctuation maps' files for the Kotlin host: the user's `punctuation/punc.mb.<lang>` under
 * fcitx's data home ([dataDir]) replaces the built-in table ([openBuiltIn]) whole when it is there, as
 * the punctuation addon has it; the editor saves the whole table. Parsing is [PunctuationTable]'s.
 */
class PunctuationMaps(private val dataDir: File, private val openBuiltIn: (lang: String) -> InputStream?) {

    private fun file(lang: String) = File(dataDir, "punctuation/punc.mb.$lang")

    /** The table in use: the user's, else the built-in one, else none. */
    fun load(lang: String): List<PunctuationMapEntry> {
        val user = file(lang)
        if (!user.isFile) return builtIn(lang)
        // a user's file that cannot be read leaves the keyboard the built-in marks, not none
        return try {
            entries(user.readBytes())
        } catch (e: IOException) {
            Timber.w(e, "%s: built-in punctuation used", user.name)
            builtIn(lang)
        }
    }

    /** The built-in table, what 恢复默认 shows. */
    fun builtIn(lang: String): List<PunctuationMapEntry> =
        openBuiltIn(lang)?.use { entries(it.readBytes()) }.orEmpty()

    /** Saves [entries] as the user's table, whole, written to a `.new` file and renamed over it. */
    @Throws(IOException::class)
    fun save(lang: String, entries: List<PunctuationMapEntry>) {
        val table = PunctuationTable.accepted(entries.map { PunctuationTable.Entry(it.key, it.mapping, it.altMapping) })
        val target = file(lang)
        val next = File(target.path + ".new")
        target.parentFile?.mkdirs()
        // synced before the rename: a power cut must not leave an empty file in place of the built-in table
        try {
            FileOutputStream(next).use { it.write(PunctuationTable.write(table)); it.fd.sync() }
        } catch (e: IOException) {
            next.delete()
            throw e
        }
        if (!next.renameTo(target)) {
            next.delete()
            throw IOException("rename to $target")
        }
    }

    private fun entries(bytes: ByteArray) =
        PunctuationTable.parse(bytes).map { PunctuationMapEntry(it.key, it.mapping, it.altMapping) }
}
