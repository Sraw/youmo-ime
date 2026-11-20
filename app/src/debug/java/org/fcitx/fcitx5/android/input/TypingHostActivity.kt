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
 * A plain `EditText` for SoftKeyboardTest and uat.py, with options the test page's WebView cannot set. Extras
 * (`am start --ez/--es/--ei`): [EXTRA_ACTION], [EXTRA_INPUT_TYPE], [EXTRA_NEXT] (a native "next" field, which the
 * WebView ignores), [EXTRA_NO_LEARNING], [EXTRA_SENSITIVE_CLIP], [EXTRA_CLIP] × [EXTRA_CLIP_TIMES]. Received editor
 * actions go to [actions]; a Return key event is not one.
 */
class TypingHostActivity : Activity() {

    lateinit var field: EditText
        private set

    val actions = CopyOnWriteArrayList<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = intent.getBooleanExtra(EXTRA_NEXT, false)
        field = EditText(this).apply {
            // typed into as another app's field: this app's own learn nothing
            privateImeOptions = CapabilityFlags.TEST_HOST_FIELD
            intent.getIntExtra(EXTRA_ACTION, -1).takeIf { it >= 0 }?.let {
                isSingleLine = true
                imeOptions = it
            }
            if (next) {
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_NEXT
                // the UAT tells the fields apart by this (an empty field's hint reads as no text)
                contentDescription = FIRST_FIELD
            }
            if (intent.getBooleanExtra(EXTRA_NO_LEARNING, false)) {
                imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
            intent.getIntExtra(EXTRA_INPUT_TYPE, -1).takeIf { it >= 0 }?.let { inputType = it }
            setOnEditorActionListener { _, actionId, event ->
                // TextView offers each Return key event here too: taking it drops the newline, and IME_NULL cannot tell it (it is
                // 0, as IME_ACTION_UNSPECIFIED is). In the "next" field Return is taken, so only the IME's "next" moves the focus.
                if (event != null) {
                    next
                } else {
                    actions += actionId
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
        // not again when a configuration change (the UAT's landscape) recreates it
        if (savedInstanceState == null) intent.getStringExtra(EXTRA_SENSITIVE_CLIP)?.let(::putSensitiveClip)
        if (savedInstanceState == null) intent.getStringExtra(EXTRA_CLIP)?.let {
            val times = intent.getIntExtra(EXTRA_CLIP_TIMES, 1).coerceAtLeast(1)
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("uat", it.repeat(times)))
        }
    }

    private fun putSensitiveClip(text: String) {
        val clip = ClipData.newPlainText("uat", text)
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
        const val EXTRA_CLIP = "clip"
        const val EXTRA_CLIP_TIMES = "clipTimes"
        const val FIRST_FIELD = "第一项"
        const val SECOND_FIELD = "第二项"
    }
}
