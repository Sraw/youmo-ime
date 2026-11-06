/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.testbed

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

class Page(val name: String, val title: String, val build: PageBuilder.() -> Unit)

/** Adds a page's views to its column; every field gets the resource id [name] (res/values/ids.xml). */
class PageBuilder(val activity: Activity, private val column: LinearLayout, val log: EventLog) {
    val imm: InputMethodManager = activity.getSystemService(InputMethodManager::class.java)

    @SuppressLint("DiscouragedApi")
    fun idOf(name: String): Int = activity.resources.getIdentifier(name, "id", activity.packageName)
        .also { check(it != 0) { "res/values/ids.xml: no id $name" } }

    /** A field, its hint and contentDescription saying what it tests */
    fun edit(
        name: String,
        desc: String,
        type: Int = InputType.TYPE_CLASS_TEXT,
        ime: Int = EditorInfo.IME_ACTION_UNSPECIFIED,
        context: Context = activity,
        idName: String = name,
        setup: TbEdit.() -> Unit = {},
    ): TbEdit = TbEdit(context, name, log).apply {
        id = idOf(idName)
        inputType = type
        imeOptions = ime
        hint = desc
        contentDescription = "$name：$desc"
        setup()
    }

    fun field(name: String, desc: String, type: Int = InputType.TYPE_CLASS_TEXT, ime: Int = EditorInfo.IME_ACTION_UNSPECIFIED, idName: String = name, setup: TbEdit.() -> Unit = {}) =
        edit(name, desc, type, ime, idName = idName, setup = setup).also { add(it) }

    fun add(v: View) = column.addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

    fun label(text: String) = add(TextView(activity).apply { this.text = text; setPadding(0, activity.dp(8), 0, 0) })

    fun button(text: String, onClick: (Button) -> Unit) = Button(activity).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick(this) }
    }.also { add(it) }

    fun hideKeyboard(v: View) = imm.hideSoftInputFromWindow(v.windowToken, 0)

    /** Run when the page goes (another page, the activity destroyed): its timers stopped */
    val onLeave = mutableListOf<() -> Unit>()

    /** A main-thread handler whose callbacks die with the page */
    fun handler(): Handler = Handler(Looper.getMainLooper()).also { h -> onLeave += { h.removeCallbacksAndMessages(null) } }

    /** [d] shown; its field out of the state (Fields) once dismissed */
    fun show(d: Dialog, field: Stateful) {
        d.setOnDismissListener { Fields.remove(field) }
        onLeave += { d.dismiss() }
        d.show()
    }
}

object Pages {
    const val DELAY_MS = 2000L
    private const val TEXT = InputType.TYPE_CLASS_TEXT
    private const val NUMBER = InputType.TYPE_CLASS_NUMBER
    private const val DATETIME = InputType.TYPE_CLASS_DATETIME
    private const val PHONE_HEAD = 3
    private const val PHONE_GROUP = 4

    val ALL = listOf(
        Page("types", "普通 EditText 输入类型") { types() },
        Page("ime", "imeOptions 和焦点") { imeOptions() },
        Page("behavior", "模拟应用行为") { behavior() },
        Page("webview", "WebView 本地网页") { webview() },
        Page("switch", "切换场景") { switching() },
    )

