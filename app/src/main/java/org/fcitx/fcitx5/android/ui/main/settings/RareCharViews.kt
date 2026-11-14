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
 * A cell of 生僻字屏蔽's grid (design §6.2): its first reading small above, the character large; held
 * back, faded and struck through, not by colour alone. One view drawing two lines, ~35,000 of
 * them scrolled: no layout of its own, the paints the grid's ([Paints], one set for all). Read
 * out as a check box, checked when held. Its size follows the font's scale, as its text does.
 */
class RareCharCell(context: Context, private val paints: Paints) : View(context) {

    /** The paints every cell draws with: made once a grid, not once a cell. */
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

    // what a screen reader says, made when first asked, not at each bind of a scroll
    private var describe: () -> String = { "" }
    private var description: String? = null

    init {
        isClickable = true
        isLongClickable = true
        isFocusable = true
    }

    /** Shows [char], read [reading], [held] or not; [describe], what a screen reader says of it. */
    fun show(char: String, reading: String, held: Boolean, describe: () -> String) {
        this.char = char
        this.reading = reading
        this.describe = describe
        description = null
        this.held = held
        invalidate()
    }

    /** Held or let through by the user: redrawn, and a screen reader told (its checked state changed). */
    fun setHeld(held: Boolean) {
        if (held == this.held) return
        this.held = held
        invalidate()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    // the description made only once a screen reader asks: not a getContentDescription() override,
    // which lint refuses (GetContentDescriptionOverride), but the node and the events it reads
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
        /** About 48dp a cell across at the font's normal scale: 7 columns on 360dp. */
        private const val WIDTH_DP = 48
        private const val HEIGHT_DP = 56
        private const val CHAR_SP = 24
        private const val READING_SP = 11
        private const val PAD_DP = 4
        private const val STRIKE_DP = 1.5f
        private const val STRIKE_INSET = 0.18f
        private const val HELD_ALPHA = 90
        private const val FULL = 255

        /** How many cells across [widthDp] takes at [fontScale]: the cell as wide as its text is large; one at least. */
        fun columns(widthDp: Int, fontScale: Float): Int = (widthDp / (WIDTH_DP * fontScale)).toInt().coerceAtLeast(1)

        /** How many rows of cells [heightDp] shows at [fontScale]: one at least. */
        fun rows(heightDp: Int, fontScale: Float): Int = (heightDp / (HEIGHT_DP * fontScale)).toInt().coerceAtLeast(1)
    }
}

/**
 * The letters down the grid's right edge (design §6.4; the app had no fast scroller): held and
 * dragged, the list goes to the letter under the finger ([onLetter]), where RareLayout says, once a
 * frame at most however fast the finger moves. Read out as an adjustable: a screen reader's swipes
 * up and down go to the next and the previous letter.
 */
class LetterScroller(context: Context) : View(context) {

    var letters: List<Char> = emptyList()
        set(value) {
            field = value
            visibility = if (value.isEmpty()) GONE else VISIBLE
            sayCurrent()
            invalidate()
        }

    var onLetter: (Char) -> Unit = {}

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.dp(LETTER_SP) * resources.configuration.fontScale
        color = context.styledColor(android.R.attr.colorPrimary)
    }

    // the letter last gone to this touch; the one the finger is on, gone to at the next frame; the
    // letter a screen reader is told it is at
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

    // the letter it is at read out after what it is, as a seek bar's value is
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
