/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * The voice panel's keys while a hold's last words are still coming: lifted, its 300 ms tail or
 * 识别中 (R16 F4486, owner 2026-10-26). Enter sent at once went in before the words, and a chat
 * sent an empty message: it waits for the session to end instead. Backspace acts at once. The
 * space key's card is not this: there any press only ends the tail (VO-25).
 */
class VoiceTailEnter {
    enum class Key { ENTER, BACKSPACE }

    enum class Press { NOW, AFTER_TEXT }

    /**
     * How the session ended. [NOTHING]: nothing heard, or the model or the microphone failed; the
     * Enter still acts, as it would have with nothing said. [GAVE_UP]: the 15 s give-up; an Enter
     * that long after its press would send whatever draft the field holds, long forgotten.
     */
    enum class End { TYPED, NOTHING, GAVE_UP, DROPPED }

    /** an Enter waiting for the session to end */
    var waiting = false
        private set

    /**
     * [released]: the finger lifted; [over]: the session ended. Only Enter after the lift and
     * before the end waits, once however often pressed (a sentence sent twice otherwise); the
     * caller ends the tail. Pressed while still holding, it acts as it always did.
     */
    fun press(key: Key, released: Boolean, over: Boolean): Press {
        if (key != Key.ENTER || !released || over) return Press.NOW
        waiting = true
        return Press.AFTER_TEXT
    }

    /** The session ended as [end]: whether the waiting Enter acts now, only in the [sameField] it was pressed in. */
    fun ended(end: End, sameField: Boolean): Boolean {
        val acts = waiting && sameField && (end == End.TYPED || end == End.NOTHING)
        waiting = false
        return acts
    }
}
