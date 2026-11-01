/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

/**
 * [done] polled till it holds, [step] between tries (the main looper idled, a relayout), failing with
 * [what] and [state] after [timeoutMs]. For work of another thread (the host's, the recognizer's, the
 * IO dispatcher's) whose end the main looper then draws: neither thread's idle alone says it is done,
 * so it is waited for in real time, ended the moment [done] holds; the bound only says when to give up.
 */
fun until(what: String, state: () -> Any? = { "" }, timeoutMs: Long = 10_000, step: () -> Unit = {}, done: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!done()) {
        check(System.currentTimeMillis() < end) { "no $what ${state()}" }
        step()
        Thread.sleep(10)
    }
}
