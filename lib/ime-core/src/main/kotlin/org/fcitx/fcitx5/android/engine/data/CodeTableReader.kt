/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import java.io.BufferedReader

/**
 * Reads a fcitx code table text file (what `libime_tabledict` compiles): header lines (`键码=`, `码长=`, `拼音=@`),
 * then `[组词规则]`, `[数据]` (`a 工`, `@a 阿`) and `[词组]` (codeless phrases, to [phrases]); libime's English names
 * too. A marker prefix (`@`) stays in the code, never a 键码. Comments only before `[数据]`: some tables key `;`.
 */
class CodeTableReader {

    val builder = CodeTable.Builder()

    var entries = 0
        private set

    val phrases = ArrayList<String>()

    /** Unmarked entries using a non-键码 character. Marked ones are not checked: pinyin may need a key the table lacks (五笔 has no z). */
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
        private val MARKER_KEYS = listOf("拼音", "构词", "提示")

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
