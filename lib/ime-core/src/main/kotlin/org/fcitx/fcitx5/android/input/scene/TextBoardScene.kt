/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.engine.table.TableKeyboard
import org.fcitx.fcitx5.android.input.keyboard.AddressKeys
import org.fcitx.fcitx5.android.input.keyboard.KeyMarks
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayers
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
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

    /** `root:x`'s, for Texts.other: none where [table] shows no roots. */
    fun roots(table: TableKeyboard?, letter: (Char) -> String): Map<String, String> =
        if (table?.labelled != true) emptyMap() else LETTERS.associate { c -> "root:$c" to (table.label(c) ?: letter(c)) }
}

/** The marks' settings ([MarkSettings]) from the app's: the swipe layers, where marks go, the user's shortcuts. */
object SceneMarks {

    /**
     * [names]' shortcuts (a layout's keys', [names]) as [shortcuts] has them; a character key's
     * shortcut drawn where [charShortcuts] (a table's roots take its room) and a swipe reaches
     * shortcuts, as KeySwipes.showMarks drew them.
     */
    fun settings(
        shortcuts: KeyShortcuts,
        layers: SwipeLayers,
        spots: KeyMarks.Spots,
        charShortcuts: Boolean,
        names: Collection<String>,
    ): MarkSettings {
        val marks = HashMap<String, MarkContent>()
        for (name in names) {
            if ('/' in name) {
                content(shortcuts.resolve(name))?.let { marks[name] = it }
            } else {
                content(shortcuts.resolve(KeyShortcuts.functionKey(name, up = true)))?.let { marks["$name:up"] = it }
                content(shortcuts.resolve(KeyShortcuts.functionKey(name, up = false)))?.let { marks["$name:down"] = it }
            }
        }
        return MarkSettings(
            symbolAt = at(spots.symbol),
            shortcutAt = at(spots.shortcut),
            charShortcuts = charShortcuts && layers.wayOf(SwipeLayers.Layer.Shortcut) != null,
            upOn = layers.up != SwipeLayers.Layer.Off,
            downOn = layers.down != SwipeLayers.Layer.Off,
            shortcuts = marks,
        )
    }

    /** The shortcut names of [layout]'s keys, its variants' too. */
    fun names(layout: Layout): Set<String> = layout.parts.filterIsInstance<Row>()
        .flatMap { it.cells }.filterIsInstance<Key>()
        .flatMap { k -> listOf(k.attrs.shortcut) + k.variants.map { it.attrs.shortcut } }
        .filterNotNull().toSet()

    fun content(s: KeyShortcut): MarkContent? = when (s) {
        is KeyShortcut.Command -> MarkContent.Icon(icon(s.command))
        is KeyShortcut.Text -> MarkContent.Text(s.text)
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

    private fun at(a: KeyMarks.At): SceneKeyMarks.At = SceneKeyMarks.At.valueOf(a.name)
}

/** A key's words for a screen reader ([org.fcitx.fcitx5.android.scene.KeyNode.a11y]) as the app says them. */
object SceneA11y {
    private val RESOURCE = Regex("a11y_[a-z0-9_]+")

    /**
     * [a11y] as it is, or, where the layout named one of the app's strings (`a11y_key_shift`), that
     * string ([string]: null where the app has none, the [label] then); nothing at all, the label.
     */
    fun spoken(a11y: String, label: String?, string: (String) -> String?): String = when {
        RESOURCE.matches(a11y) -> string(a11y) ?: label ?: a11y
        a11y.isEmpty() -> label.orEmpty()
        else -> a11y
    }
}
