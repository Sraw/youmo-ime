/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.punctuation

import org.fcitx.fcitx5.android.host.PunctuationTable
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The punctuation maps' files for the Kotlin host: the user's `punctuation/punc.mb.<lang>` under
 * fcitx's data ([dataDir]; none: the built-in tables only) replaces the built-in table ([openBuiltIn])
 * whole when it is there, as the punctuation addon has it. Parsing is [PunctuationTable]'s. A user's table
 * that cannot be read is [onUnreadable]'s to tell. Since 0.6.1 no page edits it and nothing writes one (R26
 * F8109/F8279: the save path went with the page): one edited before is deleted once ([dropEdited]), one a backup
 * brings dropped by the restore, so the built-in table is everyone's.
 */
class PunctuationMaps(
    private val dataDir: File?,
    private val onUnreadable: (lang: String) -> Unit = {},
    private val openBuiltIn: (lang: String) -> InputStream?,
) {
    private fun file(lang: String) = dataDir?.let { File(it, "$DIR/$TABLE$lang") }

    /** The table in use: the user's, else the built-in one, else none. */
    fun load(lang: String): List<PunctuationMapEntry> {
        val user = file(lang)?.takeIf { it.isFile } ?: return builtIn(lang)
        // a user's file that cannot be read leaves the keyboard the built-in marks, not none
        return try {
            entries(user.readBytes())
        } catch (e: IOException) {
            Timber.e(e, "%s: built-in punctuation used, the file kept as it is", user.name)
            onUnreadable(lang)
            builtIn(lang)
        }
    }

    /** The built-in table, what 恢复默认 shows. */
    fun builtIn(lang: String): List<PunctuationMapEntry> =
        openBuiltIn(lang)?.use { entries(it.readBytes()) }.orEmpty()

    private fun entries(bytes: ByteArray) =
        PunctuationTable.parse(bytes).map { PunctuationMapEntry(it.key, it.mapping, it.altMapping) }

    companion object {
        /** The user's tables' directory under fcitx's data. */
        const val DIR = "punctuation"

        /**
         * The user's tables under [dataDir] deleted, every language's (0.6.1: no page edits them, the built-in
         * table everywhere): whether none is left. No directory, none; one that cannot be listed, not known.
         */
        fun dropEdited(dataDir: File): Boolean {
            val dir = File(dataDir, DIR)
            if (!dir.exists()) return true
            val tables = dir.listFiles { f -> f.isFile && f.name.startsWith(TABLE) } ?: return false
            return tables.map { it.delete() }.all { it }
        }

        // a table's name, its language after it (a save's `.new` too)
        private const val TABLE = "punc.mb."
    }
}
