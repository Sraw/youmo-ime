/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FormattedText

/** A candidate as the frontend sends it (`CandidateEntity`): its text and comment through the output filters. */
data class CandidateEntry(val label: String, val text: String, val comment: String)

/**
 * What the host tells the app, one for each of the frontend's callbacks (`androidfrontend.cpp`,
 * `native-lib.cpp`), each the app's `FcitxEvent` of the same name (S3 maps them one to one).
 */
sealed class HostEvent {
    data class Commit(val text: String, val cursor: Int) : HostEvent()
    data class ClientPreedit(val text: FormattedText) : HostEvent()
    data class InputPanel(val preedit: FormattedText, val auxUp: FormattedText, val auxDown: FormattedText) : HostEvent()

    /** The bulk list's first 16 (`updateCandidatesBulk`, `androidfrontend.cpp:94-123`). */
    data class Candidates(val total: Int, val candidates: List<CandidateEntry>) : HostEvent()

    /** The page, for the hardware keyboard's window (`updateCandidatesPaged`, `:125-148`), with the list's layout hint. */
    data class Paged(
        val candidates: List<CandidateEntry>,
        val cursorIndex: Int,
        val hasPrev: Boolean,
        val hasNext: Boolean,
        val layoutHint: FcitxEvent.PagedCandidateEvent.LayoutHint = FcitxEvent.PagedCandidateEvent.LayoutHint.NotSet,
    ) : HostEvent() {
        companion object {
            val EMPTY = Paged(emptyList(), -1, hasPrev = false, hasNext = false)
        }
    }

    /** A key for the app: not taken, or forwarded (timestamp -1) (`AndroidFrontend::keyEvent`/`forwardKey`, `:385-398`). */
    data class Key(val sym: Int, val states: Int, val unicode: Int, val up: Boolean, val timestamp: Int) : HostEvent()
    data class DeleteSurrounding(val before: Int, val after: Int) : HostEvent()

    /** The active context's input method activated, or the group changed under an unfocused one (`:327-348`). */
    data class InputMethodChanged(val im: String) : HostEvent()

    /** `InputContextSwitchInputMethod` on the active context (`:349-359`), [reason] by the app's ordinal. */
    data class SwitchInputMethod(val reason: SwitchReason, val oldInputMethod: String) : HostEvent()

    /** The active context's status area anew, with its input method (`statusAreaUpdateCallback`, `androidfrontend.cpp:376-379`). */
    data class StatusArea(val entries: List<StatusEntry>, val im: String) : HostEvent()

    /** A tip (`showTip`): the app shows it on the keyboard, not through the event flow (`Fcitx.showToast`). */
    data class Tip(val text: String) : HostEvent()
}

/**
 * The host's events, in the order the host makes them: commits, deletes and keys at once, the panel
 * once per event. The app takes them through [handlers] (`KotlinFcitx`, whose own flow is the one
 * that keeps every event); [events] is for tests, and like any SharedFlow drops what is emitted
 * while nobody collects. A collector it does have loses nothing however slow: the buffer is
 * unbounded, so [emit]'s `tryEmit` always succeeds.
 */
class HostEvents {
    private val flow = MutableSharedFlow<HostEvent>(extraBufferCapacity = Int.MAX_VALUE)

    val events: SharedFlow<HostEvent> = flow.asSharedFlow()

    /** Listeners run first, on the host thread, as `fcitxEventHandlers` do before the flow (`Fcitx.kt:428-434`). */
    val handlers = ArrayList<(HostEvent) -> Unit>()

    fun emit(event: HostEvent) {
        handlers.forEach { it(event) }
        check(flow.tryEmit(event)) { "unbounded buffer refused an event" }
    }
}
