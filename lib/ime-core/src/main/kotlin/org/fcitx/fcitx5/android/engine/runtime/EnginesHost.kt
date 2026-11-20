/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.host.EngineHost

/** [EngineHost] directly on [Engines], without the app's side effects (`EngineBridge`'s): for JVM tests and replay. */
class EnginesHost(private val engines: Engines) : EngineHost {
    override fun event(im: String, event: Int, arg: Int, learning: Boolean, payload: Action?): Snapshot = engines.onEvent(im, event, arg, learning, payload)
    override fun candidates(im: String, from: Int, count: Int): List<Choice> = engines.candidates(im, from, count)
    override fun offers(im: String, index: Int): Set<Offer> = engines.offers(im, index)
    override fun settings(settings: EngineSettings) {
        engines.settings = settings
    }
}
