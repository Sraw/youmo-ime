/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.core.CandidateWord as CandidateDto

/**
 * The host's events as the app's [FcitxEvent]s, one for one, the same values `native-lib.cpp`'s
 * callbacks built (`:600-700`, `object-conversion.h`), so the event flow's collectors see no
 * difference (design §4). A [HostEvent.Tip] is no event: the app says it ([HostEvent.Tip] is shown
 * by `EngineNotices`, as `Fcitx.showToast` did).
 */
object FcitxEvents {

    /** [event] as the app's, null for a tip. */
    fun of(event: HostEvent): FcitxEvent<*>? = when (event) {
        is HostEvent.Commit -> FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data(event.text, event.cursor))
        is HostEvent.ClientPreedit -> FcitxEvent.ClientPreeditEvent(event.text)
        // no candidate tabs: no module adds one (design §1.4)
        is HostEvent.InputPanel -> FcitxEvent.InputPanelEvent(
            FcitxEvent.InputPanelEvent.Data(event.preedit, event.auxUp, event.auxDown, emptyArray()),
        )
        is HostEvent.Candidates -> FcitxEvent.CandidateListEvent(
            FcitxEvent.CandidateListEvent.Data(event.total, event.candidates.map(::candidate).toTypedArray()),
        )
        is HostEvent.Paged -> FcitxEvent.PagedCandidateEvent(
            FcitxEvent.PagedCandidateEvent.Data(
                event.candidates.map(::candidate).toTypedArray(), event.cursorIndex,
                event.layoutHint, event.hasPrev, event.hasNext,
            ),
        )
        is HostEvent.Key -> FcitxEvent.KeyEvent(
            FcitxEvent.KeyEvent.Data(KeySym(event.sym), KeyStates.of(event.states), event.unicode, event.up, event.timestamp),
        )
        is HostEvent.DeleteSurrounding -> FcitxEvent.DeleteSurroundingEvent(FcitxEvent.DeleteSurroundingEvent.Data(event.before, event.after))
        is HostEvent.InputMethodChanged -> FcitxEvent.IMChangeEvent(entry(event.im))
        is HostEvent.SwitchInputMethod -> FcitxEvent.SwitchInputMethodEvent(
            FcitxEvent.SwitchInputMethodEvent.Data(FcitxEvent.SwitchInputMethodEvent.Reason.of(event.reason.ordinal), event.oldInputMethod),
        )
        is HostEvent.StatusArea -> FcitxEvent.StatusAreaEvent(
            FcitxEvent.StatusAreaEvent.Data(event.entries.map(::action).toTypedArray(), entry(event.im)),
        )
        is HostEvent.Tip -> null
    }

    /**
     * [im]'s entry as androidengine lists it (`androidengine.cpp:393-403`): its text as the name, its
     * label and icon, its language, the addon's name, configurable; no sub mode (the addon has none).
     * One the engines do not list (none should reach here) is a bare entry of its name.
     */
    fun entry(im: String): InputMethodEntry {
        val m = Engines.listed(im) ?: return InputMethodEntry(im, im, "", "", "", "", ADDON, false)
        return InputMethodEntry(m.name, m.text, m.icon, "", m.label, m.language, ADDON, true)
    }

    /** A status area entry as `ActionEntity` carries it: not a separator, not checkable, no menu. */
    fun action(entry: StatusEntry) =
        Action(entry.id, false, false, false, entry.name, entry.icon, entry.shortText, "", null)

    private fun candidate(c: CandidateEntry) = CandidateDto(c.label, c.text, c.comment)

    private const val ADDON = "androidengine"
}
