/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

/**
 * The user's shortcuts over [KeyShortcutPreset]'s. Keys are `full/a`, `t9/2`, `fn/space.up`...; stored a line per key:
 * name, tab, then a command's key, `text:` and the escaped text, or `none`. A key with no line has its preset, so a
 * changed preset reaches everyone and upgrades need no migration.
 */
class KeyShortcuts private constructor(private val table: Map<String, KeyShortcut>) {

    val isEmpty: Boolean get() = table.isEmpty()

    /** In the order stored. */
    val names: Set<String> get() = table.keys

    fun resolve(name: String): KeyShortcut = table[name] ?: KeyShortcutPreset.of(name)

    /** Drawn outlined on the settings page. */
    fun isSet(name: String): Boolean = name in table

    fun with(name: String, shortcut: KeyShortcut): KeyShortcuts =
        KeyShortcuts(LinkedHashMap(table).also { it[name] = shortcut })

    fun without(name: String): KeyShortcuts =
        if (name in table) KeyShortcuts(LinkedHashMap(table).also { it.remove(name) }) else this

    /** Their presets too (清除这一页的快捷键). */
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

        internal fun escape(text: String) = buildString {
            text.forEach {
                when (it) {
                    '\\' -> append("\\\\")
                    '\t' -> append("\\t")
                    '\n' -> append("\\n")
                    // a stored \r once ended the line it was in, the rest of the text lost
                    '\r' -> append("\\r")
                    else -> append(it)
                }
            }
        }

        // a backslash before anything else, or at the end, is kept as it is
        internal fun unescape(text: String) = buildString {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val next = text.getOrNull(i + 1)
                if (c == '\\' && next != null && next in "\\tnr") {
                    append(
                        when (next) {
                            't' -> '\t'
                            'n' -> '\n'
                            'r' -> '\r'
                            else -> '\\'
                        },
                    )
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }

        private fun parseValue(value: String): KeyShortcut? = when {
            value == NONE -> KeyShortcut.None
            value.startsWith(TEXT) -> KeyShortcut.clip(unescape(value.removePrefix(TEXT)), KeyShortcut.MAX_TEXT)
                .takeIf { it.isNotEmpty() }?.let(KeyShortcut::Text)
            else -> KeyCommand.ofKey(value)?.let(KeyShortcut::Command)
        }

        /** Never throws: a bad line is skipped; a later line for the same key wins. */
        fun parse(text: String): KeyShortcuts {
            val table = LinkedHashMap<String, KeyShortcut>()
            // lines end at \n alone: lineSequence() would cut a text at a \r too
            text.splitToSequence('\n').mapNotNull(::parseLine).forEach { (name, shortcut) -> table[name] = shortcut }
            return if (table.isEmpty()) Empty else KeyShortcuts(table)
        }

        private fun parseLine(line: String): Pair<String, KeyShortcut>? {
            val name = line.substringBefore('\t', "")
            if (name.isEmpty() || '/' !in name) return null
            // a CRLF backup: its line-ending \r dropped; a text's own \r is stored escaped, never bare
            val value = line.substring(name.length + 1).removeSuffix("\r")
            return parseValue(value)?.let { name to it }
        }
    }
}
