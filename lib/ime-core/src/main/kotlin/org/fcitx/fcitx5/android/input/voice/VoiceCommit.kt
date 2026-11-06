/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * Where what is said goes (VoiceSession): what was said, into the field it was said for only, not
 * one the keyboard has gone to meanwhile, and never a password.
 */
object VoiceCommit {

    /** [text] to type, or null: nothing heard, another field ([sameField] false), or a password one */
    fun typed(text: String, sameField: Boolean, passwordField: Boolean): String? =
        text.takeIf { it.isNotEmpty() && sameField && !passwordField }

    /**
     * A field told by its app ([packageName]), its view's id ([fieldId]) and what it says of itself
     * ([inputType], [fieldName], [hintText]), all EditorInfo's: as ContextMemory.field names it, so
     * the inputs one view holds (a WebView's, a Compose screen's) are told apart where they differ.
     */
    data class Field(
        val packageName: String?,
        val fieldId: Int,
        val inputType: Int = 0,
        val fieldName: String? = null,
        val hintText: String? = null,
    )

    /**
     * Whether [now] is still the field [said] was, told by app, view id and what it says of itself
     * rather than by the EditorInfo object: the app restarting input in it (a WebView, an app's
     * restartInput) gives a new one for the same field. An id not set (0, or View.NO_ID: -1) tells nothing, so two such
     * fields are not taken as one; the same EditorInfo always is ([identical]).
     */
    fun sameField(said: Field?, now: Field?, identical: Boolean): Boolean = identical ||
        (said != null && now != null && said == now && said.fieldId != 0 && said.fieldId != NO_ID)

    private const val NO_ID = -1
}
