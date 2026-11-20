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
 * The user's `punctuation/punc.mb.<lang>` replaces the built-in table whole, as the punctuation addon has it. No page
 * edits it now: one edited earlier is deleted once ([dropEdited]) and a restore drops a backup's.
 */
class PunctuationMaps(
    private val dataDir: File?,
    private val onUnreadable: (lang: String) -> Unit = {},
    private val openBuiltIn: (lang: String) -> InputStream?,
) {
    private fun file(lang: String) = dataDir?.let { File(it, "$DIR/$TABLE$lang") }

    /** The user's, else the built-in one, else none. */
    fun load(lang: String): List<PunctuationMapEntry> {
        val user = file(lang)?.takeIf { it.isFile } ?: return builtIn(lang)
        // an unreadable user file leaves the built-in marks, not none
        return try {
            entries(user.readBytes())
        } catch (e: IOException) {
            Timber.e(e, "%s: built-in punctuation used, the file kept as it is", user.name)
            onUnreadable(lang)
            builtIn(lang)
        }
    }

    /** What 恢复默认 shows. */
    fun builtIn(lang: String): List<PunctuationMapEntry> =
        openBuiltIn(lang)?.use { entries(it.readBytes()) }.orEmpty()

    private fun entries(bytes: ByteArray) =
        PunctuationTable.parse(bytes).map { PunctuationMapEntry(it.key, it.mapping, it.altMapping) }

    companion object {
        const val DIR = "punctuation"

        /** Whether none is left; unknown when the directory cannot be listed. */
        fun dropEdited(dataDir: File): Boolean {
            val dir = File(dataDir, DIR)
            if (!dir.exists()) return true
            val tables = dir.listFiles { f -> f.isFile && f.name.startsWith(TABLE) } ?: return false
            return tables.map { it.delete() }.all { it }
        }

        // its language after it (a save's `.new` too)
        private const val TABLE = "punc.mb."
    }
}
