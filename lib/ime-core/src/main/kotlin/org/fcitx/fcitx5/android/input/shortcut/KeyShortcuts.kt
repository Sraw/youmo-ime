/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

/**
 * The shortcuts the user set, over [KeyShortcutPreset]'s.
 *
 * A key is named by its keyboard's form and the key ([charKey]: `full/a`, `t9/2`, `g14/cv`,
 * `num/5`, `zyfull/a`), a function key by its name and the way it is swiped ([functionKey]:
 * `fn/space.up`). Stored as text, a line per key: the name, a tab, then the value: a command's
 * key, `text:` and the text (tab, line break and backslash escaped), or `none`, set to nothing
 * over a preset. A key with no line has its preset, so a preset changed later reaches everyone
 * who did not change that key, and an upgrade needs no migration of this text.
 */
class KeyShortcuts private constructor(private val table: Map<String, KeyShortcut>) {

    val isEmpty: Boolean get() = table.isEmpty()

    /** What [name] does: the user's, else its preset's. */
    fun resolve(name: String): KeyShortcut = table[name] ?: KeyShortcutPreset.of(name)

    /** Whether the user set [name] themselves: drawn outlined on the settings page. */
    fun isSet(name: String): Boolean = name in table

    fun with(name: String, shortcut: KeyShortcut): KeyShortcuts =
        KeyShortcuts(LinkedHashMap(table).also { it[name] = shortcut })

    /** [name] back to its preset. */
    fun without(name: String): KeyShortcuts =
        if (name in table) KeyShortcuts(LinkedHashMap(table).also { it.remove(name) }) else this

    /** Every key of [form] (`full`, `fn`, …) that has a preset back to it, the others left as they are (套用编辑快捷键). */
    fun withPresets(form: String): KeyShortcuts =
        KeyShortcuts(table.filterKeys { formOf(it) != form || KeyShortcutPreset.of(it) == KeyShortcut.None })

    /** Every one of [names] set to nothing, its preset too (清除这一页的快捷键). */
    fun cleared(names: Collection<String>): KeyShortcuts =
        KeyShortcuts(LinkedHashMap(table).also { t -> names.forEach { t[it] = KeyShortcut.None } })

    fun serialize(): String = table.entries.joinToString("\n") { (name, shortcut) -> "$name\t${valueOf(shortcut)}" }

    override fun equals(other: Any?): Boolean = other is KeyShortcuts && other.table == table

    override fun hashCode(): Int = table.hashCode()

    override fun toString(): String = "KeyShortcuts($table)"

    companion object {
        val Empty = KeyShortcuts(emptyMap())

        private const val NONE = "none"
        private const val TEXT = "text:"

        const val FUNCTION = "fn"

        fun charKey(form: String, key: String) = "$form/$key"

        fun functionKey(key: String, up: Boolean) = "$FUNCTION/$key.${if (up) "up" else "down"}"

        fun formOf(name: String): String = name.substringBefore('/')

        private fun valueOf(shortcut: KeyShortcut): String = when (shortcut) {
            is KeyShortcut.Command -> shortcut.command.key
            is KeyShortcut.Text -> TEXT + escape(shortcut.text)
            KeyShortcut.None -> NONE
        }

        private fun escape(text: String) = buildString {
            text.forEach {
                when (it) {
                    '\\' -> append("\\\\")
                    '\t' -> append("\\t")
                    '\n' -> append("\\n")
                    else -> append(it)
                }
            }
        }

        // a backslash before anything else, or at the end, is kept as it is
        private fun unescape(text: String) = buildString {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val next = text.getOrNull(i + 1)
                if (c == '\\' && next != null && next in "\\tn") {
                    append(if (next == 't') '\t' else if (next == 'n') '\n' else '\\')
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }

        private fun parseValue(value: String): KeyShortcut? = when {
            value == NONE -> KeyShortcut.None
            value.startsWith(TEXT) -> unescape(value.removePrefix(TEXT))
                .take(KeyShortcut.MAX_TEXT).takeIf { it.isNotEmpty() }?.let(KeyShortcut::Text)
            else -> KeyCommand.ofKey(value)?.let(KeyShortcut::Command)
        }

        /** Never throws: a line that does not parse is skipped; a later line for the same key wins. */
        fun parse(text: String): KeyShortcuts {
            val table = LinkedHashMap<String, KeyShortcut>()
            text.lineSequence().mapNotNull(::parseLine).forEach { (name, shortcut) -> table[name] = shortcut }
            return if (table.isEmpty()) Empty else KeyShortcuts(table)
        }

        private fun parseLine(line: String): Pair<String, KeyShortcut>? {
            val name = line.substringBefore('\t', "")
            if (name.isEmpty() || '/' !in name) return null
            return parseValue(line.substring(name.length + 1))?.let { name to it }
        }
    }
}
