/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.table.SavedPhrases
import org.fcitx.fcitx5.android.engine.table.TableDictionary
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.fcitx.fcitx5.android.engine.table.TableWordOffers

/**
 * How often a table offers a word to remember when every character of a sentence is typed alone: after each
 * character (a pause after every one, the most it is seen), after a sentence's last (a pause at its end only, the
 * least), and how many [names][run] typed alone are offered whole. Nothing saved, nothing blocked, no time passing.
 */
class TableOfferRun(
    private val table: TableDictionary,
    private val options: TableOptions,
    private val boundaries: ((String) -> IntArray)?,
) {
    data class Result(
        /** Han characters after a sentence's first: where an offer may come. */
        val positions: Int,
        val offeredAt: Int,
        val sentences: Int,
        val offeredAtEnd: Int,
        val words: Int,
        val names: Int,
        val namesOffered: Int,
    )

    private fun offers() = TableWordOffers(table, options, SavedPhrases(table, "", { null }), { _, _ -> false }, boundaries)

    fun run(sentences: List<String>, names: List<String>): Result {
        var positions = 0
        var offeredAt = 0
        var atEnd = 0
        var words = 0
        for (sentence in sentences) {
            val o = offers()
            var han = 0
            var last: Any? = null
            sentence.codePoints().forEach { cp ->
                val ch = String(Character.toChars(cp))
                o.committed(ch)
                if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) return@forEach
                val offer = o.offer()
                if (han++ > 0) positions++
                if (offer != null) {
                    offeredAt++
                    words += offer.words.size
                }
                last = offer
            }
            if (last != null) atEnd++
        }
        val namesOffered = names.count { name ->
            val o = offers()
            name.codePoints().forEach { o.committed(String(Character.toChars(it))) }
            o.offer()?.words?.any { it.text == name } == true
        }
        return Result(positions, offeredAt, sentences.size, atEnd, words, names.size, namesOffered)
    }
}
