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

    /** The key it is about: a view's ([KeyRef.view]) or a scene's. */
    abstract val key: KeyRef

    data class PreviewAction(
        override val key: KeyRef,
        val content: String,
        val bounds: Rect
    ) : PopupAction()

    data class PreviewUpdateAction(
        override val key: KeyRef,
        val content: String,
        /** A swipe's shortcut named: words, smaller and kept narrow ([org.fcitx.fcitx5.android.input.popup.PreviewLabel]). */
        val words: Boolean = false,
    ) : PopupAction()

    data class DismissAction(
        override val key: KeyRef
    ) : PopupAction()

    data class ShowKeyboardAction(
        override val key: KeyRef,
        val keyboard: KeyDef.Popup.Keyboard,
        val bounds: Rect
    ) : PopupAction()

    data class ShowMenuAction(
        override val key: KeyRef,
        val menu: KeyDef.Popup.Menu,
        val bounds: Rect
    ) : PopupAction()

    /** The edit menu (编辑菜单) over the key at [bounds], opened by its swipe; copy and cut greyed in a [password]. */
    data class ShowEditMenuAction(
        override val key: KeyRef,
        val bounds: Rect,
        val password: Boolean,
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
        /** Let go over a popup with nothing to pick (a table's roots, TextKeyboard): no key typed either. */
        var outConsumed: Boolean = false,
    ) : PopupAction()
}

/** The key this view draws, as a popup knows it: by its id ([KeyRef.view]). */
val View.keyRef: KeyRef get() = KeyRef.view(id)
