/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

/** What a key's swipe does on its shortcut layer: a command, a piece of text, or nothing. */
sealed interface KeyShortcut {

    data class Command(val command: KeyCommand) : KeyShortcut

    /** Committed as it is, spaces and line breaks too. */
    data class Text(val text: String) : KeyShortcut {
        init {
            require(text.isNotEmpty())
        }
    }

    /** Set to nothing: a swipe this way is taken as a tap (a character key's) or as before (a function key's). */
    data object None : KeyShortcut

    companion object {
        /** The longest text a shortcut types: a signature or an address, not a document. */
        const val MAX_TEXT = 200
    }
}

/**
 * The commands a shortcut can run, by the [key] they are stored under (never renamed: a stored
 * key no command has any more reads as no shortcut). Offered in this order, grouped by [group].
 */
enum class KeyCommand(val key: String, val group: Group) {
    SelectAll("select_all", Group.Edit),
    Copy("copy", Group.Edit),
    Cut("cut", Group.Edit),
    Paste("paste", Group.Edit),
    Undo("undo", Group.Edit),
    Redo("redo", Group.Edit),
    Clear("clear", Group.Edit),
    Restore("restore", Group.Edit),
    FieldStart("field_start", Group.Edit),
    FieldEnd("field_end", Group.Edit),
    EditMenu("edit_menu", Group.Edit),
    Clipboard("clipboard", Group.Panel),
    Emoji("emoji", Group.Panel),
    Voice("voice", Group.Panel),
    TradSimp("trad_simp", Group.Switch),
    ChooseKeyboard("choose_keyboard", Group.Switch),
    HideKeyboard("hide_keyboard", Group.Switch);

    enum class Group { Edit, Panel, Switch }

    /** Whether it reads the field's text out: not run in a password, which the editor will not give. */
    val readsField: Boolean get() = this == Copy || this == Cut

    companion object {
        fun ofKey(key: String): KeyCommand? = entries.firstOrNull { it.key == key }
    }
}
