/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.phrase.PhraseKeys
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmClock
import java.util.Calendar

/** The user's custom phrases among English candidates: under a typed word that is their key, never first. Only the list [place] made last knows its phrases. */
class EnglishPhrases(
    private val book: PhraseBook = PhraseBook(),
    private val clock: () -> Calendar = JvmClock::calendar,
) {
    private var shown: List<String>? = null
    private var placed: Map<Int, CustomPhrases.Phrase> = emptyMap()
    private var guessed = false

    /** [candidates] with phrases put in; where the nine keys find no word, the [guess] goes first so space commits what is shown first. */
    fun place(typed: CharSequence, nineKeys: NineKeyLetters?, candidates: List<String>, guess: () -> String): List<String> {
        shown = null
        placed = emptyMap()
        guessed = false
        if (typed.isEmpty()) return candidates
        val (custom, keys) = book.typed(if (nineKeys == null) typed.toString().lowercase() else typed, builtIns = false, nineKeys)
        if (keys.isEmpty()) return candidates
        val now = clock()
        val found = custom.offered(keys, now).associate { it.text to it.phrase }
        if (found.isEmpty()) return candidates
        guessed = nineKeys != null && candidates.isEmpty()
        val words = if (guessed) listOf(guess()) else candidates
        // a word with a phrase's text before it is the learned word; after it, the phrase
        val out = custom.place(keys, { now }, words.map { it to false }, text = { it.first }, all = { true }, from = 1) { it to true }
        placed = buildMap { out.forEachIndexed { i, (text, phrase) -> if (phrase) found[text]?.let { put(i, it) } } }
        return out.map { it.first }.also { shown = it }
    }

    fun at(candidates: List<String>, index: Int): CustomPhrases.Phrase? = if (candidates === shown) placed[index] else null

    fun noWord(candidates: List<String>): Boolean = candidates.isEmpty() || (candidates === shown && guessed)

    fun delete(candidates: List<String>, index: Int): Boolean {
        val phrase = at(candidates, index) ?: return false
        book.remove(phrase.key, phrase.value)
        return true
    }
}
