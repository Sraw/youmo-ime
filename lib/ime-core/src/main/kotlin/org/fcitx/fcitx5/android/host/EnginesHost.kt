/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot

/**
 * [EngineHost] straight on [Engines], with none of the app's side effects (`EngineBridge`'s): the
 * JVM tests' and the replay's (design §7.2).
 */
class EnginesHost(private val engines: Engines) : EngineHost {
    override fun event(im: String, event: Int, arg: Int, learning: Boolean): Snapshot = engines.onEvent(im, event, arg, learning)
    override fun candidates(im: String, from: Int, count: Int): List<Choice> = engines.candidates(im, from, count)
    override fun offers(im: String, index: Int): Set<Offer> = engines.offers(im, index)
    override fun settings(flat: String) {
        engines.settings = EngineSettings.parse(flat)
    }
}
