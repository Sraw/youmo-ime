/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.testbed

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The home page (a list of the pages) or one page: `am start -n …testbed/.MainActivity --es page <name>`.
 * Each page is a column of fields over a folding log of their events (EventLog).
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        show(pageOf(intent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(pageOf(intent))
    }

    // singleTask: `am start --es page` reaches the running instance (onNewIntent); a page opened
    // here replaces the content in place, and back from a page is the list
    private fun open(name: String?) {
        intent = Intent(intent).putExtra(EXTRA_PAGE, name)
        show(name)
    }

    @Deprecated("the list on back, for every API level from 29")
    override fun onBackPressed() {
        if (pageOf(intent) != null) open(null) else super.onBackPressed()
    }

    private var leaving: List<() -> Unit> = emptyList()

    /** the page's timers and dialogs gone, before another page and with the activity */
    private fun leave() {
        leaving.forEach { it() }
        leaving = emptyList()
    }

    override fun onDestroy() {
        leave()
        super.onDestroy()
    }

    // an extra of another type (am start --ei page 1) or one that does not unparcel: the list
    private fun pageOf(i: Intent?): String? = runCatching { i?.getStringExtra(EXTRA_PAGE) }.getOrNull()

    private fun show(name: String?) {
        leave()
        Fields.live.clear()
        val page = Pages.ALL.find { it.name == name }
        if (page == null) {
            setPage("输入测试台", home())
            return
        }
        val heading = "${page.name} · ${page.title}"
        val log = EventLog(page.name)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val builder = PageBuilder(this, column, log)
        leaving = builder.onLeave
        page.build(builder)
        setPage(heading, withLog(column, log))
    }

    /** A title over [content]; from API 35 the window is edge to edge, so the bars' and keyboard's insets are padding */
    private fun setPage(heading: String, content: View) {
        title = heading
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply {
            text = heading
            textSize = TITLE_SP
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.DKGRAY)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        if (Build.VERSION.SDK_INT >= EDGE_TO_EDGE_API) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                WindowInsets.CONSUMED
            }
        }
        setContentView(root)
    }

    private fun home(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        column.addView(TextView(this).apply { text = "每页也能直接打开：am start -n $packageName/.MainActivity --es page <名字>" })
        for (p in Pages.ALL) {
            column.addView(Button(this).apply {
                text = "${p.name} · ${p.title}"
                isAllCaps = false
                setOnClickListener { open(p.name) }
            })
        }
        return ScrollView(this).apply { addView(column) }
    }

    @SuppressLint("SetTextI18n")
    private fun withLog(content: View, log: EventLog): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val panel = TextView(this).apply {
            textSize = LOG_TEXT_SP
            setTextColor(Color.DKGRAY)
            setBackgroundColor(LOG_BACKGROUND)
            contentDescription = "事件记录"
        }
        log.panel = panel
        val scroll = ScrollView(this).apply { addView(panel); visibility = View.GONE }
        val toggle = Button(this).apply {
            text = "事件记录 ▲"
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener {
                val open = scroll.visibility != View.VISIBLE
                scroll.visibility = if (open) View.VISIBLE else View.GONE
                text = if (open) "事件记录 ▼" else "事件记录 ▲"
            }
        }
        root.addView(toggle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, dp(LOG_HEIGHT_DP)))
        return root
    }

    companion object {
        const val EXTRA_PAGE = "page"
        private const val LOG_TEXT_SP = 11f
        private const val TITLE_SP = 18f
        private const val EDGE_TO_EDGE_API = 35
        private const val LOG_HEIGHT_DP = 180
        private const val LOG_BACKGROUND = 0xFFEFEFEF.toInt()
    }
}

/** Two seconds here, then back where it came from (切换场景's "跳到另一个 Activity 再回来"). */
class BounceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "另一个 Activity，2 秒后自动返回"; gravity = Gravity.CENTER })
        window.decorView.postDelayed({ finish() }, Pages.DELAY_MS)
    }
}

fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
