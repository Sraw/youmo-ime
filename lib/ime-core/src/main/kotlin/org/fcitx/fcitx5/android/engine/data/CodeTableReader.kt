/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import java.io.BufferedReader

/**
 * Reads a fcitx code table text file (the `.txt` libime's `libime_tabledict` compiles, or writes
 * back from a `.dict`):
 *
 * ```
 * ;comment
 * 键码=abcdefghijklmnopqrstuvwxy
 * 码长=4
 * 拼音=@
 * [组词规则]
 * e2=p11+p12+p21+p22
 * [数据]
 * a 工
 * @a 阿
 * [词组]
 * 工作
 * ```
 *
 * libime writes the same in English (`KeyCode=`, `[Rule]`, `[Data]`, `[Phrase]`); the header is
 * kept under the Chinese names, which the engine reads. Header and rules are kept verbatim.
 * Entries keep their code as written, including a marker prefix such as 拼音's `@`: the marker is
 * never a 键码, so those entries cannot turn up in a lookup of ordinary codes, and the engine
 * finds them by asking for the marker. A text quoted as fcitx quotes one is read unquoted.
 * Comments (`;` or `#`) are recognised only before `[数据]`, since some tables use `;` as a key.
 * The phrases of `[词组]` have no code: they are left in [phrases], for the 组词规则 to code.
 */
class CodeTableReader {

    val builder = CodeTable.Builder()

    var entries = 0
        private set

    /** The phrases listed without a code, under `[词组]`. */
    val phrases = ArrayList<String>()

    /**
     * Unmarked entries whose code uses a character that is not a 键码. Marked entries are not
     * checked: a 拼音 entry is spelt in pinyin, which may need a letter the table's own keys lack
     * (五笔 has no z key).
     */
    val strayCodes = ArrayList<String>()

    private enum class Part { HEADER, RULES, DATA, PHRASES }

    fun read(reader: BufferedReader, source: String) {
        var part = Part.HEADER
        val header = HashMap<String, String>()
        var keys: Set<Char> = emptySet()
        var markers: Set<Char> = emptySet()
        reader.forEachNumberedLine(source) { raw, _ ->
            val line = raw.trimSeparators()
            val section = SECTIONS[line]
            when {
                line.isEmpty() -> {}
                part < Part.DATA && (line.startsWith(";") || line.startsWith("#")) -> {}
                section == Part.RULES && part == Part.HEADER -> part = Part.RULES
                section == Part.DATA && part < Part.DATA -> {
                    part = Part.DATA
                    keys = header["键码"].orEmpty().toSet()
                    markers = MARKER_KEYS.mapNotNull { header[it]?.singleOrNull() }.toSet()
                }
                section == Part.PHRASES && part == Part.DATA -> part = Part.PHRASES
                part == Part.DATA -> entry(line, keys, markers)
                part == Part.PHRASES -> phrases += unescapeValue(line)
                else -> {
                    val eq = line.indexOf('=')
                    require(eq > 0) { "expected key=value, got \"$line\"" }
                    val key = line.substring(0, eq).trimSeparators()
                    val value = line.substring(eq + 1).trimSeparators()
                    if (part == Part.HEADER) {
                        val name = ENGLISH[key] ?: key
                        header[name] = value
                        builder.header(name, value)
                    } else {
                        builder.rule(key, value)
                    }
                }
            }
        }
        if (part < Part.DATA) throw SourceException(source, 0, "no [数据] section")
    }

    private fun entry(line: String, keys: Set<Char>, markers: Set<Char>) {
        val split = line.indexOfFirst { it.isSeparator() }
        require(split > 0) { "expected \"code text\", got \"$line\"" }
        val code = line.substring(0, split)
        builder.entry(code, unescapeValue(line.substring(split + 1).trimSeparators()))
        entries++
        if (code[0] !in markers && code.any { it !in keys }) strayCodes += line
    }

    companion object {
        /** Header keys whose value is a prefix marking a special kind of entry. */
        private val MARKER_KEYS = listOf("拼音", "构词", "提示")

        // as libime reads them, in either language
        private val SECTIONS = mapOf(
            "[组词规则]" to Part.RULES, "[Rule]" to Part.RULES,
            "[数据]" to Part.DATA, "[Data]" to Part.DATA,
            "[词组]" to Part.PHRASES, "[Phrase]" to Part.PHRASES,
        )

        private val ENGLISH = mapOf(
            "KeyCode" to "键码", "Length" to "码长", "InvalidChar" to "规避字符", "Pinyin" to "拼音",
            "PinyinLength" to "拼音长度", "Prompt" to "提示", "ConstructPhrase" to "构词",
        )
    }
}
