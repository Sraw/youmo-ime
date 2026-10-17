/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

/**
 * Characters by the pinyin of their parts (拆字), as the app has them (`engine/chaizi.data`,
 * ime-dict-tool's `chaizi`, from the open 汉语拆字字典 kfcd/chaizi, CC BY 3.0): `niuniuniu` 犇,
 * `shuishuishui` 淼, `koukou` 吅. Full pinyin offers them first after [KEY], as 搜狗's U mode: a
 * character the user cannot read, looked up as one is by its strokes.
 *
 * The file is text, a line each: `<letters><TAB><character>...`, the letters the parts' pinyin run
 * together, the characters apart by spaces, the commonest first.
 */
class Chaizi private constructor(private val chars: Map<String, List<String>>) {

    /** The characters whose parts [letters] spells in full, the commonest first; none for most. */
    fun of(letters: String): List<String> = chars[letters].orEmpty()

    /** How many spellings have characters. */
    val size get() = chars.size

    companion object {
        val EMPTY = Chaizi(emptyMap())

        /** What a lookup starts with: no pinyin does. */
        const val KEY = 'u'

        /** At most this many characters are kept of a spelling. */
        const val MAX = 8

        /** What [text] has, as [format] writes it. A line that is not one is skipped, as are `#` comments. */
        fun read(text: CharSequence): Chaizi {
            val chars = HashMap<String, List<String>>()
            for (line in text.lineSequence()) {
                val letters = line.substringBefore('\t', "")
                if (letters.isEmpty() || !letters.all { it in 'a'..'z' }) continue
                val found = line.substringAfter('\t').split(' ').filter { it.isNotEmpty() }
                if (found.isNotEmpty()) chars[letters] = found.take(MAX)
            }
            return Chaizi(chars)
        }

        /** [chars] as [read] takes them, by spelling, [header] a comment above. */
        fun format(chars: Map<String, List<String>>, header: String = ""): String = buildString {
            for (line in header.lines()) if (line.isNotEmpty()) append("# ").append(line).append('\n')
            for ((letters, found) in chars.toSortedMap()) {
                if (found.isEmpty()) continue
                append(letters).append('\t').append(found.take(MAX).joinToString(" ")).append('\n')
            }
        }
    }
}
