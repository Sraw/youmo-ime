/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.View
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
 * @param lift how far above the trigger's top the popup's bottom is. See [offsetY].
 * @param keys character to commit when triggered
 * @param labels symbols to show on keys
 * @param split where the keys set apart start: drawn in rows of their own above the others; none
 * past the last key
 * @param asIs every key committed as it is, never typed as a key ([keyAction])
 * @param inRow the keys before [split] in one row, in their order, the one at [inRow] under the
 * finger first (KeyDef.Popup.Keyboard.Explicit.inRow); -1, the first under it and the others round it
 * @param onFocusMoved told, with the popup's view, each time another key comes into focus: a buzz,
 * as the finger is not over the key it picks ([PopupFocus])
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
    private val lift: Int,
    private val keys: Array<String>,
    private val labels: Array<String>,
    split: Int = keys.size,
    private val asIs: Boolean = false,
    inRow: Int = -1,
    private val onFocusMoved: (View) -> Unit = {},
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

    // the keys before the split, then those from it (if any): each group's first index and size,
    // and how many rows it takes, from the bottom
    private val groups: List<IntRange> = split.coerceIn(0, keys.size).let { at ->
        listOf(0 until at, at until keys.size).filter { !it.isEmpty() }.ifEmpty { listOf(keys.indices) }
    }
    private val groupRows: List<Int>

    // the row and column in focus at first; the column read by tests, to move from it
    private val focusRow: Int
    internal val focusColumn: Int

    private val keyUis = labels.map {
        PopupKeyUi(ctx, theme, it)
    }

    /**
     * One for every key, wide enough for the widest label and no narrower than [minKeyWidth],
     * and one text size: each label fitted to a fixed width by
     * itself came out at as many sizes (`.com` smaller than `.cn`). Where the keys so wide would
     * not fit across [outerBounds], narrower, all their labels made smaller alike.
     */
    val keyWidth: Int

    init {
        // as wide as the widest group wants: up to 5 a row, its rows as even as may be; the first
        // all in one row where it is in order. round() left fewer slots than entries for 13, 17,
        // 21 ... entries and hid the last ones
        val evenly = groups.drop(if (inRow >= 0) 1 else 0).maxOfOrNull { group ->
            val count = group.count().toFloat()
            ceil(count / ceil(count / 5)).toInt()
        } ?: 0
        // in order, the first group's one row; the rest no narrower than they would be (a key of
        // one letter or none would stack its symbols one a row)
        columnCount = if (inRow >= 0) max(groups[0].count(), evenly) else evenly
        groupRows = groups.map { ceil(it.count().toFloat() / columnCount).toInt() }
        rowCount = groupRows.sum()

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
        focusColumn = if (inRow >= 0) inRow else calcInitialFocusedColumn(columnCount, keyWidth, outerBounds, triggerBounds)
    }

    /**
     * Offset on X axis made up of 2 parts:
     *  1. from trigger view bounds left to popup entry view left
     *  2. from left-most column to initial focused column
     *
     * On Y axis, the popup's bottom [lift] above the trigger's top, all its rows above that:
     *
     * ```
     *                    c───┬───┬───┐
     *                    │   │ 4 │ 5 │
     *                    ├───┼───┼───┤
     *                    │ 3 │ 1 │ 2 │
     *                    └───┴───┴───┘ ─┐lift
     *                        o──┐      ─┘
     *                        │a │
     *                        └──┘
     * ```
     * o: trigger view top-left origin; c: container view top-left origin
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
    override val offsetY = -lift - keyHeight * rowCount

    /**
     * Where the key in focus at first starts across, from the trigger's left: centred over it, unless
     * the popup was moved in. Read by tests only, since the bubble no longer sits under that key.
     */
    internal val focusedKeyLeft: Int get() = offsetX + keyWidth * focusColumn

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
    private val keyOrders = groups.flatMapIndexed { g, group ->
        // the row in order as it is; the others round the column in focus
        val order = if (g == 0 && inRow >= 0) IntArray(columnCount) { it } else columnOrder
        List(groupRows[g]) { r ->
            // a slot past its group's keys is empty: -1
            IntArray(columnCount) { col ->
                val index = group.first + r * columnCount + order[col]
                if (index <= group.last) index else -1
            }
        }
    }.toTypedArray()

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

    // by how far the finger moves from where it was when the popup opened, not where it is
    private val focus = PopupFocus(
        rows = keyOrders.toList(),
        startRow = focusRow,
        startColumn = focusColumn,
        stepX = keyWidth * PopupFocus.STEP,
        stepY = keyHeight * PopupFocus.STEP,
        cancelBelow = triggerBounds.height().toFloat(),
        // the key's bottom edge, in onChangeFocus's coordinates (the popup's, offsetY up)
        cancelAt = (triggerBounds.height() - offsetY).toFloat(),
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

    override fun onTrigger(): KeyAction? {
        val key = keys.getOrNull(focusedIndex) ?: return null
        return if (asIs) KeyAction.CommitAction(key) else keyAction(key)
    }

    companion object {
        private const val TEXT_SIZE = 20f

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
