/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Rect
import android.view.View
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.scene.KeyRef

sealed class PopupAction {

    abstract val key: KeyRef

    data class PreviewAction(
        override val key: KeyRef,
        val content: String,
        val bounds: Rect
    ) : PopupAction()

    data class PreviewUpdateAction(
        override val key: KeyRef,
        val content: String,
        val words: Boolean = false,
        /** Already what the swipe types, so no keyboard puts its letters' case over it (a q whose first is Q read q and typed Q). */
        val shown: Boolean = false,
    ) : PopupAction()

    data class DismissAction(
        override val key: KeyRef
    ) : PopupAction()

    data class ShowKeyboardAction(
        override val key: KeyRef,
        val keyboard: KeyDef.Popup.Keyboard,
        val bounds: Rect,
    ) : PopupAction()

    data class ShowMenuAction(
        override val key: KeyRef,
        val menu: KeyDef.Popup.Menu,
        val bounds: Rect
    ) : PopupAction()

    /** [down]: opened by a swipe down (PopupMenuUi.Fixed). [outShown]: whether it opened. */
    data class ShowEditMenuAction(
        override val key: KeyRef,
        val bounds: Rect,
        val password: Boolean,
        val down: Boolean = false,
        var outShown: Boolean = false,
    ) : PopupAction()

    data class ShowInputMethodMenuAction(
        override val key: KeyRef,
        val bounds: Rect,
        val down: Boolean = false,
        var outShown: Boolean = false,
    ) : PopupAction()

    data class ChangeFocusAction(
        override val key: KeyRef,
        val x: Float,
        val y: Float,
        var outResult: Boolean = false
    ) : PopupAction()

    data class TriggerAction(
        override val key: KeyRef,
        var outAction: KeyAction? = null,
        /** Let go over a popup with nothing to pick (a table's roots): no key typed either. */
        var outConsumed: Boolean = false,
    ) : PopupAction()
}

val View.keyRef: KeyRef get() = KeyRef.view(id)
