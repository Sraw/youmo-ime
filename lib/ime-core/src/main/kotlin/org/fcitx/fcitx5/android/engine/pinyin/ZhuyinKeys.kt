/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/**
 * The zhuyin keyboards' keys: 注音九键's eleven groups ([NINE]) and 大千's one symbol per key ([DACHEN]),
 * read by [T9Segmenter] via [KeySpelling.Bopomofo]. Codes are private use from U+F880 (symbols, groups,
 * tones); learned as keys under the zhuyin kinds, so never renumber them.
 */
object ZhuyinKeys {
    /** ㄅ to ㄩ (U+3105–U+3129) in Unicode's order: a symbol's index is its code's. */
    const val SYMBOLS = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦㄧㄨㄩ"

    /** 1 to 4 and 轻声 as 5, after their syllable's symbols. */
    const val TONES = 5

    /** ˉ ˊ ˇ ˋ ˙ for 1 to 5. */
    const val MARKS = "ˉˊˇˋ˙"

    /** 注音九键's groups in key order (the open-source 注音九宫格's 3×4). */
    val GROUP_SYMBOLS = listOf("ㄅㄉㄚ", "ㄍㄐㄞ", "ㄓㄗㄢㄦ", "ㄆㄊㄛ", "ㄎㄑㄟ", "ㄔㄘㄣㄧ", "ㄇㄋㄜ", "ㄏㄒㄠㄡ", "ㄕㄙㄤㄨ", "ㄈㄌㄝ", "ㄖㄥㄩ")

    val GROUPS = GROUP_SYMBOLS.size

    private const val FIRST = '\uF880'
    private val FIRST_GROUP = FIRST + SYMBOLS.length
    private val FIRST_TONE = FIRST_GROUP + GROUPS

    /** Symbols, groups and tones (U+F880–F8B4). */
    val BLOCK = FIRST..(FIRST_TONE + TONES - 1)

    fun indexOf(symbol: Char): Int = SYMBOLS.indexOf(symbol).also { require(it >= 0) { "no zhuyin symbol $symbol" } }

    fun symbolCode(symbol: Char): Char = FIRST + indexOf(symbol)

    fun groupCode(group: Int): Char = FIRST_GROUP + group

    fun toneCode(tone: Int): Char = FIRST_TONE + tone - 1

    /** 0 for no tone code. */
    fun toneOf(c: Char): Int = if (c in FIRST_TONE until FIRST_TONE + TONES) c - FIRST_TONE + 1 else 0

    /** Null for any other code. */
    fun symbolOf(c: Char): Char? = if (c in FIRST until FIRST_GROUP) SYMBOLS[c - FIRST] else null

    /** Learned [keys] as the user reads them: a group as its symbols (spaced apart where [grouped]), a symbol as itself, a tone as its mark, anything else as stored. */
    fun shown(keys: String, grouped: Boolean): String = keys.map { c ->
        val tone = toneOf(c)
        when {
            tone > 0 -> MARKS[tone - 1].toString()
            c in FIRST_GROUP until FIRST_TONE -> GROUP_SYMBOLS[c - FIRST_GROUP]
            else -> symbolOf(c)?.toString() ?: c.toString()
        }
    }.joinToString(if (grouped) " " else "")

    val NINE: NineKeyLetters = NineKeyLetters.zhuyin(GROUP_SYMBOLS, (0 until GROUPS).map { groupCode(it) }.joinToString(""))

    val DACHEN: NineKeyLetters = NineKeyLetters.zhuyin(SYMBOLS.map { it.toString() }, SYMBOLS.map { symbolCode(it) }.joinToString(""))

    /** 注音九键's code [n] (Keyboard.ZHUYIN_NINE less its first) as an input character: groups, then the tone key (bare separator, then tones), then symbols. */
    fun nineChar(n: Int): Char = when {
        n < GROUPS -> groupCode(n)
        n == GROUPS -> SyllableGraph.SEPARATOR
        n <= GROUPS + TONES -> toneCode(n - GROUPS)
        else -> FIRST + (n - GROUPS - 1 - TONES)
    }

