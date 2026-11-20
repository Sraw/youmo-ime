/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.input.editing.ContextMemory
import org.fcitx.fcitx5.android.input.editing.EditorTraits

/** What the keyboard may do with the field's text: [sensitive] reads nothing for the engine or [memory]; a [password] gets nothing paired or spoken. */
class FieldPolicy(private val memory: ContextMemory) {

    var password = false
        private set

    var sensitive = false
        private set

    /** See [learnFlags]. */
    var learnFlags = 0
        private set

    // no field between finish() and the next focus(): a read queued in the one before reads nothing
    private var focused = false

    private var field: String? = null

    /** Whether it is another field; the same one again (a WebView restart, back from another app) is checked by the context. */
    fun focusAnother(name: String, password: Boolean, sensitive: Boolean, learnFlags: Int = 0): Boolean {
        this.learnFlags = learnFlags
        this.password = password
        this.sensitive = sensitive || password
        focused = true
        memory.focus(name.takeUnless { this.sensitive })
        return (name != field).also { field = name }
    }

    private var inputMethod: String? = null

    /** fcitx resets the session it activates, leaving its journal unverified, so the field is told anew; not with no field focused. */
    fun inputMethodChanged(im: String): Boolean = (focused && im != inputMethod).also { inputMethod = im }

    fun finish() {
        focused = false
        learnFlags = 0
        password = false
        sensitive = false
    }

    /** Not in a password, a raw-key editor, or a phone field (a dialler formats as typed). */
    fun pairs(autoPairs: Boolean, rawKeys: Boolean, phone: Boolean = false): Boolean = autoPairs && !password && !rawKeys && !phone

    /**
     * Null with no field focused; empty where [sensitive] or unreadable, so the engine knows it does not know.
     * [after] is asked only of a field with nothing before and something fresh remembered (a chat once sent).
     */
    fun context(now: Long, before: () -> CharSequence?, after: () -> CharSequence?): String? {
        if (!focused) return null
        if (sensitive) return ""
        val text = before()?.toString() ?: return ""
        return memory.context(text, now, after)
    }

    /** [context] as told: [after] is read on every report of a non-[sensitive] field, so what was written can be checked. */
    fun told(now: Long, before: () -> CharSequence?, after: () -> CharSequence?, started: Boolean = false, selected: Boolean = false): Action.Context? {
        var read: CharSequence? = null
        val afterOnce by lazy(LazyThreadSafetyMode.NONE) { after() }
        val context = context(now, { before().also { read = it } }, { afterOnce }) ?: return null
        val following = if (sensitive) null else afterOnce?.toString()
        return Action.Context(
            before = context,
            cleared = context.isNotEmpty() && read?.isEmpty() == true,
            learnFlags = learnFlags,
            started = started,
            after = following,
            selected = selected,
            said = !sensitive && read != null,
        )
    }

    companion object {
        /** This app's own dialogs are [sensitive]: typing a word to add it would otherwise learn its pieces. */
        fun ownApp(fieldPackage: String?, ownPackage: String): Boolean = fieldPackage == ownPackage

        /** No pairs where an address or number is typed, no 联想 from a search. */
        fun learnFlags(traits: EditorTraits): Int =
            (if (traits.isUri || traits.isEmail || traits.isNumber) Commit.NO_PAIRS else 0) or (if (traits.isSearch) Commit.NO_PREDICT else 0)
    }
}
