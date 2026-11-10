/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.text.SpannableStringBuilder
import android.text.Layout
import android.text.Spanned
import android.text.SpannedString
import android.text.style.DynamicDrawableSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.annotation.ColorInt
import androidx.core.text.buildSpannedString
import org.fcitx.fcitx5.android.core.toSpannedString
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.scene.engine.PreeditLook
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.verticalLayout

open class PreeditUi(
    override val ctx: Context,
    private val theme: Theme,
    private val setupTextView: (TextView.() -> Unit)? = null,
) : Ui {

    class CursorSpan(ctx: Context, @ColorInt color: Int, metrics: Paint.FontMetricsInt) :
        DynamicDrawableSpan() {
        private val drawable = ShapeDrawable(RectShape()).apply {
            paint.color = color
            setBounds(0, metrics.ascent, ctx.dp(1), metrics.bottom)
        }

        override fun getDrawable() = drawable
    }

    /** The code being typed as the theme has it (060-render-p3 T6b: `preedit`). */
    private val look: PreeditLook = SceneBar.of(ctx, theme).preedit

    private val cursorSpan by lazy {
        CursorSpan(ctx, look.cursor.value, upView.paint.fontMetricsInt)
    }

    private fun createTextView() = PreeditTextView(ctx, look.underline).apply {
        setTextColor(look.color.value)
        ThemeAssets.candidateTypeface(ThemeManager.shownLook(theme))?.let { typeface = it }
        textSize = 16f
        setupTextView?.invoke(this)
    }

    protected val upView = createTextView()

    private val downView = createTextView()

    var visible = false
        private set

    // made when first asked for: a subclass's root is its own, which upView cannot be in as well
    override val root: View by lazy {
        verticalLayout {
            add(upView, lParams())
            add(downView, lParams())
        }
    }

    /** The line above and the line below, made of the preedit's line ([above]) and aux below ([below]). */
    protected open fun lines(above: SpannedString, below: SpannedString): Pair<CharSequence, CharSequence> = above to below

    /** Where in the line above the cursor is drawn, -1 for none. */
    protected var cursorShown = -1
        private set

    private fun updateTextView(view: TextView, str: CharSequence, visible: Boolean) {
        view.text = str
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    open fun update(inputPanel: HostEvent.InputPanel) {
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
        val (upLine, downString) = lines(upString, inputPanel.auxDown.toSpannedString(activeBkg))
        val hasUp = upLine.isNotEmpty()
        val hasDown = downString.isNotEmpty()
        visible = hasUp || hasDown
        cursorShown = if (upCursor < 0 || upCursor == upLine.length) -1 else upCursor
        if (!visible) {
            updateTextView(upView, "", false)
            updateTextView(downView, "", false)
            return
        }
        val upStringWithCursor = if (cursorShown < 0) {
            upLine
        } else buildSpannedString {
            if (upCursor > 0) append(upLine, 0, upCursor)
            append('|')
            setSpan(cursorSpan, upCursor, upCursor + 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            append(upLine, upCursor, upLine.length)
        }
        updateTextView(upView, underlined(upStringWithCursor), hasUp)
        updateTextView(downView, underlined(downString), hasDown)
    }

    // the underline fcitx asks for (UnderlineSpan) as the theme has it: drawn by the view ([ThemedUnderline]), or none
    private fun underlined(text: CharSequence): CharSequence {
        val u = look.underline ?: return text
        if (text !is Spanned) return text
        val spans = text.getSpans(0, text.length, UnderlineSpan::class.java)
        if (spans.isEmpty()) return text
        return SpannableStringBuilder(text).apply {
            for (span in spans) {
                val start = getSpanStart(span)
                val end = getSpanEnd(span)
                removeSpan(span)
                if (u.color != null) setSpan(ThemedUnderline(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    /** Where the theme's underline goes under the code (its width and colour the view's). */
    class ThemedUnderline

    /**
     * The code's text, the theme's underline ([PreeditLook.Underline]) drawn under each [ThemedUnderline]:
     * no public API sets an underline's colour or thickness (`TextPaint.underlineColor` and
     * `underlineThickness` are hidden at every API level; `UnderlineSpan` takes the text's colour and the
     * font's thickness), so the view draws the line, where the font puts its own (`Paint.underlinePosition`, API 29).
     */
    @SuppressLint("ViewConstructor")
    class PreeditTextView(context: Context, private val underline: PreeditLook.Underline?) : AppCompatTextView(context) {
        private val line = Paint().apply { underline?.color?.let { color = it.value } }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val text = text as? Spanned ?: return
            val layout = layout ?: return
            val width = underline?.let { maxOf(1f, (it.widthDp * resources.displayMetrics.density).toInt().toFloat()) } ?: return
            val below = paint.underlinePosition
            for (span in text.getSpans(0, text.length, ThemedUnderline::class.java)) {
                for (seg in segments(layout, text.getSpanStart(span), text.getSpanEnd(span))) {
                    val top = totalPaddingTop + layout.getLineBaseline(seg.line) + below
                    canvas.drawRect(totalPaddingLeft + seg.left, top, totalPaddingLeft + seg.right, top + width, line)
                }
            }
        }

        /** A stretch of underline on [line], from [left] to [right] of the layout, px. */
        data class Segment(val line: Int, val left: Float, val right: Float)

        companion object {
            /**
             * [start] to [end]'s stretch on each line it is on, the code wrapped (the floating strip): a line it
             * goes on past ends at the line's end edge, right or left by its paragraph's direction, not at the
             * offset there, which is the next line's start; a span ending at a wrap draws nothing on the next line.
             * The end edge is the line's text measured whole, the space it wrapped at too (getLineRight and
             * getLineLeft leave a trailing space out, which the underline then stopped short of).
             */
            fun segments(layout: Layout, start: Int, end: Int): List<Segment> {
                if (end <= start) return emptyList()
                return (layout.getLineForOffset(start)..layout.getLineForOffset(end - 1)).map { l ->
                    val lineStart = layout.getLineStart(l)
                    val from = layout.getPrimaryHorizontal(maxOf(start, lineStart))
                    val rtl = layout.getParagraphDirection(l) == Layout.DIR_RIGHT_TO_LEFT
                    val wraps = end >= layout.getLineEnd(l) && l < layout.lineCount - 1
                    val to = if (wraps) {
                        val whole = Layout.getDesiredWidth(layout.text, lineStart, layout.getLineEnd(l), layout.paint)
                        layout.getPrimaryHorizontal(lineStart) + if (rtl) -whole else whole
                    } else {
                        layout.getPrimaryHorizontal(end)
                    }
                    Segment(l, minOf(from, to), maxOf(from, to))
                }
            }
        }
    }
}
