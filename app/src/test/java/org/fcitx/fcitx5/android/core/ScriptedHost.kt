/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.until
import org.fcitx.fcitx5.android.utils.appContext

/**
 * The Kotlin host on [side] (a [ScriptedEngine] for most), started and ready; as on a new install's
 * first run unless [firstRun] is false (a restart): AppPrefs outlives a test's sandbox, with the last
 * one's first run done. [beforeStart] is for what must see the first event (a collector of eventFlow,
 * which keeps no replay).
 */
fun startedHost(side: KotlinHost.EngineSide, firstRun: Boolean = true, beforeStart: (KotlinHost) -> Unit = {}): KotlinHost {
    if (firstRun) AppPrefs.getInstance().internal.firstRun.setValue(true)
    return KotlinHost(appContext, side).apply {
        beforeStart(this)
        start()
        until("the host ready") { isReady }
    }
}

/**
 * A keyboard on [host] with the input context onBindInput activates made first: the harness starts
 * input without a binding, and focus needs one. Not started: the caller sets what it reads first.
 */
fun keyboardOn(host: KotlinHost) = KeyboardHarness(host = host).also { h ->
    h.service.postFcitxJob { activate(1, "org.example.app") }
}
