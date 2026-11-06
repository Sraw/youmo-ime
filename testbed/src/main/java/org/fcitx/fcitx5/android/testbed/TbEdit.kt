/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.testbed

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.TextSnapshot
import android.widget.EditText
import android.widget.TextView
import android.annotation.TargetApi
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

/** A field StateProvider can read */
interface Stateful {
    fun state(): JSONObject
}

/** The fields of the page on screen (dialogs' too): what StateProvider reads. Main thread only. */
object Fields {
    val live = mutableListOf<WeakReference<Stateful>>()

    fun all(): List<Stateful> = live.mapNotNull { it.get() }

    fun remove(f: Stateful) = live.removeAll { it.get() === f || it.get() == null }
}

/**
 * An EditText that reports its text changes (with the composing span), selection, focus,
 * editor actions, keys and each new InputConnection (a restartInput) to [log], under [name].
 */
@SuppressLint("AppCompatCustomView")
class TbEdit(context: Context, val name: String, private val log: EventLog) : EditText(context), Stateful {
    /** "读不到内容": the IME's getExtractedText answered null */
    var nullExtracted = false

    /** "读不到内容": nothing before the cursor reaches the IME (getTextBeforeCursor, the surrounding text) */
    var nullBeforeCursor = false

    /**
     * What an editor action does in the app: true when handled. Run once per action: for the IME's
     * action (no key event) or a key's down; the key's up is swallowed. [naive]: run for every call.
     */
    var onAction: ((Int) -> Boolean)? = null
    var naive = false
    private var ready = false
    private var batch = 0
    private var connections = 0

    private val password: Boolean
        get() {
            val v = inputType and InputType.TYPE_MASK_VARIATION
            return when (inputType and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_TEXT -> v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                InputType.TYPE_CLASS_NUMBER -> v == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }
        }

