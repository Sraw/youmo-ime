/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import java.util.TreeMap

/** What one user lexicon shares with a table: the user's words coded by its rules ([CodedWords]), and the blocked words. */
interface SharedWords {
    /** The user's words under codes starting with [prefix]; none the table has so, nor those [longer] gives. */
    fun words(prefix: String): List<Pair<String, String>>

    /** The merged dictionaries' words under codes longer than [prefix], lazily (~80,000 for a 五笔 letter). */
    fun longer(prefix: String, byLength: Boolean): Sequence<Pair<String, String>> = emptySequence()

    fun leadsAnywhere(prefix: String): Boolean

    fun blocked(text: String): Boolean

    /** Whether a 联想 block may reach [text]: a pinyin-readable word of two or more, as a block covers every reading and would drop a single character from pinyin and strokes too. */
    fun blockable(text: String): Boolean

    fun forget(text: String)

    /** Starts what [words] needs in the background as the table comes up: made at the first key, it came too late for the first code. */
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

/** [texts] of two characters or more under their [table] code; unlike built-in words, a key after which the table's codes led nowhere (顶屏) may lead on to one. */
class CodedWords(private val table: TableDictionary, texts: Collection<String>, wanted: () -> Boolean = { true }) {
    private val byCode = TreeMap<String, MutableList<String>>()

    init {
        if (!table.rules.isEmpty) {
            // 300k+ texts coded in the background: stop once superseded
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
