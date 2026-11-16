/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.cards

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CollectionInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CollectionItemInfoCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.R as MaterialR
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.cards.CardColumns
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.resources.styledDrawable

/*
 * The settings cards' shared parts (dev/design/061-settings-cards.md §2, §7.1): a card's look, the
 * check strip under a card, the grid that lays cards out. A tap on a card looks (opens, picks,
 * unfolds) and never changes data; its strip is what checks.
 */

/** A settings card: rounded, outlined, flat, a step off the page's or the dialog's surface (§2.5, §2.7). */
fun settingsCard(ctx: Context): MaterialCardView = MaterialCardView(ctx).apply {
    radius = ctx.dp(12).toFloat()
    cardElevation = 0f
    strokeWidth = ctx.dp(1)
    strokeColor = ctx.styledColor(MaterialR.attr.colorOutlineVariant)
    setCardBackgroundColor(ctx.styledColor(MaterialR.attr.colorSurfaceContainerLow))
}

/**
 * The strip across a card's foot (§2.2): a three-state box and a count, under a line. The whole strip
 * is the one target and the one node, read as a CheckBox; the box is only a picture, so a card 76 dp
 * wide still has room for the count. At least 48 dp high, taller when the count wraps; never cut.
 * [edgeDp]: its side padding (6 dp, 12 in a group card).
 *
 * The owner sets [state], [count]'s text, the node's contentDescription and [onCheck], which runs
 * only while the strip is enabled. The node is checked, partly checked or not as [state] is, and a
 * partly checked strip says so in its stateDescription unless the owner set one of its own.
 */
class CheckStrip(ctx: Context, edgeDp: Int = 6) : LinearLayout(ctx) {

    // a picture of the state: no touch, no focus, no node, none of the 48 dp a checkbox claims. Its
    // drawable is 32 dp with the box in the middle 18: the margins take the empty sides back
    internal val box = MaterialCheckBox(ctx).apply {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
    }

    val count = TextView(ctx).apply {
        textSize = 13f
        setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurface))
    }

    private val partly = ctx.getString(R.string.settings_card_partial)

    /**
     * One of [MaterialCheckBox.STATE_CHECKED], [MaterialCheckBox.STATE_INDETERMINATE],
     * [MaterialCheckBox.STATE_UNCHECKED]; set at once, the node reads it next time it is asked.
     */
    var state: Int
        get() = box.checkedState
        set(value) {
            box.checkedState = value
            // the default saying of the partial state, never over one the owner set
            val said = ViewCompat.getStateDescription(this)
            if (said == null || said == partly) {
                ViewCompat.setStateDescription(this, partly.takeIf { value == MaterialCheckBox.STATE_INDETERMINATE })
            }
        }

    var onCheck: (() -> Unit)? = null

    private val line = Paint().apply { color = ctx.styledColor(MaterialR.attr.colorOutlineVariant) }
    private val lineWidth = ctx.dp(1)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.dp(MIN_HEIGHT_DP)
        // 4 dp above and below, so a count wrapped to two lines keeps off the line and the card's edge
        setPadding(ctx.dp(edgeDp), lineWidth + ctx.dp(4), ctx.dp(edgeDp), ctx.dp(4))
        background = styledDrawable(android.R.attr.selectableItemBackground)
        isClickable = true
        isFocusable = true
        setWillNotDraw(false)
        addView(box, LayoutParams(ctx.dp(BOX_DP), ctx.dp(BOX_DP)).apply {
            marginStart = -ctx.dp(BOX_INSET_DP)
            marginEnd = ctx.dp(4) - ctx.dp(BOX_INSET_DP)
        })
        addView(count, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        // performClick reaches a listener even while disabled: the strip itself keeps a disabled one inert
        setOnClickListener { if (isEnabled) onCheck?.invoke() }
    }

    /** Usable or not; when not, dimmed to [dim] (38%; the rare page's switch off: 50%, §2.5). */
    fun setUsable(usable: Boolean, dim: Float = DISABLED_ALPHA) {
        isEnabled = usable
        alpha = if (usable) 1f else dim
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), lineWidth.toFloat(), line)
    }

    override fun getAccessibilityClassName(): CharSequence = CheckBox::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // said outright: Robolectric leaves it unset, and the UAT scripts find the strip by it
        info.className = CheckBox::class.java.name
        info.isCheckable = true
        AccessibilityNodeInfoCompat.wrap(info).setChecked(
            when (state) {
                MaterialCheckBox.STATE_CHECKED -> AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE
                MaterialCheckBox.STATE_INDETERMINATE -> AccessibilityNodeInfoCompat.CHECKED_STATE_PARTIAL
                else -> AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE
            }
        )
    }

    companion object {
        const val MIN_HEIGHT_DP = 48

        // Material's checkbox drawable, and the empty side of it around the box
        internal const val BOX_DP = 32
        internal const val BOX_INSET_DP = 7
        const val DISABLED_ALPHA = 0.38f
    }
}

