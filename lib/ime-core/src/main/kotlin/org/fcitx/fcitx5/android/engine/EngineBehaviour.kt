/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine

/**
 * The version of what the engine and host do with a key, for parity recordings (specs/testing.md,
 * "When to bump ENGINE_BEHAVIOUR"). A recording made at another version still compares, labelled
 * "engine changed". Bumped by hand: a source hash changed on every comment and rename, and the native
 * host's recordings (tag `native-last`) can never be made again.
 */
object EngineBehaviour {
    /** 1: the behaviour of the native host's last tree (`native-last`) and of the Kotlin host at 0.4.0. */
    /**
     * 2 (R15): a word lifted to third (HabitRanker.LIFTED_TO) keeps it before the emoji (PY-09). Shipped
     * with it, putting back what `native-last` did (so 0.4.0's Kotlin recordings differ there): a
     * handwriting tap commits and resets (HostCore.moveCursor, F3788); ENTER right after an IM switch
     * learns by the old flags (F3855); the keysyms of five code points (F3812).
     */
    const val VERSION = 2
}
