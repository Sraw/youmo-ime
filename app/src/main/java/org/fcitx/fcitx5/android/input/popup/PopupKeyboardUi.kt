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
 * @param ctx [Context]
 * @param theme [Theme]
 * @param outerBounds bound [Rect] of [PopupComponent] root view.
 * @param triggerBounds bound [Rect] of popup trigger view. Used to calculate free space of both sides and
 * determine column order. See [focusColumn] and [columnOrder].
 * @param onDismissSelf callback when popup keyboard wants to close
 * @param minKeyWidth key width in popup keyboard, at least: see [keyWidth]
 * @param keyHeight key height in popup keyboard
 * @param lift how far above the trigger's top the popup's bottom is. See [offsetY].
 * @param keys character to commit when triggered
 * @param labels symbols to show on keys
 * @param split where the keys set apart start: drawn in rows of their own above the others; none
 * past the last key
 * @param asIs every key committed as it is, never typed as a key ([keyAction])
 * @param inRow the keys before [split] in one row, in their order, the one at [inRow] in focus first,
 * at the column the room either side picks, or at the row's end by an edge it would run past (PopupFocus.inOrderRow); -1, the first under it and the others round it
 * @param onFocusMoved told, with the popup's view, each time another key comes into focus: a buzz,
 * as the finger is not over the key it picks ([PopupFocus])
 * @param scenes the engine's, the keys drawn as its scene (060-render-p2 S2), sized by its rules ([PopupKeys])
 * @param faceInset how far [triggerBounds] (the key's face) is in from the key's cell across: the
 * finger's x, given [onChangeFocus], is from the cell (the theme's inset, 3dp by default)
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
    private val onFocusMoved: (View) -> Unit = {},
    private val scenes: PopupScenes,
    private val faceInset: Int = 0,
) : PopupContainerUi(ctx, theme, outerBounds, triggerBounds, onDismissSelf) {

    // the engine's scene of the keys: made again as the focus moves
    private val builder = scenes.builder()

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

    // the in-order row's slots (PopupFocus.inOrderRow); null, no row in order
    private val inOrderSlots: IntArray?


    // the scene's keys' size; null, the engine could not say ([unmade])
    private val sceneKeys: PopupKeys?

    /** One for every key, as the engine's rules size them ([PopupKeys]): wide enough for the widest label, fitted across [outerBounds]. */
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

        val room = outerBounds.takeUnless { it.isEmpty }?.width()?.toDouble()
        sceneKeys = scenes.made("keys' size", builder?.popupKeys(labels.toList(), columnCount, room, minKeyWidth.toDouble(), keyHeight.toDouble()))
        // not made (logged, nothing shown): still a width that fits, for the places worked out below
        keyWidth = sceneKeys?.width?.toInt() ?: room?.let { min(minKeyWidth, it.toInt() / columnCount) } ?: minKeyWidth

        focusRow = 0
        // by the room either side, as for any popup: inRow's own place left the row's start under a
        // key at the screen's right, the popup moved in and the finger right of every key but none
        val wanted = calcInitialFocusedColumn(columnCount, keyWidth, outerBounds, triggerBounds)
        // a row in order with its own key at it, or at the row's end by the edge where it would run
        // past it (PopupFocus.inOrderRow): from a key by the edge the finger only slides inward
        inOrderSlots = if (inRow >= 0) PopupFocus.inOrderRow(groups[0].count(), inRow, columnCount, wanted) else null
        focusColumn = inOrderSlots?.indexOf(inRow) ?: wanted
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
        // the row in order, its own key in focus, ending at the edge where it would run past it; the
        // others round the column in focus
        val order = inOrderSlots?.takeIf { g == 0 } ?: columnOrder
        List(groupRows[g]) { r ->
            // a slot past its group's keys is empty: -1
            IntArray(columnCount) { col ->
                val index = group.first + r * columnCount + order[col]
                if (order[col] >= 0 && index <= group.last) index else -1
            }
        }
    }.toTypedArray()

    private var focusedIndex = keyOrders[focusRow][focusColumn]

    private val sceneView = sceneKeys?.let { made(it, focusedIndex) }?.let { first -> scenes.view(ctx).also { it.scene = first } }

    /**
     * The engine could not make the keys (logged): nothing to show, the long press as one with no
     * popup, never keys not drawn that a lift would still type (060: no other drawing to fall back to).
     */
    val unmade: Boolean get() = sceneView == null

    // shown only when made ([unmade]): an empty view otherwise, never added
    override val root: View = sceneView ?: Space(ctx)

    // the keys in rows as keyOrders has them, the bottom first, the one in focus selected
    private fun made(keys: PopupKeys, focus: Int) = scenes.made("keys", builder?.popup(labels.toList(), keyOrders.map { it.toList() }, keys, focus))

    // the focus to move to [focus]: the scene made again; false where it could not be, the one before
    // kept and the focus with it, so a lift types the key drawn selected
    private fun drawScene(focus: Int): Boolean {
        val view = sceneView ?: return true
        view.scene = made(sceneKeys ?: return false, focus) ?: return false
        return true
    }

    private val inOrder = inRow >= 0
    private val edgeMargin = EDGE_MARGIN * ctx.resources.displayMetrics.density

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
        // the screen's edges, in onChangeFocus's coordinates (the popup's, offsetX across, from the
        // key's cell: the trigger its face, faceInset in it), EDGE_MARGIN in: the last key picked a
        // little short of the edge. A row in order only: the full keyboard's and the pickers' popups
        // as before
        leftEdge = if (outerBounds.isEmpty || !inOrder) Float.NEGATIVE_INFINITY else outerBounds.left - triggerBounds.left + faceInset - offsetX + edgeMargin,
        rightEdge = if (outerBounds.isEmpty || !inOrder) Float.POSITIVE_INFINITY else outerBounds.right - triggerBounds.left + faceInset - offsetX - edgeMargin,
        minStepX = keyWidth * PopupFocus.MIN_STEP,
    )

    override fun onChangeFocus(x: Float, y: Float): Boolean {
        val newFocus = focus.moveTo(x, y)
        if (newFocus == PopupFocus.CANCELLED) {
            onDismissSelf(this)
            return true
        }
        if (newFocus != focusedIndex && drawScene(newFocus)) {
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
        /** How far in from the screen's edge, in dp, the finger picks a row's last key. */
        const val EDGE_MARGIN = 4f

        /** What picking [key] types: from the long press, and from a letter's swipe (its first). */
        fun keyAction(key: String): KeyAction =
            // one character goes through fcitx like any key; anything longer (a user-entered word, or
            // text that happens to spell a key name such as "Tab") is not a key and is committed as text
            if (key.codePointCount(0, key.length) == 1) KeyAction.HostKeyAction(key)
            else KeyAction.CommitAction(key)
    }

}
