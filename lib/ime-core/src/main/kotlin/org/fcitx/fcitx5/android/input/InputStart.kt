/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

/**
 * Input starting in a field, as the service's onStartInput takes it. The screen turned, input
 * restarts with the EditorInfo the field first started with (resetStateForNewConfiguration): its
 * initial selection and text are that start's, and the editor's own are what the service followed
 * since, a select-all's selection among them. An app restarting input gives an EditorInfo of its own,
 * whose initial selection and text are the editor's then.
 *
 * [again]: input restarts with the EditorInfo it last started with. [followedSelected]: the editor's
 * selection as the service followed it, text selected; [editorComposing]: whether the editor's last
 * cursor report held a composing text; [initialSelStart] and [initialSelEnd]: the EditorInfo's.
 */
class InputStart(
    private val again: Boolean,
    followedSelected: Boolean,
    editorComposing: Boolean,
    initialSelStart: Int,
    initialSelEnd: Int,
) {
    /** Whether text is selected in the editor, which an empty composing text would take the place of. */
    val selected: Boolean = if (again) followedSelected else initialSelStart != initialSelEnd

    /** Whether the editor holds a composing text: known again, from its last report; an app restarting set new text. */
    val editorComposes: Boolean = again && editorComposing

    /** Whether the EditorInfo's initial text is the field's now: again, it is as the field first started, and the editor is asked. */
    val initialTextIsNow: Boolean get() = !again
}
