/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

/** What tells a phrase or a fragment from a word among `words`' candidates: a function character at an end, 的, or most of its count inside a longer one. */
internal class Phrases(private val known: (String, Int, Int) -> Boolean) {
    // the most any one candidate a character longer counts, by the candidate it contains
    private val longest = HashMap<String, Int>()

    // how often a word the dictionary has runs into this run from the character before (洛阳 into 阳市), and out of it
    private val crossedIn = HashMap<String, Int>()
    private val crossedOut = HashMap<String, Int>()

    fun saw(text: String, count: Int) {
        if (text.length < 3) return
        for (part in listOf(text.dropLast(1), text.drop(1))) longest.merge(part, count, ::maxOf)
        if ((2..text.length).any { end -> known(text, 0, end) }) crossedIn.merge(text.drop(1), count, Int::plus)
        if ((0..text.length - 2).any { start -> known(text, start, text.length) }) crossedOut.merge(text.dropLast(1), count, Int::plus)
    }

    fun isPhrase(text: String, count: Int): Boolean {
        if (INSIDE.any { it in text }) return true
        if (text.first() in STOP || endsAsOne(text)) return true
        val crossed = maxOf(crossedIn[text] ?: 0, crossedOut[text] ?: 0)
        return maxOf(longest[text] ?: 0, crossed) > count * FRAGMENT_SHARE
    }

    private fun endsAsOne(text: String): Boolean {
        val last = text.last()
        if (last !in STOP || last in LAST_IS_FINE) return false
        return !(text.length == 2 && last in VERB_END)
    }

    private companion object {
        const val STOP = "的了是在就都也不和与着过得地吗呢吧啊呀哦嘛我你他她它们这那个把被将让给对从向为以之其此些很太更最又再还才并或而但却如若因所比跟没有要会能可"
        const val VERB_END = "了过"
        // function characters at the start of a phrase that end words too: 碳中和, 保有
        const val LAST_IS_FINE = "和有"
        const val INSIDE = "的"
        const val FRAGMENT_SHARE = 0.5
    }
}
