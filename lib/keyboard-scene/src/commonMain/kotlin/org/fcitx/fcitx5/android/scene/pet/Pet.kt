/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.pet

/** A theme's one `<pet>`: frames on a corner of the keys or at the bar's end, a state each kind of key. */
data class Pet(
    val place: Place,
    val align: Align,
    val sizeDp: Double,
    val offsetXDp: Double,
    val offsetYDp: Double,
    val states: Map<PetState.Name, PetState>,
) {
    enum class Place(val id: String) { KEYBOARD("keyboard"), BAR_END("bar-end") }

    enum class Align(val id: String, val top: Boolean, val start: Boolean) {
        TOP_START("top-start", true, true),
        TOP_END("top-end", true, false),
        BOTTOM_START("bottom-start", false, true),
        BOTTOM_END("bottom-end", false, false),
    }

    val idle: PetState get() = states.getValue(PetState.Name.IDLE)

    /** Its files through [path] (a pack's directory). */
    fun withPaths(path: (String) -> String): Pet = copy(states = states.mapValues { it.value.copy(sheet = path(it.value.sheet)) })
}

/** One state's sprite sheet: [frames] left to right, top to bottom, [columns] a row. */
data class PetState(val sheet: String, val frames: Int, val columns: Int, val fps: Int, val loop: Boolean) {
    val rows: Int get() = (frames + columns - 1) / columns

    /** In the order a phone short of memory drops them, [IDLE] last. */
    enum class Name(val id: String) {
        IDLE("idle"), TYPING("typing"), DELETE("delete"), ENTER("enter"), SPACE("space"), SLEEP("sleep"), WAKE("wake");

        companion object {
            fun of(id: String): Name? = entries.firstOrNull { it.id == id }
        }
    }
}

/** What kind of key was pressed, the one thing the pet follows. */
enum class PetTrigger(val state: PetState.Name) {
    TYPING(PetState.Name.TYPING), DELETE(PetState.Name.DELETE), ENTER(PetState.Name.ENTER), SPACE(PetState.Name.SPACE)
}
