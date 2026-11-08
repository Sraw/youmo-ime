/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.rules.KeyNames

/**
 * A key the scene draws as a screen reader is told it (060-render-p2 S3): all of it from the scene's
 * key, none from a view. [text] its label as drawn, [description] what TalkBack reads ([SceneA11y.spoken]),
 * [resourceName] the old view's id (`button_space`, [KeyNames.RESOURCES]; null for one that had none, a
 * letter), [state] Shift's as the app says it, [alt] its corner as drawn, kept apart from the label and
 * not read out (an extra, [SceneA11y.EXTRA_ALT]: U6), [id] the scene's, [enabled] whether it takes a press.
 */
data class KeySpeech(
    val text: String,
    val description: String,
    val resourceName: String?,
    val state: String?,
    val alt: String?,
    val id: String,
    val enabled: Boolean,
)

/** A key's words for a screen reader ([KeyNode.a11y]) as the app says them. */
object SceneA11y {
    private val RESOURCE = Regex("a11y_[a-z0-9_]+")

    // the keys said with their state in their words ([onOrOff])
    private val TOGGLES = setOf("reading", "lock")

    /** The node's extra holding the key's corner ([KeySpeech.alt]): UAT's (Dumper's `alt`), TalkBack reads no extras. */
    const val EXTRA_ALT = "youmo.alt"

    /** The node's extra holding the scene's key id (`q`, `space`): what UAT and tests find a key by (Dumper's `key`). */
    const val EXTRA_KEY = "youmo.key"

    /**
     * The class a key's node says it is: the old key views' (a FrameLayout), for which TalkBack says no
     * role ("button"), so the keys read as they did.
     */
    const val CLASS_NAME = "android.widget.FrameLayout"

    /** The app's strings for Shift's states, as the views' state descriptions were (TextKeyboard, T9Keyboard). */
    const val SHIFT_ONCE = "a11y_state_shift_once"
    const val CAPS_LOCK = "a11y_state_caps_lock"

    /**
     * [a11y] as it is, or, where the layout named one of the app's strings (`a11y_key_shift`), that
     * string ([string]: null where the app has none, the [label] then); nothing at all, the label.
     */
    fun spoken(a11y: String, label: String?, string: (String) -> String?): String = when {
        RESOURCE.matches(a11y) -> string(a11y) ?: label ?: a11y
        a11y.isEmpty() -> label.orEmpty()
        else -> a11y
    }

    /**
     * The name of the app's string a key's [states] are told by: Shift's, once ([KeyState.Shifted]) or
     * locked ([KeyState.Locked]); null for none, and for every other key (a press is no state to tell).
     */
    fun state(role: String?, states: Set<KeyState>): String? = when {
        role != "shift" -> null
        KeyState.Locked in states -> CAPS_LOCK
        KeyState.Shifted in states -> SHIFT_ONCE
        else -> null
    }

    /**
     * [key]'s a11y, a toggle's (handwriting's 注, role `reading`; the symbol panel's lock, `lock`) with its state in it as the views
     * said it (`a11y_key_reading_on`, `…_off`: "候选词注音，已开"), no state description of its own.
     */
    private fun onOrOff(key: KeyNode): String = when {
        key.role !in TOGGLES || !RESOURCE.matches(key.a11y) -> key.a11y
        KeyState.On in key.states || KeyState.Locked in key.states -> key.a11y + "_on"
        else -> key.a11y + "_off"
    }

    /** All a screen reader is told of [key], the app's strings by name from [string]. */
    fun of(key: KeyNode, string: (String) -> String?): KeySpeech = of(key, null, string)

    /** [of] a key of [board] (its layout's id): its resource name may be its board's ([KeyNames.BOARD_RESOURCES]). */
    fun of(key: KeyNode, board: String?, string: (String) -> String?): KeySpeech = KeySpeech(
        text = key.label.orEmpty(),
        description = spoken(onOrOff(key), key.label, string),
        resourceName = board?.let { KeyNames.BOARD_RESOURCES["$it/${key.id}"] } ?: key.role?.let(KeyNames.RESOURCES::get),
        state = state(key.role, key.states)?.let { string(it) ?: it },
        alt = key.alt?.takeIf { it.isNotEmpty() },
        id = key.id,
        enabled = KeyState.Disabled !in key.states,
    )
}
