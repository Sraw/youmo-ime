/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.voice.VoiceCommit

/**
 * Whether the keys start in the field they last started in: by app, view id and traits. A view without an id (a
 * WebView made in code) is the same one only restarted with all of them alike, so its inputs alike in all are taken as
 * one, and it left and come back to starts anew.
 */
class FieldTrack {

    private var last: VoiceCommit.Field? = null

    /** [identical]: the same EditorInfo as the last start; [restarting]: onStartInputView's. */
    fun started(field: VoiceCommit.Field, identical: Boolean, restarting: Boolean): Boolean =
        (VoiceCommit.sameField(last, field, identical) || (restarting && last == field)).also { last = field }
}
