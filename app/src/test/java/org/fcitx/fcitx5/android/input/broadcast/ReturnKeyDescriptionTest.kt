/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.broadcast

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent.Companion.appearanceFromEditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Icon, label and announcement all come from `EditorKeyPolicy.onReturn`, the decision the press uses. */
class ReturnKeyDescriptionTest {

    private fun appearance(block: EditorInfo.() -> Unit) =
        appearanceFromEditorInfo(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            block()
        })

    @Test
    fun eachEditorActionIsShownAndAnnouncedAsItself() {
        mapOf(
            EditorInfo.IME_ACTION_GO to (R.drawable.ic_baseline_arrow_forward_24 to R.string.a11y_key_action_go),
            EditorInfo.IME_ACTION_SEARCH to (R.drawable.ic_baseline_search_24 to R.string.a11y_key_action_search),
            EditorInfo.IME_ACTION_SEND to (R.drawable.ic_baseline_send_24 to R.string.a11y_key_action_send),
            EditorInfo.IME_ACTION_NEXT to (R.drawable.ic_baseline_keyboard_tab_24 to R.string.a11y_key_action_next),
            EditorInfo.IME_ACTION_DONE to (R.drawable.ic_baseline_done_24 to R.string.a11y_key_action_done),
            EditorInfo.IME_ACTION_PREVIOUS to (R.drawable.ic_baseline_keyboard_tab_reverse_24 to R.string.a11y_key_action_previous),
        ).forEach { (action, expected) ->
            val a = appearance { imeOptions = action }
            assertEquals("icon for action $action", expected.first, a.icon)
            assertEquals("label for action $action", expected.second, a.labelRes)
            assertNull(a.customLabel)
        }
    }

    @Test
    fun noActionIsEnter() {
        assertEquals(ReturnKeyAppearance.Enter, appearance { imeOptions = EditorInfo.IME_ACTION_UNSPECIFIED })
        assertEquals(ReturnKeyAppearance.Enter, appearance { imeOptions = EditorInfo.IME_ACTION_NONE })
    }

    @Test
    fun noEnterActionIsEnterEvenWithAnAction() {
        assertEquals(
            ReturnKeyAppearance.Enter,
            appearance { imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_ENTER_ACTION },
        )
    }

    /** TYPE_NULL gets a raw Enter whatever action it declares: "Done" would announce what a press does not do. */
    @Test
    fun aRawKeyEditorIsEnterEvenWithAnAction() {
        assertEquals(
            ReturnKeyAppearance.Enter,
            appearance {
                inputType = InputType.TYPE_NULL
                imeOptions = EditorInfo.IME_ACTION_DONE
            },
        )
    }

    @Test
    fun aCustomActionIsAnnouncedByTheEditorsOwnLabel() {
        val a = appearance {
            imeOptions = EditorInfo.IME_ACTION_SEND
            actionLabel = "Post"
            actionId = 0x42
        }
        assertEquals("Post", a.customLabel)
        assertEquals("the icon stays the closest generic one", R.drawable.ic_baseline_send_24, a.icon)
    }

    @Test
    fun aCustomActionWithNoGenericOneShowsTheReturnIcon() {
        val a = appearance { actionLabel = "Post"; actionId = 0x42 }
        assertEquals("Post", a.customLabel)
        assertEquals(ReturnKeyAppearance.Enter.icon, a.icon)
    }
}
