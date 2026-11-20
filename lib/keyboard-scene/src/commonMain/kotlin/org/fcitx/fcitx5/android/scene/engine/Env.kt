/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.layout.Condition
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard
import org.fcitx.fcitx5.android.scene.theme.Selector

/** Everything outside the layout and the theme a scene depends on: the app reads its settings and Android's state into one; the engine reads nothing else. */
data class Env(
    /** The app's side and bottom padding already taken off. */
    val width: Double,
    val height: Double,
    val density: Double,
    val landscape: Boolean = false,
    val compact: Boolean = false,
    val floating: Double? = null,
    val split: Boolean = false,
    val oneHanded: SplitKeyboard.Dock = SplitKeyboard.Dock.FULL,
    /** split_dock */
    val dock: SplitKeyboard.Dock = SplitKeyboard.Dock.RIGHT,
    val dark: Boolean = false,
    /** key_border_choice; the theme's `@flat` styles then apply. */
    val flat: Boolean = false,
    val input: Input = Input(),
    val shift: Shift = Shift.None,
    val texts: Texts = Texts(),
    val marks: MarkSettings = MarkSettings(),
    val states: Map<String, Set<KeyState>> = emptyMap(),
    val board: String? = null,
    /** For the theme's scenes, in place of [input], which is what `when=` asks (a qwerty board's `ime=pinyin` under English too). */
    val typed: Set<String>? = null,
    val keyStyle: KeyStyleOver? = null,
    val base: String? = null,
) {
    /** As Android's configuration has it (rounded down). */
    val widthDp: Int get() = (width / density + DP_EPSILON).toInt()

    val isOneHanded: Boolean
        get() = floating == null && !split && SplitKeyboard.placement(oneHanded, dock, false, true, widthDp) != null

    /** Cascades are kept by the ones a theme names ([org.fcitx.fcitx5.android.scene.theme.Cascades]). */
    val scenes: Set<String>
        get() = buildSet {
            if (compact) add("compact")
            if (landscape) add("landscape")
            if (floating != null) add("floating")
            if (split) add("split")
            if (isOneHanded) add("one-handed")
            if (dark) add("dark")
            if (flat) add("flat")
            if (typed != null) {
                addAll(typed)
            } else {
                input.ime?.let { add("im=$it") }
                input.table?.let { add("im=$it") }
                input.field?.takeIf { it in Selector.FIELDS }?.let { add("field=$it") }
            }
            board?.let { add("keyboard=$it") }
            base?.let { add("keyboard=$it") }
        }

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
    val pad: String? = null,
)

/** What the layout's bindings read; [alts] by key name (`full/q`, `g14/qw`, `t9/2`), [other] by `name:arg`. */
data class Texts(
    val imeName: String = "",
    val comma: String = ",",
    val period: String = ".",
    val returnLabel: String = "",
    val returnIcon: String = "enter",
    /** key_confirm */
    val confirm: String = "确定",
    /** picker_back */
    val back: String = "返回",
    val keepUppercase: Boolean = false,
    /** LetterCaseRule.forIme: English's always [LetterCase.Shift]. */
    val letterCase: LetterCase = LetterCase.Shift,
    val alts: Map<String, String>? = null,
    val punctuation: Map<String, String> = emptyMap(),
    /** t9_letters, or 双拼's NINE_SP: lower case, the binding cases them. */
    val nineLetters: Map<String, String> = NINE_LETTERS,
    val other: Map<String, String> = emptyMap(),
    /** a11y_key_grouped */
    val a11yGrouped: String = "%1\$s, %2\$s",
    /** a11y_key_space_with_ime */
    val a11ySpace: String = "Space, %1\$s",
) {
    companion object {
        val NINE_LETTERS = mapOf(
            "2" to "abc", "3" to "def", "4" to "ghi", "5" to "jkl", "6" to "mno", "7" to "pqrs", "8" to "tuv", "9" to "wxyz",
        )
    }
}

/** A key's `alt` is no mark: what it reaches is named in [MarkSettings.shortcuts] alone. */
sealed interface MarkContent {
    data class Icon(val name: String) : MarkContent
    /** [shorter]: drawn where [text] has no room; where that does not fit either, [SIGN]. */
    data class Text(val text: String, val shorter: String? = null) : MarkContent {
        val ladder: List<MarkContent> get() = listOfNotNull(shorter?.let(::Text), SIGN)

        companion object {
            val SIGN = Icon(org.fcitx.fcitx5.android.scene.Icons.SHORT_TEXT)
        }
    }
    data class Symbol(val text: String) : MarkContent
    data object Empty : MarkContent

    val symbol: Boolean get() = this is Symbol
}

/** Looked up in [shortcuts] by the layout's `shortcut=` name and the way (`full/a:up`); an unnamed key draws nothing. */
data class MarkSettings(
    val upAt: KeyMarks.At = KeyMarks.At.SymbolDefault,
    val downAt: KeyMarks.At = KeyMarks.At.ShortcutDefault,
    val symbols: Boolean = true,
    val charShortcuts: Boolean = true,
    val upOn: Boolean = true,
    val downOn: Boolean = true,
    val firstUp: Boolean = true,
    val shortcuts: Map<String, MarkContent> = emptyMap(),
    /** Their marks are kept over the built-in ones where two meet. */
    val user: Set<String> = emptySet(),
) {
    fun isUser(name: String?, up: Boolean): Boolean = name != null && "$name:${if (up) "up" else "down"}" in user

    fun of(name: String?, up: Boolean): MarkContent = name?.let { shortcuts["$it:${if (up) "up" else "down"}"] } ?: MarkContent.Empty
}
