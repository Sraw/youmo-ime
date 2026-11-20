/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters

/**
 * When a layout's own groups ([EngineSettings.ownKeys]) become the ones codes are read by, asked before each key
 * (EngineBridge.syncOwnKeys). New groups take over as another keyboard does in [KeyboardPair]: keys other than
 * their codes still act on the old groups' input; their first code commits it as Return would, then is read by them.
 */
object OwnKeysTurn {

    enum class Step {
        /** The groups told are read from this key on (or are the same). */
        TURN,

        /** Commit the old groups' input as Return does, then [TURN]. */
        COMMIT_AND_TURN,

        /** Not a code of the groups told: the old groups still read it. */
        WAIT,
    }

    /** [Step.TURN] for the first or same groups; for other groups or none told (the keyboard took them back), [Step.WAIT] until a layout's own code ([isCode]), then [Step.COMMIT_AND_TURN]. A Backspace in between edits the input rather than committing it. */
    fun at(inUse: List<String>?, told: List<String>?, codePoint: Int): Step = when {
        inUse == null || told == inUse -> Step.TURN
        !isCode(codePoint) -> Step.WAIT
        else -> Step.COMMIT_AND_TURN
    }

    /** A layout's own keyboard's code, or a tap of one with its place ([Keyboard.PLACES]). */
    fun isCode(codePoint: Int): Boolean = codePoint in NineKeyLetters.CUSTOM_BLOCK || codePoint in Keyboard.PLACES
}
