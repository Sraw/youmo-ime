/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.host.KeySyms
import org.fcitx.fcitx5.android.input.cursor.CursorRange

/**
 * 编码行 输入框里: whether the session's code goes into the field as composing text or floats over the keyboard.
 * Decided as a code starts and kept till it ends, field to strip only, never back; a field that let it down once
 * ([fallBack]) floats it for the rest of its input.
 */
class CodeInField {

    enum class Route {
        FIELD,

        /** As 浮在键盘上方 has it. */
        STRIP,
    }

    private var fieldTakes = false

    /** The field let it down once. */
    var fallenBack = false
        private set

    /** Null with none typed. */
    var route: Route? = null
        private set

    /** [restarting] (the screen turned, the app restarting): a fall-back stays, as the field that let it down may be this one. */
    fun startInput(traits: EditorTraits, restarting: Boolean) {
        fieldTakes = takes(traits)
        if (!restarting) fallenBack = false
        route = null
    }

    /** A browser's may differ from input's start: the later is kept. */
    fun startInputView(traits: EditorTraits) {
        fieldTakes = takes(traits)
    }

    fun finishInput() {
        fallenBack = false
        route = null
    }

    /** A rewrite, no read, a restart. */
    fun fallBack() {
        fallenBack = true
        floats()
    }

    /** The field not to blame (its first write not taken): the next is decided anew. */
    fun floats() {
        if (route == Route.FIELD) route = Route.STRIP
    }

    /** [selection] known and empty for the field; a code holding a keyboard's own (private use) character goes to the strip, there from then on. */
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
        /** Not raw keys (no composing text), passwords, or number/phone/date fields; URL, email and no-suggestion fields do, as 微信输入法. */
        fun takes(traits: EditorTraits): Boolean = !traits.isRawKeyInput && !traits.isPassword && !traits.isNumber
    }
}
