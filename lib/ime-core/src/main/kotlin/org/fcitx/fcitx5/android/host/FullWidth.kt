/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/**
 * fcitx's `Fullwidth`, on a context whose status area has [action]: [keyHook] commits an unmodified space..`~` press
 * full width through [commit] and accepts it without filtering (ReservedLast still runs; only here is space U+3000);
 * [commitFilter] converts every committed character above space. No hotkey, nothing saved: off at start.
 */
class FullWidth(private val statusArea: StatusArea, private val commit: (InputContext, String) -> Unit) {

    var enabled: Boolean = false
        private set

    val action: StatusAction = object : StatusAction("fullwidth") {
        override fun icon(ic: InputContext) = if (enabled) "fcitx-fullwidth-active" else "fcitx-fullwidth-inactive"
        override fun shortText(ic: InputContext) = if (enabled) "Full width Character" else "Half width Character"
        override fun active(ic: InputContext) = enabled
        override fun activate(ic: InputContext) = setEnabled(!enabled, ic)
    }.also(statusArea::register)

    fun setEnabled(enabled: Boolean, ic: InputContext) {
        if (enabled == this.enabled) return
        this.enabled = enabled
        action.updateOn(ic)
    }

    fun inWhiteList(ic: InputContext) = statusArea.contains(ic, action)

    val keyHook = PostKeyHook { ic, e ->
        // the engine's acceptance is not asked: fcitx's filterKey does not look at it
        if (!enabled || !inWhiteList(ic)) return@PostKeyHook
        if (e.key.states != 0 || e.release) return@PostKeyHook
        val sym = e.key.sym
        if (sym >= KeySyms.SPACE && sym - KeySyms.SPACE < TABLE.length) {
            e.accept()
            commit(ic, TABLE[sym - KeySyms.SPACE].toString())
        }
    }

    val commitFilter = CommitFilter { ic, text ->
        if (!enabled || !inWhiteList(ic)) return@CommitFilter text
        convert(text)
    }

    companion object {
        /** `sCornerTrans`: U+0020–U+007E's full-width forms, all in the BMP. */
        const val TABLE = "　！＂＃￥％＆＇（）＊＋，－．／０１２３４５６７８９：；＜＝＞？＠" +
            "ＡＢＣＤＥＦＧＨＩＪＫＬＭＮＯＰＱＲＳＴＵＶＷＸＹＺ［＼］＾＿｀" +
            "ａｂｃｄｅｆｇｈｉｊｋｌｍｎｏｐｑｒｓｔｕｖｗｘｙｚ｛｜｝～"

        /** Characters above space within the table. */
        fun convert(text: String): String = buildString(text.length) {
            var i = 0
            while (i < text.length) {
                val c = text.codePointAt(i)
                if (c > KeySyms.SPACE && c - KeySyms.SPACE < TABLE.length) append(TABLE[c - KeySyms.SPACE]) else appendCodePoint(c)
                i += Character.charCount(c)
            }
        }
    }
}
