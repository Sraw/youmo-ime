/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/**
 * The full-width module, fcitx's `Fullwidth` (`ca/fullwidth/fullwidth.cpp`): while on, and only on a
 * context whose status area has its toggle ([action]), printable ASCII comes out full width.
 *
 * - [keyHook], at PostInputMethod (`:37-82`): a press with no modifier of a key from space to `~`
 *   commits its full-width form through [commit] (so the commit filters see it) and is accepted,
 *   not filtered: ReservedLast still runs. Space is U+3000 this way only.
 * - [commitFilter] (`:84-103`): every committed character above space (`wc > 32`) in the table; not
 *   space itself.
 *
 * No hotkey (`fullwidth.h:18-19` has no default, Q6) and nothing saved: off when the app starts.
 */
class FullWidth(private val statusArea: StatusArea, private val commit: (InputContext, String) -> Unit) {

    var enabled: Boolean = false
        private set

    val action: StatusAction = object : StatusAction("fullwidth") {
        override fun icon(ic: InputContext) = if (enabled) "fcitx-fullwidth-active" else "fcitx-fullwidth-inactive"
        override fun shortText(ic: InputContext) = if (enabled) "Full width Character" else "Half width Character"
        override fun activate(ic: InputContext) = setEnabled(!enabled, ic)
    }.also(statusArea::register)

    /** `setEnabled` (`fullwidth.h:60-65`). */
    fun setEnabled(enabled: Boolean, ic: InputContext) {
        if (enabled == this.enabled) return
        this.enabled = enabled
        action.updateOn(ic)
    }

    fun inWhiteList(ic: InputContext) = statusArea.contains(ic, action)

    val keyHook = PostKeyHook { ic, e ->
        // the engine's acceptance is not asked: fcitx's filterKey does not look at it (:37-53)
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
        /** `sCornerTrans` (`fullwidth.cpp:23-33`): U+0020–U+007E's full-width forms, all in the BMP. */
        const val TABLE = "　！＂＃￥％＆＇（）＊＋，－．／０１２３４５６７８９：；＜＝＞？＠" +
            "ＡＢＣＤＥＦＧＨＩＪＫＬＭＮＯＰＱＲＳＴＵＶＷＸＹＺ［＼］＾＿｀" +
            "ａｂｃｄｅｆｇｈｉｊｋｌｍｎｏｐｑｒｓｔｕｖｗｘｙｚ｛｜｝～"

        /** The commit filter's rule: characters above space within the table, by code point. */
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
