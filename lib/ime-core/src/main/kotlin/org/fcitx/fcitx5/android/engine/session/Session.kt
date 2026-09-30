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
interface Session {
    fun apply(action: Action): Snapshot

    /**
     * Whether [c] goes into the input now. Other keys are the host's to act on: space and digits
     * pick; the rest are sent as [Action.Key] all the same, to end the input, then typed.
     */
    fun reads(c: Char): Boolean

    /**
     * Whether what the user picks is learned. The host turns it off where the app asks it not to
     * (a password, an incognito tab), before the actions typed there.
     */
    var learning: Boolean

    /**
     * The candidates from the [from]th of all, at most [count]: for a host that shows more than
     * the page, such as fcitx's candidate bar scrolled sideways.
     */
    fun candidates(from: Int, count: Int): List<Choice>
}

/** A candidate as a host lists it, with its [hint] (see [Snapshot.hints]). */
data class Choice(val text: String, val hint: String = "")

/** What a user does. */
sealed class Action {
    /**
     * A key typed. One the engine [reads][Session.reads] goes into the input; any other ends it,
     * committed as the engine would have it, and is the app's (the snapshot is not
     * [handled][Snapshot.handled]): what comes after it does not run on from what came before.
     */
    data class Key(val char: Char) : Action()

    object Backspace : Action()

    /** The candidate at [index] on the page shown (a digit key; space is 0). */
    data class Select(val index: Int) : Action()

    /** The candidate at [index] of all, whatever page is shown (a tap on the host's list). */
    data class Pick(val index: Int) : Action()

    /**
     * Forgets what was learned of the candidate at [index] of all (a long press on it), then
     * reads the input again: see [Snapshot.forgets].
     */
    data class Forget(val index: Int) : Action()

    object NextPage : Action()
    object PreviousPage : Action()

    /** Commits what was typed, as typed (enter). */
    object CommitRaw : Action()

    /** Drops everything: the input, and what was committed as context (the cursor moved away). */
    object Reset : Action()

    /**
     * Drops everything as [Reset] does, then takes [before], the text before the cursor, as what
     * the input to come follows: what the host read off the editor, where the engine committed
     * nothing yet (a field focused, the cursor put somewhere).
     */
    data class Context(val before: String) : Action()
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
 * @property total how many candidates there are in all, -1 if not yet known
 * @property first the index among all of the first candidate of the page shown
 * @property forgets whether the candidates are the engine's to [forget][Action.Forget]: not a
 *   prediction, nor where nothing is learned
 * @property labels the keys picking the candidates shown, in order, where they are not the
 *   digits (电报码's `qwertyuiop`, its codes being digits); empty for the digits
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
    val total: Int = candidates.size,
    val first: Int = 0,
    val forgets: Boolean = false,
    val labels: String = "",
)
