/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot

/**
 * The host's port to the engines, so ime-core does not depend on the app: the app's `EngineBridge`
 * implements it (`EngineBridge.host`: its side effects, `syllablesState`, `englishTypingState`, the
 * unloadable latch, stay there), tests implement it with `Engines` directly.
 */
interface EngineHost {
    /**
     * [event] with [arg] to [im]'s session, [payload] what its key carried ([KeyEvent.payload]); null
     * when the engine cannot load (the key is not handled). No default for [payload]: a host that
     * passes keys through and forgets it compiles no longer, and its handwriting and glides come to nothing.
     */
    fun event(im: String, event: Int, arg: Int, learning: Boolean, payload: Action?): Snapshot?

    /** [count] of [im]'s candidates from [from], as the frontend fetches pages. */
    fun candidates(im: String, from: Int, count: Int): List<Choice>

    /** What the long-press menu offers on candidate [index]. */
    fun offers(im: String, index: Int): Set<Offer>

    /** New engine options ([EngineOptions.toSettings]). */
    fun settings(settings: EngineSettings)
}
