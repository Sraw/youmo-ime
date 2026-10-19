/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.ViewOutlineProvider
import android.widget.Space
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.view
import splitties.views.gravityCenter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * @param ctx [Context]
 * @param theme [Theme]
 * @param outerBounds bound [Rect] of [PopupComponent] root view.
 * @param triggerBounds bound [Rect] of popup trigger view. Used to calculate free space of both sides and
 * determine column order. See [focusColumn] and [columnOrder].
 * @param onDismissSelf callback when popup keyboard wants to close
 * @param radius popup keyboard and key radius
 * @param minKeyWidth key width in popup keyboard, at least: see [keyWidth]
 * @param keyHeight key height in popup keyboard
 * @param popupHeight popup preview view height. Used to transform gesture coordinate from
 * trigger view to popup keyboard view. See [offsetX] and [offsetY].
 * @param keys character to commit when triggered
 * @param labels symbols to show on keys
 */
class PopupKeyboardUi(
    override val ctx: Context,
    theme: Theme,
    outerBounds: Rect,
    triggerBounds: Rect,
    onDismissSelf: PopupContainerUi.() -> Unit = {},
    private val radius: Float,
    minKeyWidth: Int,
    private val keyHeight: Int,
    private val popupHeight: Int,
    private val keys: Array<String>,
    private val labels: Array<String>
) : PopupContainerUi(ctx, theme, outerBounds, triggerBounds, onDismissSelf) {

    class PopupKeyUi(override val ctx: Context, val theme: Theme, val text: String) : Ui {

        val textView = view(::AutoScaleTextView) {
            text = this@PopupKeyUi.text
            scaleMode = AutoScaleTextView.Mode.Proportional
            textSize = TEXT_SIZE
            // as markInactive has it: keyTextColor left the keys not yet passed over another colour
            setTextColor(theme.popupTextColor)
        }

        override val root = frameLayout {
            add(textView, lParams {
                gravity = gravityCenter
            })
        }
    }

    private val inactiveBackground = GradientDrawable().apply {
        cornerRadius = radius
        setColor(theme.popupBackgroundColor)
    }

    private val focusBackground = GradientDrawable().apply {
        cornerRadius = radius
        setColor(theme.genericActiveBackgroundColor)
    }

    private val rowCount: Int
    private val columnCount: Int

    // those 2 variables meas initial focus row/column during initialization
    private val focusRow: Int
    private val focusColumn: Int

    private val keyUis = labels.map {
        PopupKeyUi(ctx, theme, it)
    }

    /**
     * One for every key, wide enough for the widest label and no narrower than [minKeyWidth]
     * (the bubble over the key pressed), and one text size: each label fitted to a fixed width by
     * itself came out at as many sizes (`.com` smaller than `.cn`). Where the keys so wide would
     * not fit across [outerBounds], narrower, all their labels made smaller alike.
     */
    val keyWidth: Int

    init {
        val keyCount: Float = keys.size.toFloat()
        rowCount = ceil(keyCount / 5).toInt()
        // round() left fewer slots than entries for 13, 17, 21 ... entries and hid the last ones
        columnCount = ceil(keyCount / rowCount).toInt()

        val padding = ctx.dp(KEY_PADDING)
        val widest = keyUis.maxOfOrNull { it.textView.paint.measureText(it.text) } ?: 0f
        val fitting = ceil(widest).toInt() + 2 * padding
        val wanted = max(minKeyWidth, fitting)
        // bounds not laid out yet are empty: nothing to fit in, rather than keys of no width
        keyWidth = if (outerBounds.isEmpty) wanted else min(wanted, outerBounds.width() / columnCount)
        if (fitting > keyWidth) {
            // its padding too, so never below nothing however narrow
            val size = TEXT_SIZE * keyWidth / fitting
            keyUis.forEach { it.textView.textSize = size }
        }

        focusRow = 0
        focusColumn = calcInitialFocusedColumn(columnCount, keyWidth, outerBounds, triggerBounds)
    }

    /**
     * Offset on X axis made up of 2 parts:
     *  1. from trigger view bounds left to popup entry view left
     *  2. from left-most column to initial focused column
     *
     * Offset on Y axis made up of 2 parts as well:
     *  1. from trigger view top to popup entry view top
     *  2. from top-most row to initial focused row (bottom row)
     *
     * ```
     *                    c───┬───┬───┐
     *                    │   │ 4 │ 5 │
     *                 ┌─ ├───p───┼───┤ ─┐
     *   popupKeyHeight│  │ 3 │ 1 │ 2 │  │
     *                 └─ └───┼───┼───┘  │
     *                        │   │      │popupHeight
     *                 ┌───── │o─┐│      │
     *  bounds.height()│      ││a││      │
     *                 └───── └┴─┴┘ ─────┘
     * ```
     * o: trigger view top-left origin
     *
     * p: popup preview ([PopupEntryUi]) top-left origin
     *
     * c: container view top-left origin
     *
     * Applying only `1.` parts of both X and Y offset, the origin should transform from `o` to `p`.
     * `2.` parts of both offset transform it from `p` to `c`.
     *
     * Where no column centred over the trigger leaves the popup inside [outerBounds] (keys wider than
     * the trigger, at an edge), it is moved in by the rest, every key on screen.
     */
    override val offsetX = run {
        val centred = ((triggerBounds.width() - keyWidth) / 2) - (keyWidth * focusColumn)
        if (outerBounds.isEmpty) return@run centred
        val left = (triggerBounds.left + centred).coerceIn(outerBounds.left, outerBounds.right - columnCount * keyWidth)
        left - triggerBounds.left
    }
    override val offsetY = (triggerBounds.height() - popupHeight) - (keyHeight * (rowCount - 1))

    /**
     * Where the key in focus at first starts across, from the trigger's left: centred over it, unless
     * the popup was moved in. The bubble under it belongs there, as one shape with it.
     */
    val focusedKeyLeft: Int get() = offsetX + keyWidth * focusColumn

    private val columnOrder = createColumnOrder(columnCount, focusColumn)

    /**
     * row with smaller index displays at bottom.
     * for example, keyOrders array:
     * ```
     * [[2, 0, 1, 3], [6, 4, 5, 7]]
     * ```
     * displays as
     * ```
     * | 6 | 4 | 5 | 7 |
     * | 2 | 0 | 1 | 3 |
     * ```
     * in which `0` indicates default focus
     */
    private val keyOrders = Array(rowCount) { row ->
        IntArray(columnCount) { col -> row * columnCount + columnOrder[col] }
    }

    private var focusedIndex = keyOrders[focusRow][focusColumn]

    init {
        markFocus(focusedIndex)
    }

    override val root = verticalLayout root@{
        background = inactiveBackground
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(2f)
        // add rows in reverse order, because newly added view shows at bottom
        for (i in rowCount - 1 downTo 0) {
            val order = keyOrders[i]
            add(horizontalLayout row@{
                for (j in 0 until columnCount) {
                    // an empty slot keeps its column, so each key is drawn where onChangeFocus selects it
                    val slot = keyUis.getOrNull(order[j])?.root ?: Space(ctx)
                    add(slot, lParams(keyWidth, keyHeight))
                }
            }, lParams(width = matchParent))
        }
    }

    private fun markFocus(index: Int) {
        keyUis.getOrNull(index)?.apply {
            root.background = focusBackground
            textView.setTextColor(theme.genericActiveForegroundColor)
        }
    }

    private fun markInactive(index: Int) {
        keyUis.getOrNull(index)?.apply {
            root.background = null
            textView.setTextColor(theme.popupTextColor)
        }
    }

    override fun onChangeFocus(x: Float, y: Float): Boolean {
        // the row drawn under the finger, row 0 at the bottom; below it (still on the key) is row 0.
        // Rounded with a 30% lead, a finger on the bottom row's upper 70% picked the row above (KB-16)
        var newRow = rowCount - 1 - floor(y / keyHeight).toInt()
        // move to next column when gesture moves out of current column
        var newColumn = floor(x / keyWidth).toInt()
        // retain focus when gesture moves between ±2 rows/columns of range
        if (newRow < -2 || newRow > rowCount + 1 || newColumn < -2 || newColumn > columnCount + 1) {
            onDismissSelf(this)
            return true
        }
        newRow = limitIndex(newRow, rowCount)
        newColumn = limitIndex(newColumn, columnCount)
        val newFocus = keyOrders[newRow][newColumn]
        if (newFocus < keyUis.size) {
            markInactive(focusedIndex)
            markFocus(newFocus)
            focusedIndex = newFocus
        }
        return false
    }

    override fun onTrigger(): KeyAction? {
        val key = keys.getOrNull(focusedIndex) ?: return null
        return keyAction(key)
    }

    companion object {
        private const val TEXT_SIZE = 23f

        /** Space each side of the widest label, in dp. */
        private const val KEY_PADDING = PopupComponent.TEXT_PADDING

        /** What picking [key] types: from the long press, and from a letter's swipe (its first). */
        fun keyAction(key: String): KeyAction =
            // one character goes through fcitx like any key; anything longer (a user-entered word, or
            // text that happens to spell a key name such as "Tab") is not a key and is committed as text
            if (key.codePointCount(0, key.length) == 1) KeyAction.FcitxKeyAction(key)
            else KeyAction.CommitAction(key)
    }

}
