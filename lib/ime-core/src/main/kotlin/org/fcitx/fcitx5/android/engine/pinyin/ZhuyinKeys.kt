/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/**
 * The two zhuyin keyboards' keys as their sessions' input has them: 注音九键's eleven groups
 * ([NINE]) and 大千's one symbol a key ([DACHEN]), each a [NineKeyLetters] of zhuyin symbols the
 * [T9Segmenter] reads syllables off through [Spelling.ZHUYIN]. The input's characters are private
 * use, U+F880 on, the syllables' last block (Keyboard.SYLLABLES ends at U+F87F): a symbol's code
 * ([symbolCode], 大千's key and 九键's symbol typed as itself alike), a group's ([groupCode]) and a
 * tone's ([toneCode]); a symbol from the symbols page is text, never one of them. Only on a key's
 * way through the session: learned as the keys pressed under the zhuyin kinds, so never renumbered.
 */
object ZhuyinKeys {
    /** ㄅ to ㄩ, U+3105 to U+3129, in Unicode's (大千's columns') order: a symbol's index is its code's. */
    const val SYMBOLS = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦㄧㄨㄩ"

    /** 1 to 4 and 轻声 as 5, in the input after the symbols of their syllable. */
    const val TONES = 5

    /** How a tone shows: ˉ ˊ ˇ ˋ ˙ for 1 to 5. */
    const val MARKS = "ˉˊˇˋ˙"

    /**
     * 注音九键's groups, in the keys' order (design 051 Q1 A, the 3×4 of the open-source 注音九宫格):
     * every key with an initial, a medial or a final, told apart by place.
     */
    val GROUP_SYMBOLS = listOf("ㄅㄉㄚ", "ㄍㄐㄞ", "ㄓㄗㄢㄦ", "ㄆㄊㄛ", "ㄎㄑㄟ", "ㄔㄘㄣㄧ", "ㄇㄋㄜ", "ㄏㄒㄠㄡ", "ㄕㄙㄤㄨ", "ㄈㄌㄝ", "ㄖㄥㄩ")

    val GROUPS = GROUP_SYMBOLS.size

    private const val FIRST = ''
    private val FIRST_GROUP = FIRST + SYMBOLS.length
    private val FIRST_TONE = FIRST_GROUP + GROUPS

    /** Every character of the block, symbols, groups and tones (U+F880–F8B4). */
    val BLOCK = FIRST..(FIRST_TONE + TONES - 1)

    fun indexOf(symbol: Char): Int = SYMBOLS.indexOf(symbol).also { require(it >= 0) { "no zhuyin symbol $symbol" } }

    fun symbolCode(symbol: Char): Char = FIRST + indexOf(symbol)

    fun groupCode(group: Int): Char = FIRST_GROUP + group

    /** [tone], 1 to 5, as the input has it. */
    fun toneCode(tone: Int): Char = FIRST_TONE + tone - 1

    /** The tone [c] of the input is, 1 to 5; 0 for no tone's code. */
    fun toneOf(c: Char): Int = if (c in FIRST_TONE until FIRST_TONE + TONES) c - FIRST_TONE + 1 else 0

    /** The symbol a symbol's code [c] is, null for any other. */
    fun symbolOf(c: Char): Char? = if (c in FIRST until FIRST_GROUP) SYMBOLS[c - FIRST] else null

    /** 注音九键: [GROUP_SYMBOLS], their codes [groupCode]. */
    val NINE: NineKeyLetters = NineKeyLetters.zhuyin(GROUP_SYMBOLS, (0 until GROUPS).map { groupCode(it) }.joinToString(""))

    /** 大千: a symbol a key, its code [symbolCode]. */
    val DACHEN: NineKeyLetters = NineKeyLetters.zhuyin(SYMBOLS.map { it.toString() }, SYMBOLS.map { symbolCode(it) }.joinToString(""))

    /**
     * The input's character for 注音九键's code [n] (Keyboard.ZHUYIN_NINE less its first): a group,
     * then the tone key (bare, the syllable separator; then each tone), then a symbol typed as itself.
     */
    fun nineChar(n: Int): Char = when {
        n < GROUPS -> groupCode(n)
        n == GROUPS -> SyllableGraph.SEPARATOR
        n <= GROUPS + TONES -> toneCode(n - GROUPS)
        else -> FIRST + (n - GROUPS - 1 - TONES)
    }

    /** The input's character for 大千's code [n] (Keyboard.ZHUYIN_FULL less its first): a symbol, then a tone. */
    fun fullChar(n: Int): Char = if (n < SYMBOLS.length) FIRST + n else toneCode(n - SYMBOLS.length + 1)

    /**
     * The pinyin letters each symbol may stand for in a word's pinyin, a syllable's spelling aside:
     * how a custom phrase's key of letters (`nihao`, `rq`) is found from zhuyin typed. Loose, as a
     * key is a word's: ㄧ is i, y or yi; ㄨ u, w, wu or o (ㄨㄥ ong); ㄓ zh or zhi alone.
     */
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

    /**
     * 大千's keys by their place on a computer's keyboard, as a QWERTY key's name: what a layout,
     * the gestures' presets, names a key by. The number row's 3, 4, 6 and 7 are ˇ ˋ ˊ ˙.
     */
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