/**
 * Cards in [columns] (from [CardColumns.columns]), [CardColumns.GAP_DP] apart, every shown child a
 * cell in order; the cards of a row as high as its highest (§2.3, §2.6). A GONE child takes no cell
 * and is not counted; an INVISIBLE one keeps its cell. Children's margins are not read: the grid's
 * gap is the only space between. Read as a list of its VISIBLE cards, [selectionMode] one of
 * [CollectionInfoCompat]'s, so a reader says "item 2 of 8" (§2.8); each card says its own place with [itemOf].
 */
class CardGrid(
    ctx: Context,
    columns: Int,
    private val selectionMode: Int = CollectionInfoCompat.SELECTION_MODE_NONE,
) : ViewGroup(ctx) {

    var columns: Int = columns
        set(value) {
            require(value > 0) { "columns $value" }
            field = value
            requestLayout()
        }

    private val shown: List<View> get() = (0 until childCount).map(::getChildAt).filter { it.visibility != GONE }

    // what a reader counts: an INVISIBLE card holds its cell but is not there to read
    private val read: List<View> get() = shown.filter { it.visibility == VISIBLE }

    val rows: Int get() = (shown.size + columns - 1) / columns

    private val gap = ctx.dp(CardColumns.GAP_DP)
    private var rowHeights = IntArray(0)
    private var cell = 0

    init {
        require(columns > 0) { "columns $columns" }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        cell = maxOf(0, (width - paddingLeft - paddingRight - gap * (columns - 1)) / columns)
        val across = MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY)
        rowHeights = shown.chunked(columns).map { cards ->
            cards.forEach { it.measure(across, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)) }
            val high = cards.maxOf { it.measuredHeight }
            // measured again at the row's height, so the row's cards end level
            cards.forEach { it.measure(across, MeasureSpec.makeMeasureSpec(high, MeasureSpec.EXACTLY)) }
            high
        }.toIntArray()
        val height = paddingTop + paddingBottom + rowHeights.sum() + gap * maxOf(0, rowHeights.size - 1)
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var top = paddingTop
        shown.chunked(columns).forEachIndexed { row, cards ->
            val high = rowHeights.getOrElse(row) { 0 }
            cards.forEachIndexed { column, child ->
                val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) {
                    width - paddingRight - (column + 1) * cell - column * gap
                } else {
                    paddingLeft + column * (cell + gap)
                }
                child.layout(left, top, left + cell, top + high)
            }
            top += high + gap
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        AccessibilityNodeInfoCompat.wrap(info).setCollectionInfo(
            CollectionInfoCompat.obtain(read.size, 1, false, selectionMode)
        )
    }

    /** Where [view] sits among the shown cards, (row, column), or null if it is not one: for laying out. */
    fun placeOf(view: View): Pair<Int, Int>? =
        shown.indexOf(view).takeIf { it >= 0 }?.let { it / columns to it % columns }

    /** [view]'s place as the reader has it, its index among the visible cards (§2.8), or null if it is not one. */
    fun itemOf(view: View): CollectionItemInfoCompat? =
        read.indexOf(view).takeIf { it >= 0 }?.let { CollectionItemInfoCompat.obtain(it, 1, 0, 1, false, view.isSelected) }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    override fun shouldDelayChildPressedState(): Boolean = false
}
