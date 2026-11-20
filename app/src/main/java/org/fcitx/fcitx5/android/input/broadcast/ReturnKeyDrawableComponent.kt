/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.broadcast

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.dependency.TypedComponent
import org.fcitx.fcitx5.android.input.editing.EditorKeyPolicy
import org.fcitx.fcitx5.android.input.editing.toEditorTraits
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

/** From the same decision as what the key does ([EditorKeyPolicy.onReturn]), so the icon and label cannot name another action. */
data class ReturnKeyAppearance(
    @DrawableRes val icon: Int,
    @StringRes val labelRes: Int,
    val customLabel: CharSequence? = null,
    val composing: Boolean = false,
) {
    fun label(context: Context): CharSequence = customLabel ?: context.getString(labelRes)

    companion object {
        val Enter = ReturnKeyAppearance(R.drawable.ic_baseline_keyboard_return_24, R.string.a11y_key_enter)

        val Composing = Enter.copy(composing = true)
    }
}

class ReturnKeyDrawableComponent :
    TypedComponent<ReturnKeyDrawableComponent>(), Dependent, ManagedHandler by managedHandler() {

    companion object {
        private fun appearanceOfAction(action: Int): ReturnKeyAppearance? = when (action) {
            EditorInfo.IME_ACTION_GO ->
                ReturnKeyAppearance(R.drawable.ic_baseline_arrow_forward_24, R.string.a11y_key_action_go)
            EditorInfo.IME_ACTION_SEARCH ->
                ReturnKeyAppearance(R.drawable.ic_baseline_search_24, R.string.a11y_key_action_search)
            EditorInfo.IME_ACTION_SEND ->
                ReturnKeyAppearance(R.drawable.ic_baseline_send_24, R.string.a11y_key_action_send)
            EditorInfo.IME_ACTION_NEXT ->
                ReturnKeyAppearance(R.drawable.ic_baseline_keyboard_tab_24, R.string.a11y_key_action_next)
            EditorInfo.IME_ACTION_DONE ->
                ReturnKeyAppearance(R.drawable.ic_baseline_done_24, R.string.a11y_key_action_done)
            EditorInfo.IME_ACTION_PREVIOUS ->
                ReturnKeyAppearance(R.drawable.ic_baseline_keyboard_tab_reverse_24, R.string.a11y_key_action_previous)
            else -> null
        }

        fun appearanceFromEditorInfo(info: EditorInfo): ReturnKeyAppearance {
            val traits = info.toEditorTraits()
            return when (val action = EditorKeyPolicy.onReturn(traits)) {
                EditorKeyPolicy.ReturnAction.SendEnter -> ReturnKeyAppearance.Enter
                is EditorKeyPolicy.ReturnAction.PerformEditorAction ->
                    if (traits.customActionId != null) {
                        // the icon is still the closest generic one
                        val generic = traits.imeAction?.let(::appearanceOfAction) ?: ReturnKeyAppearance.Enter
                        generic.copy(customLabel = info.actionLabel)
                    } else {
                        appearanceOfAction(action.actionId) ?: ReturnKeyAppearance.Enter
                    }
            }
        }
    }

    private val broadcaster: InputBroadcaster by manager.must()

    var appearance: ReturnKeyAppearance = ReturnKeyAppearance.Enter
        private set

    private var actionAppearance: ReturnKeyAppearance = ReturnKeyAppearance.Enter

    fun updateDrawableOnEditorInfo(info: EditorInfo) {
        actionAppearance = appearanceFromEditorInfo(info)
        if (appearance == actionAppearance) return
        appearance = actionAppearance
        broadcaster.onReturnKeyDrawableUpdate(appearance)
    }

    fun updateDrawableOnPreedit(preeditEmpty: Boolean) {
        val new = if (preeditEmpty) actionAppearance else ReturnKeyAppearance.Composing
        if (appearance == new) return
        appearance = new
        broadcaster.onReturnKeyDrawableUpdate(appearance)
    }
}
