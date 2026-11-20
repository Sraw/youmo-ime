/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.widget.Space
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupScenes
import org.fcitx.fcitx5.android.scene.engine.PopupKeys
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * @param lift how far above the trigger's top the popup's bottom is. See [offsetY].
 * @param split where the keys set apart start: drawn in rows of their own above the others
 * @param inRow the key's own among those before [split] (one row): in focus first, the rest round it; -1, the first
 */
class PopupKeyboardUi(
    override val ctx: Context,
    theme: Theme,
    outerBounds: Rect,
    triggerBounds: Rect,
    onDismissSelf: PopupContainerUi.() -> Unit = {},
    minKeyWidth: Int,
    private val keyHeight: Int,
    private val lift: Int,
    private val keys: Array<String>,
    private val labels: Array<String>,
    split: Int = keys.size,
    private val asIs: Boolean = false,
    inRow: Int = -1,
    private val actions: List<KeyAction>? = null,
    opensOn: Int = -1,
    private val onFocusMoved: (View) -> Unit = {},
    private val scenes: PopupScenes,
) : PopupContainerUi(ctx, theme, outerBounds, triggerBounds, onDismissSelf) {

    private val builder = scenes.builder()

    private val rowCount: Int
    private val columnCount: Int

    // each group's first index, size and rows from the bottom. A row of the key's own alone (九键's 1) goes
    // in with the rest, which fill their rows (10 as 5×2, not 9 with a gap over a lone 1)
    private val ownRow = inRow >= 0 && !(split == 1 && keys.size > 1)
    private val groups: List<IntRange> = (if (ownRow || inRow < 0) split else keys.size).coerceIn(0, keys.size).let { at ->
        listOf(0 until at, at until keys.size).filter { !it.isEmpty() }.ifEmpty { listOf(keys.indices) }
    }
    private val groupRows: List<Int>

    private val focusRow: Int
    internal val focusColumn: Int

    private val sceneKeys: PopupKeys?

    val keyWidth: Int

    init {
        // up to 5 a row, rows as even as may be; round() left fewer slots than entries for 13, 17, 21… entries
        val evenly = groups.drop(if (ownRow) 1 else 0).maxOfOrNull { group ->
            val count = group.count().toFloat()
            ceil(count / ceil(count / 5)).toInt()
        } ?: 0
        // the rest no narrower than they would be (a key of one letter would stack its symbols one a row)
        var columns = if (ownRow) max(groups[0].count(), evenly) else evenly
        fun rowsOf(c: Int) = groups.map { ceil(it.count().toFloat() / c).toInt() }
        // rows too tall for the room above the key (16 items in landscape): wider rows till they fit
        val above = if (outerBounds.isEmpty) Int.MAX_VALUE else triggerBounds.top - outerBounds.top - lift
        while (rowsOf(columns).sum() * keyHeight > above && columns < keys.size) columns++
        columnCount = columns
        groupRows = rowsOf(columnCount)
        rowCount = groupRows.sum()

        val room = outerBounds.takeUnless { it.isEmpty }?.width()?.toDouble()
        sceneKeys = scenes.made("keys' size", builder?.popupKeys(labels.toList(), columnCount, room, minKeyWidth.toDouble(), keyHeight.toDouble()))
        // not made (logged, nothing shown): still a width that fits, for the places worked out below
        keyWidth = sceneKeys?.width?.toInt() ?: room?.let { min(minKeyWidth, it.toInt() / columnCount) } ?: minKeyWidth

        focusRow = 0
        // by the screen's edge the key's own at that end
        focusColumn = calcInitialFocusedColumn(columnCount, keyWidth, outerBounds, triggerBounds)
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
     * Moved in where keys wider than the trigger at an edge would leave [outerBounds].
     */
    override val offsetX = run {
        val centred = ((triggerBounds.width() - keyWidth) / 2) - (keyWidth * focusColumn)
        if (outerBounds.isEmpty) return@run centred
        val left = (triggerBounds.left + centred).coerceIn(outerBounds.left, outerBounds.right - columnCount * keyWidth)
        left - triggerBounds.left
    }
    override val offsetY = -lift - keyHeight * rowCount

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
        // each group round the column in focus, nearest first, its own key first (PopupFocus.ownFirst)
        val own = if (opensOn in group) opensOn - group.first else if (g == 0) inRow else -1
        val priority = if (own >= 0) PopupFocus.ownFirst(group.count(), own) else null
        List(groupRows[g]) { r ->
            IntArray(columnCount) { col ->
                val at = r * columnCount + columnOrder[col]
                val index = group.first + (priority?.getOrNull(at) ?: at)
                if (index <= group.last) index else -1
            }
        }
    }.toTypedArray()

    private val startRow = groups.indexOfFirst { opensOn in it }.takeIf { it >= 0 }?.let { g -> groupRows.take(g).sum() } ?: focusRow

    private var focusedIndex = keyOrders[startRow][focusColumn]

    private val sceneView = sceneKeys?.let { made(it, focusedIndex) }?.let { first -> scenes.view(ctx).also { it.scene = first } }

    val unmade: Boolean get() = sceneView == null

    override val root: View = sceneView ?: Space(ctx)

    private fun made(keys: PopupKeys, focus: Int) = scenes.made("keys", builder?.popup(labels.toList(), keyOrders.map { it.toList() }, keys, focus))

    // false where it could not be remade: the focus stays, so a lift types the key drawn selected
    private fun drawScene(focus: Int): Boolean {
        val view = sceneView ?: return true
        view.scene = made(sceneKeys ?: return false, focus) ?: return false
        return true
    }

    private val focus = PopupFocus(
        rows = keyOrders.toList(),
        startRow = startRow,
        startColumn = focusColumn,
        stepX = keyWidth * PopupFocus.STEP,
        stepY = keyHeight * PopupFocus.STEP,
        cancelBelow = triggerBounds.height().toFloat(),
        // the key's bottom edge, in onChangeFocus's coordinates (the popup's, offsetY up)
        cancelAt = (triggerBounds.height() - offsetY).toFloat(),
    )

    override fun onChangeFocus(x: Float, y: Float) = moveFocus(focus, x, y, focusedIndex, ::drawScene) {
        focusedIndex = it
        onFocusMoved(root)
    }

    override fun onTrigger(): KeyAction? {
        actions?.let { return it.getOrNull(focusedIndex) }
        val key = keys.getOrNull(focusedIndex) ?: return null
        return if (asIs) KeyAction.CommitAction(key) else keyAction(key)
    }

    companion object {
        fun keyAction(key: String): KeyAction =
            // one character goes through fcitx like any key; anything longer (a word, or "Tab") is committed as text
            if (key.codePointCount(0, key.length) == 1) KeyAction.HostKeyAction(key)
            else KeyAction.CommitAction(key)
    }

}
