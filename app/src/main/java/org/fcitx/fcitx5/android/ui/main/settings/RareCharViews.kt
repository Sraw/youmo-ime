/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.SeekBar
import androidx.core.view.ViewCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp

/**
 * A 生僻字屏蔽 grid cell: reading small above, character large; held back means faded and struck through, not colour
 * alone. One view drawing two lines (~35,000 scrolled): no layout of its own, shared [Paints]. Read as a check box.
 */
class RareCharCell(context: Context, private val paints: Paints) : View(context) {

    /** Made once a grid, not once a cell. */
    class Paints(context: Context) {
        private val scale = context.resources.configuration.fontScale
        val char = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = context.dp(CHAR_SP) * scale
        }
        val reading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = context.dp(READING_SP) * scale
        }
        val strike = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = context.dp(STRIKE_DP) }
        val color = context.styledColor(android.R.attr.textColorPrimary)
        val secondary = context.styledColor(android.R.attr.textColorSecondary)
        val height = (context.dp(HEIGHT_DP) * scale).toInt()
        val pad = context.dp(PAD_DP) * scale
    }

    var char: String = ""
        private set
    var reading: String = ""
        private set
    var held: Boolean = false
        private set

    // made when first asked, not at each bind of a scroll
    private var describe: () -> String = { "" }
    private var description: String? = null

    init {
        isClickable = true
        isLongClickable = true
        isFocusable = true
    }

    fun show(char: String, reading: String, held: Boolean, describe: () -> String) {
        this.char = char
        this.reading = reading
        this.describe = describe
        description = null
        this.held = held
        invalidate()
    }

    /** Redrawn, and a screen reader told. */
    fun setHeld(held: Boolean) {
        if (held == this.held) return
        this.held = held
        invalidate()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    // lazily for a screen reader: lint refuses a getContentDescription() override (GetContentDescriptionOverride)
    private fun described(): String = description ?: describe().also { description = it }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.contentDescription = described()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), paints.height)
    }

    override fun onDraw(canvas: Canvas) {
        val alpha = if (held) HELD_ALPHA else FULL
        val p = paints
        p.reading.color = p.secondary
        p.reading.alpha = alpha
        p.char.color = p.color
        p.char.alpha = alpha
        val x = width / 2f
        canvas.drawText(reading, x, -p.reading.ascent() + p.pad, p.reading)
        canvas.drawText(char, x, height - p.char.descent() - p.pad, p.char)
        if (held) {
            p.strike.color = p.color
            val inset = width * STRIKE_INSET
            canvas.drawLine(inset, height - inset, width - inset, inset, p.strike)
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.contentDescription = described()
        info.className = CheckBox::class.java.name
        info.isCheckable = true
        // setChecked(Int) is API 36's; the boolean is the one API 29 has
        @Suppress("DEPRECATION")
        info.isChecked = held
    }

    companion object {
        /** 7 columns on 360dp at normal font scale. */
        private const val WIDTH_DP = 48
        private const val HEIGHT_DP = 56
        private const val CHAR_SP = 24
        private const val READING_SP = 11
        private const val PAD_DP = 4
        private const val STRIKE_DP = 1.5f
        private const val STRIKE_INSET = 0.18f
        private const val HELD_ALPHA = 90
        private const val FULL = 255

        /** As wide as its text is large; one at least. */
        fun columns(widthDp: Int, fontScale: Float): Int = (widthDp / (WIDTH_DP * fontScale)).toInt().coerceAtLeast(1)

        /** One at least. */
        fun rows(heightDp: Int, fontScale: Float): Int = (heightDp / (HEIGHT_DP * fontScale)).toInt().coerceAtLeast(1)
    }
}

/**
 * Letters down the grid's right edge: dragged, the list goes to the letter under the finger ([onLetter]), at most once
 * a frame. Read as an adjustable: a screen reader's swipes go to the next and previous letter.
 */
class LetterScroller(context: Context) : View(context) {

    var letters: List<Char> = emptyList()
        set(value) {
            field = value
            visibility = if (value.isEmpty()) GONE else VISIBLE
            // another group or search starts at its top, not at the last letter gone to
            last = null
            current = null
            invalidate()
        }

    var onLetter: (Char) -> Unit = {}

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.dp(LETTER_SP) * resources.configuration.fontScale
        color = context.styledColor(android.R.attr.colorPrimary)
    }

    // the letter last gone to this touch; also the one a screen reader is told it is at
    private var last: Char? = null
    private var current: Char? = null
        set(value) {
            field = value
            sayCurrent()
        }
    private var pending: Char? = null
    private val jump = Runnable {
        pending?.let { c ->
            pending = null
            if (c != last) {
                last = c
                current = c
                onLetter(c)
            }
        }
    }

    init {
        contentDescription = context.getString(R.string.rare_chars_letters)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    // read out after what it is, as a seek bar's value is
    private fun sayCurrent() = ViewCompat.setStateDescription(this, (current ?: letters.firstOrNull())?.uppercase())

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dp(WIDTH_DP), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (letters.isEmpty()) return
        val step = height.toFloat() / letters.size
        letters.forEachIndexed { i, c -> canvas.drawText(c.uppercase(), width / 2f, step * i + step / 2 - (paint.ascent() + paint.descent()) / 2, paint) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val c = letters[(event.y / height * letters.size).toInt().coerceIn(0, letters.size - 1)]
                if (pending == null) postOnAnimation(jump)
                pending = c
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // where the finger was let go is gone to, then the next touch starts afresh
                jump.run()
                removeCallbacks(jump)
                last = null
            }
        }
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = SeekBar::class.java.name
        if (letters.isEmpty()) return
        val at = letters.indexOf(current ?: letters.first()).coerceAtLeast(0)
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0f, letters.size - 1f, at.toFloat())
        if (at > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        if (at < letters.size - 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val step = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 1
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -1
            else -> return super.performAccessibilityAction(action, arguments)
        }
        if (letters.isEmpty()) return false
        val at = letters.indexOf(current ?: letters.first()).coerceAtLeast(0)
        val next = letters.getOrNull(at + step) ?: return false
        current = next
        onLetter(next)
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
        return true
    }

    companion object {
        private const val WIDTH_DP = 24
        private const val LETTER_SP = 11
    }
}
