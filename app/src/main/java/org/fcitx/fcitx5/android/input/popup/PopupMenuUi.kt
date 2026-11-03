/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.view.View
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.shortcut.EditMenuLayout
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.imageDrawable

class PopupMenuUi(
    override val ctx: Context,
    theme: Theme,
    outerBounds: Rect,
    triggerBounds: Rect,
    onDismissSelf: PopupContainerUi.() -> Unit = {},
    private val items: Array<KeyDef.Popup.Menu.Item>,
    private val onFocusMoved: (View) -> Unit = {},
    /** Cells in their own order, [Fixed.start] over the finger: the edit menu's ([EditMenuLayout]). */
    private val fixed: Fixed? = null,
) : PopupContainerUi(ctx, theme, outerBounds, triggerBounds, onDismissSelf) {

    /**
     * A row whose cells stay where they are, the one in focus first [start]; [disabled] ones drawn
     * faded, picking nothing. Opened by a swipe up, the finger already above the key: back down onto
     * the key cancels it.
     */
    class Fixed(val start: Int, val disabled: Set<Int>)

    private val keySize = ctx.dp(48)

    private val inactiveBackground = InsetDrawable(
        ShapeDrawable(OvalShape()).apply {
            paint.color = theme.accentKeyBackgroundColor
        },
        (keySize - ctx.dp(33)) / 2
    )

    private val activeBackground = InsetDrawable(
        ShapeDrawable(OvalShape()).apply {
            paint.color = ColorUtils.compositeColors(
                theme.keyPressHighlightColor,
                theme.accentKeyBackgroundColor
            )
        },
        (keySize - ctx.dp(34)) / 2
    )

    private val columnCount = items.size
    private val focusColumn = fixed?.start
        ?: calcInitialFocusedColumn(columnCount, keySize, outerBounds, triggerBounds)

    override val offsetX = if (fixed != null) {
        EditMenuLayout.rowLeft(triggerBounds.centerX(), keySize, outerBounds.left, outerBounds.right) - triggerBounds.left
    } else {
        ((triggerBounds.width() - keySize) / 2) - (keySize * focusColumn)
    }
    override val offsetY = ctx.dp(-52)

    private val columnOrder = if (fixed != null) IntArray(columnCount) { it } else createColumnOrder(columnCount, focusColumn)

    private var focusedIndex = columnOrder[focusColumn]

    private val keyViews = items.map {
        imageView {
            background = inactiveBackground
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageDrawable = drawable(it.icon)!!.apply {
                setTint(theme.accentKeyTextColor)
            }
            contentDescription = it.label
        }
    }.also { views ->
        fixed?.disabled?.forEach { views.getOrNull(it)?.alpha = DISABLED_ALPHA }
    }

    init {
        markFocus(focusedIndex)
    }

    override val root = horizontalLayout root@{
        for (i in 0 until columnCount) {
            val view = keyViews[columnOrder[i]]
            add(view, lParams(keySize, keySize))
        }
    }

    private fun markFocus(index: Int) {
        keyViews.getOrNull(index)?.apply {
            background = activeBackground
        }
    }

    private fun markInactive(index: Int) {
        keyViews.getOrNull(index)?.apply {
            background = inactiveBackground
        }
    }

    // as a long press's keys pick ([PopupKeyboardUi]): by how far the finger moves, down off the key none
    private val focus = PopupFocus(
        rows = listOf(columnOrder.copyOf()),
        startRow = 0,
        startColumn = focusColumn,
        stepX = keySize * PopupFocus.STEP,
        stepY = keySize * PopupFocus.STEP,
        cancelBelow = triggerBounds.height().toFloat(),
        // the key's bottom edge, in onChangeFocus's coordinates (the popup's, offsetY up); the edit
        // menu's its top, the finger above it as the menu opens
        cancelAt = ((if (fixed != null) 0 else triggerBounds.height()) - offsetY).toFloat(),
    )

    override fun onChangeFocus(x: Float, y: Float): Boolean {
        val newFocus = focus.moveTo(x, y)
        if (newFocus == PopupFocus.CANCELLED) {
            onDismissSelf(this)
            return true
        }
        if (newFocus != focusedIndex) {
            markInactive(focusedIndex)
            markFocus(newFocus)
            focusedIndex = newFocus
            onFocusMoved(root)
        }
        return false
    }

    override fun onTrigger() = items.getOrNull(focusedIndex)?.takeIf { fixed?.disabled?.contains(focusedIndex) != true }?.action

    private companion object {
        const val DISABLED_ALPHA = 0.3f
    }
}
