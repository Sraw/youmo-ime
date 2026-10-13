/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.Candidate
import org.fcitx.fcitx5.android.engine.lattice.CoarseWords
import org.fcitx.fcitx5.android.engine.user.UserModel

/**
 * Where a list of [data]'s candidates puts those with a word of [coarse] the [user] never typed:
 * past its first page (see [CoarseWords]). [PinyinSession] lists by it, and so does ime-eval, whose
 * ranks are to be those of the list the app shows: a copy of the rule there drifted from this one.
 */
class CoarseCandidates(private val coarse: CoarseWords, private val data: PinyinData, private val user: UserModel?) {

    /**
     * The candidates of [listed] with a coarse word the user never typed that a first page of
     * [pageSize] would show: they wait past it, those further down keep their place, so what more
     * words read goes after all that was listed.
     */
    fun held(listed: List<Candidate>, pageSize: Int): List<Candidate> {
        val typedOnce = HashMap<String, Boolean>()
        val held = ArrayList<Candidate>()
        var shown = 0
        for (c in listed) {
            if (shown == pageSize) break
            if (isCoarse(c, typedOnce)) held += c else shown++
        }
        return held
    }

    /** [listed] as pages of [pageSize] show it: those [held] right after the first page of the others. */
    fun order(listed: List<Candidate>, pageSize: Int, held: List<Candidate> = this.held(listed, pageSize)): List<Candidate> {
        if (held.isEmpty()) return listed
        val kept = listed.filter { it !in held }
        return kept.take(pageSize) + held + kept.drop(pageSize)
    }

    // one of its words coarse, or words in turn spelling one the user never typed: 射 then 精 is
    // 射精 as well, and once typed it is read as 射, 精 still
    private fun isCoarse(c: Candidate, typedOnce: MutableMap<String, Boolean>): Boolean {
        val words = c.words
        for (i in words.indices) {
            if (coarse.unasked(words[i], user, data.vocabulary.size)) return true
            // a run of one word is that word, asked above
            if (i == words.size - 1 || words[i] == NO_WORD) continue
            val run = StringBuilder(text(words[i]))
            for (j in i + 1 until words.size) {
                if (words[j] == NO_WORD || run.length >= coarse.longest) break
                run.append(text(words[j]))
                val text = run.toString()
                if (coarse.has(text) && !typedOnce.getOrPut(text) { typed(text) }) return true
            }
        }
        return false
    }

    private fun text(word: Int) = if (word < data.vocabulary.size || user == null) data.vocabulary.word(word) else user.text(word)

    // a word of the vocabulary or of the user's own
    private fun typed(text: String): Boolean {
        val user = user ?: return false
        val word = data.wordIndex.find(text)
        return word >= 0 && user.count(word) > 0f || user.typed(text)
    }
}
