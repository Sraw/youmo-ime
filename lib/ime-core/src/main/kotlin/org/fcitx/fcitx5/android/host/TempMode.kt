/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.host.Keyboard

/**
 * A temporary input mode (quick phrase, Unicode), fcitx's `TempMode` (`fcitx/tempmode.h`). While
 * one is active on a context it gets every key and invoke action ahead of the engine.
 */
interface TempMode {
    fun isActive(ic: InputContext): Boolean

    /**
     * Whether [e] (normalized, nobody took it before PostInputMethod; releases too, a mode ignores
     * them itself) starts the mode; when it does, the mode makes itself active and the key is
     * filtered and accepted.
     */
    fun trigger(ic: InputContext, e: KeyEvent): Boolean

    /** A key while active; true when used (accepted), false hands it back to the application. */
    fun keyEvent(ic: InputContext, e: KeyEvent): Boolean

    /** An invoke action (a tap in the code row) while active; true when used. */
    fun invokeAction(ic: InputContext, e: InvokeActionEvent): Boolean

    /** Leave the mode: focus out, reset or an input method switch while active. */
    fun reset(ic: InputContext)
}

/**
 * fcitx's `TempModeManager` (`fcitx/tempmodemanager.cpp`): the registered modes in order, the first
 * active one owns the keys.
 */
class TempModeManager(private val modes: List<TempMode>) {

    /**
     * PreInputMethod (`tempmodemanager.cpp:63-72`): an active mode filters every key, so the engine
     * never sees it, and accepts the ones it uses. A grouped keyboard's code, a tap's place with it
     * too, reaches the mode as the letter it stands for ([Keyboard.typedLetter]): the modes read
     * letters, and the code is a symbol no field should get (the 14 keys' BN typed its private use code).
     */
    fun keyEvent(ic: InputContext, e: KeyEvent) {
        val mode = modes.firstOrNull { it.isActive(ic) } ?: return
        e.filter()
        val letter = Keyboard.typedLetter(KeySyms.toUnicode(e.key.sym))
        // KeyEvent is immutable: the mode gets a letter's, its use the one's that came
        val seen = if (letter == null) e else KeyEvent(Key(KeySyms.fromUnicode(letter.code), e.origKey.states), e.release, e.virtual, e.timestamp)
        if (mode.keyEvent(ic, seen)) e.accept()
    }

    /** Invoke actions the same way (`tempmodemanager.cpp:74-82`). */
    fun invokeAction(ic: InputContext, e: InvokeActionEvent) {
        val mode = modes.firstOrNull { it.isActive(ic) } ?: return
        e.filter()
        if (mode.invokeAction(ic, e)) e.accept()
    }

    /** PostInputMethod (`Default` phase, `tempmodemanager.cpp:54-61`): the first mode [e] triggers. */
    fun checkTrigger(ic: InputContext, e: KeyEvent) {
        if (modes.any { it.trigger(ic, e) }) e.filterAndAccept()
    }

    /** Focus out, reset, input method switch (`tempmodemanager.cpp:28-52`): every active mode resets. */
    fun reset(ic: InputContext) {
        modes.forEach { if (it.isActive(ic)) it.reset(ic) }
    }
}
