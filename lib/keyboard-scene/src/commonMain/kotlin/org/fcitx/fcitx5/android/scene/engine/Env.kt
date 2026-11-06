/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.layout.Condition
import org.fcitx.fcitx5.android.scene.rules.KeyMarks

/**
 * Everything outside the layout and the theme a scene depends on: the keyboard's size, the scene's
 * conditions (the theme's `@` prefixes), the input's state the layout's `when=` asks of, what its
 * bindings read, and the marks' settings. The app reads its settings and Android's state into one
 * of these; the engine reads nothing else (CLAUDE.md: values in, a decision out).
 */
data class Env(
    /** The keyboard's keys' area, px: the app's side padding and bottom padding already taken off. */
    val width: Double,
    val height: Double,
    /** px a dp. */
    val density: Double,
    val landscape: Boolean = false,
    val compact: Boolean = false,
    /** Floating: the keys' text scale there (FloatingKeyboard.textScale); null docked, or a settings preview. */
    val floating: Double? = null,
    /** The letters split (SplitKeyboard.splits, never floating). */
    val split: Boolean = false,
    val oneHanded: Boolean = false,
    val dark: Boolean = false,
    /** 按键样式 made the keys flat (key_border_choice), the theme's `@flat` styles then apply. */
    val flat: Boolean = false,
    val input: Input = Input(),
    val shift: Shift = Shift.None,
    val texts: Texts = Texts(),
    val marks: MarkSettings = MarkSettings(),
) {
    /** The theme's scene prefixes this environment is in. */
    val scenes: Set<String>
        get() = buildSet {
            if (compact) add("compact")
            if (landscape) add("landscape")
            if (floating != null) add("floating")
            if (split) add("split")
            if (oneHanded) add("one-handed")
            if (dark) add("dark")
            if (flat) add("flat")
        }

    /** What a `when=` asks of, answered here. */
    val facts: Condition.Facts
        get() = Condition.Facts { name, values ->
            when (name) {
                "ime" -> input.ime.isIn(values)
                "scheme" -> input.scheme.isIn(values)
                "scheme-types" -> if (values == null) input.schemeTypes.isNotEmpty() else input.schemeTypes.any { it in values }
                "table" -> input.table.isIn(values)
                "table-extra" -> input.tableExtra.isIn(values)
                "field" -> input.field.isIn(values)
                "action" -> input.action.isIn(values)
                "split-side" -> false
                else -> flag(name)
            }
        }

    private fun String?.isIn(values: Set<String>?): Boolean = this != null && (values == null || this in values)

    private fun flag(name: String): Boolean = when (name) {
        "roots" -> input.roots
        "compact" -> compact
        "landscape" -> landscape
        "split" -> split
        "floating" -> floating != null
        "one-handed" -> oneHanded
        "voice" -> input.voice
        "hold-voice" -> input.holdVoice
        "lang-key" -> input.langKey
        "composing" -> input.composing
        "looking-up" -> input.lookingUp
        "shifted" -> shift != Shift.None
        "caps-lock" -> shift == Shift.Lock
        else -> false
    }
}

enum class Shift { None, Once, Lock }

/**
 * The input's state as `when=` asks: the input method (`pinyin`, `shuangpin`, `english`, `table`…),
 * its 双拼 scheme and the keys it types beyond the letters (`semicolon`), the table and where its
 * extra key goes, the field and its action, and the settings that add keys.
 */
data class Input(
    val ime: String? = "pinyin",
    val scheme: String? = null,
    val schemeTypes: Set<String> = emptySet(),
    val table: String? = null,
    val tableExtra: String? = null,
    val roots: Boolean = false,
    val field: String? = "text",
    val action: String? = null,
    val voice: Boolean = false,
    val holdVoice: Boolean = false,
    val langKey: Boolean = true,
    val composing: Boolean = false,
    val lookingUp: Boolean = false,
)

/**
 * What the layout's bindings read (layout-1.xsd's `text`): the space's input method name, the comma
 * and full stop as punctuation maps them now, Return's label and icon, the letters' long press
 * firsts the user set ([alts]: none there, none drawn), [punctuation] the map, the rest by
 * `name:arg` ([other]: `root:a`, `nine-letters:2`…).
 */
data class Texts(
    val imeName: String = "",
    val comma: String = ",",
    val period: String = ".",
    val returnLabel: String = "",
    val returnIcon: String = "enter",
    val keepUppercase: Boolean = false,
    val alts: Map<String, String>? = null,
    val punctuation: Map<String, String> = emptyMap(),
    val other: Map<String, String> = emptyMap(),
)

/** What a mark shows: a command's icon or a text (KeyShortcutLooks). */
sealed interface MarkContent {
    data class Icon(val name: String) : MarkContent
    data class Text(val text: String) : MarkContent
}

/**
 * The marks' settings (rules B §7): where symbols and shortcuts go, whether a character key draws
 * its symbol ([symbols]: the swipe has one) and its shortcut ([charShortcuts]: 快捷键标记 and a
 * shortcut layer), whether a function key's up and down ones are drawn, and the shortcuts by
 * [shortcuts]' names: a character key's `full/a`, a function key's `space:up`, `backspace:down`.
 */
data class MarkSettings(
    val symbolAt: KeyMarks.At = KeyMarks.At.SymbolDefault,
    val shortcutAt: KeyMarks.At = KeyMarks.At.ShortcutDefault,
    val symbols: Boolean = true,
    val charShortcuts: Boolean = true,
    val upOn: Boolean = true,
    val downOn: Boolean = true,
    val shortcuts: Map<String, MarkContent> = PRESET,
) {
    companion object {
        /** KeyShortcutPreset's: a select all, c copy, x cut, v paste, z undo, y redo; the space's edit menu, ⌫'s clear and restore. */
        val PRESET: Map<String, MarkContent> = mapOf(
            "full/a" to MarkContent.Icon("select-all"),
            "full/c" to MarkContent.Icon("content-copy"),
            "full/x" to MarkContent.Icon("content-cut"),
            "full/v" to MarkContent.Icon("content-paste"),
            "full/z" to MarkContent.Icon("undo"),
            "full/y" to MarkContent.Icon("redo"),
            "space:up" to MarkContent.Icon("cursor-move"),
            "backspace:up" to MarkContent.Icon("delete-sweep"),
            "backspace:down" to MarkContent.Icon("restore"),
        )
    }
}
