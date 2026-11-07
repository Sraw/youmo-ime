/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.scene.KeyNode

/**
 * One of a layout's actions (layout-1's words, LayoutReader's ACTION_PATTERNS) read as what it does
 * (060-render-p2 U4 B): the app turns each into its key action, [Placeholder]s by the keyboard's own
 * table (`action:zhuyin`, a tone); a key's [Group], from its `letters=` (060-render-v2 §15).
 */
sealed interface SceneAction {
    /** One character, typed as a key is (`,`, the 18 keys' `:`). */
    data class Key(val text: String) : SceneAction

    /** A key by its keysym's name (`sym:BackSpace`, `sym:KP_Add`). */
    data class Sym(val name: String) : SceneAction

    /** Another keyboard or page (`layout:Symbol`, `layout:Number`, `layout:Text`). */
    data class Layout(val name: String) : SceneAction

    /** A picker's page (`picker:Emoji`); [name] null (`picker:`), the one shown last. */
    data class Picker(val name: String?) : SceneAction

    /** [text] committed as it is (`commit:.com`). */
    data class Commit(val text: String) : SceneAction

    /** The character [codePoint] typed as a key (`code:U+FF0C`). */
    data class Code(val codePoint: Int) : SceneAction

    /** A keypad's digit (`keypad:1`): typed, or its candidate picked while typing. */
    data class Keypad(val digit: Int) : SceneAction

    /** Shift (`caps`), locked (`caps:lock`). */
    data class Caps(val lock: Boolean) : SceneAction

    /** A swipe shortcut's command by its key (`shortcut:copy`). */
    data class Shortcut(val name: String) : SceneAction

    /** The candidates' reading (`reading:pinyin`); [style] null (`reading`), the next. */
    data class Reading(val style: String?) : SceneAction

    /** The emoji search (`emoji-search:on`). */
    data class EmojiSearch(val arg: String) : SceneAction

    /** What the app does of itself, by its word (`lang`, `quickphrase`, `space-long-press`…). */
    data class Command(val name: String) : SceneAction

    /** `action:<name>`: what the keyboard's own table says ([name] `zhuyin`, `zhuyin-slot`, `tone-1`…). */
    data class Placeholder(val name: String) : SceneAction

    /**
     * The group of [letters] typed as one key (a key's `letters=` with no `send=`, the 14 and 18
     * keys'): its code as the engine reads the keyboard's groups, by where the finger fell. Written as
     * no word: the key's letters say it.
     */
    data class Group(val letters: String) : SceneAction

    companion object {
        private val NAMED = Regex("([a-z-]+):(.*)")

        /** [raw] read; null for nothing the vocabulary has (the reader lets none through). */
        fun of(raw: String): SceneAction? {
            if (raw.isNotEmpty() && raw.codePointCount(0, raw.length) == 1) return Key(raw)
            val m = NAMED.matchEntire(raw)
            if (m == null) return if (raw == "caps") Caps(false) else if (raw == "reading") Reading(null) else Command(raw)
            val (kind, arg) = m.destructured
            return when (kind) {
                "sym" -> Sym(arg)
                "layout" -> Layout(arg)
                "picker" -> Picker(arg.ifEmpty { null })
                "commit" -> Commit(arg)
                "code" -> arg.takeIf { it.startsWith("U+") }?.substring(2)?.toIntOrNull(HEX)?.let(::Code)
                "keypad" -> arg.toIntOrNull()?.takeIf { it in 0..9 }?.let(::Keypad)
                "caps" -> if (arg == "lock") Caps(true) else null
                "shortcut" -> Shortcut(arg)
                "reading" -> Reading(arg.ifEmpty { null })
                "emoji-search" -> EmojiSearch(arg)
                "action" -> Placeholder(arg)
                else -> null
            }
        }

        private const val HEX = 16
    }
}

/**
 * What a key the scene draws does, all of it from its layout (060-render-p2 U4 B): its press, long
 * press, repeat, swipe and double tap ([SceneAction]s), the bubble its press shows, its long press's
 * keys or menu, its sound, its swipes' axes and its shortcut's name. Made from its node ([of]), equal
 * for a node that does the same (a scene made anew keeps the keys made for the last).
 *
 * The bubble and the long press's keys follow the old keys' (KeyDefPreset): a key that types (a
 * letter's or a group's face, the comma, the full stop, an address's key) shows a bubble of what
 * it types, with its corner where it swipes one; a function key (分词, 符, 123, ⌫) shows none. A
 * key that types one character and names no long press offers its character's preset (SymbolKey's).
 */
