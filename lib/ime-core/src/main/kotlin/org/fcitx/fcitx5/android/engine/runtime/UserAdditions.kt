/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.phrase.CommonWordBook
import org.fcitx.fcitx5.android.engine.phrase.CommonWords
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.runtime.Engines.Additions
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import java.io.IOException

/**
 * What the user added to pinyin, as [read] gives it ([Additions]): their dictionaries merged in one
 * file, opened once ([dictionary]), the tables' indexes of it ([indexes]) and their custom phrases
 * ([phrases]). Read at first need and again after [drop]; a failure is told [onError] once, typing
 * going on without them. Split out of [UserLexicon], which makes its models over [dictionary] (S11).
 */
internal class UserAdditions(
    private val read: () -> Additions?,
    private val onError: (IOException) -> Unit,
) {
    private var additions: Additions? = null
    private var addedRead = false
    // read() threw as last read: which dictionaries the user has is not known
    private var addedFailed = false

    private fun added(): Additions? {
        if (addedRead) return additions
        addedRead = true
        addedFailed = false
        additions = try {
            read()
        } catch (e: IOException) {
            addedFailed = true
            onError(e)
            null
        }
        return additions
    }

    // the merged dictionaries of the additions last read, opened once for them: a failure told once,
    // typing going on without them, not tried again on every focus as 0.3.1's OOM was
    private var mergedOf: Additions? = null
    private var mergedFile: DictionaryFile? = null
    private var mergedFailed = false

    /** The user's dictionaries merged, opened: null for none, or none that opened. */
    fun dictionary(): DictionaryFile? {
        val now = added()
        if (now === mergedOf) return mergedFile
        mergedOf = now
        // failed until the open returns: an unchecked throw is not remembered as "no dictionaries" (F3403),
        // nor is the last additions' file kept under these
        mergedFailed = true
        mergedFile = null
        mergedFile = try {
            now?.dictionary?.invoke().also { mergedFailed = false }
        } catch (e: IOException) {
            onError(e)
            null
        }
        return mergedFile
    }

    /** Whether the words of the user's dictionaries are not known: they failed to read or open, or are being made (0.3.1's minute of upgrade). */
    fun unknown(): Boolean = mergedFailed || addedFailed || added()?.dictionaryPending?.invoke() == true

    /** Whether [dictionary] was compiled against another base dictionary than this one's (review S7). */
    fun stale(): Boolean = added()?.dictionaryStale == true

    /** Which dictionaries the user has, as read now: [Additions.dictionaries]. */
    fun dictionaries(): String = added()?.dictionaries.orEmpty()

    /** Where the tables keep their indexes of the merged dictionaries: [Additions.indexes]. */
    fun indexes(): UserFiles? = added()?.indexes

    private var phrases: PhraseBook? = null

    fun phrases(): PhraseBook = phrases ?: PhraseBook(CustomPhrases.parse(added()?.phrases.orEmpty()), CustomPhrases.BUILT_IN) {
        try {
            additions?.savePhrases?.invoke(it)
        } catch (e: IOException) {
            // refused (not read, or the data not settled): told, and thrown on so the book takes no
            // change, as a refused punctuation save does not
            onError(e)
            throw e
        }
    }.also { phrases = it }

    private var commonWords: CommonWordBook? = null

    /** The common words, read once as the phrases are; a line not read (no reading) left out. */
    fun commonWords(): CommonWordBook = commonWords ?: CommonWordBook(CommonWords.parse(added()?.commonWords.orEmpty()).words) {
        try {
            additions?.saveCommonWords?.invoke(it)
        } catch (e: IOException) {
            onError(e)
            throw e
        }
    }.also { commonWords = it }

    /** As a fresh install has them: the built-in phrases alone, the user's left out (U-93). */
    fun freshPhrases(): PhraseBook = PhraseBook(CustomPhrases.parse(""), CustomPhrases.BUILT_IN) {}

    /** Forgets what was read, read again at next need: which dictionaries there were, to tell them from [dictionaries] then. */
    fun drop(): String {
        val before = additions?.dictionaries.orEmpty()
        addedRead = false
        phrases = null
        commonWords = null
        return before
    }
}
