/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.input.editing.ContextMemory
import org.fcitx.fcitx5.android.input.editing.EditorTraits

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

    /** What learning leaves out in the field focused ([Commit.NO_PAIRS], [Commit.NO_PREDICT]; see [learnFlags]). */
    var learnFlags = 0
        private set

    // no field between finish() and the next focus(): a read queued in the one before reads nothing
    private var focused = false

    // the name of the field focused last
    private var field: String? = null

    /**
     * A field focused, named as [ContextMemory.field] names it; a [password] is [sensitive] whatever
     * the rest says. Whether it is another field than the last ([Action.Context.started]): the same
     * one again (restarted by a WebView, gone back to from another app) is checked by the context,
     * not taken for a new one (review-1c S3).
     */
    fun focusAnother(name: String, password: Boolean, sensitive: Boolean, learnFlags: Int = 0): Boolean {
        this.learnFlags = learnFlags
        this.password = password
        this.sensitive = sensitive || password
        focused = true
        memory.focus(name.takeUnless { this.sensitive })
        return (name != field).also { field = name }
    }

    // the input method active when last asked (inputMethodChanged)
    private var inputMethod: String? = null

    /**
     * [im] became the active input method: whether to tell it the field as one [Action.Context.started].
     * fcitx resets the session it activates, which leaves its journal unverified (U-03), and nothing
     * else tells it this field: in one empty no context would verify it again, so nothing deleted
     * at once would be taken back (UAT HW-22/23, a switch to 手写). The field is new to that session;
     * any text in it another input method's. Not the same one again, nor with no field focused.
     */
    fun inputMethodChanged(im: String): Boolean = (focused && im != inputMethod).also { inputMethod = im }

    /** The input finished: no field until the next [focusAnother]. */
    fun finish() {
        focused = false
        learnFlags = 0
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

    /**
     * [context] as the engine is told it: cleared where the field is empty and what was written in
     * it is told ([Action.Context.cleared]); the text [after] the cursor where learning, for what was
     * written to be checked (U-03), so asked on every report of a field not [sensitive], once even
     * where [context] asked it too (a round trip each; accepted in review-design-1c); [started] and
     * [selected] as [Action.Context] has them.
     */
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
        /**
         * A field of this app itself ([fieldPackage] is [ownPackage]): the add/edit word and custom
         * phrase dialogs, settings search. It is [sensitive], as a field that asks not to be learned
         * from: typing a word out to add it would otherwise count its pieces and the pairs between
         * them, which deleting the word later cannot take back (UAT WD-03).
         */
        fun ownApp(fieldPackage: String?, ownPackage: String): Boolean = fieldPackage == ownPackage

        /**
         * What learning leaves out of what is typed in a field like [traits] (U-91): no pairs where
         * an address or a number is typed, no 联想 from a search.
         */
        fun learnFlags(traits: EditorTraits): Int =
            (if (traits.isUri || traits.isEmail || traits.isNumber) Commit.NO_PAIRS else 0) or (if (traits.isSearch) Commit.NO_PREDICT else 0)
    }
}
