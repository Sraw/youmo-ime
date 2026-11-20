/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

/**
 * Why the keyboard's views are made anew, and what becomes of half-typed input. A setting that swaps the keyboard
 * ends what was typed first, as leaving the field does (kept, the 14 keys' codes went on in the full keyboard's
 * session). A view made anew always shows the engine's whole state, never only its own events'.
 */
enum class ViewRebuild(
    /** As leaving the field: the code dropped, the field's text committed. */
    val endsComposition: Boolean,
) {
    /** From the settings or the keyboard itself. */
    SETTING(endsComposition = true),

    /** 键盘样式 changed (floating, split, the handle dragged): the same keyboard laid out another way; what is typed stays. */
    STYLE(endsComposition = false),

    /** 候选词注音 changed: only the bar's height; what is typed stays, annotated anew (HostCore.configure's recall). */
    READING(endsComposition = false),

    /** The system asked (onCreateInputView) with nothing changed by the user: what is typed stays. */
    SYSTEM(endsComposition = false),

    /** Seen as a field starts: the field before already took what was typed. */
    LANGUAGE(endsComposition = false),
}
