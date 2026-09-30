/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.data.unescapeValue

/**
 * A table input method's `.conf` as fcitx's table addon reads it: the table [file] its `[Table]`
 * section names, and the [options] it sets, libime's defaults for those it does not. [learning]
 * off, what the user picks is not kept.
 *
 * Options the engine has no use for (PageSize, which the user sets for all, ExactMatch, the
 * keys of `[Table/...]`) are skipped, as are values that do not read.
 */
class TableConf(val file: String, val options: TableOptions, val learning: Boolean) {

    companion object {
        /** What a `.conf` saying nothing means to libime's table. */
        val DEFAULTS = TableOptions(
            autoSelect = false,
            autoSelectLength = 0,
            noMatchAutoSelectLength = 0,
            noSortInputLength = 0,
            sortByCodeLength = true,
            orderByUse = false,
            matchingKey = null,
            pinyinKey = null,
            hint = false,
            autoPhraseLength = -1,
            saveAutoPhraseAfter = -1,
        )

        /**
         * [text], with what [settings] (the user's, kept apart as fcitx keeps them) set over it.
         *
         * @throws IllegalArgumentException if it names no table file
         */
        fun parse(text: String, settings: String = ""): TableConf {
            val table = section(text, "Table") + section(settings, "Table")
            val file = table["File"]?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("no [Table] File")
            fun int(key: String, default: Int) = table[key]?.toIntOrNull() ?: default
            fun bool(key: String, default: Boolean) = when (table[key]?.lowercase()) {
                "true" -> true
                "false" -> false
                else -> default
            }
            fun key(key: String, default: Char?) = table[key]?.let { keyChar(it) } ?: default
            val d = DEFAULTS
            val options = TableOptions(
                autoSelect = bool("AutoSelect", d.autoSelect),
                autoSelectLength = int("AutoSelectLength", d.autoSelectLength),
                noMatchAutoSelectLength = int("NoMatchAutoSelectLength", d.noMatchAutoSelectLength),
                noSortInputLength = int("NoSortInputLength", d.noSortInputLength),
                sortByCodeLength = bool("SortByCodeLength", d.sortByCodeLength),
                // Fast orders by use as Freq does, libime only saving the order less often
                orderByUse = table["OrderPolicy"]?.let { it != "No" } ?: d.orderByUse,
                matchingKey = key("MatchingKey", d.matchingKey),
                pinyinKey = key("PinyinKey", d.pinyinKey),
                hint = bool("Hint", d.hint),
                autoPhraseLength = int("AutoPhraseLength", d.autoPhraseLength),
                saveAutoPhraseAfter = int("SaveAutoPhraseAfter", d.saveAutoPhraseAfter),
            )
            return TableConf(file, options, bool("Learning", true))
        }

        /** The keys of [name]'s section in the ini [text]: fcitx's format, `#` starting a comment. */
        internal fun section(text: String, name: String): Map<String, String> {
            val out = HashMap<String, String>()
            var inside = false
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                when {
                    line.isEmpty() || line.startsWith("#") -> {}
                    line.startsWith("[") && line.endsWith("]") -> inside = line.substring(1, line.length - 1).trim() == name
                    inside -> {
                        val eq = line.indexOf('=')
                        if (eq > 0) out[line.substring(0, eq).trim()] = unescapeValue(line.substring(eq + 1).trim())
                    }
                }
            }
            return out
        }

        /**
         * The character a key such as fcitx writes it types: itself if one character, else a
         * keysym's name. Null for any other, a key with modifiers among them: no table key.
         */
        internal fun keyChar(key: String): Char? = key.singleOrNull() ?: KEYSYMS[key]

        private val KEYSYMS = mapOf(
            "grave" to '`', "asciitilde" to '~', "exclam" to '!', "at" to '@', "numbersign" to '#',
            "dollar" to '$', "percent" to '%', "asciicircum" to '^', "ampersand" to '&', "asterisk" to '*',
            "parenleft" to '(', "parenright" to ')', "minus" to '-', "underscore" to '_', "equal" to '=',
            "plus" to '+', "bracketleft" to '[', "bracketright" to ']', "braceleft" to '{', "braceright" to '}',
            "backslash" to '\\', "bar" to '|', "semicolon" to ';', "colon" to ':', "apostrophe" to '\'',
            "quotedbl" to '"', "comma" to ',', "less" to '<', "period" to '.', "greater" to '>',
            "slash" to '/', "question" to '?',
        )
    }
}
