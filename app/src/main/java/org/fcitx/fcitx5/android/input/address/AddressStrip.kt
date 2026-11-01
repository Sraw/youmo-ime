/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.address

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.editing.ContextMemory
import org.fcitx.fcitx5.android.input.editing.toEditorTraits
import org.fcitx.fcitx5.android.utils.rippleDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.horizontalPadding

/**
 * The bar's suggestions in an email or a URL field while it is empty else ([AddressSuggestions]):
 * one tap puts one in. A field left for another, what it was left with is learned
 * ([AddressLearning], [AddressBook.left]), but where the field asks not to be learned from (无痕),
 * is a password's, or the habits are not used (「不用我的输入习惯」), and before the first unlock,
 * when the settings are the device-protected copy's; and only if it changed while the keyboard was
 * up: an address the app filled in is not the user's. Where nothing is learned the text is read
 * for the suggestions alone, at most [READ] units either side of the cursor, and kept nowhere.
 */
class AddressStrip(private val ctx: Context, private val theme: Theme, private val service: FcitxInputMethodService) {

    private val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val view = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        visibility = View.GONE
        addView(row, LinearLayout.LayoutParams(wrapContent, matchParent))
    }

    private val prefs = AppPrefs.getInstance()

    private var field: AddressField? = null
    private var learns = false

    /** A field started, [capFlags] its, as the service tells fcitx: the last one's suggestions gone. */
    fun start(info: EditorInfo, capFlags: CapabilityFlags) {
        // whatever the field, a restart's too: nothing of the one before offered in it
        show(emptyList())
        val traits = info.toEditorTraits()
        field = when {
            traits.isEmail -> AddressField.EMAIL
            traits.isUri -> AddressField.URL
            else -> null
        }
        learns = !capFlags.hasAny(CapabilityFlag.PasswordOrSensitive) && !FcitxApplication.getInstance().isDirectBootMode &&
            prefs.internal.useHabits.getValue()
        val name = ContextMemory.field(info.packageName, info.fieldId, info.inputType, info.fieldName, info.hintText)
        val read = if (field != null && learns) Around.read(service.currentInputConnection, info.initialSelStart == info.initialSelEnd && info.initialSelStart >= 0) else null
        service.addressLearning.start(name, field, learns, read?.whole, read = read != null, AddressStore.forgotten)
            ?.let(AddressStore::learn)
        refresh(info.initialSelStart, info.initialSelEnd, read)
    }

    /** The keyboard hidden, the bar made anew, or the field left: nothing offered till the next start. */
    fun finish() {
        field = null
        show(emptyList())
    }

    /** The cursor at [start]..[end], the text maybe changed: offered anew. */
    fun refresh(start: Int, end: Int) = refresh(start, end, null)

    // [read], what start read already
    private fun refresh(start: Int, end: Int, read: Around?) {
        val f = field ?: return
        val around = read ?: Around.read(service.currentInputConnection, start == end && start >= 0)
        if (learns) service.addressLearning.seen(around?.whole, read = around != null)
        // a selection: nothing to put beside it. One the app gave no position of (-1, -1) shows as the text selected
        if (around == null || start != end || around.selected.isNotEmpty()) return show(emptyList())
        val habits = prefs.internal.useHabits.getValue()
        // the remembered addresses not offered while they are not remembered, kept for when they are again
        val book = AddressStore.book().takeIf { habits }
            ?.let { if (prefs.keyboard.rememberAddresses.getValue()) it else AddressBook(it.counts) }
        show(AddressSuggestions.suggest(f, around.before, around.after, AddressStore.lists(), book, read = READ))
    }

    /** The text around the cursor as read once: [READ] units either side, and the selection. */
    private class Around(val before: String, val selected: String, val after: String) {
        // the field's text, the selection's too: null where more than was read
        val whole: String? get() = (before + selected + after).takeIf { before.length < READ && selected.length < READ && after.length < READ }

        companion object {
            // null: the connection gave nothing (none, a WebView's timeout), not an empty field. Each a
            // round trip to the app on the main thread at every cursor move: the selection not asked for
            // where there is none ([collapsed])
            fun read(ic: InputConnection?, collapsed: Boolean): Around? {
                ic ?: return null
                val before = ic.getTextBeforeCursor(READ, 0) ?: return null
                val selected = if (collapsed) "" else ic.getSelectedText(0) ?: ""
                val after = ic.getTextAfterCursor(READ, 0) ?: return null
                return Around(before.toString(), selected.toString(), after.toString())
            }
        }
    }

    private fun show(suggestions: List<AddressSuggestion>) {
        row.removeAllViews()
        for (s in suggestions) row.addView(chip(s), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        view.visibility = if (suggestions.isEmpty()) View.GONE else View.VISIBLE
        view.scrollTo(0, 0)
    }

    private fun chip(s: AddressSuggestion) = TextView(ctx).apply {
        text = s.shown
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MIDDLE
        gravity = Gravity.CENTER
        textSize = ThemeManager.prefs.candidateTextSize.getValue().toFloat()
        setTextColor(theme.candidateTextColor)
        horizontalPadding = ctx.dp(12)
        background = rippleDrawable(theme.keyPressHighlightColor)
        setOnClickListener { service.putAround(s.deleteBefore, s.deleteAfter, s.text, s.cursor) }
    }

    companion object {
        /** An address is shorter: more read is a text that is no address, learned from not at all. */
        const val READ = 256
    }
}
