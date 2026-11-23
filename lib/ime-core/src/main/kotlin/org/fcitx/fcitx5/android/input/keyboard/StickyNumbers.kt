/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.editing.NumberKind
import org.fcitx.fcitx5.android.input.voice.VoiceCommit

/**
 * Whether the number keyboard stays for the next field of the same app. [Mode.Armed]: the user opened it in a field
 * without a pad of its own, kept across plain fields while the keyboard stays up. [Mode.Locked]: its lock, kept through
 * hiding and letter fields too. Back, another keyboard shown by the user or a field of another app let go of either: the
 * home screen, recents or a system dialog in between bind the input method elsewhere, but are no app switch to the user.
 */
class StickyNumbers {

    enum class Mode { Off, Armed, Locked }

    var mode = Mode.Off
        private set

    private var app: String? = null

    private class Current(val pkg: String?, val ownPad: Boolean)

    // null while hidden: a switch posted before hiding and shown after arms nothing
    private var current: Current? = null

    private var last: VoiceCommit.Field? = null

    // what the field shows, which the same field started again keeps: a locked keyboard's letters in an email field too
    private var numbersHere = false

    val locked: Boolean get() = mode == Mode.Locked

    /**
     * At each field's start. True: the number keyboard, though the field has no pad of its own. [identical]: the same
     * EditorInfo as the last start; [restarting] as onStartInputView's, which with the same traits is the same field.
     */
    fun fieldStarted(plain: Boolean, number: NumberKind?, field: VoiceCommit.Field, identical: Boolean, restarting: Boolean): Boolean {
        val pkg = field.packageName
        current = Current(pkg, number != null)
        // a native view's next field is another view, so restarting; a WebView's or a list's share one id
        val same = identical || (restarting && VoiceCommit.sameField(last, field, false))
        last = field
        val kept = when {
            mode == Mode.Off -> false
            pkg == null || pkg != app -> letGo()
            // its own pad shows; the next plain field has the number keyboard again
            number != null -> false
            same -> numbersHere
            plain -> true
            else -> {
                if (mode == Mode.Armed) letGo()
                false
            }
        }
        numbersHere = kept
        return kept
    }

    /** A switch shown; [byUser]: the user's (a key, a panel), not a field's. */
    fun shown(isNumberPad: Boolean, byUser: Boolean) {
        val now = current ?: return
        numbersHere = isNumberPad
        when {
            // in a field with a pad 返回 then 123 only gives that pad back
            isNumberPad && byUser -> if (!now.ownPad && mode == Mode.Off) arm(now.pkg)
            isNumberPad -> {}
            byUser -> letGo()
            mode == Mode.Armed -> letGo()
        }
    }

    /** The keyboard hidden, or a hardware keyboard took over. */
    fun hidden() {
        current = null
        if (mode == Mode.Armed) letGo()
    }

    /** The number keyboard's lock tapped. Let go, it keeps the number keyboard as a 123 tap would, where that arms. */
    fun toggleLock() {
        val now = current ?: return
        when {
            mode != Mode.Locked -> {
                mode = Mode.Locked
                app = now.pkg
            }
            now.ownPad -> letGo()
            else -> arm(now.pkg)
        }
    }

    private fun arm(pkg: String?) {
        mode = Mode.Armed
        app = pkg
    }

    private fun letGo(): Boolean {
        mode = Mode.Off
        app = null
        return false
    }
}
