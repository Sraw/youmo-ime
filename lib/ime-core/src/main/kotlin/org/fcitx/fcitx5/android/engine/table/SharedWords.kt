/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import java.util.TreeMap

/**
 * What one user lexicon shares with a table: the words the user made, added or typed with the
 * other input methods, coded by the table's rules ([CodedWords]), and the words blocked, offered
 * by none of them. The phrases a table saved in an older build go the other way (TableRegistry).
 */
interface SharedWords {
    /**
     * The user's words under codes starting with [prefix], code and text; none the table has so.
     * But those [longer] gives, which come after these at their code length.
     */
    fun words(prefix: String): List<Pair<String, String>>

    /**
     * The words of the merged dictionaries under a code longer than [prefix], read as asked for:
     * by code length, then code ([byLength]), or by code; none [words] has. Some 80,000 a letter
     * of 五笔 for rime-frost: made whole at every key, they were 22 MB of garbage a key.
     */
    fun longer(prefix: String, byLength: Boolean): Sequence<Pair<String, String>> = emptySequence()

    /** Whether one of [words] has a code starting with [prefix]. */
    fun leadsAnywhere(prefix: String): Boolean

    /** Whether the user blocked [text], a word: no input method offers it. */
    fun blocked(text: String): Boolean

    /**
     * Whether [text] is a word a block of what may follow (联想) can reach: one pinyin can read, of
     * two characters or more, as a block reaches every reading of it: a single character blocked
     * would go from pinyin and strokes too.
     */
    fun blockable(text: String): Boolean

    /** Forgets what every input method learned of [text], a phrase a table saved or a word it shares. */
    fun forget(text: String)

    /** Whether [forget] has anything of [text] to forget: what an input method learned of it, or a word the user made. */
    fun learned(text: String): Boolean = false

    /**
     * Starts whatever [words] needs made in the background, as the table comes up rather than at
     * its first key: coded at the first key, the dictionaries' words came too late for the first
     * code typed, which committed the table's only candidate instead (原 UAT TBL-12a).
     */
    fun prepare() = Unit

    companion object {
        val NONE = object : SharedWords {
            override fun words(prefix: String) = emptyList<Pair<String, String>>()
            override fun leadsAnywhere(prefix: String) = false
            override fun blocked(text: String) = false
            override fun blockable(text: String) = false
            override fun forget(text: String) = Unit
        }
    }
}

/**
 * [texts], words of two characters or more, each under its [table] code; those it has so left out.
 * Unlike the words [TableText.addWords] builds in, under any code: a key after which the table's
 * codes led nowhere, and so committed (顶屏), may lead on to one.
 */
class CodedWords(private val table: TableDictionary, texts: Collection<String>, wanted: () -> Boolean = { true }) {
    private val byCode = TreeMap<String, MutableList<String>>()

    init {
        if (!table.rules.isEmpty) {
            // coded in the background for 300k+ texts: stop once superseded, the result is dropped
            val going = texts.asSequence().withIndex().takeWhile { (i, _) -> i % STOP_CHECK != 0 || wanted() }
            for ((_, text) in going) {
                val code = text.takeIf { it.codePointCount(0, it.length) >= 2 }?.let(table::encode)
                    ?.takeUnless { table.contains(it, text) } ?: continue
                val list = byCode.getOrPut(code) { ArrayList(1) }
                if (text !in list) list += text
            }
        }
    }

    fun words(prefix: String): List<Pair<String, String>> =
        range(prefix).flatMap { (code, list) -> list.map { code to it } }

    fun leadsAnywhere(prefix: String) = range(prefix).isNotEmpty()

    val size: Int get() = byCode.values.sumOf { it.size }

    internal companion object {
        const val STOP_CHECK = 4096
    }

    private fun range(prefix: String) = if (prefix.isEmpty()) emptyMap() else byCode.subMap(prefix, true, prefix + Char.MAX_VALUE, false)
}
