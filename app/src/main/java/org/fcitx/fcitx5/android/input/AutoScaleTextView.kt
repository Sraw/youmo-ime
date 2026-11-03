/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.util.AttributeSet
import android.view.Gravity
import android.widget.TextView
import androidx.core.graphics.withSave
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@SuppressLint("AppCompatCustomView")
class AutoScaleTextView @JvmOverloads constructor(
    context: Context?,
    attributeSet: AttributeSet? = null
) : TextView(context, attributeSet) {

    enum class Mode {
        /**
         * do not scale or ellipse text, overflow when cannot fit width
         */
        None,

        /**
         * only scale in X axis, makes text looks "condensed" or "slim"
         */
        Horizontal,

        /**
         * scale both in X and Y axis, align center vertically
         */
        Proportional
    }

    var scaleMode = Mode.None

    private var needsMeasureText = true
    private val fontMetrics = Paint.FontMetrics()
    private val textBounds = Rect()

    private var needsCalculateTransform = true
    private var baselineX = 0.0f
    private var baselineY = 0.0f
    private var textScaleX = 1.0f
    private var textScaleY = 1.0f

    // a text with spans (one part smaller or fainter than the rest) laid out by the platform, which
    // draws them; drawn as a plain string they were lost
    private var styled: Layout? = null

    override fun setText(charSequence: CharSequence?, bufferType: BufferType) {
        if (charSequence == null || changes(charSequence)) {
            needsMeasureText = true
            needsCalculateTransform = true
            super.setText(charSequence, bufferType)
            requestLayout()
            invalidate()
        }
    }

    // the same characters styled otherwise are a change too
    private fun changes(to: CharSequence) = !text.contentEquals(to) || to is Spanned || text is Spanned

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val width = measureTextBounds().width() + paddingLeft + paddingRight
        val height = ceil(textHeight() + paddingTop + paddingBottom).toInt()
        val maxHeight = if (maxHeight >= 0) maxHeight else Int.MAX_VALUE
        val maxWidth = if (maxWidth >= 0) maxWidth else Int.MAX_VALUE
        setMeasuredDimension(
            measure(widthMode, widthSize, min(max(width, minimumWidth), maxWidth)),
            measure(heightMode, heightSize, min(max(height, minimumHeight), maxHeight))
        )
    }

    private fun measure(specMode: Int, specSize: Int, calculatedSize: Int): Int = when (specMode) {
        MeasureSpec.EXACTLY -> specSize
        MeasureSpec.AT_MOST -> min(calculatedSize, specSize)
        else -> calculatedSize
    }

    private fun measureTextBounds(): Rect {
        if (needsMeasureText) {
            val paint = paint
            paint.getFontMetrics(fontMetrics)
            val codePointCount = Character.codePointCount(text, 0, text.length)
            val spanned = text as? Spanned
            styled = null
            if (spanned != null) {
                val width = ceil(Layout.getDesiredWidth(spanned, paint)).toInt()
                styled = StaticLayout.Builder.obtain(spanned, 0, spanned.length, paint, max(width, 1))
                    .setIncludePad(false).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
                textBounds.set(0, floor(fontMetrics.top).toInt(), width, ceil(fontMetrics.bottom).toInt())
            } else if (codePointCount == 1) {
                // use actual text bounds when there is only one "character",
                // e.g. full-width punctuation
                paint.getTextBounds(text.toString(), 0, text.length, textBounds)
            } else {
                textBounds.set(
                    /* left = */ 0,
                    /* top = */ floor(fontMetrics.top).toInt(),
                    /* right = */ ceil(paint.measureText(text.toString())).toInt(),
                    /* bottom = */ ceil(fontMetrics.bottom).toInt()
                )
            }
            needsMeasureText = false
        }
        return textBounds
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (needsCalculateTransform || changed) {
            calculateTransform(right - left, bottom - top)
            needsCalculateTransform = false
        }
    }

    private fun calculateTransform(viewWidth: Int, viewHeight: Int) {
        val contentWidth: Int = viewWidth - paddingLeft - paddingRight
        val contentHeight: Int = viewHeight - paddingTop - paddingBottom
        measureTextBounds()
        val textLeft: Float = textBounds.left.toFloat()
        val textWidth: Float = textBounds.width().toFloat()
        // lines drawn from their top, the first's baseline nowhere in particular
        val textTop: Float = if (lines() != null) 0.0f else fontMetrics.top
        val textHeight: Float = textHeight()
        val taller = lines() != null && textHeight > contentHeight
        if (textWidth > contentWidth || taller) {
            when (scaleMode) {
                Mode.None -> {
                    textScaleX = 1.0f
                    textScaleY = 1.0f
                }
                Mode.Horizontal -> {
                    textScaleX = contentWidth / textWidth
                    textScaleY = 1.0f
                }
                Mode.Proportional -> {
                    val scale = if (taller) min(contentWidth / textWidth, contentHeight / textHeight) else contentWidth / textWidth
                    textScaleX = scale
                    textScaleY = scale
                }
            }
        } else {
            textScaleX = 1.0f
            textScaleY = 1.0f
        }
        baselineX = calculateBaselineX(paddingLeft, contentWidth, textLeft, textWidth, textScaleX)
        baselineY = calculateBaselineY(paddingTop, contentHeight, textTop, textHeight, textScaleY)
    }

    private fun calculateBaselineX(
        contentLeft: Int,
        contentWidth: Int,
        textLeft: Float,
        textWidth: Float,
        scaleX: Float
    ): Float {
        val scaledTextLeft = textLeft * scaleX
        val scaledTextWidth = textWidth * scaleX
        val horizontalGravity =
            Gravity.getAbsoluteGravity(gravity, layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK
        val targetLeft: Float = @SuppressLint("RtlHardcoded") when (horizontalGravity) {
            Gravity.LEFT -> contentLeft.toFloat()
            Gravity.RIGHT -> contentLeft + contentWidth - scaledTextWidth
            else -> contentLeft + (contentWidth - scaledTextWidth) / 2.0f
        }
        return targetLeft - scaledTextLeft
    }

    private fun calculateBaselineY(
        contentTop: Int,
        contentHeight: Int,
        textTop: Float,
        textHeight: Float,
        scaleY: Float
    ): Float {
        val scaledTextTop = textTop * scaleY
        val scaledTextHeight = textHeight * scaleY
        val verticalGravity = gravity and Gravity.VERTICAL_GRAVITY_MASK
        val targetTop: Float = when (verticalGravity) {
            Gravity.TOP -> contentTop.toFloat()
            Gravity.BOTTOM -> contentTop + contentHeight - scaledTextHeight
            else -> contentTop + (contentHeight - scaledTextHeight) / 2.0f
        }
        return targetTop - scaledTextTop
    }

    override fun onDraw(canvas: Canvas) {
        if (needsCalculateTransform) {
            calculateTransform(width, height)
            needsCalculateTransform = false
        }
        val paint = paint
        paint.color = currentTextColor
        canvas.withSave {
            translate(scrollX.toFloat(), scrollY.toFloat())
            translate(baselineX, baselineY)
            scale(textScaleX, textScaleY)
            val layout = styled
            if (layout != null) {
                // its first line's baseline where the plain text's is; lines from their top
                if (layout.lineCount == 1) translate(0.0f, -layout.getLineBaseline(0).toFloat())
                layout.draw(this)
            } else {
                drawText(text.toString(), 0.0f, 0.0f, paint)
            }
        }
    }

    private val inkBounds = Rect()

    /**
     * Where its glyphs are drawn, in its own coordinates, into [out]: their ink, not the line's
     * height and advance the view is sized by (a key's label giving way to its corner's mark,
     * KeyMarkYield). False for none: nothing to draw, or a styled text, its lines' ink unmeasured.
     */
    fun inkBounds(out: RectF): Boolean {
        if (text.isEmpty()) return false
        // what it is styled with known only once its transform is (laid out, nothing drawn yet)
        if (needsCalculateTransform) {
            calculateTransform(width, height)
            needsCalculateTransform = false
        }
        if (styled != null) return false
        paint.getTextBounds(text.toString(), 0, text.length, inkBounds)
        if (inkBounds.isEmpty) return false
        out.set(
            baselineX + inkBounds.left * textScaleX, baselineY + inkBounds.top * textScaleY,
            baselineX + inkBounds.right * textScaleX, baselineY + inkBounds.bottom * textScaleY,
        )
        return true
    }

    // a styled text of more than one line (a table's roots on a key, TextKeyboard): sized and centred whole
    private fun lines(): Layout? = styled?.takeIf { it.lineCount > 1 }

    private fun textHeight(): Float {
        measureTextBounds()
        return lines()?.height?.toFloat() ?: (fontMetrics.bottom - fontMetrics.top)
    }

    override fun getBaseline(): Int {
        // baselineY is measured from the view's top, padding included
        return baselineY.roundToInt()
    }
}
