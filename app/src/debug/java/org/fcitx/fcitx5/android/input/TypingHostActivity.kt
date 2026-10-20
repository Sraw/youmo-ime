/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.core.CapabilityFlags
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A plain `EditText` for the instrumented `SoftKeyboardTest` to type into -- the most common
 * editor there is. Debug builds only (see this source set's manifest).
 *
 * Extras: [EXTRA_ACTION], an `EditorInfo.IME_ACTION_*` that makes it a single-line field with
 * that action; [EXTRA_INPUT_TYPE], an `InputType` (a URL field, say). Editor actions it receives are recorded in [actions]; a Return key event is not one.
 */
class TypingHostActivity : Activity() {

    lateinit var field: EditText
        private set

    val actions = CopyOnWriteArrayList<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        field = EditText(this).apply {
            // typed into as another app's field, not as this app's own (which learns nothing)
            privateImeOptions = CapabilityFlags.TEST_HOST_FIELD
            intent.getIntExtra(EXTRA_ACTION, -1).takeIf { it >= 0 }?.let {
                isSingleLine = true
                imeOptions = it
            }
            intent.getIntExtra(EXTRA_INPUT_TYPE, -1).takeIf { it >= 0 }?.let { inputType = it }
            setOnEditorActionListener { _, actionId, event ->
                // TextView offers each Return key event here too, with the event: taking it drops the
                // newline. Not told by IME_NULL: that is 0, as IME_ACTION_UNSPECIFIED is
                if (event != null) {
                    false
                } else {
                    actions += actionId
                    true
                }
            }
        }
        setContentView(FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(field, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        })
        field.requestFocus()
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_INPUT_TYPE = "inputType"
    }
}
