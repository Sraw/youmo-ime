/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.keys

/**
 * Every key's two swipe slots as built in (060-swipe-slots' presets, 060-keys-unify: one table, the
 * app's KeyShortcutPreset and the scene's default marks both from it). A character key's long press's
 * first the way the orientation puts it, its letters' command (a select all, c copy…) the other; a
 * function key its command, the same on every keyboard.
 */
object SlotDefaults {

    sealed interface Slot {
        /** Its long press's first. */
        data object First : Slot

        /** A command, by KeyCommand's key (`select_all`, `edit_menu`). */
        data class Command(val key: String) : Slot

        data object None : Slot
    }

    private val LETTERS = mapOf('a' to "select_all", 'c' to "copy", 'x' to "cut", 'v' to "paste", 'z' to "undo", 'y' to "redo")

    // a group of several: the one most asked for
    private val PRIORITY = listOf("paste", "copy", "undo", "select_all", "cut", "redo")

    private val FUNCTION = mapOf(
        "space" to true to "edit_menu",
        "backspace" to true to "clear",
        "backspace" to false to "restore",
    )

    /** [id]'s slot [up] (else down), the first up where [firstUp]. */
    fun of(id: KeyId, up: Boolean, firstUp: Boolean): Slot {
        if (id.isFunction) return FUNCTION[id.key to up]?.let(Slot::Command) ?: Slot.None
        if (up == firstUp) return Slot.First
        return command(id)?.let(Slot::Command) ?: Slot.None
    }

    /** The command a character key's letters stand for, on the way its first is not. */
    fun command(id: KeyId): String? = when (id.form) {
        "full", "g14", "g18", "zyfull" -> ofLetters(id.key)
        "t9" -> id.key.singleOrNull()?.let(KeyboardVariant.STANDARD_NINE::get)?.let(::ofLetters)
        // 注音九键's keys are no letters, the number pad's digits none either
        else -> null
    }

    fun ofLetters(letters: String): String? {
        val commands = letters.lowercase().mapNotNull { LETTERS[it] }
        return PRIORITY.firstOrNull { it in commands }
    }

    /** A command's icon as the scene names it; the app draws each name's drawable. */
    fun icon(command: String): String = when (command) {
        "select_all" -> "select-all"
        "copy" -> "content-copy"
        "cut" -> "content-cut"
        "paste" -> "content-paste"
        "clear" -> "delete-sweep"
        "edit_menu" -> "cursor-move"
        else -> command.replace('_', '-')
    }
}
