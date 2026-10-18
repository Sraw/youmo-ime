/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.phrase.Chaizi

/**
 * The engine's `chaizi.data` ([Chaizi]) from the open 汉语拆字字典 (kfcd/chaizi, CC BY 3.0): its
 * `chaizi-jt.txt` has a character a line, then its ways to split, a tab before each, the parts
 * apart by spaces (`湘<TAB>水 相<TAB>氵 相`). A side radical is mostly written as the character it
 * stands for (水 for 氵, 人 for 亻), which is what a user types it by: a way with a part the
 * dictionary cannot read (氵, 丷) is left out.
 */
internal object ChaiziTable {

    /** Ways of more parts than this are no one's way to type a character. */
    const val MAX_PARTS = 4

    /**
     * Each character of [lines] (chaizi-jt.txt's) by the spellings of its ways, a part's readings
     * from [reading] (`chang/zhang`, the likeliest first, empty for none); not a way that is the
     * character alone or has it as a part.
     */
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

    // a radical read as the character it stands for, where the pinyin data's likeliest reading is
    // another (dict_sc.txt: 艹 ao, 氵 san, 亻 dan, 扌 ti, 犭 fan, 刂 li, 灬 biao), which spelt 芭 aoba
    // beside 草巴's caoba; 忄 xin, 讠 yan, 钅 jin, 纟 si and the like read as their characters already
    private val RADICALS = mapOf("艹" to "草", "氵" to "水", "亻" to "人", "扌" to "手", "犭" to "犬", "刂" to "刀", "灬" to "火")

    // a way [parts] of [c] read, each part by its likeliest reading: a rare one (广 as ān) would
    // spell the character as no one says it (anmu for 床); none for a way that is no split of it,
    // or has a part not read
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