data class SceneBehavior(
    val id: String,
    val role: String?,
    val press: SceneAction?,
    val longPress: SceneAction?,
    val repeat: Boolean,
    val swipe: SceneAction?,
    val doubleTap: SceneAction?,
    val bubble: Bubble?,
    val popup: LongPopup?,
    val menu: List<MenuChoice>,
    /** `standard`, `space`, `delete`, `return` (layout-1's sound=). */
    val sound: String,
    /** Its swipes go both ways, each locked to its axis (the space's cursor, backspace's selection). */
    val bothWays: Boolean,
    val shortcut: String?,
) {
    /** The bubble over the key pressed: [content], and the [corner] a swipe types (AltPreview's) or null. */
    data class Bubble(val content: String, val corner: String?)

    /** The keys a long press offers. */
    sealed interface LongPopup {
        /** The user's 按键字符 for [label] (PopupOverrides), mapped as punctuation where [transformPunctuation]. */
        data class Preset(val label: String, val transformPunctuation: Boolean) : LongPopup

        /** These [items], each committed as it is where [asIs]. */
        data class Listed(val items: List<String>, val asIs: Boolean) : LongPopup

        /**
         * A group's [letters] around its corner, the symbols of each above (GroupedKeyboard.longPressOf),
         * made as it opens: a key with `letters=` and no popup written, or `from="group"`.
         */
        data class Letters(val letters: String) : LongPopup

        /** The keyboard's own, made as it opens (`from="nine"`, a zhuyin key's `from="group"`). */
        data class Composed(val from: String) : LongPopup
    }

    /** A long press's menu item: [label], [icon] (the scene's name), what it does. */
    data class MenuChoice(val label: String, val icon: String?, val action: SceneAction?)

    /** Whether the key types (letters, groups, punctuation, an address's keys): a bubble shown as it is pressed. */
    val types: Boolean get() = bubble != null

    companion object {
        /** The keys that type though their kind is a function key's (rules C §4, KeyDefPreset's SymbolKey and AddressKey). */
        val TYPING_ROLES = setOf("comma", "period", "slash", "at", "dot", "domain", "semicolon", "star")

        fun of(node: KeyNode): SceneBehavior {
            val a = node.actions
            val press = a["send"]?.let(SceneAction::of) ?: node.letters?.let(SceneAction::Group)
            val swipe = a["swipe-up"]?.let(SceneAction::of)
            val typing = node.role in TYPING_ROLES || (node.kind == "letter" && !node.label.isNullOrEmpty())
            val bubble = if (!typing) null else Bubble(content(node, press), swipe?.let(::typed))
            return SceneBehavior(
                id = node.id,
                role = node.role,
                press = press,
                longPress = a["long-press"]?.let(SceneAction::of),
                repeat = a["repeat"] == "true",
                swipe = swipe,
                doubleTap = a["double-tap"]?.let(SceneAction::of),
                bubble = bubble,
                popup = popupOf(node, press, typing),
                menu = node.menu.map { MenuChoice(it.label, it.icon, SceneAction.of(it.action)) },
                sound = node.sound,
                bothWays = a["swipe-axis"] == "both",
                shortcut = node.shortcut,
            )
        }

        // a letter's or a group's face; the comma's and an address's key's what it types (the comma's
        // face is as punctuation maps it: the app maps the bubble's as it shows it)
        private fun content(node: KeyNode, press: SceneAction?): String =
            if (node.kind == "letter") node.label.orEmpty() else press?.let(::typed) ?: node.label.orEmpty()

        /** What [action] types as text: a character, a commit's, a keypad's digit; null for anything else. */
        fun typed(action: SceneAction): String? = when (action) {
            is SceneAction.Key -> action.text
            is SceneAction.Commit -> action.text
            is SceneAction.Keypad -> action.digit.toString()
            is SceneAction.Code -> String(Character.toChars(action.codePoint))
            else -> null
        }

        private fun popupOf(node: KeyNode, press: SceneAction?, typing: Boolean): LongPopup? {
            val p = node.popup
            // a group's letters' own, unless the layout wrote another (060-render-v2 §15)
            val letters = node.letters
            if (letters != null && (p == null || p.from == "group")) return LongPopup.Letters(letters)
            val transform = p?.options?.get("transform-punctuation") != "false"
            return when (p?.from) {
                "none" -> null
                "list" -> LongPopup.Listed(p.items.map { it.first }, p.options["as-is"] == "true")
                "preset" -> (p.preset ?: (press as? SceneAction.Key)?.text)?.let { LongPopup.Preset(it, transform) }
                null -> (press as? SceneAction.Key)?.takeIf { typing }?.let { LongPopup.Preset(it.text, true) }
                else -> LongPopup.Composed(p.from)
            }
        }
    }
}
