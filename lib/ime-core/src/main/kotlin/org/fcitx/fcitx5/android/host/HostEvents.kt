/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym

/** A candidate as the frontend sends it (`CandidateEntity`): its text and comment through the output filters. */
typealias CandidateEntry = CandidateWord

/** fcitx's `CandidateLayoutHint`, as the paged list carries it. */
enum class LayoutHint { NotSet, Vertical, Horizontal }

/**
 * What the host tells the app, one for each of the frontend's callbacks (`androidfrontend.cpp`,
 * `native-lib.cpp`), with the values those built for the app (`object-conversion.h`). The app
 * takes these as they are: there is no second event type.
 */
sealed class HostEvent {
    data class Commit(val text: String, val cursor: Int) : HostEvent()
    data class ClientPreedit(val text: FormattedText) : HostEvent()
    data class InputPanel(
        val preedit: FormattedText,
        val auxUp: FormattedText,
        val auxDown: FormattedText,
        /** Quick input's panel ([TempPanel.quickPhrase]). */
        val quickPhrase: Boolean = false,
    ) : HostEvent() {
        companion object {
            val EMPTY = InputPanel(FormattedText.Empty, FormattedText.Empty, FormattedText.Empty)
        }
    }

    /** The bulk list's first 16 (`updateCandidatesBulk`, `androidfrontend.cpp:94-123`). */
    data class Candidates(val total: Int, val candidates: List<CandidateEntry>) : HostEvent() {
        companion object {
            val EMPTY = Candidates(0, emptyList())
        }
    }

    /** The page, for the hardware keyboard's window (`updateCandidatesPaged`, `:125-148`), with the list's layout hint. */
    data class Paged(
        val candidates: List<CandidateEntry>,
        val cursorIndex: Int,
        val hasPrev: Boolean,
        val hasNext: Boolean,
        val layoutHint: LayoutHint = LayoutHint.NotSet,
    ) : HostEvent() {
        companion object {
            val EMPTY = Paged(emptyList(), -1, hasPrev = false, hasNext = false)
        }
    }

    /** A key for the app: not taken, or forwarded (timestamp -1) (`AndroidFrontend::keyEvent`/`forwardKey`, `:385-398`). */
    data class Key(val sym: Int, val states: Int, val unicode: Int, val up: Boolean, val timestamp: Int) : HostEvent() {
        val keySym: KeySym get() = KeySym(sym)
        val keyStates: KeyStates get() = KeyStates.of(states)
    }

    data class DeleteSurrounding(val before: Int, val after: Int) : HostEvent()

    /** The active context's input method activated, or the group changed under an unfocused one (`:327-348`). */
    data class InputMethodChanged(val entry: InputMethodEntry) : HostEvent() {
        constructor(im: String) : this(InputMethodEntries.of(im))

        val im: String get() = entry.uniqueName
    }

    /** `InputContextSwitchInputMethod` on the active context (`:349-359`). */
    data class SwitchInputMethod(val reason: SwitchReason, val oldInputMethod: String) : HostEvent()

    /**
     * The active context's status area anew, with its input method (`statusAreaUpdateCallback`, `androidfrontend.cpp:376-379`),
     * and [punctuation]: what each punctuation key types now (`,` to `，`; empty, punctuation off), worked out on the host's
     * thread with the status, so the keyboard switches in this event, before any key after it. Null: not told (a status
     * replayed before the host sent one): the keyboard keeps what it has.
     */
    data class StatusArea(val entries: List<StatusEntry>, val entry: InputMethodEntry, val punctuation: Map<String, String>? = null) : HostEvent() {
        constructor(entries: List<StatusEntry>, im: String, punctuation: Map<String, String>? = null) : this(entries, InputMethodEntries.of(im), punctuation)

        val im: String get() = entry.uniqueName
    }

    /**
     * The punctuation keys (`,` `.`) that now type their own character, not their mark: after a letter
     * or digit with "half width after a letter or number" on ([Punctuation.typesAsItself]); empty, none.
     */
    data class PunctuationAsTyped(val marks: Set<String>) : HostEvent()

    /** A tip (`showTip`): the app shows it on the keyboard, not through its event flow (`Fcitx.showToast`). */
    data class Tip(val text: String) : HostEvent()

    /** The app's host is up (`KotlinHost` sends it once, after its start); the core never sends it. */
    data object Ready : HostEvent()
}

/**
 * Who hears the host's events, on the host thread, in the order the host makes them: commits,
 * deletes and keys at once, the panel once per event. `HostCore.events` runs each listener in
 * turn, as `fcitxEventHandlers` ran (`Fcitx.kt:428-434`); the app's `KotlinHost` is one, and
 * makes its event flow of what it hears.
 */
fun interface HostEvents {
    fun onEvent(event: HostEvent)
}
