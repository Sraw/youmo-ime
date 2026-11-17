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
 * [api]: what the keyboard calls, [host] or a wrapper of it; jobs wait for [host] to be up either way.
 */
fun keyboardOn(host: KotlinHost, api: HostApi = host) = KeyboardHarness(host = api, ready = host).also { h ->
    h.service.postFcitxJob { activate(1, "org.example.app") }
}

/**
 * What a [KotlinHost] told a case (its events, its notices), for the case to read. They come on the host's thread,
 * and not only within a case's own calls: its timers send too (on a first run the input method's name shown 30 ms
 * after the group is set, its panel and list sent again), so they are read as a copy taken under the lock. Iterated
 * as the host added to it, a synchronized list threw ConcurrentModificationException.
 */
class HostLog<T> {
    private val items = ArrayList<T>()

    fun add(item: T) {
        synchronized(items) { items += item }
    }

    fun clear() = synchronized(items) { items.clear() }

    /** What was told till now, a copy. */
    fun list(): List<T> = synchronized(items) { items.toList() }
}