    init {
        addTextChangedListener(object : TextWatcher {
            private var before = ""
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                before = s.toString()
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) {
                val o = logged()
                if (password) {
                    o.put("beforeLength", before.length).put("detail", "${before.length} → ${s.length} 个字（密码只记长度）")
                } else {
                    o.put("before", before).put("detail", "「$before」→「$s」")
                }
                log.event(name, "text", o)
            }
        })
        setOnEditorActionListener { _, actionId, event ->
            log.event(name, "action", JSONObject().put("actionId", actionId).put("key", event?.keyCode ?: -1)
                .put("keyAction", event?.action ?: -1).put("detail", "actionId=$actionId key=${event?.action ?: "-"}"))
            when {
                naive || event == null || event.action == KeyEvent.ACTION_DOWN -> onAction?.invoke(actionId) ?: false
                else -> onAction != null   // the key's up: already done on its down
            }
        }
        Fields.live.add(WeakReference(this))
        ready = true
    }

    override fun state(): JSONObject {
        val t = text
        return JSONObject().put("name", name).put("text", t.toString())
            .put("selStart", selectionStart).put("selEnd", selectionEnd)
            .put("compStart", BaseInputConnection.getComposingSpanStart(t))
            .put("compEnd", BaseInputConnection.getComposingSpanEnd(t))
            .put("focused", isFocused).put("batch", batch).put("connections", connections)
            .put("hint", hint?.toString() ?: "")
            .put("desc", contentDescription?.toString() ?: "")
    }

    /** [state] for the log: a password field's length only */
    private fun logged(): JSONObject = state().apply {
        if (password) {
            put("length", text.length)
            remove("text")
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // called from EditText's constructor, before this class's fields are set
        if (ready) log.event(name, "selection", logged().put("detail", "$selStart..$selEnd"))
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
        // without the initial surrounding text (API 30+ hands it to the IME in the EditorInfo)
        val ic = if (nullBeforeCursor) {
            val full = EditorInfo()
            super.onCreateInputConnection(full).also { copyAttrs(full, outAttrs) }
        } else {
            super.onCreateInputConnection(outAttrs)
        } ?: return null
        connections++
        batch = 0
        log.event(name, "connection", JSONObject().put("n", connections).put("detail", "InputConnection #$connections"))
        return Wrapper(ic)
    }

    private fun copyAttrs(from: EditorInfo, to: EditorInfo) {
        to.inputType = from.inputType
        to.imeOptions = from.imeOptions
        to.privateImeOptions = from.privateImeOptions
        to.actionLabel = from.actionLabel
        to.actionId = from.actionId
        to.initialSelStart = from.initialSelStart
        to.initialSelEnd = from.initialSelEnd
        to.initialCapsMode = from.initialCapsMode
        to.hintText = from.hintText
        to.label = from.label
        to.extras = from.extras
        to.hintLocales = from.hintLocales
        to.contentMimeTypes = from.contentMimeTypes
    }

    private inner class Wrapper(ic: InputConnection) : InputConnectionWrapper(ic, false) {
        override fun beginBatchEdit(): Boolean {
            batch++
            return super.beginBatchEdit()
        }

        override fun endBatchEdit(): Boolean {
            if (batch > 0) batch--
            return super.endBatchEdit()
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
            if (nullExtracted) refused("getExtractedText") else super.getExtractedText(request, flags)

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
            if (nullBeforeCursor) refused("getTextBeforeCursor") else super.getTextBeforeCursor(n, flags)

        @TargetApi(Build.VERSION_CODES.S)
        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? =
            if (nullBeforeCursor) refused("getSurroundingText") else super.getSurroundingText(beforeLength, afterLength, flags)

        @TargetApi(Build.VERSION_CODES.TIRAMISU)
        override fun takeSnapshot(): TextSnapshot? =
            if (nullBeforeCursor || nullExtracted) refused("takeSnapshot") else super.takeSnapshot()

        // the IME's read, answered null, in the log: proof it asked and got nothing
        private fun <T> refused(call: String): T? {
            post { log.event(name, "refused", JSONObject().put("call", call).put("detail", "$call → null")) }
            return null
        }
    }
}

/**
 * A terminal-like view as Termux does it: a text editor to the framework, TYPE_NULL to the IME,
 * which then sends key events (or commits through BaseInputConnection, which turns them into keys).
 */
class TbTerminal(context: Context, val name: String, private val log: EventLog) : View(context), Stateful {
    private val buffer = StringBuilder()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = "$name：TYPE_NULL 原始按键（像终端）"
        Fields.live.add(WeakReference(this))
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        log.event(name, "connection", JSONObject().put("detail", "BaseInputConnection(TYPE_NULL)"))
        return BaseInputConnection(this, false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            requestFocus()
            context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when {
            keyCode == KeyEvent.KEYCODE_DEL -> if (buffer.isNotEmpty()) buffer.setLength(buffer.length - 1)
            keyCode == KeyEvent.KEYCODE_ENTER -> buffer.append('\n')
            event.unicodeChar != 0 -> buffer.appendCodePoint(event.unicodeChar)
        }
        log.event(name, "key", JSONObject().put("keyCode", keyCode).put("down", true).put("detail", KeyEvent.keyCodeToString(keyCode)))
        invalidate()
        return true
    }

    // BaseInputConnection's dummy mode sends a commit of more than one character as one ACTION_MULTIPLE
    @Suppress("DEPRECATION")
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        val chars = event.characters ?: return super.onKeyMultiple(keyCode, repeatCount, event)
        buffer.append(chars)
        log.event(name, "key", JSONObject().put("keyCode", keyCode).put("characters", chars).put("detail", "multiple「$chars」"))
        invalidate()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        log.event(name, "key", JSONObject().put("keyCode", keyCode).put("down", false).put("detail", "↑" + KeyEvent.keyCodeToString(keyCode)))
        return true
    }

    // the buffer drawn as plain text
    private val paint = android.graphics.Paint().apply { textSize = TEXT_PX; isAntiAlias = true }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(if (isFocused) FOCUSED_BG else IDLE_BG)
        canvas.drawText("$ " + buffer.toString().replace('\n', '⏎'), PAD_PX, TEXT_PX + PAD_PX, paint)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        log.event(name, "focus", JSONObject().put("focused", gainFocus).put("detail", "$gainFocus"))
        invalidate()
    }

    override fun state(): JSONObject = JSONObject().put("name", name).put("text", buffer.toString())
        .put("focused", isFocused).put("batch", 0).put("desc", contentDescription?.toString() ?: "")

    private companion object {
        const val TEXT_PX = 40f
        const val PAD_PX = 16f
        const val FOCUSED_BG = 0xFFDDEEFF.toInt()
        const val IDLE_BG = 0xFFEEEEEE.toInt()
    }
}
