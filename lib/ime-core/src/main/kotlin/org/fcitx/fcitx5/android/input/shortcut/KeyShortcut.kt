/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.engine.phrase.TextVariables
import java.util.Calendar

/** What a key's swipe does on its shortcut layer. */
sealed interface KeyShortcut {

    data class Command(val command: KeyCommand) : KeyShortcut

    /** Committed as is; with [variables], `$year` and the like filled in when run or drawn. */
    data class Text(val text: String, val variables: Boolean = false) : KeyShortcut {
        init {
            require(text.isNotEmpty())
        }

        /** At most [MAX_TEXT] chars where it has [variables]. */
        fun typed(now: Calendar): String = if (variables) clip(TextVariables.expand(text, now), MAX_TEXT) else text
    }

    /** A character key's swipe taken as a tap, a function key's as before. */
    data object None : KeyShortcut

    companion object {
        /** A signature or an address, not a document. */
        const val MAX_TEXT = 200

        /** One fewer where the last would be half a surrogate pair; a longer emoji sequence may still be cut. */
        fun clip(text: String, length: Int): String =
            if (text.length > length && length > 0 && text[length - 1].isHighSurrogate()) text.take(length - 1) else text.take(length)
    }
}

/** By the stored [key], never renamed (a line with an unknown key is skipped and dropped on the next save); offered in this order. */
enum class KeyCommand(val key: String, val group: Group, val stroke: Char? = null) {
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

    /** Only by a swipe, selecting as the finger moves sideways; run any other way, nothing. */
    SwipeSelect("swipe_select", Group.Edit),
    Clipboard("clipboard", Group.Panel),
    QuickInput("quick_input", Group.Panel),
    Emoji("emoji", Group.Panel),
    Voice("voice", Group.Panel),
    TradSimp("trad_simp", Group.Switch),
    ChooseKeyboard("choose_keyboard", Group.Switch),
    HideKeyboard("hide_keyboard", Group.Switch),

    /* A stroke for 笔画筛选, as one drawn on the keys; nothing where the input method filters by none. Fixed: never the stroke wanted next. */
    StrokeHeng("stroke_h", Group.Stroke, 'h'),
    StrokeShu("stroke_s", Group.Stroke, 's'),
    StrokePie("stroke_p", Group.Stroke, 'p'),
    StrokeDian("stroke_n", Group.Stroke, 'n'),
    StrokeZhe("stroke_z", Group.Stroke, 'z');

    enum class Group { Edit, Panel, Switch, Stroke }

    /** Not run in a password, which the editor will not give. */
    val readsField: Boolean get() = this == Copy || this == Cut

    companion object {
        fun ofKey(key: String): KeyCommand? = entries.firstOrNull { it.key == key }
    }
}
