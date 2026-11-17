/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters

/**
 * When the groups a layout's own keyboard on screen told ([EngineSettings.ownKeys], 060-render-p3 T9b) become the ones
 * the engine reads its codes by, asked before each key (the app's EngineBridge.syncOwnKeys). Other groups than the ones
 * typed on take over as another keyboard does in [KeyboardPair] (061-custom-layout §6, 换分组时上屏): every key but a
 * code of theirs stays the groups' before, a Backspace, a space, the separator or a syllable picked working on what
 * they typed; their first code commits that as Return commits it, then is read by them.
 */
object OwnKeysTurn {

    /** What a key does with the groups told before it. */
    enum class Step {
        /** The groups told are the ones read from this key on (the same ones: nothing changes). */
        TURN,

        /** What the groups before typed committed as Return commits it, then [TURN]. */
        COMMIT_AND_TURN,

        /** The groups before still read this key: it is no code of the ones told. */
        WAIT,
    }

    /**
     * What a key of [codePoint] does where the codes are read by [inUse] (null: no groups told yet) and the keyboard
     * shown told [told]: the first groups ever told, or the same ones, [Step.TURN]; other groups, or none (the keyboard
     * shown took them back, SceneBoard.ownLayout: it sends the built-in groups' codes, which KeyboardPair turns at, as a
     * built-in grouping leaves the groups told as they were), [Step.WAIT] till a code of a layout's own keyboard
     * ([isCode]: theirs, the only such keyboard shown), then [Step.COMMIT_AND_TURN]. A Backspace after none told so
     * edits what was typed, as after other groups (review L4 N1), rather than committing it first.
     */
    fun at(inUse: List<String>?, told: List<String>?, codePoint: Int): Step = when {
        inUse == null || told == inUse -> Step.TURN
        !isCode(codePoint) -> Step.WAIT
        else -> Step.COMMIT_AND_TURN
    }

    /**
     * Whether [codePoint] is a layout's own keyboard's code, or a tap of one with its place ([Keyboard.PLACES]). B1 (the
     * codes as an Action's payload) changes this with KotlinHost.key and Engines.isOwnKeysCode, which asks it.
     */
    fun isCode(codePoint: Int): Boolean = codePoint in NineKeyLetters.CUSTOM_BLOCK || codePoint in Keyboard.PLACES
}
