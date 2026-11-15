/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.view.KeyEvent
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.hardware.HardwareShortcuts
import org.fcitx.fcitx5.android.input.hardware.InputMethodSwitch
import timber.log.Timber

/**
 * The input method as the hardware keyboard goes to it, for [FcitxInputMethodService]: its shortcuts
 * ([HardwareShortcuts]). What it does to fcitx is [post]ed, in order with the keys' own jobs.
 *
 * [inTextField]:
 * a field fcitx is focused in, not one of raw keys (`EditorInfo.isTypeNull`, a game's or a terminal
 * view's), where every key is the app's, Ctrl+Space too. [shortcutsOn]: the user's setting.
 */
class HardwareKeyboard(
    private val post: (suspend HostApi.() -> Unit) -> Unit,
    private val inTextField: () -> Boolean,
    private val shortcutsOn: () -> Boolean,
) {

    private val shortcuts = HardwareShortcuts()

    // the fcitx thread's, touched in its jobs only: the Chinese input method last in use, for Shift or
    // Ctrl+Space to come back to from English
    private var leftForEnglish: String? = null

    // fcitx's input method and the enabled list as last seen on its thread, for Ctrl+Space to tell on the
    // main one whether a toggle has somewhere to go; nothing seen yet, it has
    @Volatile
    private var seen: Pair<String, List<String>>? = null

    /** [keyCode] goes down, [ctrl], [shift] and [others] (Alt, Meta) held or not: whether it goes no further. */
    fun down(keyCode: Int, ctrl: Boolean, shift: Boolean, others: Boolean, repeat: Boolean, timeMs: Long): Boolean {
        if (!shortcutsApply()) {
            if (!repeat) shortcuts.passed(keyOf(keyCode))
            return false
        }
        return applyShortcut(shortcuts.down(keyOf(keyCode), ctrl, shift, others, repeat, timeMs, ::hasToggle))
    }

    /** [keyCode] comes up: whether it goes no further. */
    fun up(keyCode: Int, timeMs: Long): Boolean {
        val decision = shortcuts.up(keyOf(keyCode), timeMs)
        // no longer applying since it went down, nothing is switched; a Space taken then still goes no further
        return applyShortcut(if (shortcutsApply()) decision else decision.copy(action = null))
    }

    private fun shortcutsApply() = shortcutsOn() && inTextField()

    // with nowhere to go, Ctrl+Space is no shortcut: it goes on, through fcitx (whose own TriggerKeys
    // native-lib clears), to the app (an editor's completion)
    private fun hasToggle(): Boolean {
        val (current, enabled) = seen ?: return true
        return InputMethodSwitch.canToggle(current, enabled, InputMethodCatalog.ENGLISH)
    }

    private suspend fun HostApi.see() {
        seen = inputMethodEntryCached.uniqueName to enabledIme().map { it.uniqueName }
    }

    /** A text field started: what Ctrl+Space goes by read again, the list maybe changed in the settings. */
    fun fieldStarted() = post { see() }

    private fun keyOf(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> HardwareShortcuts.Key.SHIFT
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> HardwareShortcuts.Key.CTRL
        KeyEvent.KEYCODE_SPACE -> HardwareShortcuts.Key.SPACE
        else -> HardwareShortcuts.Key.OTHER
    }

    /** Does what [decision] says; whether the key goes no further. */
    private fun applyShortcut(decision: HardwareShortcuts.Decision): Boolean {
        when (decision.action) {
            HardwareShortcuts.Action.TOGGLE -> switchInputMethod(HardwareShortcuts.Action.TOGGLE) { current, enabled ->
                InputMethodSwitch.toggle(current, enabled, leftForEnglish, InputMethodCatalog.ENGLISH)
            }
            HardwareShortcuts.Action.NEXT -> switchInputMethod(HardwareShortcuts.Action.NEXT) { current, enabled ->
                InputMethodSwitch.next(current, enabled)
            }
            null -> {}
        }
        return decision.consumed
    }

    private fun switchInputMethod(
        shortcut: HardwareShortcuts.Action,
        target: (current: String, enabled: List<String>) -> String?
    ) = post job@{
        val from = inputMethodEntryCached.uniqueName
        val enabled = enabledIme().map { it.uniqueName }
        // what was seen as Ctrl went down, a Ctrl held across several Spaces sees it here
        seen = from to enabled
        val to = target(from, enabled) ?: return@job
        Timber.d("hardware keyboard $shortcut: $from -> $to")
        activateIme(to)
    }

    /**
     * fcitx's input method changed, however gone to, or the enabled list was set anew (androidfrontend tells
     * that as a change too): a Chinese one is what Shift comes back to from English, and the list is read again.
     */
    fun inputMethodChanged(im: String) = post {
        if (im != InputMethodCatalog.ENGLISH) leftForEnglish = im
        see()
    }
}
