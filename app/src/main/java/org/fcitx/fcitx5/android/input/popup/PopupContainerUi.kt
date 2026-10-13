/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.content.Context
import android.graphics.Rect
import android.view.View
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import splitties.views.dsl.core.Ui
import kotlin.math.abs
import kotlin.math.roundToInt

abstract class PopupContainerUi(
    override val ctx: Context,
    val theme: Theme,
    val outerBounds: Rect,
    val triggerBounds: Rect,
    val onDismissSelf: PopupContainerUi.() -> Unit
) : Ui {

    /**
     * Popup container view
     */
    abstract override val root: View

    /**
     * Offset on X axis to put this [PopupKeyboardUi] relative to popup trigger view [triggerBounds]
     */
    abstract val offsetX: Int

    /**
     * Offset on Y axis to put this [PopupKeyboardUi] relative to popup trigger view [triggerBounds]
     */
    abstract val offsetY: Int

    /**
     * The column of the key in focus at first, the one centred over the trigger: the one nearest the
     * middle that leaves every column inside [outerBounds], else the one past them least. Keys wider
     * than the trigger may leave none that fits (nine keys' 9, a long word over Q):
     * [PopupKeyboardUi] moves itself in by the rest. Bounds not laid out yet, empty, have nothing
     * to fit in: the middle one.
     */
    fun calcInitialFocusedColumn(
        columnCount: Int,
        columnWidth: Int,
        outerBounds: Rect,
        triggerBounds: Rect
    ): Int {
        val middle = (columnCount - 1) / 2
        // every column is past empty bounds, and the search took one at the right: the popup opened left of the key
        if (outerBounds.isEmpty) return middle
        // the key in focus as offsetX centres it: wider than the trigger, it overhangs it both sides
        val keyLeft = triggerBounds.left + (triggerBounds.width() - columnWidth) / 2
        val leftSpace = keyLeft - outerBounds.left
        val rightSpace = outerBounds.right - (keyLeft + columnWidth)
        // not a column at a time: that loop never ended for keys of no width
        return (0 until columnCount).minWith(
            compareBy<Int> { maxOf(columnWidth * it - leftSpace, columnWidth * (columnCount - it - 1) - rightSpace, 0) }
                .thenBy { abs(it - middle) }
        )
    }

    /**
     * column order priority: center, right, left. eg.
     * ```
     * | 6 | 4 | 2 | 0 | 1 | 3 | 5 |
     * ```
     * in case free space is not enough in right (left), just skip that cell. eg.
     * ```
     *    | 3 | 2 | 1 | 0 |(no free space)
     * (no free space)| 0 | 1 | 2 | 3 |
     * ```
     */
    fun createColumnOrder(columnCount: Int, initialFocus: Int) = IntArray(columnCount).also {
        var order = 0
        it[initialFocus] = order++
        for (i in 1 until columnCount * 2) {
            val sign = if (i % 2 == 0) -1 else 1
            val delta = (i / 2f).roundToInt()
            val nextColumn = initialFocus + sign * delta
            if (nextColumn < 0 || nextColumn >= columnCount) continue
            it[nextColumn] = order++
        }
    }

    fun changeFocus(x: Float, y: Float): Boolean {
        return onChangeFocus(x - offsetX, y - offsetY)
    }

    /**
     * Handle focus change of this [PopupKeyboardUi].
     * [x], [y] axis are relative to container itself.
     *
     * @return Whether the gesture should be consumed, ie. no more gesture events should
     * be dispatched to the trigger view.
     */
    abstract fun onChangeFocus(x: Float, y: Float): Boolean

    abstract fun onTrigger(): KeyAction?

    companion object {
        fun limitIndex(i: Int, limit: Int) = if (i < 0) 0 else if (i >= limit) limit - 1 else i
    }
}
