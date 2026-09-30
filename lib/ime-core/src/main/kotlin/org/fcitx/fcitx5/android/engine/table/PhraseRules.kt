/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

/**
 * A table's 组词规则: how the code of a phrase is put together from the codes of its characters,
 * as libime reads them. `e2=p11+p12+p21+p22` says a phrase of exactly two characters takes the
 * first two code keys of each; `a4=p11+p21+p31+n11` says one of four or more takes the first key
 * of its first three characters and of its last (`n` counts from the end). A key index may be a
 * letter too, counting from the code's end: `z` is its last key, `y` the one before.
 *
 * Rules are tried in the order the table lists them; the first that applies wins.
 */
class PhraseRules(rules: Map<String, String>) {

    private class Part(val fromEnd: Boolean, val char: Int, val key: Int)

    private class Rule(val exact: Boolean, val length: Int, val parts: List<Part>)

    private val rules = rules.map { (name, value) -> parse(name, value) }

    val isEmpty: Boolean get() = rules.isEmpty()

    /**
     * The code of [text], its characters' codes found by [codeOf]; null if no rule applies or a
     * character it needs has no code.
     */
    fun encode(text: String, codeOf: (String) -> String?): String? {
        val chars = codePoints(text)
        return rules.firstNotNullOfOrNull { rule ->
            val applies = if (rule.exact) chars.size == rule.length else chars.size >= rule.length
            if (applies) apply(rule, chars, codeOf) else null
        }
    }

    private fun apply(rule: Rule, chars: List<String>, codeOf: (String) -> String?): String? {
        val out = StringBuilder()
        val used = HashSet<Pair<Int, Int>>()
        // p00: a placeholder, some tables pad their rules with it
        for (part in rule.parts.filter { it.char != 0 }) {
            if (part.char > chars.size) return null
            val index = if (part.fromEnd) chars.size - part.char else part.char - 1
            val code = codeOf(chars[index]) ?: return null
            val at = if (part.key > 0) part.key - 1 else code.length + part.key
            // a code shorter than the rule reaches is fine: 五笔's 工 is just a; and p11 and p1z
            // name the same key of a one-key code, taken once (libime takes it twice; no shipped
            // table's rules name keys from the end)
            if (at in code.indices && used.add(index to at)) out.append(code[at])
        }
        return if (out.isEmpty()) null else out.toString()
    }

    companion object {
        private fun parse(name: String, value: String): Rule {
            require(name.length == 2 && name[0].lowercaseChar() in "ea" && name[1] in '1'..'9') { "rule \"$name\"" }
            val parts = value.split('+').map { part ->
                require(part.length == 3 && part[0].lowercaseChar() in "pn" && part[1] in '0'..'9') { "rule part \"$part\" in $name" }
                val key = when (val k = part[2].lowercaseChar()) {
                    in '0'..'9' -> k - '0'
                    in 'a'..'z' -> k - 'z' - 1
                    else -> throw IllegalArgumentException("rule part \"$part\" in $name")
                }
                require((part[1] == '0') == (key == 0)) { "rule part \"$part\" in $name" }
                Part(part[0].lowercaseChar() == 'n', part[1] - '0', key)
            }
            return Rule(name[0].lowercaseChar() == 'e', name[1] - '0', parts)
        }

        internal fun codePoints(text: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i < text.length) {
                val next = text.offsetByCodePoints(i, 1)
                out += text.substring(i, next)
                i = next
            }
            return out
        }
    }
}
