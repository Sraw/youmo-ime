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

/** `CandidateEntity`: text and comment through the output filters. */
typealias CandidateEntry = CandidateWord

/** fcitx's `CandidateLayoutHint`. */
enum class LayoutHint { NotSet, Vertical, Horizontal }

/** One per frontend callback, with the values those built for the app; the app takes these as they are. */
sealed class HostEvent {
    data class Commit(val text: String, val cursor: Int) : HostEvent()
    data class ClientPreedit(val text: FormattedText) : HostEvent()
    data class InputPanel(
        val preedit: FormattedText,
        val auxUp: FormattedText,
        val auxDown: FormattedText,
        val quickPhrase: Boolean = false,
        /** [preedit] is the session's code ([Panel.code]): what 编码行 输入框里 may write into the field. */
        val code: Boolean = false,
    ) : HostEvent() {
        companion object {
            val EMPTY = InputPanel(FormattedText.Empty, FormattedText.Empty, FormattedText.Empty)
        }
    }

    /** `updateCandidatesBulk`'s first 16; [predicting]: 联想's with nothing typed, the bar shows its ×. */
    data class Candidates(val total: Int, val candidates: List<CandidateEntry>, val predicting: Boolean = false) : HostEvent() {
        companion object {
            val EMPTY = Candidates(0, emptyList())
        }
    }

    /** `updateCandidatesPaged`, for the hardware keyboard's window. */
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

    /** Not taken, or forwarded (timestamp -1). */
    data class Key(val sym: Int, val states: Int, val unicode: Int, val up: Boolean, val timestamp: Int) : HostEvent() {
        val keySym: KeySym get() = KeySym(sym)
        val keyStates: KeyStates get() = KeyStates.of(states)
    }

    data class DeleteSurrounding(val before: Int, val after: Int) : HostEvent()

    /** Or the group changed under an unfocused one. */
    data class InputMethodChanged(val entry: InputMethodEntry) : HostEvent() {
        constructor(im: String) : this(InputMethodEntries.of(im))

        val im: String get() = entry.uniqueName
    }

    /** `InputContextSwitchInputMethod` on the active context. */
    data class SwitchInputMethod(val reason: SwitchReason, val oldInputMethod: String) : HostEvent()

    /** [punctuation] computed with the status so the keyboard switches before the next key; null: not told, keep it. [fullWidth]: keys type full width too. */
    data class StatusArea(
        val entries: List<StatusEntry>,
        val entry: InputMethodEntry,
        val punctuation: Map<String, String>? = null,
        val fullWidth: Boolean = false,
    ) : HostEvent() {
        constructor(entries: List<StatusEntry>, im: String, punctuation: Map<String, String>? = null, fullWidth: Boolean = false) :
            this(entries, InputMethodEntries.of(im), punctuation, fullWidth)

        val im: String get() = entry.uniqueName
    }

    /** After a letter or digit with "half width after a letter or number" on; empty, none. */
    data class PunctuationAsTyped(val marks: Set<String>) : HostEvent()

    /** Shown on the keyboard, not through the event flow. */
    data class Tip(val text: String) : HostEvent()

    /** `KotlinHost` sends it once, after its start; the core never sends it. */
    data object Ready : HostEvent()
}

/** On the host thread, in order: commits, deletes and keys at once, the panel once per event. */
fun interface HostEvents {
    fun onEvent(event: HostEvent)
}
