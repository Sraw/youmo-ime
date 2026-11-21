/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.content.Context
import android.graphics.Rect
import android.view.View
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupScenes
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.scene.engine.MenuCell
import org.fcitx.fcitx5.android.scene.engine.Popups
import org.fcitx.fcitx5.android.input.shortcut.EditMenuLayout
import splitties.dimensions.dp

class PopupMenuUi(
    override val ctx: Context,
    theme: Theme,
    outerBounds: Rect,
    triggerBounds: Rect,
    onDismissSelf: PopupContainerUi.() -> Unit = {},
    private val items: Array<KeyDef.Popup.Menu.Item>,
    private val onFocusMoved: (View) -> Unit = {},
    /** [Fixed.start] over the finger: the edit menu's, 选输入法's. */
    private val fixed: Fixed? = null,
    private val scenes: PopupScenes,
) : PopupContainerUi(ctx, theme, outerBounds, triggerBounds, onDismissSelf) {

    // made again as the focus moves
    private val builder = scenes.builder()
    private val cells = items.map { MenuCell(it.label, if (it.icon == 0) null else SceneIcons.nameOf(it.icon)) }
    private val sceneKeys = scenes.made("menu's size", builder?.menuKeys(cells))

    /**
     * Cells that stay where they are; [disabled] ones faded, picking nothing. Opened by a swipe up, the finger
     * already above the key: back down onto the key cancels it; opened by a swipe [down], back up onto it does.
     * [at]: window pixels; null just over the key.
     */
    class Fixed(val start: Int, val disabled: Set<Int>, val at: EditMenuLayout.Spot? = null, val down: Boolean = false)

    private val keySize = sceneKeys?.height?.toInt() ?: ctx.dp(CELL_DP)

    private val itemWidth = sceneKeys?.width?.toInt() ?: keySize

    private val columnCount = items.size
    private val focusColumn = fixed?.start
        ?: calcInitialFocusedColumn(columnCount, itemWidth, outerBounds, triggerBounds)

    override val offsetX = when {
        fixed?.at != null -> fixed.at.left - triggerBounds.left
        fixed != null -> EditMenuLayout.rowLeft(triggerBounds.centerX(), itemWidth, outerBounds.left, outerBounds.right, items.size, fixed.start) - triggerBounds.left
        else -> ((triggerBounds.width() - itemWidth) / 2) - (itemWidth * focusColumn)
    }
    override val offsetY = fixed?.at?.let { it.top - triggerBounds.top }
        ?: -Popups.px(Popups.MENU_LIFT_DP, scenes.density).toInt()

    private val columnOrder = if (fixed != null) IntArray(columnCount) { it } else createColumnOrder(columnCount, focusColumn)

    private var focusedIndex = columnOrder[focusColumn]

    private val sceneView = sceneKeys?.let { made(focusedIndex) }?.let { first -> scenes.view(ctx).also { it.scene = first } }

    /** Logged: the long press as one with no menu, never cells not drawn that a lift would still pick. */
    val unmade: Boolean get() = sceneView == null

    // never shown (PopupComponent)
    override val root: View = sceneView ?: View(ctx)

    private fun made(focus: Int) = scenes.made("menu", builder?.menu(cells, columnOrder.toList(), focus, fixed?.disabled.orEmpty()))

    // false where it could not be: the one before kept with the focus, so a lift picks the cell drawn selected
    private fun drawScene(focus: Int): Boolean {
        val view = sceneView ?: return true
        view.scene = made(focus) ?: return false
        return true
    }

    // as a long press's keys pick: by how far the finger moves, down off the key none
    private val focus = PopupFocus(
        rows = listOf(columnOrder.copyOf()),
        startRow = 0,
        startColumn = focusColumn,
        stepX = itemWidth * PopupFocus.STEP,
        stepY = keySize * PopupFocus.STEP,
        // a finger going on down from a swipe down is no going back
        cancelBelow = if (fixed?.down == true) Float.POSITIVE_INFINITY else triggerBounds.height().toFloat(),
        // in onChangeFocus's coordinates (offsetY up); the edit menu's its top, the finger above it as it opens
        cancelAt = if (fixed?.down == true) Float.NaN else ((if (fixed != null) 0 else triggerBounds.height()) - offsetY).toFloat(),
        cancelAboveAt = if (fixed?.down == true) (triggerBounds.height() - offsetY).toFloat() else Float.NaN,
    )

    override fun onChangeFocus(x: Float, y: Float) = moveFocus(focus, x, y, focusedIndex, ::drawScene) {
        focusedIndex = it
        onFocusMoved(root)
    }

    override fun onTrigger() = items.getOrNull(focusedIndex)?.takeIf { fixed?.disabled?.contains(focusedIndex) != true }?.action

    companion object {
        /** In dp: a key's. */
        const val CELL_DP = 48
    }
}
