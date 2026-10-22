/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.english.EnglishUser
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.pinyin.UserLatinWords
import org.fcitx.fcitx5.android.engine.user.UserModel

/**
 * U-31 §3 (EN-24): the English words English learned, its dictionary's aside ([englishHas]), typed
 * in 全拼's 中英混输 as the dictionary's Latin words are (youmo): listed in the user's model and
 * spelled by [words]. Made again as English's words change ([LearnState.englishVersion], not at
 * each pinyin commit: review-uat-fixes S9), before a key of 全拼's.
 */
internal class LatinSync(private val englishHas: (String) -> Boolean) {
    /** The words, in lower case, for the segmenter. */
    val words = UserLatinWords()

    // English's version and the typing day synced at: its words are those of its last 30 typing
    // days, which move with the day
    private var at: Pair<Long, Int>? = null
    private var state: LearnState? = null
    private var model: UserModel? = null

    // English's data asked only of a word typed, not added, and once a run
    private val lacks = HashMap<String, Boolean>()

    /** [lexicon] given [user]'s words (of the record's [state]; null with the habits off) while [on] (中英混输), else none. */
    fun sync(on: Boolean, user: EnglishUser, state: () -> LearnState?, lexicon: UserLexicon) {
        if (!on) {
            // none listed, as none is spelled
            if (model != null) lexicon.listLatin(emptySet())
            words.set(emptySet())
            model = null
            at = null
            return
        }
        val model = lexicon.user()
        val state = state()
        val at = (state?.englishVersion ?: -1L) to (state?.day ?: Int.MIN_VALUE)
        if (at == this.at && state === this.state && model === this.model) return
        this.at = at
        this.state = state
        this.model = model
        val listed = user.words.filter { w -> isLatin(w) && (w in user.addedWords || lacks.getOrPut(w) { !englishHas(w) }) }
            // one spelling of a word learned two ways (github, GitHub): the one added, else the one used more (review-uat-fixes N11)
            .groupBy { it.lowercase() }.values
            .mapTo(HashSet()) { same -> same.maxWith(compareBy({ it in user.addedWords }, { user.count(it) })) }
        lexicon.listLatin(listed)
        words.set(listed.mapTo(HashSet()) { it.lowercase() })
    }

    // a word of letters alone, two or more: what the segmenter spells
    private fun isLatin(word: String) = word.length >= 2 && word.all { it in 'a'..'z' || it in 'A'..'Z' }
}
