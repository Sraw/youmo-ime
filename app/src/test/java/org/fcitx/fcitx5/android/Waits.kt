/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

/** Far past any wait that succeeds, which ends at once, on a loaded machine. */
const val WAIT_MS = 30_000L

/**
 * Work of another thread whose end the main looper then draws: neither thread's idle alone says it is done, so it
 * is waited for in real time, ended the moment [done] holds.
 */
fun until(what: String, state: () -> Any? = { "" }, timeoutMs: Long = WAIT_MS, step: () -> Unit = {}, done: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!done()) {
        check(System.currentTimeMillis() < end) { "no $what ${state()}" }
        step()
        Thread.sleep(10)
    }
}
