/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.host.KeySyms
import org.fcitx.fcitx5.android.input.cursor.CursorRange

/**
 * 编码行 输入框里 (实验性): whether the session's code (`ni hao`, `你hao`) goes into the field as its
 * composing text ([EditingSession.writeCode]) or floats over the keyboard as with 浮在键盘上方
 * (dev/design/061-code-in-field.md §4.4, §6). One per service, its state the current input's.
 *
 * Decided as a code starts and kept till it ends: from the field to the strip only, never back,
 * so a code is never half in one and half in the other. A field that let the code down once
 * ([fallBack]: rewritten, not read back, input restarted over it) floats it for the rest of its input.
 */
class CodeInField {

    enum class Route {
        /** In the field, the strip drawing none of it. */
        FIELD,

        /** On the floating strip, as 浮在键盘上方 has it. */
        STRIP,
    }

    // whether the field takes the code at all, as it declares itself ([takes])
    private var fieldTakes = false

    /** The code floats for the rest of this input: the field let it down once. */
    var fallenBack = false
        private set

    /** Where the code being typed goes; null with none typed. */
    var route: Route? = null
        private set

    /**
     * Input started in a field of [traits]. [restarting] (the same view's, the screen turned, the app
     * restarting it): a fall-back stays, as the field that let the code down may be the one restarted.
     */
    fun startInput(traits: EditorTraits, restarting: Boolean) {
        fieldTakes = takes(traits)
        if (!restarting) fallenBack = false
        route = null
    }

    /** The field said anew what it is as its view started ([traits]; a browser's may differ from input's start): the later is kept. */
    fun startInputView(traits: EditorTraits) {
        fieldTakes = takes(traits)
    }

    /** The field left: the next starts afresh. */
    fun finishInput() {
        fallenBack = false
        route = null
    }

    /** The code could not stay in the field (a rewrite, no read, a restart): it floats from now on, this input through. */
    fun fallBack() {
        fallenBack = true
        floats()
    }

    /** This code floats, the field not to blame (its first write not taken): the next is decided anew. */
    fun floats() {
        if (route == Route.FIELD) route = Route.STRIP
    }

    /**
     * Where the panel's code goes: [code], the session's code the panel shows (HostEvent.InputPanel.code),
     * null with none typed. [inField], 编码行 is 输入框里; [selection], the editor's, known and empty
     * for the field (§6.2: an editor that has said nothing has it at -1); [othersComposing], voice's or
     * English's text composes in the field. A code with a keyboard's own character in it (private use,
     * §4.2) is no text for the field: on the strip, there from then on if it was in the field.
     */
    fun route(inField: Boolean, code: CharSequence?, selection: CursorRange, othersComposing: Boolean): Route? {
        if (code == null) {
            route = null
            return null
        }
        val text = code.codePoints().noneMatch(KeySyms::isPrivateUse)
        if (!text) floats()
        route?.let { return it }
        val fieldWants = inField && fieldTakes && !fallenBack && text
        val fieldFree = selection.isEmpty() && selection.start >= 0 && !othersComposing
        return (if (fieldWants && fieldFree) Route.FIELD else Route.STRIP).also { route = it }
    }

    companion object {
        /**
         * Whether a field of [traits] takes the code (§6.1, the user's answer to Q3): not one of raw keys
         * (TYPE_NULL: a terminal has no composing text), a password's, hidden or shown, nor a number's,
         * phone's or date's. URL, email and no-suggestion fields do, as 微信输入法 has it.
         */
        fun takes(traits: EditorTraits): Boolean = !traits.isRawKeyInput && !traits.isPassword && !traits.isNumber
    }
}
