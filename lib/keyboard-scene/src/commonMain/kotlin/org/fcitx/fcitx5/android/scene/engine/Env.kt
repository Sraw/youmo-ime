/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.SlotDefaults
import org.fcitx.fcitx5.android.scene.layout.Condition
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard
import org.fcitx.fcitx5.android.scene.theme.Selector

/**
 * Everything outside the layout and the theme a scene depends on: the keyboard's size, the scene's
 * conditions (the theme's `@` prefixes), the input's state the layout's `when=` asks of, what its
 * bindings read, and the marks' settings. The app reads its settings and Android's state into one
 * of these; the engine reads nothing else (CLAUDE.md: values in, a decision out).
 */
data class Env(
    /**
     * The keyboard's keys' area, px: the app's side padding and bottom padding already taken off; a
     * board one-handed or at a side ([oneHanded], [dock]) is placed in it by the engine (rules D §2).
     */
    val width: Double,
    val height: Double,
    /** px a dp. */
    val density: Double,
    val landscape: Boolean = false,
    val compact: Boolean = false,
    /** Floating: the keys' text scale there (FloatingKeyboard.textScale); null docked, or a settings preview. */
    val floating: Double? = null,
    /** The letters split (SplitKeyboard.splits, never floating); the other boards then go to [dock]'s side. */
    val split: Boolean = false,
    /** 单手: the side the board goes to ([SplitKeyboard.Dock.FULL]: off). */
    val oneHanded: SplitKeyboard.Dock = SplitKeyboard.Dock.FULL,
    /** 分体时其它键盘靠哪边 (split_dock). */
    val dock: SplitKeyboard.Dock = SplitKeyboard.Dock.RIGHT,
    val dark: Boolean = false,
    /** 按键样式 made the keys flat (key_border_choice), the theme's `@flat` styles then apply. */
    val flat: Boolean = false,
    val input: Input = Input(),
    val shift: Shift = Shift.None,
    val texts: Texts = Texts(),
    val marks: MarkSettings = MarkSettings(),
    /**
     * The states keys are in, but pressed and Shift's (from [shift]), by a key's id or its role:
     * `reading` [KeyState.On] (手写的注 on), `lock` [KeyState.Locked] (a panel's lock).
     */
    val states: Map<String, Set<KeyState>> = emptyMap(),
    /** The board's id, its layout's (`nine`): the theme's `@keyboard=` scene. Null: none (a popup's, every board's). */
    val board: String? = null,
    /**
     * What is typed as the input method knows it, as the theme's scenes (`im=wubi`, `field=password`):
     * in place of what [input] says of it, which is what the board's `when=` asks (a qwerty board's
     * `ime=pinyin` under English too). Null: as [input] says (a test, a settings page's keys).
     */
    val typed: Set<String>? = null,
    /** The app's 按键样式 over a 4.0 theme's own keys, key by key after its cascade; null: the theme's alone (a 3.0 look has them in it, the theme maker none). */
    val keyStyle: KeyStyleOver? = null,
) {
    /** The keyboard's width in dp as Android's configuration has it (a whole number, rounded down). */
    val widthDp: Int get() = (width / density + DP_EPSILON).toInt()

    /** Whether a board one-handed goes to a side here ([oneHanded] on, not split, not floating, room beside it: rules D §2). */
    val isOneHanded: Boolean
        get() = floating == null && !split && SplitKeyboard.placement(oneHanded, dock, false, true, widthDp) != null

    /**
     * The theme's scene prefixes this environment is in: the screen's and the settings', and what is
     * typed (`im=pinyin`, `im=table` and `im=wubi` a table, `field=password`, `keyboard=nine`). A
     * theme's cascades are kept by those of them it names ([org.fcitx.fcitx5.android.scene.theme.Cascades]).
     */
    val scenes: Set<String>
        get() = buildSet {
            if (compact) add("compact")
            if (landscape) add("landscape")
            if (floating != null) add("floating")
            if (split) add("split")
            if (isOneHanded) add("one-handed")
            if (dark) add("dark")
            if (flat) add("flat")
            // what is typed: the input method (a table also by its name), the field, the board
            if (typed != null) {
                addAll(typed)
            } else {
                input.ime?.let { add("im=$it") }
                input.table?.let { add("im=$it") }
                input.field?.takeIf { it in Selector.FIELDS }?.let { add("field=$it") }
            }
            board?.let { add("keyboard=$it") }
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
                "pad" -> input.pad.isIn(values)
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
        "one-handed" -> isOneHanded
        "voice" -> input.voice
        "hold-voice" -> input.holdVoice
        "lang-key" -> input.langKey
        "composing" -> input.composing
        "looking-up" -> input.lookingUp
        "shifted" -> shift != Shift.None
        "caps-lock" -> shift == Shift.Lock
        else -> false
    }

    private companion object {
        // 1080 px at 2.625 is 411.43 dp: Android says 411; a width of exactly 600 dp computed as 599.9999 must say 600
        const val DP_EPSILON = 1e-6
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
    /** A number field's pad (NumberPad: full, digits, signed, decimal, signed-decimal, phone, datetime). */
    val pad: String? = null,
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
    /** The key a table's code is confirmed by, in Return's place (key_confirm: 确定, OK in English). */
    val confirm: String = "确定",
    /** The symbol panel's way back to the keys (picker_back: 返回, Back in English). */
    val back: String = "返回",
    val keepUppercase: Boolean = false,
    /** 拼音键字母大小写 as it holds for this input method (LetterCaseRule.forIme: English's always [LetterCase.Shift]). */
    val letterCase: LetterCase = LetterCase.Shift,
    val alts: Map<String, String>? = null,
    val punctuation: Map<String, String> = emptyMap(),
    /** The nine keys' letters, by digit (t9_letters, or 双拼's NINE_SP): lower case, the binding cases them. */
    val nineLetters: Map<String, String> = NINE_LETTERS,
    val other: Map<String, String> = emptyMap(),
    /** A key's label and its alt read together, `%1$s` and `%2$s` (a11y_key_grouped; "%1$s，%2$s" in Chinese). */
    val a11yGrouped: String = "%1\$s, %2\$s",
    /** The space read with the input method's name, `%1$s` (a11y_key_space_with_ime; "空格，%1$s" in Chinese). */
    val a11ySpace: String = "Space, %1\$s",
) {
    companion object {
        /** NineKeyLetters' default: the phone's. */
        val NINE_LETTERS = mapOf(
            "2" to "abc", "3" to "def", "4" to "ghi", "5" to "jkl", "6" to "mno", "7" to "pqrs", "8" to "tuv", "9" to "wxyz",
        )
    }
}

/**
 * What a way of a key's swipe draws (060-swipe-slots, KeyShortcutLooks): a command's [Icon] or a [Text]
 * (as shortcuts are drawn, at the shortcuts' size), [Corner] the key's own `alt` and [Symbol] a given
 * one (as symbols are, a corner's), or [Empty], nothing.
 */
sealed interface MarkContent {
    data class Icon(val name: String) : MarkContent
    data class Text(val text: String) : MarkContent
    data class Symbol(val text: String) : MarkContent
    data object Corner : MarkContent
    data object Empty : MarkContent

    /** Drawn as a symbol (a corner's look), not as a shortcut. */
    val symbol: Boolean get() = this is Symbol || this == Corner
}

/**
 * The marks' settings (rules B §7, 060-swipe-slots): every key has an up and a down way, each drawn
 * at its place ([upAt], [downAt]) where that way is on ([upOn], [downOn]), by [shortcuts]' names, the
 * layout's `shortcut=` and the way: `full/a:up`, `fn/space:down`. A character key with no name there
 * draws its [MarkContent.Corner] the way [firstUp] says and nothing the other; a function key nothing.
 * [symbols] whether symbols are drawn at all, [charShortcuts] whether a character key's shortcuts are
 * (a table's roots take their room).
 */
data class MarkSettings(
    val upAt: KeyMarks.At = KeyMarks.At.SymbolDefault,
    val downAt: KeyMarks.At = KeyMarks.At.ShortcutDefault,
    val symbols: Boolean = true,
    val charShortcuts: Boolean = true,
    val upOn: Boolean = true,
    val downOn: Boolean = true,
    val firstUp: Boolean = true,
    val shortcuts: Map<String, MarkContent> = PRESET,
) {
    /** [name]'s way [up] by [shortcuts], else its default: a character key's corner the way [firstUp], else nothing. */
    fun of(name: String?, char: Boolean, up: Boolean): MarkContent =
        name?.let { shortcuts["$it:${if (up) "up" else "down"}"] } ?: if (char && up == firstUp) MarkContent.Corner else MarkContent.Empty

    companion object {
        /**
         * [SlotDefaults]' commands of the full keyboard's letters and the function keys, the long press's first up:
         * a select all, c copy, x cut, v paste, z undo, y redo down; the space's edit menu, ⌫'s clear and restore.
         */
        val PRESET: Map<String, MarkContent> = (('a'..'z').map { KeyId("full/$it") } + listOf("space", "backspace").map(KeyId::function))
            .flatMap { id ->
                listOf(true, false).mapNotNull { up ->
                    (SlotDefaults.of(id, up, firstUp = true) as? SlotDefaults.Slot.Command)
                        ?.let { "${id.name}:${if (up) "up" else "down"}" to MarkContent.Icon(SlotDefaults.icon(it.key)) }
                }
            }.toMap()
    }
}
