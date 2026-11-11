/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.engine.table.TableKeyboard
import org.fcitx.fcitx5.android.input.keyboard.AddressKeys
import org.fcitx.fcitx5.android.input.keyboard.KeyMarks
import org.fcitx.fcitx5.android.input.keyboard.KeySwipe
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyMarkText
import org.fcitx.fcitx5.android.input.shortcut.SwipeOrientation
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Key
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.Row
import org.fcitx.fcitx5.android.scene.rules.KeyMarks as SceneKeyMarks

/**
 * What the 26 keys (TextKeyboard) show as the input is, as values: what qwerty.xml's `when=` asks of
 * ([input]). The keyboard decides each as it always has (its show*Key): this says it in the scene's
 * words, so the keys drawn are the keys it has shown.
 */
data class TextBoardState(
    /** The 双拼 scheme types `ing` on `;` (semicolonKey): the key shown, unsplit. */
    val semicolonScheme: Boolean = false,
    /** A table input method's keyboard: its 拼 key in Shift's place, 确定 in Return's while a code is typed. */
    val table: Boolean = false,
    /** The table's code key the letters have no room for (TableKeyboard.extraKey): `;` or `*`. */
    val tableExtra: Char? = null,
    val lookingUp: Boolean = false,
    /** Something typed: Return commits it (ReturnKeyAppearance.composing). */
    val composing: Boolean = false,
    /** An address field's keys in place of the comma and the full stop. */
    val address: AddressKeys = AddressKeys.NONE,
    val langKey: Boolean = true,
    /** Holding the space talks: a microphone on it. */
    val holdVoice: Boolean = false,
    /** A table's roots or their names over the letters (TableKeyboard.labelled): `{root:x}` on them. */
    val roots: Boolean = false,
) {
    fun input(): Input = Input(
        ime = when {
            semicolonScheme -> "shuangpin"
            table -> "table"
            else -> "pinyin"
        },
        schemeTypes = if (semicolonScheme) setOf("semicolon") else emptySet(),
        table = if (table) "table" else null,
        roots = roots,
        tableExtra = when (tableExtra) {
            ';' -> "semicolon"
            '*' -> "star"
            else -> null
        },
        field = when (address) {
            AddressKeys.URL -> "url"
            AddressKeys.EMAIL -> "email"
            AddressKeys.NONE -> "text"
        },
        voice = holdVoice,
        holdVoice = holdVoice,
        langKey = langKey,
        // qwerty.xml asks it only of a table (确定 in Return's place): elsewhere the first key typed and
        // the word committed would each give a new environment, so a scene made again the same as before
        composing = composing && table,
        lookingUp = lookingUp,
    )
}

/**
 * The letters' texts the 26 keys' bindings read ([org.fcitx.fcitx5.android.scene.engine.Texts]):
 * `{alt:x}` each letter's corner as the keyboard shows it ([corner]: null, none), `{root:x}` what a
 * table shows over it ([TableKeyboard.label]; the letter as [letter] shows it where the table has none).
 */
object SceneLetters {
    private val LETTERS = 'a'..'z'

    fun alts(corner: (Char) -> String?): Map<String, String> =
        LETTERS.mapNotNull { c -> corner(c)?.let { c.toString() to it } }.toMap()

    /**
     * `root:x`'s, for Texts.other: none where [table] shows no roots; "" for a letter with none, which the
     * engine's `{root:x}` falls back on `{case:x}` for (the letter as 拼音键字母大小写 and Shift show it).
     */
    fun roots(table: TableKeyboard?): Map<String, String> =
        if (table?.labelled != true) emptyMap() else LETTERS.associate { c -> "root:$c" to table.label(c).orEmpty() }
}

/** The marks' settings ([MarkSettings]) from the app's: each key's two swipe slots, 上滑 and 下滑, where each draws. */
object SceneMarks {

    /**
     * [names]' marks (a layout's keys', [names]) as [slots] has them, 上滑 and 下滑 on as [ways] are,
     * drawn at [upAt] and [downAt]; a character key's shortcuts drawn where [charShortcuts] (a table's
     * roots take their room). A long press's first is the key's corner, unless [firsts] has the key:
     * the first of a long press the user set by the key's name (null: turned off), or a function key's.
     */
    @Suppress("LongParameterList")
    fun settings(
        slots: SwipeSlots,
        ways: KeySwipe.Ways,
        upAt: KeyMarks.At,
        downAt: KeyMarks.At,
        charShortcuts: Boolean,
        names: Collection<String>,
        firsts: Map<String, String?> = emptyMap(),
    ): MarkSettings {
        val marks = HashMap<String, MarkContent>()
        // the names as a layout reads them, one a key (`full/q`, `fn/space`): a slot stored by the same
        for (name in names) {
            for (up in listOf(true, false)) {
                val first = if (name in firsts) firsts[name]?.let(MarkContent::Symbol) ?: MarkContent.Empty else MarkContent.Corner
                marks["$name:${if (up) "up" else "down"}"] = when (val slot = slots.resolve(name, up)) {
                    // a function key has no corner: its first is its long press's ([firsts]), or nothing
                    SwipeSlot.First -> if (first == MarkContent.Corner && SwipeSlots.isFunction(name)) MarkContent.Empty else first
                    else -> content(slot.shortcut) ?: MarkContent.Empty
                }
            }
        }
        return MarkSettings(
            upAt = at(upAt),
            downAt = at(downAt),
            charShortcuts = charShortcuts,
            upOn = ways.up,
            downOn = ways.down,
            firstUp = slots.orientation == SwipeOrientation.FirstUp,
            shortcuts = marks,
        )
    }

    /** The shortcut names of [layout]'s keys, its variants' too, and of the [fragments] it includes (a shared bottom row's). */
    fun names(layout: Layout, fragments: Collection<Fragment> = emptyList()): Set<String> =
        (layout.parts.filterIsInstance<Row>() + fragments.flatMap { it.rows })
            .flatMap { it.cells }.filterIsInstance<Key>()
            .flatMap { k -> listOf(k.attrs.shortcut) + k.variants.map { it.attrs.shortcut } }
            .filterNotNull().toSet()

    fun content(s: KeyShortcut): MarkContent? = when (s) {
        is KeyShortcut.Command -> MarkContent.Icon(icon(s.command))
        // at most two characters as seen, the rest an ellipsis (要做 1): the bubble shows it whole
        is KeyShortcut.Text -> MarkContent.Text(KeyMarkText.of(s.text))
        KeyShortcut.None -> null
    }

    /** A command's icon as the scene names it ([MarkSettings.PRESET]'s names); the app draws each name's drawable. */
    fun icon(command: KeyCommand): String = when (command) {
        KeyCommand.SelectAll -> "select-all"
        KeyCommand.Copy -> "content-copy"
        KeyCommand.Cut -> "content-cut"
        KeyCommand.Paste -> "content-paste"
        KeyCommand.Clear -> "delete-sweep"
        KeyCommand.EditMenu -> "cursor-move"
        else -> command.key.replace('_', '-')
    }

    internal fun at(a: KeyMarks.At): SceneKeyMarks.At = SceneKeyMarks.At.valueOf(a.name)
}
