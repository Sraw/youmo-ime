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
 * @param inRow the key's own among those before [split] (one row): in focus first, the rest round it as
 * listed (PopupFocus.ownFirst); -1, the first, as the full keyboard's
 * @param actions what each key types (060-keys-unify: the item's own, [asIs] not read); null, [keys] as [keyAction] says
 * @param opensOn the key in focus as the popup opens, wherever it is (a named key's first: in the row, among the
 * symbols, on a key with no row), laid over the trigger as its group's own; -1: [inRow]'s, else the first
 * @param onFocusMoved told, with the popup's view, each time another key comes into focus: a buzz,
 * as the finger is not over the key it picks ([PopupFocus])
 * @param scenes the engine's, the keys drawn as its scene (060-render-p2 S2), sized by its rules ([PopupKeys])
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

    // the engine's scene of the keys: made again as the focus moves
    private val builder = scenes.builder()

    private val rowCount: Int
    private val columnCount: Int

    // the keys before the split, then those from it (if any): each group's first index and size,
    // and how many rows it takes, from the bottom
    // a row of the key's own alone (九键's 1: [1], then its symbols) is not a row by itself: it goes in
    // with the rest, which then fill their rows (10 as 5×2, not 9 with a gap over a lone 1), still in focus
    private val ownRow = inRow >= 0 && !(split == 1 && keys.size > 1)
    private val groups: List<IntRange> = (if (ownRow || inRow < 0) split else keys.size).coerceIn(0, keys.size).let { at ->
        listOf(0 until at, at until keys.size).filter { !it.isEmpty() }.ifEmpty { listOf(keys.indices) }
    }
    private val groupRows: List<Int>

    // the row and column in focus at first; the column read by tests, to move from it
    private val focusRow: Int
    internal val focusColumn: Int

    // the scene's keys' size; null, the engine could not say ([unmade])
    private val sceneKeys: PopupKeys?

    /** One for every key, as the engine's rules size them ([PopupKeys]): wide enough for the widest label, fitted across [outerBounds]. */
    val keyWidth: Int

    init {
        // as wide as the widest group wants: up to 5 a row, its rows as even as may be; the first
        // all in one row where it has a key's own ([inRow]). round() left fewer slots than entries for 13, 17,
        // 21 ... entries and hid the last ones
        val evenly = groups.drop(if (ownRow) 1 else 0).maxOfOrNull { group ->
            val count = group.count().toFloat()
            ceil(count / ceil(count / 5)).toInt()
        } ?: 0
        // with a key's own, the first group's one row; the rest no narrower than they would be (a key
        // of one letter or none would stack its symbols one a row)
        var columns = if (ownRow) max(groups[0].count(), evenly) else evenly
        fun rowsOf(c: Int) = groups.map { ceil(it.count().toFloat() / c).toInt() }
        // rows of five too tall for the room above the key (16 items in landscape: four rows): wider rows till they fit
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
        // by the room either side, as for any popup: by the screen's edge the key's own at that end
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
        // every group round the column in focus, nearest first; a group's own key first (the one it [opensOn] where it
        // is in the group, else the row's [inRow]), the rest as listed (PopupFocus.ownFirst), as the full keyboard
        // lays its keys
        val own = if (opensOn in group) opensOn - group.first else if (g == 0) inRow else -1
        val priority = if (own >= 0) PopupFocus.ownFirst(group.count(), own) else null
        List(groupRows[g]) { r ->
            // a slot past its group's keys is empty: -1
            IntArray(columnCount) { col ->
                val at = r * columnCount + columnOrder[col]
                val index = group.first + (priority?.getOrNull(at) ?: at)
                if (index <= group.last) index else -1
            }
        }
    }.toTypedArray()

    // the row it [opensOn]: that key's group's first, the key over the column in focus (keyOrders)
    private val startRow = groups.indexOfFirst { opensOn in it }.takeIf { it >= 0 }?.let { g -> groupRows.take(g).sum() } ?: focusRow

    private var focusedIndex = keyOrders[startRow][focusColumn]

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

    // by how far the finger moves from where it was when the popup opened, not where it is
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
        actions?.let { return it.getOrNull(focusedIndex) }
        val key = keys.getOrNull(focusedIndex) ?: return null
        return if (asIs) KeyAction.CommitAction(key) else keyAction(key)
    }

    companion object {
        /** What picking [key] types: from the long press, and from a letter's swipe (its first). */
        fun keyAction(key: String): KeyAction =
            // one character goes through fcitx like any key; anything longer (a user-entered word, or
            // text that happens to spell a key name such as "Tab") is not a key and is committed as text
            if (key.codePointCount(0, key.length) == 1) KeyAction.HostKeyAction(key)
            else KeyAction.CommitAction(key)
    }

}
