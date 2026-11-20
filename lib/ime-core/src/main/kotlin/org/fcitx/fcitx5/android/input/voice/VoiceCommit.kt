/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/** Into the field it was said for only, never a password. */
object VoiceCommit {

    /** Null: nothing heard, another field, or a password one. */
    fun typed(text: String, sameField: Boolean, passwordField: Boolean): String? =
        text.takeIf { it.isNotEmpty() && sameField && !passwordField }

    /** As ContextMemory.field names it, so the inputs one view holds (a WebView's) are told apart. */
    data class Field(
        val packageName: String?,
        val fieldId: Int,
        val inputType: Int = 0,
        val fieldName: String? = null,
        val hintText: String? = null,
    )

    /** By app, view id and traits, not the EditorInfo object (restartInput gives a new one); an unset id (0, -1) tells nothing, the same object always does. */
    fun sameField(said: Field?, now: Field?, identical: Boolean): Boolean = identical ||
        (said != null && now != null && said == now && said.fieldId != 0 && said.fieldId != NO_ID)

    private const val NO_ID = -1
}