    private fun PageBuilder.types() {
        field("text", "普通文本")
        field("multiline", "多行", TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        field("short_message", "短消息，回车是发送", TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE, EditorInfo.IME_ACTION_SEND)
        field("cap_chars", "全部大写（CAP_CHARACTERS）", TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        field("cap_words", "单词首字母大写（CAP_WORDS）", TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        field("cap_sentences", "句首大写（CAP_SENTENCES）", TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        field("no_suggestions", "不要提示（NO_SUGGESTIONS）", TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        field("email", "邮箱", TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        field("uri", "网址（URI）", TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        field("person_name", "人名", TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
        // Android has no postal-code type: the postal address variation is the nearest
        field("postal", "邮编/地址（POSTAL_ADDRESS）", TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS)
        field("number", "数字", NUMBER)
        field("signed", "带符号数字", NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)
        field("decimal", "小数", NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        field("phone", "电话", InputType.TYPE_CLASS_PHONE)
        field("date", "日期", DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE)
        field("time", "时间", DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        field("datetime", "日期时间", DATETIME)
        field("pw_text", "文本密码", TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        field("pw_number", "数字密码", NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        field("pw_web", "网页密码", TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        field("pw_visible", "可见密码", TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        field("no_learning", "不要个性化学习（NO_PERSONALIZED_LEARNING）", ime = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        field("no_extract", "横屏不进全屏提取（NO_EXTRACT_UI）", ime = EditorInfo.IME_FLAG_NO_EXTRACT_UI)
    }

    private fun PageBuilder.imeOptions() {
        field("ime_go", "回车是 go", ime = EditorInfo.IME_ACTION_GO)
        field("ime_search", "回车是 search", ime = EditorInfo.IME_ACTION_SEARCH)
        field("ime_send", "回车是 send", ime = EditorInfo.IME_ACTION_SEND)
        field("ime_next", "回车是 next", ime = EditorInfo.IME_ACTION_NEXT)
        field("ime_previous", "回车是 previous", ime = EditorInfo.IME_ACTION_PREVIOUS)
        field("ime_done", "回车是 done", ime = EditorInfo.IME_ACTION_DONE)
        field("ime_none", "回车是 none（换行键）", ime = EditorInfo.IME_ACTION_NONE)
        label("一组可以 next/previous 来回跳的框：")
        val chain = listOf("chain_1", "chain_2", "chain_3")
        chain.forEachIndexed { i, n ->
            var flags = if (i == chain.lastIndex) EditorInfo.IME_ACTION_PREVIOUS else EditorInfo.IME_ACTION_NEXT
            if (i > 0) flags = flags or EditorInfo.IME_FLAG_NAVIGATE_PREVIOUS
            if (i < chain.lastIndex) flags = flags or EditorInfo.IME_FLAG_NAVIGATE_NEXT
            field(n, "跳转链第 ${i + 1} 个", ime = flags)
        }
        field("with_hint", "这是提示文字（hint）")
        field("initial_text", "有初始文字") { setText("已经有的文字") }
        field("preselected", "预先选中一段") { setText("前面选中的后面"); setSelection(2, 4) }
        field("max10", "最多 10 个字（maxLength=10）") { filters = arrayOf(InputFilter.LengthFilter(MAX_LENGTH)) }
    }

    private const val MAX_LENGTH = 10
    private const val DEFAULT_INTERVAL_S = 3

    @SuppressLint("SetTextI18n")
    private fun PageBuilder.behavior() {
        label("聊天框：点发送或回车就清空（事件记录里 connection 是一次新的 InputConnection，即重启输入）")
        field("chat_clear", "聊天：发送后 text.clear()，不重启输入", ime = EditorInfo.IME_ACTION_SEND) {
            onAction = { text.clear(); true }
        }
        // setText makes the framework restart the input itself: at once up to Android 10, deferred from
        // API 30 (Editor.scheduleRestartInputForSetText), through invalidateInput from API 34 (no new connection)
        field("chat_settext", "聊天：发送后 setText(\"\")（框架自己让输入法重来：10 直接重启，30 起延后，34 起 invalidateInput）", ime = EditorInfo.IME_ACTION_SEND) {
            onAction = { setText(""); true }
        }
        field("chat_restart", "聊天：发送后 setText(\"\") 再 restartInput", ime = EditorInfo.IME_ACTION_SEND) {
            onAction = { setText(""); imm.restartInput(this); true }
        }
        field("chat_naive", "粗糙的聊天：回车的按下和抬起各处理一次（清两次）", ime = EditorInfo.IME_ACTION_SEND) {
            naive = true
            onAction = { setText(""); true }
        }
        field("chat_hide", "聊天：发送后清空并收起键盘", ime = EditorInfo.IME_ACTION_SEND) {
            onAction = { text.clear(); hideKeyboard(this); clearFocus(); true }
        }
        label("应用在 composing 时自己提交 / 改写")
        field("finish_composing", "搜索框：每次变化应用就 clearComposingText()") {
            addTextChangedListener(afterChange { clearComposingText() })
        }
        field("finish_composing_async", "搜索框：每次变化应用 post{} 异步去掉 composing") {
            addTextChangedListener(afterChange { post { clearComposingText() } })
        }
        field("phone_format", "手机号：应用自动加空格（3-4-4）", InputType.TYPE_CLASS_PHONE) {
            addTextChangedListener(afterChange { s ->
                val digits = s.filter { it.isDigit() }
                val formatted = (listOf(digits.take(PHONE_HEAD)) + digits.drop(PHONE_HEAD).chunked(PHONE_GROUP))
                    .filter { it.isNotEmpty() }.joinToString(" ")
                if (formatted != s.toString()) s.replace(0, s.length, formatted)
            })
        }
        field("upper", "输入时应用自动转大写") {
            addTextChangedListener(afterChange { s ->
                for (i in s.indices) {
                    val u = s[i].uppercaseChar()
                    // a letter whose upper case is itself (ß, some others) left alone: no endless rewrite
                    if (u != s[i]) s.replace(i, i + 1, u.toString())
                }
            })
        }
        timedChanges()
        val restart = field("restart_target", "点下面的按钮就对它 restartInput")
        button("restartInput") { imm.restartInput(restart); log.event("restart_target", "app", JSONObject().put("detail", "restartInput")) }
        unreadable()
        containers()
    }

    @SuppressLint("SetTextI18n")
    private fun PageBuilder.timedChanges() {
        label("应用每隔 N 秒改文字或光标")
        val interval = field("interval", "间隔秒数", NUMBER) { setText("$DEFAULT_INTERVAL_S") }
        val timed = field("timed", "应用定时改它：setText 加 # 或 setSelection(0)")
        fun every() = (interval.text.toString().toLongOrNull() ?: DEFAULT_INTERVAL_S.toLong()).coerceAtLeast(1) * MS
        timer("定时 setText", every = ::every) {
            timed.setText("${timed.text}#"); timed.setSelection(timed.length())
            log.event("timed", "app", JSONObject().put("detail", "setText"))
        }
        timer("定时 setSelection(0)", every = ::every) {
            timed.setSelection(0)
            log.event("timed", "app", JSONObject().put("detail", "setSelection(0)"))
        }
    }

    /** A start/stop button of its own, its own handler */
    private fun PageBuilder.timer(label: String, every: () -> Long, tick: () -> Unit) {
        val h = handler()
        var on = false
        val r = object : Runnable {
            override fun run() {
                tick()
                h.postDelayed(this, every())
            }
        }
        button("$label：开始") { b ->
            on = !on
            b.text = if (on) "$label：停止" else "$label：开始"
            if (on) h.post(r) else h.removeCallbacks(r)
        }
    }

    private fun PageBuilder.unreadable() {
        field("no_digits", "InputFilter 不让输入数字") {
            filters = arrayOf(InputFilter { src, start, end, _, _, _ ->
                val kept = src.subSequence(start, end).filterNot { it.isDigit() }
                if (kept.length == end - start) null else kept
            })
        }
        field("null_extracted", "getExtractedText 返回 null") { nullExtracted = true }
        field("null_before", "光标前读不到：getTextBeforeCursor/getSurroundingText 返回 null，没有初始周围文字") { nullBeforeCursor = true }
        label("TYPE_NULL 原始按键（像终端，点一下弹键盘）")
        add(TbTerminal(activity, "terminal", log).apply { id = idOf("terminal"); minimumHeight = activity.dp(TERMINAL_DP) })
        label("两个框共用一个 View id（shared）")
        field("shared_a", "共用 id 的第一个", idName = "shared")
        field("shared_b", "共用 id 的第二个", idName = "shared")
    }

    private fun PageBuilder.containers() {
        button("打开对话框里的输入框") {
            val e = edit("dialog_field", "对话框里的输入框")
            show(AlertDialog.Builder(activity).setTitle("对话框").setView(e).setPositiveButton("关闭", null).create(), e)
        }
        button("打开底部弹出层里的输入框") {
            val d = Dialog(activity)
            val e = edit("sheet_field", "底部弹出层里的输入框", context = d.context)
            d.setContentView(e)
            d.window?.apply {
                setGravity(Gravity.BOTTOM)
                setLayout(MATCH_PARENT, WRAP_CONTENT)
            }
            show(d, e)
        }
        field("landscape_extract", "横屏全屏提取：先把屏幕转横再点（不带 noExtractUi）")
        label("最后一个框在内容最底部，弹出键盘时会被挡住或要页面跟着滚")
        add(View(activity).apply { minimumHeight = activity.dp(SPACER_DP) })
        field("bottom", "最底部的框")
    }

    private const val MS = 1000L
    private const val SPACER_DP = 600
    private const val TERMINAL_DP = 56

    @SuppressLint("SetJavaScriptEnabled")
    private fun PageBuilder.webview() {
        label("本地网页（assets/web.html），不访问网络；网页里的事件也进下面的记录（field 是网页框的 id）")
        val web = WebView(activity).apply {
            settings.javaScriptEnabled = true
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    val o = runCatching { JSONObject(m.message()) }.getOrNull() ?: return false
                    log.event(o.optString("field"), "web_" + o.optString("event"), o.put("detail", o.optString("value")))
                    return true
                }
            }
            loadUrl("file:///android_asset/web.html")
        }
        add(web)
    }

    private fun PageBuilder.switching() {
        val a = field("switch_a", "框 A")
        val b = field("switch_b", "框 B")
        val h = handler()
        button("2 秒后把焦点切到另一个框") {
            h.postDelayed({
                val to = if (a.isFocused) b else a
                to.requestFocus()
                log.event(to.name, "app", JSONObject().put("detail", "requestFocus"))
            }, DELAY_MS)
        }
        button("2 秒后跳到另一个 Activity，再自动回来") {
            h.postDelayed({ activity.startActivity(Intent(activity, BounceActivity::class.java)) }, DELAY_MS)
        }
    }

    private fun afterChange(f: (Editable) -> Unit) = object : TextWatcher {
        private var busy = false
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable) {
            if (busy) return
            busy = true
            try { f(s) } finally { busy = false }
        }
    }
}
