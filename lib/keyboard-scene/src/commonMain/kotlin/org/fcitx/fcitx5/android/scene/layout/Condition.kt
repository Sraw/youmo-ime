/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.Parsed

/**
 * A `when=` (layout-1.xsd's `condition`): groups split by ", " are "or", a group's terms split by
 * spaces "and" (and binds first), `!` "not"; `name=a|b` holds where the name is one of them, a bare
 * name where it has any value (a flag: where it is set). The `;` key's is the case "and" alone could
 * not write: `ime=shuangpin scheme-types=semicolon !split, table-extra=semicolon` (rules A §3).
 */
data class Condition(val anyOf: List<List<Term>>) {

    data class Term(val name: String, val values: Set<String>?, val negated: Boolean) {
        override fun toString(): String = (if (negated) "!" else "") + name + (values?.joinToString("|", "=") ?: "")
    }

    /** Whether it holds where [facts] answers each name. */
    fun holds(facts: Facts): Boolean = anyOf.any { group -> group.all { it.holds(facts) } }

    private fun Term.holds(facts: Facts): Boolean = facts.has(name, values) != negated

    override fun toString(): String = anyOf.joinToString(", ") { it.joinToString(" ") }

    /** What a condition asks of: the environment, as [org.fcitx.fcitx5.android.scene.engine.Env] answers. */
    fun interface Facts {
        /** Whether [name] has one of [values] (null: any value; a flag: set). */
        fun has(name: String, values: Set<String>?): Boolean
    }

    companion object {
        /** Names with values (`ime=pinyin`); a bare one asks whether there is any. */
        val VALUED = setOf("ime", "scheme", "scheme-types", "table", "table-extra", "field", "action", "split-side", "pad")

        /** Flags: set or not. */
        val FLAGS = setOf(
            "roots", "compact", "landscape", "split", "floating", "one-handed", "voice", "hold-voice", "lang-key",
            "composing", "looking-up", "shifted", "caps-lock",
        )

        private val VALUE = Regex("[A-Za-z0-9_-]+")

        fun parse(text: String): Parsed<Condition> {
            if (text.isBlank()) return Parsed.Failed(0, "an empty when=")
            val groups = text.split(", ").map { group ->
                group.split(' ').map { term(it) ?: return Parsed.Failed(0, "when=\"$text\": \"$it\" is no condition") }
            }
            return Parsed.Ok(Condition(groups))
        }

        private fun term(raw: String): Term? {
            val negated = raw.startsWith("!")
            val body = raw.removePrefix("!")
            val name = body.substringBefore('=')
            val values = if ('=' in body) body.substringAfter('=').split('|') else null
            return when {
                values == null && (name in FLAGS || name in VALUED) -> Term(name, null, negated)
                values != null && name in VALUED && values.all { VALUE.matches(it) } -> Term(name, values.toSet(), negated)
                else -> null
            }
        }
    }
}
