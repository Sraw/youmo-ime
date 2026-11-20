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

/** The host's port to the engines, so ime-core does not depend on the app: `EngineBridge.host` in the app, `Engines` in tests. */
interface EngineHost {
    /** Null: the engine cannot load. [payload] has no default: a host forgetting it would lose handwriting and glides. */
    fun event(im: String, event: Int, arg: Int, learning: Boolean, payload: Action?): Snapshot?

    /** As the frontend fetches pages. */
    fun candidates(im: String, from: Int, count: Int): List<Choice>

    fun offers(im: String, index: Int): Set<Offer>

    fun settings(settings: EngineSettings)
}
