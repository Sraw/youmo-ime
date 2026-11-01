/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

/**
 * Why the keyboard's views are made anew, and what becomes of half-typed input then (A2).
 *
 * A setting that makes them anew or swaps the keyboard (pinyin's 14 keys for the full one, 九键's
 * letters, a theme) ends what was typed first, as leaving the field does: what shows only above the
 * candidates (pinyin's code) dropped, what is already in the field as composing text (English's
 * half-typed word, handwriting's characters) committed there (user, 2026-10-27 and 2026-10-31).
 * Kept, it outlived the keyboard it was typed on: the 14 keys' codes went on in the full keyboard's
 * session, committed as typed at its first letter, and the new view showed the code row with no
 * candidates under it. Whatever the cause, a view made anew then shows the engine's whole state
 * (code row, candidates, input method), never only what its own events bring.
 */
enum class ViewRebuild(
    /** Whether what was typed ends (as leaving the field: the code dropped, the field's text committed) before the views are made. */
    val endsComposition: Boolean,
) {
    /** A setting changed, from the settings or from the keyboard itself, that the views are made anew for. */
    SETTING(endsComposition = true),

    /**
     * The handle dragged between floating and docked (or the status area's toggle): the same
     * keyboard moved, not a setting changed (user, 2026-10-31). What is typed stays, and the new
     * views show it.
     */
    FLOATING(endsComposition = false),

    /**
     * The system asked for them (onCreateInputView, the window's views made again) with nothing
     * changed by the user: what is typed stays, and the new views show it.
     */
    SYSTEM(endsComposition = false),

    /**
     * The app's language picked anew, seen as a field starts (onStartInputView): the field before it
     * left already took what was typed with it.
     */
    LANGUAGE(endsComposition = false),
}
