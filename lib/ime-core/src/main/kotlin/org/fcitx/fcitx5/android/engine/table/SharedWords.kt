/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import java.util.TreeMap

/**
 * What one user lexicon shares with a table: the words the user made, added or typed with the
 * other input methods, coded by the table's rules ([CodedWords]), and the words blocked, offered
 * by none of them. A word the table saves goes the other way: see [TableUser.onSaved].
 */
interface SharedWords {
    /** The user's words under codes starting with [prefix], code and text; none the table has so. */
    fun words(prefix: String): List<Pair<String, String>>

    /** Whether one of [words] has a code starting with [prefix]. */
    fun leadsAnywhere(prefix: String): Boolean

    /** Whether the user blocked [text], a word: no input method offers it. */
    fun blocked(text: String): Boolean

    /** Whether [text] is a word [block] can block: one pinyin can read. */
    fun blockable(text: String): Boolean

    /** Blocks [text] for every input method, however read; whether it could be. */
    fun block(text: String): Boolean

    /** Forgets what every input method learned of [text], a phrase a table saved or a word it shares. */
    fun forget(text: String)

    companion object {
        val NONE = object : SharedWords {
            override fun words(prefix: String) = emptyList<Pair<String, String>>()
            override fun leadsAnywhere(prefix: String) = false
            override fun blocked(text: String) = false
            override fun blockable(text: String) = false
            override fun block(text: String) = false
            override fun forget(text: String) = Unit
        }
    }
}

/** [texts], words of two characters or more, each under its [table] code; those it has so left out. */
class CodedWords(private val table: TableDictionary, texts: Collection<String>) {
    private val byCode = TreeMap<String, MutableList<String>>()

    init {
        if (!table.rules.isEmpty) {
            for (text in texts) {
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

    private fun range(prefix: String) = if (prefix.isEmpty()) emptyMap() else byCode.subMap(prefix, true, prefix + Char.MAX_VALUE, false)
}