    /** 大千's code [n] (Keyboard.ZHUYIN_FULL less its first): a symbol, then a tone. */
    fun fullChar(n: Int): Char = if (n < SYMBOLS.length) FIRST + n else toneCode(n - SYMBOLS.length + 1)

    /** Pinyin letters each symbol may stand for in a phrase key, loosely: ㄧ i, y or yi; ㄨ u, w, wu or o; ㄓ zh or zhi. */
    val PINYIN_WAYS: Map<Char, List<String>> = mapOf(
        'ㄅ' to listOf("b"), 'ㄆ' to listOf("p"), 'ㄇ' to listOf("m"), 'ㄈ' to listOf("f"),
        'ㄉ' to listOf("d"), 'ㄊ' to listOf("t"), 'ㄋ' to listOf("n"), 'ㄌ' to listOf("l"),
        'ㄍ' to listOf("g"), 'ㄎ' to listOf("k"), 'ㄏ' to listOf("h"),
        'ㄐ' to listOf("j"), 'ㄑ' to listOf("q"), 'ㄒ' to listOf("x"),
        'ㄓ' to listOf("zh", "zhi"), 'ㄔ' to listOf("ch", "chi"), 'ㄕ' to listOf("sh", "shi"), 'ㄖ' to listOf("r", "ri"),
        'ㄗ' to listOf("z", "zi"), 'ㄘ' to listOf("c", "ci"), 'ㄙ' to listOf("s", "si"),
        'ㄚ' to listOf("a"), 'ㄛ' to listOf("o"), 'ㄜ' to listOf("e"), 'ㄝ' to listOf("e"),
        'ㄞ' to listOf("ai"), 'ㄟ' to listOf("ei", "i"), 'ㄠ' to listOf("ao"), 'ㄡ' to listOf("ou", "u"),
        'ㄢ' to listOf("an"), 'ㄣ' to listOf("en", "n"), 'ㄤ' to listOf("ang"), 'ㄥ' to listOf("eng", "ng"),
        'ㄦ' to listOf("er", "r"),
        'ㄧ' to listOf("i", "y", "yi"), 'ㄨ' to listOf("u", "w", "wu", "o"), 'ㄩ' to listOf("v", "u", "yu"),
    )

    /** 大千's keys by their QWERTY key name, as layouts and gesture presets name them; number-row 3, 4, 6, 7 are ˇ ˋ ˊ ˙. */
    val DACHEN_QWERTY: Map<Char, Char> = mapOf(
        '1' to 'ㄅ', 'q' to 'ㄆ', 'a' to 'ㄇ', 'z' to 'ㄈ',
        '2' to 'ㄉ', 'w' to 'ㄊ', 's' to 'ㄋ', 'x' to 'ㄌ',
        '3' to 'ˇ', 'e' to 'ㄍ', 'd' to 'ㄎ', 'c' to 'ㄏ',
        '4' to 'ˋ', 'r' to 'ㄐ', 'f' to 'ㄑ', 'v' to 'ㄒ',
        '5' to 'ㄓ', 't' to 'ㄔ', 'g' to 'ㄕ', 'b' to 'ㄖ',
        '6' to 'ˊ', 'y' to 'ㄗ', 'h' to 'ㄘ', 'n' to 'ㄙ',
        '7' to '˙', 'u' to 'ㄧ', 'j' to 'ㄨ', 'm' to 'ㄩ',
        '8' to 'ㄚ', 'i' to 'ㄛ', 'k' to 'ㄜ', ',' to 'ㄝ',
        '9' to 'ㄞ', 'o' to 'ㄟ', 'l' to 'ㄠ', '.' to 'ㄡ',
        '0' to 'ㄢ', 'p' to 'ㄣ', ';' to 'ㄤ', '/' to 'ㄥ',
        '-' to 'ㄦ',
    )
}
