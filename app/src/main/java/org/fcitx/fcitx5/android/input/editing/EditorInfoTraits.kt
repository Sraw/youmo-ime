/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * `EditorInfo.privateImeOptions` value by which an editor opts in to having Backspace sent as
 * `deleteSurroundingText` instead of a key event. Only the app's own key preference UI sets it.
 */
@Suppress("ConstPropertyName")
const val DeleteSurroundingFlag = "org.fcitx.fcitx5.android.DELETE_SURROUNDING"

/** Decodes the bits of [EditorInfo] that [EditorKeyPolicy] and [EditingSession] act on. */
fun EditorInfo.toEditorTraits(): EditorTraits {
    val type = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    val action = imeOptions and EditorInfo.IME_MASK_ACTION
    return EditorTraits(
        isRawKeyInput = type == InputType.TYPE_NULL,
        isUri = type == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI,
        acceptsDeleteSurrounding = privateImeOptions == DeleteSurroundingFlag,
        noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0,
        customActionId = actionId.takeIf {
            actionLabel?.isNotEmpty() == true && it != EditorInfo.IME_ACTION_UNSPECIFIED
        },
        imeAction = action.takeIf {
            it != EditorInfo.IME_ACTION_UNSPECIFIED && it != EditorInfo.IME_ACTION_NONE
        },
    )
}
