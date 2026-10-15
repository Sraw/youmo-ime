/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.text.Spanned
import android.text.SpannedString
import android.text.style.DynamicDrawableSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.text.buildSpannedString
import androidx.core.view.doOnPreDraw
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.toSpannedString
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout

open class PreeditUi(
    override val ctx: Context,
    private val theme: Theme,
    private val setupTextView: (TextView.() -> Unit)? = null,
    // the line below put after the one above, for a row of one line's height: scrolled to keep
    // the cursor in sight, and a tap on it is [onTap]'s
    private val oneLine: Boolean = false,
) : Ui {

    /** Where in the preedit a tap on the row puts the cursor, in code points (FcitxAPI.moveCursor). */
    var onTap: ((Int) -> Unit)? = null

    class CursorSpan(ctx: Context, @ColorInt color: Int, metrics: Paint.FontMetricsInt) :
        DynamicDrawableSpan() {
        private val drawable = ShapeDrawable(RectShape()).apply {
            paint.color = color
            setBounds(0, metrics.ascent, ctx.dp(1), metrics.bottom)
        }

        override fun getDrawable() = drawable
    }

    private val cursorSpan by lazy {
        CursorSpan(ctx, theme.keyTextColor, upView.paint.fontMetricsInt)
    }

    private fun createTextView() = textView {
        setTextColor(theme.keyTextColor)
        textSize = 16f
        setupTextView?.invoke(this)
    }

    private val upView = createTextView()

    private val downView = createTextView()

    var visible = false
        private set

    override val root: View = if (oneLine) {
        HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // a tap past the end of short text is still on it: the cursor to its end
            isFillViewport = true
            add(upView, lParams(wrapContent, matchParent))
        }
    } else verticalLayout {
        add(upView, lParams())
        add(downView, lParams())
    }

    // what the line above shows: the length of aux, the preedit, and where the cursor drawn is, -1 for none
    private var auxShown = 0
    private var preeditShown = ""
    private var cursorShown = -1
    private var revealing = false

    init {
        if (oneLine) {
            val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = preeditShown.isNotEmpty()

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val layout = upView.layout ?: return false
                    onTap?.invoke(preeditAt(layout.getOffsetForHorizontal(0, e.x - upView.totalPaddingLeft)))
                    return true
                }
            })
            upView.setOnTouchListener { _, e -> taps.onTouchEvent(e) }
        }
    }

    // [offset] into the line above, as the place in the preedit it is: the cursor drawn is no character of it
    private fun preeditAt(offset: Int): Int {
        val at = ((if (cursorShown in 0 until offset) offset - 1 else offset) - auxShown).coerceIn(0, preeditShown.length)
        return preeditShown.codePointCount(0, at)
    }

    // the row scrolled to show the cursor with some of the text around it, the end if it is there
    private fun reveal() {
        val row = root as HorizontalScrollView
        val layout = upView.layout ?: return
        val x = if (cursorShown < 0) upView.width else upView.totalPaddingLeft + layout.getPrimaryHorizontal(cursorShown).toInt()
        val margin = ctx.dp(REVEAL_MARGIN)
        // the keyboard's side padding is the row's (InputView.placeBar)
        val seen = row.width - row.paddingLeft - row.paddingRight
        val to = when {
            x - margin < row.scrollX -> x - margin
            x + margin > row.scrollX + seen -> x + margin - seen
            else -> return
        }
        row.scrollTo(to.coerceAtLeast(0), 0)
    }

    private fun updateTextView(view: TextView, str: CharSequence, visible: Boolean) {
        view.text = str
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun update(inputPanel: FcitxEvent.InputPanelEvent.Data) {
        val activeBkg = theme.genericActiveBackgroundColor
        val upString: SpannedString
        val upCursor: Int
        if (inputPanel.auxUp.isEmpty()) {
            upString = inputPanel.preedit.toSpannedString(activeBkg)
            upCursor = inputPanel.preedit.cursor
        } else {
            upString = buildSpannedString {
                append(inputPanel.auxUp.toSpannedString(activeBkg))
                append(inputPanel.preedit.toSpannedString(activeBkg))
            }
            upCursor = inputPanel.preedit.cursor.let {
                if (it < 0) it
                else inputPanel.auxUp.length + it
            }
        }
        val below = inputPanel.auxDown.toSpannedString(activeBkg)
        val downString = if (oneLine) SpannedString("") else below
        val upLine = if (oneLine && below.isNotEmpty()) buildSpannedString { append(upString); append("  "); append(below) } else upString
        val hasUp = upLine.isNotEmpty()
        val hasDown = downString.isNotEmpty()
        visible = hasUp || hasDown
        auxShown = inputPanel.auxUp.length
        preeditShown = inputPanel.preedit.toString()
        cursorShown = if (upCursor < 0 || upCursor == upLine.length) -1 else upCursor
        if (!visible) {
            updateTextView(upView, "", false)
            updateTextView(downView, "", false)
            return
        }
        val upStringWithCursor = if (upCursor < 0 || upCursor == upLine.length) {
            upLine
        } else buildSpannedString {
            if (upCursor > 0) append(upLine, 0, upCursor)
            append('|')
            setSpan(cursorSpan, upCursor, upCursor + 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            append(upLine, upCursor, upLine.length)
        }
        updateTextView(upView, upStringWithCursor, hasUp)
        updateTextView(downView, downString, hasDown)
        // once laid out with the new text; one waiting at a time, while the window draws nothing
        if (oneLine && !revealing) {
            revealing = true
            upView.doOnPreDraw {
                revealing = false
                reveal()
            }
        }
    }

    companion object {
        private const val REVEAL_MARGIN = 32
    }
}
