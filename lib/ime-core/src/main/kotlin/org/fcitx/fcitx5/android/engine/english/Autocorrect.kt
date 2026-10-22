/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.data.NgramModel

/**
 * Which word space puts in place of the one typed, for [EnglishSession]: a slip of no more than
 * [maxCorrection], or a [misspelling][Misspellings] in [misspellings]. Whether to correct at all
 * (a word never corrected, one with a hyphen) is the session's.
 */
internal class Autocorrect(private val maxCorrection: Float, private val misspellings: Map<String, String>) {

    /**
     * What the word [typed] was meant to be, of what was [found] for its [keys] after [context]:
     * - typed no word, the likeliest the keys spell written otherwise (dont as don't, london as
     *   London), or else the likeliest a slip of no more than [maxCorrection] from them (teh as
     *   the), from [EnglishSession.MIN_CORRECTED] keys; typed as a [name], a slip of no more than
     *   [EnglishSession.NAME_CORRECTION], of a word with a capital (Stebe as Steve; Anushika is someone's);
     * - typed a word (nad, oft, of the many the pages have), the same if [EnglishSession.KNOWN_MARGIN] likelier,
     *   but not a capital more or less (ladies, Ladies), nor a longer word (gear, years).
     * An apostrophe typed is never taken away. Null for none, or for keys that start a word likelier.
     * A [misspelling][Misspellings] is no word, whatever the pages have: the word meant.
     */
    fun of(data: EnglishData, typed: String, keys: String, found: List<EnglishDecoder.Suggestion>, context: Long, name: Boolean): String? {
        misspellings[typed.lowercase()]?.let { return it }
        val id = data.id(typed).takeIf { it != NgramModel.NO_WORD } ?: data.id(typed.lowercase())
        val own = if (id == NgramModel.NO_WORD) null else data.model.scoreAfter(context, id)
        val allowed = allowedFor(data, typed, id, known = own != null)
        val written = found.firstOrNull { !it.completes && EnglishData.key(data.word(it.word)) == keys && allowed(it) }
        val slip = slipOf(data, keys, found, name, allowed)
        val best = (if (own == null) written ?: slip else slip ?: written) ?: return null
        if (startsInstead(data, best, keys, known = own != null)) return null
        if (own != null && best.score - own < EnglishSession.KNOWN_MARGIN) return null
        return data.word(best.word)
    }

    // what may stand for [typed] (the word [id], if [known]): not itself, an apostrophe typed kept,
    // and a word not as typed but for a capital more or less
    private fun allowedFor(data: EnglishData, typed: String, id: Int, known: Boolean): (EnglishDecoder.Suggestion) -> Boolean {
        val apostrophe = '\'' in typed
        // what follows the last apostrophe typed: s, ll, t, or nothing (dogs')
        val after = typed.substringAfterLast('\'').lowercase()
        return { s ->
            val text = data.word(s.word)
            val kept = !apostrophe || '\'' in text && text.substringAfterLast('\'').lowercase() == after
            s.word != id && kept && !(known && text.equals(typed, ignoreCase = true))
        }
    }

    private fun slipOf(
        data: EnglishData,
        keys: String,
        found: List<EnglishDecoder.Suggestion>,
        name: Boolean,
        allowed: (EnglishDecoder.Suggestion) -> Boolean,
    ): EnglishDecoder.Suggestion? {
        if (keys.length < EnglishSession.MIN_CORRECTED) return null
        val most = if (name) EnglishSession.NAME_CORRECTION else maxCorrection
        return found.firstOrNull { it.cost <= most && allowed(it) && (!name || data.word(it.word)[0].isUpperCase()) }
    }

    // typed so far, not wrong: the start of a word likelier than a slip (hel for hello, not he);
    // a letter short with a slip as well is wrong all the same (softwae for software), but for a word
    private fun startsInstead(data: EnglishData, best: EnglishDecoder.Suggestion, keys: String, known: Boolean): Boolean {
        if (!best.completes || known) return best.completes
        return best.cost == 0f || EnglishData.key(data.word(best.word)).length > keys.length + 1
    }
}
