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
import org.fcitx.fcitx5.android.input.editing.EditorKeyPolicy
import org.fcitx.fcitx5.android.input.editing.toEditorTraits
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

/**
 * How the Return key looks and what it is announced as. Both come from the same decision as
 * what the key does ([EditorKeyPolicy.onReturn]), so the icon and the TalkBack label cannot
 * name a different action from the one a press performs.
 */
data class ReturnKeyAppearance(
    @DrawableRes val icon: Int,
    @StringRes val labelRes: Int,
    /** The editor's own name for its action (`EditorInfo.actionLabel`), when it gave one. */
    val customLabel: CharSequence? = null,
) {
    fun label(context: Context): CharSequence = customLabel ?: context.getString(labelRes)

    companion object {
        /** A plain Return: inserts a newline, or commits the composition while composing. */
        val Enter = ReturnKeyAppearance(R.drawable.ic_baseline_keyboard_return_24, R.string.a11y_key_enter)
    }
}

class ReturnKeyDrawableComponent :
    UniqueComponent<ReturnKeyDrawableComponent>(), Dependent, ManagedHandler by managedHandler() {

    companion object {
        @DrawableRes
        val DEFAULT_DRAWABLE = ReturnKeyAppearance.Enter.icon

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
                        // an action the editor named itself: say its name; the icon is still
                        // the closest generic one, as it has always been
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
        // while composing, Return commits the composition whatever the editor's action is
        val new = if (preeditEmpty) actionAppearance else ReturnKeyAppearance.Enter
        if (appearance == new) return
        appearance = new
        broadcaster.onReturnKeyDrawableUpdate(appearance)
    }
}
