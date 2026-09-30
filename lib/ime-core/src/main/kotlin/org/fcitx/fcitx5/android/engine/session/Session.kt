/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

/**
 * An input method engine as its host sees it: actions in, what to show and commit out. The host
 * (the fcitx5 addon) turns key events into [Action]s and renders each [Snapshot]; the engine
 * keeps everything else.
 */
fun interface Session {
    fun apply(action: Action): Snapshot
}

/** What a user does. */
sealed class Action {
    /** A key the engine reads: a letter, `'`, or a key a 双拼 scheme uses. */
    data class Key(val char: Char) : Action()

    object Backspace : Action()

    /** The candidate at [index] on the page shown (a digit key; space is 0). */
    data class Select(val index: Int) : Action()

    object NextPage : Action()
    object PreviousPage : Action()

    /** Commits what was typed, as typed (enter). */
    object CommitRaw : Action()

    /** Drops everything: the input, and what was committed as context (the cursor moved away). */
    object Reset : Action()
}

/**
 * The engine's state after an action.
 *
 * @property commit text to put into the app now, or empty
 * @property preedit what is being typed, as the engine reads it
 * @property candidates the page shown
 * @property handled false when the action is the app's to act on (backspace with nothing typed)
 * @property predicting the candidates follow what was committed, and read no input (联想)
 * @property hints shown beside each candidate, empty or one per candidate: what is left of its
 *   code to type, or its whole code when the input does not spell it out (a wildcard in a
 *   table's code, or a table's pinyin lookup)
 */
data class Snapshot(
    val commit: String,
    val preedit: String,
    val candidates: List<String>,
    val page: Int,
    val hasPreviousPage: Boolean,
    val hasNextPage: Boolean,
    val handled: Boolean,
    val predicting: Boolean,
    val hints: List<String> = emptyList(),
)
