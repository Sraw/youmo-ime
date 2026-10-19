/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.input.editing.ContextMemory

/**
 * What the keyboard may do with the focused field's text. A password, or a field that asks not to
 * be learned from, is [sensitive]: nothing of it is read for the engine or kept in [memory]. A
 * [password], its characters shown or not, gets nothing paired and nothing said out loud.
 *
 * The service decodes the field's `EditorInfo` and reads the editor; what that allows is decided
 * here, so each kind of field is tested without an editor.
 */
class FieldPolicy(private val memory: ContextMemory) {

    var password = false
        private set

    var sensitive = false
        private set

    // no field between finish() and the next focus(): a read queued in the one before reads nothing
    private var focused = false

    /** A field focused, named as [ContextMemory.field] names it; a [password] is [sensitive] whatever the rest says. */
    fun focus(name: String, password: Boolean, sensitive: Boolean) {
        this.password = password
        this.sensitive = sensitive || password
        focused = true
        memory.focus(name.takeUnless { this.sensitive })
    }

    /** The input finished: no field until the next [focus]. */
    fun finish() {
        focused = false
        password = false
        sensitive = false
    }

    /**
     * Brackets and quotes typed in pairs, where the user wants it ([autoPairs]): not in a
     * password, where what is typed is all there is, nor where the editor takes key events only.
     */
    fun pairs(autoPairs: Boolean, rawKeys: Boolean): Boolean = autoPairs && !password && !rawKeys

    /**
     * The engine's context where the editor shows [before] before the cursor at [now]; null, nothing
     * to tell, where no field is focused. Where the field is [sensitive] (not even read) or the
     * editor would not say, empty: the engine is told it does not know what is there, as what it
     * committed may not be before the cursor now (English would put its space behind punctuation
     * in some other word). [after], what follows the cursor, is asked only of a field with nothing
     * before it and something fresh remembered of it, as a chat's box once a message is sent: a
     * round trip.
     */
    fun context(now: Long, before: () -> CharSequence?, after: () -> CharSequence?): String? {
        if (!focused) return null
        if (sensitive) return ""
        val text = before()?.toString() ?: return ""
        return memory.context(text, now, after)
    }

    /** [context] as the engine is told it: cleared where the field is empty and what was written in it is told ([Action.Context.cleared]). */
    fun told(now: Long, before: () -> CharSequence?, after: () -> CharSequence?): Action.Context? {
        var read: CharSequence? = null
        val context = context(now, { before().also { read = it } }, after) ?: return null
        return Action.Context(context, cleared = context.isNotEmpty() && read?.isEmpty() == true)
    }

    companion object {
        /**
         * A field of this app itself ([fieldPackage] is [ownPackage]): the add/edit word and custom
         * phrase dialogs, settings search. It is [sensitive], as a field that asks not to be learned
         * from: typing a word out to add it would otherwise count its pieces and the pairs between
         * them, which deleting the word later cannot take back (UAT WD-03).
         */
        fun ownApp(fieldPackage: String?, ownPackage: String): Boolean = fieldPackage == ownPackage
    }
}
