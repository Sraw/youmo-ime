/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.core.CoreLog
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.keys.KeyDefaults
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.theme.SoundKey

/** A layout action (LayoutReader's ACTION_PATTERNS) as what it does; [Placeholder]s map by the keyboard's own table. */
sealed interface SceneAction {
    data class Key(val text: String) : SceneAction

    data class Sym(val name: String) : SceneAction

    data class Layout(val name: String) : SceneAction

    /** [name] null (`picker:`): the one shown last. */
    data class Picker(val name: String?) : SceneAction

    data class Commit(val text: String) : SceneAction

    data class Code(val codePoint: Int) : SceneAction

    /** Typed, or its candidate picked while typing. */
    data class Keypad(val digit: Int) : SceneAction

    data class Caps(val lock: Boolean) : SceneAction

    data class Shortcut(val name: String) : SceneAction

    data class EmojiSearch(val arg: String) : SceneAction

    data class Command(val name: String) : SceneAction

    data class Placeholder(val name: String) : SceneAction

    /** A key's `letters=` with no `send=`: its code by where the finger fell, as the engine reads the groups. */
    data class Group(val letters: String) : SceneAction

    companion object {
        private val NAMED = Regex("([a-z-]+):(.*)")

        /** Null for anything outside the vocabulary (logged: the reader lets none through). */
        fun of(raw: String): SceneAction? {
            if (raw.isNotEmpty() && raw.codePointCount(0, raw.length) == 1) return Key(raw)
            val m = NAMED.matchEntire(raw)
            if (m == null) {
                return when (raw) {
                    "caps" -> Caps(false)
                    else -> Command(raw)
                }
            }
            val (kind, arg) = m.destructured
            return named(raw, kind, arg).also { if (it == null) CoreLog.e("scene action: \"$raw\" is none the app has") }
        }

        private fun named(raw: String, kind: String, arg: String): SceneAction? = when (kind) {
            "sym" -> Sym(arg)
            "layout" -> arg.ifEmpty { null }?.let(::Layout)
            "picker" -> Picker(arg.ifEmpty { null })
            "commit" -> Commit(arg)
            // a scalar value: U+10FFFF at most, no surrogate (the reader's own test)
            "code" -> LayoutReader.codePoint(raw)?.let(::Code)
            "keypad" -> arg.toIntOrNull()?.takeIf { it in 0..9 }?.let(::Keypad)
            "caps" -> if (arg == "lock") Caps(true) else null
            "shortcut" -> Shortcut(arg)
            "emoji-search" -> arg.takeIf { it == "on" || it == "off" }?.let(::EmojiSearch)
            "action" -> Placeholder(arg)
            else -> null
        }
    }
}

/**
 * All a drawn key does, from its layout node; equal for nodes that do the same, so a remade scene keeps its keys.
 * A typing key shows a bubble, a function key none, unless its `preview=` says otherwise; a board's
 * `preview="off"` wins over its keys. A one-character key with no long press offers its character's preset.
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
    /** Its sound= and, where that is `standard`, its kind (SoundKey.of). */
    val sound: SoundKey,
    /** Its sound= alone, so a key of no theme sound clicks as it always has. */
    val systemSound: SoundKey,
    /** Each swipe locked to its axis (the space's cursor, backspace's selection). */
    val bothWays: Boolean,
    val shortcut: String?,
) {
    /** [corner]: what a swipe types; a named key's is empty (its resolver's). */
    data class Bubble(val content: String, val corner: String?)

    sealed interface LongPopup {
        /** The user's 按键字符 for [label], mapped as punctuation where [transformPunctuation]. */
        data class Preset(val label: String, val transformPunctuation: Boolean) : LongPopup

        data class Listed(val items: List<String>, val asIs: Boolean) : LongPopup

        /** Made as it opens (GroupedKeyboard.longPressOf): `letters=` with no popup written, or `from="group"`. */
        data class Letters(val letters: String) : LongPopup

        data class Composed(val from: String) : LongPopup
    }

    data class MenuChoice(val label: String, val icon: String?, val action: SceneAction?)

    val types: Boolean get() = bubble != null

    companion object {
        fun of(node: KeyNode): SceneBehavior {
            val a = node.actions
            // a send= written is the tap, letters or not: one that reads as nothing is nothing (logged), not the group's code
            val send = a["send"]
            val press = if (send != null) SceneAction.of(send) else node.letters?.group?.let(SceneAction::Group)
            // a function key acts by its name alone on every keyboard: a layout's long-press= or swipe-up= is ignored
            val function = node.shortcut?.let(KeyId::of)?.isFunction == true
            val swipe = if (function) null else a["swipe-up"]?.let(SceneAction::of)
            val typing = KeyDefaults.typing(node.role, node.kind, node.label, a["preview"])
            val bubble = if (!typing) null else Bubble(content(node, press), cornerOf(node, swipe))
            return SceneBehavior(
                id = node.id,
                role = node.role,
                press = press,
                longPress = (if (function) functionAction(node.shortcut) else a["long-press"])?.let(SceneAction::of),
                repeat = a["repeat"] == "true",
                swipe = swipe,
                doubleTap = a["double-tap"]?.let(SceneAction::of),
                bubble = bubble,
                popup = popupOf(node, press, typing),
                menu = node.menu.map { MenuChoice(it.label, it.icon, SceneAction.of(it.action)) },
                sound = SoundKey.of(node.sound, node.kind),
                systemSound = SoundKey.of(node.sound, "letter"),
                bothWays = a["swipe-axis"] == "both",
                shortcut = node.shortcut,
            )
        }

        private fun functionAction(name: String?): String? =
            name?.let(KeyId::of)?.takeIf { it.isFunction }?.let { KeyDefaults.FUNCTION_ACTIONS[it.key] }

        // a named key's corner is empty whatever its swipe-up= says (its resolver's); an unnamed key's, what swipe-up= types
        private fun cornerOf(node: KeyNode, swipe: SceneAction?): String? = when {
            node.shortcut != null -> ""
            swipe != null -> typed(swipe)
            else -> null
        }

        // the comma's face is as punctuation maps it: the app maps the bubble's as it shows it
        private fun content(node: KeyNode, press: SceneAction?): String =
            if (node.kind == "letter") node.label.orEmpty() else press?.let(::typed) ?: node.label.orEmpty()

        fun typed(action: SceneAction): String? = when (action) {
            is SceneAction.Key -> action.text
            is SceneAction.Commit -> action.text
            is SceneAction.Keypad -> action.digit.toString()
            is SceneAction.Code -> String(Character.toChars(action.codePoint))
            else -> null
        }

        private fun popupOf(node: KeyNode, press: SceneAction?, typing: Boolean): LongPopup? {
            val p = node.popup
            val letters = node.letters?.group
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
