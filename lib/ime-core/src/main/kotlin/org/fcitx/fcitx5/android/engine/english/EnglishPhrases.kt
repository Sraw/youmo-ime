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

/**
 * The user's custom phrases among [EnglishSession]'s candidates (A1; not the built-in date and
 * time, whose keys are pinyin's): under a word typed that is their key, its letters in any case or
 * the nine keys' as any of theirs ([PhraseKeys]), where their order says but never first, as space
 * commits the word typed or its correction. Which candidates are phrases is known of the list
 * [place] made alone: any other the session shows (predictions, a correction tapped back) has none.
 */
class EnglishPhrases(
    private val book: PhraseBook = PhraseBook(),
    // the time a dynamic phrase is filled in with
    private val clock: () -> Calendar = JvmClock::calendar,
) {
    // the list [place] made last, the phrases in it by their place, and whether its first is a guess
    private var shown: List<String>? = null
    private var placed: Map<Int, CustomPhrases.Phrase> = emptyMap()
    private var guessed = false

    /**
     * [candidates] of [typed] with the phrases under it put in; [nineKeys] the keys' letters where
     * the input is the nine keys', null where it is letters. Where the nine keys find no word, the
     * [guess] space commits (each key's first letter) comes first, so what space commits is shown first.
     */
    fun place(typed: CharSequence, nineKeys: NineKeyLetters?, candidates: List<String>, guess: () -> String): List<String> {
        shown = null
        placed = emptyMap()
        guessed = false
        if (typed.isEmpty()) return candidates
        val (custom, keys) = book.typed(if (nineKeys == null) typed.toString().lowercase() else typed, builtIns = false, nineKeys)
        if (keys.isEmpty()) return candidates
        val now = clock()
        val found = custom.offered(keys, now).associate { it.text to it.phrase }
        // keys under which every phrase is off, or that only lead to one: no guess shown for none
        if (found.isEmpty()) return candidates
        guessed = nineKeys != null && candidates.isEmpty()
        val words = if (guessed) listOf(guess()) else candidates
        // a word of the same text before a phrase is that word, learned; one after it the phrase stands for
        val out = custom.place(keys, { now }, words.map { it to false }, text = { it.first }, all = { true }, from = 1) { it to true }
        placed = buildMap { out.forEachIndexed { i, (text, phrase) -> if (phrase) found[text]?.let { put(i, it) } } }
        return out.map { it.first }.also { shown = it }
    }

    /** The phrase at [index] of [candidates], if they are the list [place] made last and one is there. */
    fun at(candidates: List<String>, index: Int): CustomPhrases.Phrase? = if (candidates === shown) placed[index] else null

    /** Whether no candidate is a word: none at all, or phrases after the nine keys' guess alone. */
    fun noWord(candidates: List<String>): Boolean = candidates.isEmpty() || (candidates === shown && guessed)

    /** The phrase at [index] of [candidates] deleted from the phrases; false where none is there. */
    fun delete(candidates: List<String>, index: Int): Boolean {
        val phrase = at(candidates, index) ?: return false
        book.remove(phrase.key, phrase.value)
        return true
    }
}
