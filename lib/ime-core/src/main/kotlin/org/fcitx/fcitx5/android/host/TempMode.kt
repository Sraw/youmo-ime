/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.runtime.Keyboard

/** fcitx's `TempMode`: while active it gets every key and invoke action ahead of the engine. */
interface TempMode {
    fun isActive(ic: InputContext): Boolean

    /** [e] normalized, untaken before PostInputMethod, releases too; starting, the mode activates and the key is filtered and accepted. */
    fun trigger(ic: InputContext, e: KeyEvent): Boolean

    /** False hands it back to the application. */
    fun keyEvent(ic: InputContext, e: KeyEvent): Boolean

    /** A tap in the code row. */
    fun invokeAction(ic: InputContext, e: InvokeActionEvent): Boolean

    /** Focus out, reset or an input method switch while active. */
    fun reset(ic: InputContext)

    /** As it came, not as [Keyboard.typedLetter]'s letter. */
    val readsCodes: Boolean get() = false
}

/** fcitx's `TempModeManager`: the first active mode owns the keys. */
class TempModeManager(private val modes: List<TempMode>) {

    /** PreInputMethod: an active mode filters every key; a grouped keyboard's code arrives as its letter (its private use code is no text). */
    fun keyEvent(ic: InputContext, e: KeyEvent) {
        val mode = modes.firstOrNull { it.isActive(ic) } ?: return
        e.filter()
        // a stroke drawn (笔画筛选) filters no mode's: swallowed, the app would type its private-use character
        if (KeySyms.toUnicode(e.key.sym) in Keyboard.STROKES) {
            e.accept()
            return
        }
        val letter = if (mode.readsCodes) null else Keyboard.typedLetter(KeySyms.toUnicode(e.key.sym))
        // KeyEvent is immutable: the mode gets a letter's, its use the one's that came
        val seen = if (letter == null) e else KeyEvent(Key(KeySyms.fromUnicode(letter.code), e.origKey.states), e.release, e.virtual, e.timestamp, e.payload)
        if (mode.keyEvent(ic, seen)) e.accept()
    }

    fun invokeAction(ic: InputContext, e: InvokeActionEvent) {
        val mode = modes.firstOrNull { it.isActive(ic) } ?: return
        e.filter()
        if (mode.invokeAction(ic, e)) e.accept()
    }

    /** PostInputMethod: the first mode [e] triggers. */
    fun checkTrigger(ic: InputContext, e: KeyEvent) {
        if (modes.any { it.trigger(ic, e) }) e.filterAndAccept()
    }

    /** Every active mode resets. */
    fun reset(ic: InputContext) {
        modes.forEach { if (it.isActive(ic)) it.reset(ic) }
    }
}
