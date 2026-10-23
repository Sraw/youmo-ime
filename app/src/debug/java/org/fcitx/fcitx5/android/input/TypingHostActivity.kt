/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import org.fcitx.fcitx5.android.core.CapabilityFlags
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A plain `EditText` for the instrumented `SoftKeyboardTest` and the UAT (`uat/tools/uat.py`) to
 * type into -- the most common editor there is, and one whose options the test page's WebView
 * cannot set. Debug builds only (see this source set's manifest).
 *
 * Extras: [EXTRA_ACTION], an `EditorInfo.IME_ACTION_*` that makes it a single-line field with
 * that action; [EXTRA_INPUT_TYPE], an `InputType` (a URL field, say). Editor actions it receives are
 * recorded in [actions]; a Return key event is not one.
 * For the UAT, all settable with `am start --ez/--es`: [EXTRA_NEXT], a single-line field whose
 * action is "next" with a second field under it that the action moves to (the WebView test browser
 * ignores an input method's "next", so a native field shows it); [EXTRA_NO_LEARNING], the field
 * asks for no personalized learning (what an incognito tab's fields ask); [EXTRA_SENSITIVE_CLIP],
 * that text put on the clipboard marked sensitive, as a password manager copies a password.
 */
class TypingHostActivity : Activity() {

    lateinit var field: EditText
        private set

    val actions = CopyOnWriteArrayList<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = intent.getBooleanExtra(EXTRA_NEXT, false)
        field = EditText(this).apply {
            // typed into as another app's field, not as this app's own (which learns nothing)
            privateImeOptions = CapabilityFlags.TEST_HOST_FIELD
            intent.getIntExtra(EXTRA_ACTION, -1).takeIf { it >= 0 }?.let {
                isSingleLine = true
                imeOptions = it
            }
            if (next) {
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_NEXT
                // the UAT tells the two fields apart by this (an empty field's hint reads as no text)
                contentDescription = FIRST_FIELD
            }
            if (intent.getBooleanExtra(EXTRA_NO_LEARNING, false)) {
                imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
            intent.getIntExtra(EXTRA_INPUT_TYPE, -1).takeIf { it >= 0 }?.let { inputType = it }
            setOnEditorActionListener { _, actionId, event ->
                // TextView offers each Return key event here too, with the event: taking it drops the
                // newline. Not told by IME_NULL: that is 0, as IME_ACTION_UNSPECIFIED is. In the
                // "next" field a Return key is taken: a single-line TextView moves the focus on a
                // Return of its own, so only the input method's "next" action may move it here
                if (event != null) {
                    next
                } else {
                    actions += actionId
                    // "next" left to TextView, which moves the focus on as in any app
                    !(next && actionId == EditorInfo.IME_ACTION_NEXT)
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            if (next) {
                addView(EditText(this@TypingHostActivity).apply {
                    privateImeOptions = CapabilityFlags.TEST_HOST_FIELD
                    isSingleLine = true
                    contentDescription = SECOND_FIELD
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        })
        field.requestFocus()
        // once per start: not again when a configuration change (the UAT's landscape) recreates it
        if (savedInstanceState == null) intent.getStringExtra(EXTRA_SENSITIVE_CLIP)?.let(::putSensitiveClip)
    }

    private fun putSensitiveClip(text: String) {
        val clip = ClipData.newPlainText("uat", text)
        // the flag is Android 13's; older systems have no sensitive clips to show as dots
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_INPUT_TYPE = "inputType"
        const val EXTRA_NEXT = "next"
        const val EXTRA_NO_LEARNING = "noLearning"
        const val EXTRA_SENSITIVE_CLIP = "sensitiveClip"
        const val FIRST_FIELD = "第一项"
        const val SECOND_FIELD = "第二项"
    }
}
