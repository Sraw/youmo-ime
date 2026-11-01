/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.punctuation

import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.host.PunctuationTable
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * The punctuation maps' files for the Kotlin host: the user's `punctuation/punc.mb.<lang>` under
 * fcitx's data ([dataDir]; none: the built-in tables only) replaces the built-in table ([openBuiltIn])
 * whole when it is there, as the punctuation addon has it; the editor saves the whole table, only if
 * [writable]. Parsing is [PunctuationTable]'s. A user's table that cannot be read is [onUnreadable]'s
 * to tell, and is never saved over this run: what the editor shows of it then is the built-in one.
 */
class PunctuationMaps(
    private val dataDir: File?,
    private val writable: Boolean = true,
    private val onUnreadable: (lang: String) -> Unit = {},
    private val openBuiltIn: (lang: String) -> InputStream?,
) {
    // the languages whose user table did not read this run
    private val unread = HashSet<String>()

    private fun file(lang: String) = dataDir?.let { File(it, "punctuation/punc.mb.$lang") }

    /** The table in use: the user's, else the built-in one, else none. */
    fun load(lang: String): List<PunctuationMapEntry> {
        val user = file(lang)?.takeIf { it.isFile } ?: return builtIn(lang)
        // a user's file that cannot be read leaves the keyboard the built-in marks, not none
        return try {
            entries(user.readBytes())
        } catch (e: IOException) {
            Timber.e(e, "%s: built-in punctuation used, the file kept as it is", user.name)
            synchronized(unread) { unread += lang }
            onUnreadable(lang)
            builtIn(lang)
        }
    }

    /** The built-in table, what 恢复默认 shows. */
    fun builtIn(lang: String): List<PunctuationMapEntry> =
        openBuiltIn(lang)?.use { entries(it.readBytes()) }.orEmpty()

    /** Saves [entries] as the user's table, whole, written to a `.new` file and renamed over it. */
    @Throws(IOException::class)
    fun save(lang: String, entries: List<PunctuationMapEntry>) {
        val target = saveTarget(lang)
        val table = PunctuationTable.accepted(entries.map { PunctuationTable.Entry(it.key, it.mapping, it.altMapping) })
        writeOver(target, PunctuationTable.write(table))
    }

    // where [lang]'s table is saved, or why it is not
    private fun saveTarget(lang: String): File = when {
        !writable -> throw Refused(R.string.user_data_not_settled, "where fcitx's data is, is not settled")
        synchronized(unread) { lang in unread } -> throw Refused(R.string.user_data_unreadable, "$lang's table was not read, kept as it is")
        else -> file(lang) ?: throw Refused(R.string.user_data_not_settled, "no data directory")
    }

    /**
     * A save refused: [message] the log's, in English; the user's ([getLocalizedMessage], what a toast
     * shows) [reason], read only then, so a JVM test needs no resources.
     */
    class Refused(@StringRes internal val reason: Int, detail: String) : IOException("punctuation: $detail") {
        // toString reads it too: a JVM test's failure, with no app, prints the English
        override fun getLocalizedMessage(): String = runCatching { appContext.getString(reason) }.getOrDefault(message.orEmpty())
    }

    private fun writeOver(target: File, bytes: ByteArray) {
        val next = File(target.path + ".new")
        target.parentFile?.mkdirs()
        // synced before the rename: a power cut must not leave an empty file in place of the built-in table
        try {
            FileOutputStream(next).use { it.write(bytes); it.fd.sync() }
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
