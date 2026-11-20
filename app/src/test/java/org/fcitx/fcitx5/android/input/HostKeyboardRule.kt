/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import java.io.File
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.core.ScriptedEngine
import org.fcitx.fcitx5.android.core.keyboardOn
import org.fcitx.fcitx5.android.core.startedHost
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.rules.ExternalResource
import org.fcitx.fcitx5.android.until as waitUntil

/** A real [KotlinHost] on a fresh home with a [KeyboardHarness]; inside a PrefsRule use `order = 1`, so the keyboard closes before settings are set back. */
class HostKeyboardRule(private val dir: String, private val after: () -> Unit = {}) : ExternalResource() {

    private lateinit var home: File
    lateinit var fcitx: KotlinHost
        private set
    lateinit var h: KeyboardHarness
        private set

    private var started = false

    /** The keyboard is not started. */
    fun up(side: (File) -> KotlinHost.EngineSide = ::ScriptedEngine, api: (KotlinHost) -> HostApi = { it }): KeyboardHarness {
        started = true
        home = File(appContext.filesDir, dir).apply { deleteRecursively() }
        fcitx = startedHost(side(home))
        h = keyboardOn(fcitx, api(fcitx))
        return h
    }

    val preedit get() = fcitx.inputPanelCached.preedit.toString()

    /** The host answers on its thread, the keyboard draws on main: runs both till [done]. */
    fun until(what: String, state: () -> Any? = { "field [${h.field.text}] preedit [$preedit]" }, done: () -> Boolean) =
        waitUntil(what, state, step = { h.idle() }, done = done)

    fun synced() {
        var done = false
        h.service.postFcitxJob { done = true }
        until("the host's queue done") { done }
        h.idle()
    }

    /** For a test that starts another host, else run after it. */
    fun down() {
        if (!started) return
        started = false
        if (::h.isInitialized) h.close()
        if (::fcitx.isInitialized) fcitx.stop()
        home.deleteRecursively()
    }

    override fun after() {
        down()
        after.invoke()
    }
}
