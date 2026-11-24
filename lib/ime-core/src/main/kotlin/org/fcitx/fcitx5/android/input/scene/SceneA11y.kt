/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.rules.KeyNames

/**
 * All from the scene's key; [resourceName] the old view's id (null for a letter); [alt] the corner, an extra not read out;
 * [longClick] what holding it does where that has words (the number pad's 返回 locks), else null.
 */
data class KeySpeech(
    val text: String,
    val description: String,
    val resourceName: String?,
    val state: String?,
    val alt: String?,
    val id: String,
    val enabled: Boolean,
    val longClick: String? = null,
)

object SceneA11y {
    private val RESOURCE = Regex("a11y_[a-z0-9_]+")

    // the keys said with their state in their words ([onOrOff])
    private val TOGGLES = setOf("lock")

    /** UAT's (Dumper's `alt`); TalkBack reads no extras. */
    const val EXTRA_ALT = "youmo.alt"

    /** What UAT and tests find a key by (Dumper's `key`). */
    const val EXTRA_KEY = "youmo.key"

    /** The old key views' class, for which TalkBack says no role ("button"), so keys read as they did. */
    const val CLASS_NAME = "android.widget.FrameLayout"

    /** As the views' state descriptions were (TextKeyboard, T9Keyboard). */
    const val SHIFT_ONCE = "a11y_state_shift_once"
    const val CAPS_LOCK = "a11y_state_caps_lock"

    /** A key held to lock (`long-press="panel-lock"`) while locked. */
    const val LOCKED = "a11y_state_locked"

    private const val LOCK_SAID = "a11y_key_panel_lock"

    /** Where the layout names an app string (`a11y_key_shift`), that string, else [label]; nothing at all, the label. */
    fun spoken(a11y: String, label: String?, string: (String) -> String?): String = when {
        RESOURCE.matches(a11y) -> string(a11y) ?: label ?: a11y
        a11y.isEmpty() -> label.orEmpty()
        else -> a11y
    }

    /** Shift's once or locked, a key held to lock's locked; null for none and for every other key (a press is no state to tell). */
    fun state(role: String?, states: Set<KeyState>, longPress: String? = null): String? = when {
        longPress == KeyNames.PANEL_LOCK && KeyState.Locked in states -> LOCKED
        role != "shift" -> null
        KeyState.Locked in states -> CAPS_LOCK
        KeyState.Shifted in states -> SHIFT_ONCE
        else -> null
    }

    // the lock key's words for what a tap does (锁定 / 解锁), its board's ([onOrOff]); none, the plain long click
    private fun longClick(key: KeyNode, string: (String) -> String?): String? =
        if (key.actions["long-press"] != KeyNames.PANEL_LOCK) null
        else string(LOCK_SAID + if (KeyState.Locked in key.states) "_on" else "_off")

    /** A toggle's (the symbol panel's lock) a11y with its state, as the views said it (`_on`/`_off`); a non-resource name as is. */
    private fun onOrOff(key: KeyNode): String = when {
        key.role !in TOGGLES || !RESOURCE.matches(key.a11y) -> key.a11y
        KeyState.On in key.states || KeyState.Locked in key.states -> key.a11y + "_on"
        else -> key.a11y + "_off"
    }

    fun of(key: KeyNode, string: (String) -> String?): KeySpeech = of(key, null, string)

    /** Its resource name may be its board's ([KeyNames.BOARD_RESOURCES]). */
    fun of(key: KeyNode, board: String?, string: (String) -> String?): KeySpeech = KeySpeech(
        text = key.label.orEmpty(),
        description = spoken(onOrOff(key), key.label, string),
        resourceName = board?.let { KeyNames.BOARD_RESOURCES["$it/${key.id}"] } ?: key.role?.let(KeyNames.RESOURCES::get),
        state = state(key.role, key.states, key.actions["long-press"])?.let { string(it) ?: it },
        alt = key.alt?.takeIf { it.isNotEmpty() },
        id = key.id,
        enabled = KeyState.Disabled !in key.states,
        longClick = longClick(key, string),
    )
}
