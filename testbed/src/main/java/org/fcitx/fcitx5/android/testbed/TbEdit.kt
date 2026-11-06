/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.testbed

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import java.lang.ref.WeakReference

/** Every event of a page's fields, in order: the page's log panel and logcat (tag Testbed, one JSON a line). */
class EventLog(private val page: String) {
    var panel: TextView? = null
    private val lines = ArrayDeque<String>()

    fun event(field: String, type: String, data: JSONObject = JSONObject()) {
        data.put("page", page).put("field", field).put("event", type).put("t", System.currentTimeMillis())
        Log.i(TAG, data.toString())
        lines.addLast("$field $type ${data.optString("detail")}")
        while (lines.size > MAX_LINES) lines.removeFirst()
        panel?.text = lines.reversed().joinToString("\n")
    }

    companion object {
        const val TAG = "Testbed"
        private const val MAX_LINES = 200
    }
}

/** The fields of the page on screen (dialogs' too): what StateProvider reads. Main thread only. */
object Fields {
    val live = mutableListOf<WeakReference<TbEdit>>()

    fun all(): List<TbEdit> = live.mapNotNull { it.get() }
}

/**
 * An EditText that reports its text changes (with the composing span), selection, focus,
 * editor actions and keys to [log], under [name] (also its resource id's name, when it has one).
 */
@SuppressLint("AppCompatCustomView")
class TbEdit(context: Context, val name: String, private val log: EventLog) : EditText(context) {
    /** "读不到内容": the IME's getExtractedText answered null */
    var nullExtracted = false

    /** "读不到内容": the IME's getTextBeforeCursor answered null */
    var nullBeforeCursor = false
    private var ready = false

    init {
        addTextChangedListener(object : TextWatcher {
            private var before = ""
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                before = s.toString()
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) {
                log.event(name, "text", state().put("before", before).put("detail", "「$before」→「$s」"))
            }
        })
        setOnEditorActionListener { _, actionId, event ->
            log.event(name, "action", JSONObject().put("actionId", actionId).put("key", event?.keyCode ?: -1)
                .put("detail", "actionId=$actionId"))
            onAction?.invoke(actionId) ?: false
        }
        Fields.live.add(WeakReference(this))
        ready = true
    }

    /** What an editor action does in the app: true when it is handled */
    var onAction: ((Int) -> Boolean)? = null

    fun state(): JSONObject {
        val t = text
        return JSONObject().put("name", name).put("text", t.toString())
            .put("selStart", selectionStart).put("selEnd", selectionEnd)
            .put("compStart", BaseInputConnection.getComposingSpanStart(t))
            .put("compEnd", BaseInputConnection.getComposingSpanEnd(t))
            .put("focused", isFocused).put("hint", hint?.toString() ?: "")
            .put("desc", contentDescription?.toString() ?: "")
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // called from EditText's constructor, before this class's fields are set
        if (ready) log.event(name, "selection", state().put("detail", "$selStart..$selEnd"))
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (ready) log.event(name, "focus", JSONObject().put("focused", focused).put("detail", "$focused"))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        log.event(name, "key", JSONObject().put("keyCode", keyCode).put("detail", KeyEvent.keyCodeToString(keyCode)))
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        if (!nullExtracted && !nullBeforeCursor) return ic
        return object : InputConnectionWrapper(ic, false) {
            override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
                if (nullExtracted) null else super.getExtractedText(request, flags)

            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
                if (nullBeforeCursor) null else super.getTextBeforeCursor(n, flags)
        }
    }
}
