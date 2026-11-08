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
    /** Cells in their own order, [Fixed.start] over the finger: the edit menu's ([EditMenuLayout]). */
    private val fixed: Fixed? = null,
    /** The engine's: the cells drawn as its scene (060-render-p2 S2), sized by its rules. */
    private val scenes: PopupScenes,
) : PopupContainerUi(ctx, theme, outerBounds, triggerBounds, onDismissSelf) {

    // the engine's scene of the cells: made again as the focus moves
    private val builder = scenes.builder()
    private val cells = items.map { MenuCell(it.label, if (it.icon == 0) null else SceneIcons.nameOf(it.icon)) }
    private val sceneKeys = scenes.made("menu's size", builder?.menuKeys(cells))

    /**
     * A row whose cells stay where they are, the one in focus first [start]; [disabled] ones drawn
     * faded, picking nothing. Opened by a swipe up, the finger already above the key: back down onto
     * the key cancels it. [at]: its top-left corner in window pixels ([EditMenuLayout.place]), up
     * where the notices go; null just over the key.
     */
    class Fixed(val start: Int, val disabled: Set<Int>, val at: EditMenuLayout.Spot? = null)

    private val keySize = sceneKeys?.height?.toInt() ?: ctx.dp(CELL_DP)

    private val itemWidth = sceneKeys?.width?.toInt() ?: keySize

    private val columnCount = items.size
    private val focusColumn = fixed?.start
        ?: calcInitialFocusedColumn(columnCount, itemWidth, outerBounds, triggerBounds)

    override val offsetX = when {
        fixed?.at != null -> fixed.at.left - triggerBounds.left
        fixed != null -> EditMenuLayout.rowLeft(triggerBounds.centerX(), itemWidth, outerBounds.left, outerBounds.right) - triggerBounds.left
        else -> ((triggerBounds.width() - itemWidth) / 2) - (itemWidth * focusColumn)
    }
    override val offsetY = fixed?.at?.let { it.top - triggerBounds.top }
        ?: -Popups.px(Popups.MENU_LIFT_DP, scenes.density).toInt()

    private val columnOrder = if (fixed != null) IntArray(columnCount) { it } else createColumnOrder(columnCount, focusColumn)

    private var focusedIndex = columnOrder[focusColumn]

    private val sceneView = sceneKeys?.let { made(focusedIndex) }?.let { first -> scenes.view(ctx).also { it.scene = first } }

    /**
     * The engine could not make the cells (logged): nothing to show, the long press as one with no
     * menu, never cells not drawn that a lift would still pick (060: no other drawing to fall back to).
     */
    val unmade: Boolean get() = sceneView == null

    // unmade: never shown (PopupComponent), so nothing
    override val root: View = sceneView ?: View(ctx)

    // the cells in their columns' order, the one in focus selected, the disabled faded
    private fun made(focus: Int) = scenes.made("menu", builder?.menu(cells, columnOrder.toList(), focus, fixed?.disabled.orEmpty()))

    // the focus to move to [focus]: the scene made again; false where it could not be, the one before
    // kept and the focus with it, so a lift picks the cell drawn selected
    private fun drawScene(focus: Int): Boolean {
        val view = sceneView ?: return true
        view.scene = made(focus) ?: return false
        return true
    }

    // as a long press's keys pick ([PopupKeyboardUi]): by how far the finger moves, down off the key none
    private val focus = PopupFocus(
        rows = listOf(columnOrder.copyOf()),
        startRow = 0,
        startColumn = focusColumn,
        stepX = itemWidth * PopupFocus.STEP,
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
        if (newFocus != focusedIndex && drawScene(newFocus)) {
            focusedIndex = newFocus
            onFocusMoved(root)
        }
        return false
    }

    override fun onTrigger() = items.getOrNull(focusedIndex)?.takeIf { fixed?.disabled?.contains(focusedIndex) != true }?.action

    companion object {
        /** A cell's width and height, in dp: a key's. */
        const val CELL_DP = 48
    }
}
