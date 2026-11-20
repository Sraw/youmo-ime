/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.phrase.Chaizi

/** The engine's `chaizi.data` from 汉语拆字字典 (kfcd/chaizi, CC BY 3.0), less ways with a part the dictionary cannot read. */
internal object ChaiziTable {

    /** Ways of more parts than this are no one's way to type a character. */
    const val MAX_PARTS = 4

    /** Each character of [lines] by the spellings of its ways, a part's readings from [reading]; not ways that are it or contain it. */
    fun spellings(lines: Sequence<String>, reading: (String) -> String): Map<String, Set<String>> {
        val chars = LinkedHashMap<String, MutableSet<String>>()
        for (line in lines) {
            val fields = line.split('\t')
            val c = fields[0].trim()
            // a character, not the □ the dictionary writes for one it cannot
            if (fields.size < 2 || c.codePointCount(0, c.length) != 1 || Character.UnicodeScript.of(c.codePointAt(0)) != Character.UnicodeScript.HAN) continue
            for (way in fields.drop(1)) spelt(c, way.split(' ').filter { it.isNotEmpty() }, reading)?.let { chars.getOrPut(c) { LinkedHashSet() } += it }
        }
        return chars
    }

    // a radical read as the character it stands for where the pinyin data's likeliest reading is another (艹 ao)
    private val RADICALS = mapOf("艹" to "草", "氵" to "水", "亻" to "人", "扌" to "手", "犭" to "犬", "刂" to "刀", "灬" to "火")

    // a way [parts] read, each by its likeliest reading (a rare one would spell it as no one says it); none if not a split or unread
    private fun spelt(c: String, parts: List<String>, reading: (String) -> String): String? {
        if (parts.size !in 2..MAX_PARTS || c in parts) return null
        val readings = parts.map { reading(RADICALS[it] ?: it).substringBefore('/') }
        return if (readings.any { it.isEmpty() }) null else readings.joinToString("")
    }

    /** [spellings] by spelling, the characters in [order] (the commonest first), [Chaizi.MAX] of each. */
    fun bySpelling(spellings: Map<String, Set<String>>, order: List<String>): Map<String, List<String>> {
        val rank = order.withIndex().associate { it.value to it.index }
        val found = HashMap<String, MutableList<String>>()
        for ((c, spelt) in spellings) for (s in spelt) found.getOrPut(s) { ArrayList() } += c
        return found.mapValues { (_, cs) -> cs.sortedBy { rank[it] ?: Int.MAX_VALUE }.take(Chaizi.MAX) }
    }
}
