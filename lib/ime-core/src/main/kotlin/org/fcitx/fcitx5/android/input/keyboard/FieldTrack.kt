/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.voice.VoiceCommit

/**
 * Whether the keys start in the field they last started in: by the same EditorInfo, else by app, view id, input type,
 * field name and hint. An unset id (a WebView made in code) counts only so or on a restart with all of them alike, so
 * leaving such a view and coming back starts anew; a list's rows sharing one id are taken as one field.
 */
class FieldTrack {

    private var last: VoiceCommit.Field? = null

    /** [identical]: the same EditorInfo as the last start; [restarting]: onStartInputView's. */
    fun started(field: VoiceCommit.Field, identical: Boolean, restarting: Boolean): Boolean =
        (VoiceCommit.sameField(last, field, identical) || (restarting && last == field)).also { last = field }
}
