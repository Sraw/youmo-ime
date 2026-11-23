/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags

/**
 * Option in `EditorInfo.privateImeOptions` (a comma-separated list) by which an editor opts in to
 * having Backspace sent as `deleteSurroundingText` instead of a key event. Only the app's own key
 * preference UI sets it.
 */
@Suppress("ConstPropertyName", "TopLevelPropertyNaming")
const val DeleteSurroundingFlag = "org.fcitx.fcitx5.android.DELETE_SURROUNDING"

private val EMAIL_VARIATIONS = setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)

private fun numberKind(type: Int): NumberKind? = when (type) {
    InputType.TYPE_CLASS_NUMBER -> NumberKind.NUMBER
    InputType.TYPE_CLASS_PHONE -> NumberKind.PHONE
    InputType.TYPE_CLASS_DATETIME -> NumberKind.DATETIME
    else -> null
}

/** [ownPackage] given, a field of this app is sensitive as the service tells fcitx ([CapabilityFlags.forField]). */
fun EditorInfo.toEditorTraits(ownPackage: String? = null): EditorTraits {
    val type = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    val action = imeOptions and EditorInfo.IME_MASK_ACTION
    val isUri = type == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI
    val isEmail = type == InputType.TYPE_CLASS_TEXT && variation in EMAIL_VARIATIONS
    val isPassword = CapabilityFlags.isPassword(this)
    return EditorTraits(
        isRawKeyInput = type == InputType.TYPE_NULL,
        isUri = isUri,
        isEmail = isEmail,
        isPassword = isPassword,
        isNumber = type == InputType.TYPE_CLASS_NUMBER || type == InputType.TYPE_CLASS_PHONE || type == InputType.TYPE_CLASS_DATETIME,
        number = numberKind(type),
        isSearch = action == EditorInfo.IME_ACTION_SEARCH,
        isSensitive = (if (ownPackage != null) CapabilityFlags.forField(this, ownPackage) else CapabilityFlags.fromEditorInfo(this))
            .hasAny(CapabilityFlag.PasswordOrSensitive),
        acceptsDeleteSurrounding =
            privateImeOptions?.split(',')?.any { it.trim() == DeleteSurroundingFlag } == true,
        noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0,
        customActionId = actionId.takeIf {
            actionLabel?.isNotEmpty() == true && it != EditorInfo.IME_ACTION_UNSPECIFIED
        },
        imeAction = action.takeIf {
            it != EditorInfo.IME_ACTION_UNSPECIFIED && it != EditorInfo.IME_ACTION_NONE
        },
        sends = action == EditorInfo.IME_ACTION_SEND || (actionLabel?.isNotEmpty() == true && actionId == EditorInfo.IME_ACTION_SEND),
        isMultiLine = type == InputType.TYPE_CLASS_TEXT &&
            inputType and (InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0,
        showsLines = type == InputType.TYPE_CLASS_TEXT && inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0,
        isShortMessage = type == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
        isPlainText = type == InputType.TYPE_CLASS_TEXT && !isUri && !isEmail && !isPassword,
    )
}
