/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.data.NgramModel

/** Which word space puts in place of the one typed: a slip within [maxCorrection] or a known misspelling. */
internal class Autocorrect(private val maxCorrection: Float, private val misspellings: Map<String, String>) {

    /**
     * What [typed] was meant to be: with no word typed, the likeliest respelling (dont as don't) else a slip within
     * [maxCorrection] (teh as the); a typed word only if [EnglishSession.KNOWN_MARGIN] likelier, never by case or a
     * longer word (gear, years). A typed apostrophe is never removed; null for keys likelier a word's start.
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

    // candidates for [typed]: not itself, its apostrophe kept, and a known word only differing in case is out
    private fun allowedFor(data: EnglishData, typed: String, id: Int, known: Boolean): (EnglishDecoder.Suggestion) -> Boolean {
        val apostrophe = '\'' in typed
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

    // typed so far, not wrong: a likelier word start beats a slip (hel for hello); a letter short with a slip
    // too is still wrong (softwae)
    private fun startsInstead(data: EnglishData, best: EnglishDecoder.Suggestion, keys: String, known: Boolean): Boolean {
        if (!best.completes || known) return best.completes
        return best.cost == 0f || EnglishData.key(data.word(best.word)).length > keys.length + 1
    }
}
