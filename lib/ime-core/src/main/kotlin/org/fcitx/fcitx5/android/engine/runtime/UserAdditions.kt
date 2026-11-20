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
import org.fcitx.fcitx5.android.engine.user.RareChoice
import java.io.IOException

/** What the user added to pinyin ([Additions]): their dictionaries merged in one file ([dictionary]), the tables' indexes ([indexes]) and custom phrases ([phrases]). Read at need and after [drop]; failures told [onError] once, typing going on without them. */
internal class UserAdditions(
    private val read: () -> Additions?,
    private val onError: (IOException) -> Unit,
) {
    private var additions: Additions? = null
    private var addedRead = false
    // read() threw last time: which dictionaries the user has is unknown
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

    // the merged dictionaries opened once per additions: a failure told once, not retried on every focus
    private var mergedOf: Additions? = null
    private var mergedFile: DictionaryFile? = null
    private var mergedFailed = false

    /** Null for none, or none that opened. */
    fun dictionary(): DictionaryFile? {
        val now = added()
        if (now === mergedOf) return mergedFile
        mergedOf = now
        // failed until the open returns: an unchecked throw is not remembered as "no dictionaries", nor the old file kept
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

    /** Whether the user's dictionary words are unknown: failed to read or open, or still being made. */
    fun unknown(): Boolean = mergedFailed || addedFailed || added()?.dictionaryPending?.invoke() == true

    /** Whether [dictionary] was compiled against another base dictionary. */
    fun stale(): Boolean = added()?.dictionaryStale == true

    fun dictionaries(): String = added()?.dictionaries.orEmpty()

    fun indexes(): UserFiles? = added()?.indexes

    private var phrases: PhraseBook? = null

    fun phrases(): PhraseBook = phrases ?: PhraseBook(CustomPhrases.parse(added()?.phrases.orEmpty()), CustomPhrases.BUILT_IN) {
        try {
            additions?.savePhrases?.invoke(it)
        } catch (e: IOException) {
            // refused (unread, or the data not settled): told, and rethrown so the book takes no change
            onError(e)
            throw e
        }
    }.also { phrases = it }

    private var commonWords: CommonWordBook? = null

    /** Read once, as the phrases are; unreadable lines left out. */
    fun commonWords(): CommonWordBook = commonWords ?: CommonWordBook(CommonWords.parse(added()?.commonWords.orEmpty()).words) {
        try {
            additions?.saveCommonWords?.invoke(it)
        } catch (e: IOException) {
            onError(e)
            throw e
        }
    }.also { commonWords = it }

    private var rareChoice: RareChoice? = null

    /** 生僻字屏蔽 as chosen ([RareChoice]), read once: the same object till [drop]. */
    fun rareChoice(): RareChoice = rareChoice ?: RareChoice.parse(added()?.rareChars.orEmpty()).also { rareChoice = it }

    /** As a fresh install has them: built-ins alone. */
    fun freshPhrases(): PhraseBook = PhraseBook(CustomPhrases.parse(""), CustomPhrases.BUILT_IN) {}

    /** Forgets what was read, reread at next need; returns the old [dictionaries] for comparison. */
    fun drop(): String {
        val before = additions?.dictionaries.orEmpty()
        addedRead = false
        phrases = null
        commonWords = null
        rareChoice = null
        return before
    }
}
