/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupScenes
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.scene.engine.MenuCell
import org.fcitx.fcitx5.android.scene.engine.Popups
import org.fcitx.fcitx5.android.input.shortcut.EditMenuLayout
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.textView
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
    /** The engine's, to draw the cells as its scene (a key the canvas draws, 060-render-p2 S2), sized by its rules; null, as views. */
    private val scenes: PopupScenes? = null,
) : PopupContainerUi(ctx, theme, outerBounds, triggerBounds, onDismissSelf) {

    // the engine's scene of the cells, drawn instead of views: made again as the focus moves
    private val builder = scenes?.builder()
    private val cells = items.map { MenuCell(it.label, if (it.icon == 0) null else SceneIcons.nameOf(it.icon)) }
    private val sceneKeys = scenes?.made("menu's size", builder?.menuKeys(cells))

    /**
     * A row whose cells stay where they are, the one in focus first [start]; [disabled] ones drawn
     * faded, picking nothing. Opened by a swipe up, the finger already above the key: back down onto
     * the key cancels it. [at]: its top-left corner in window pixels ([EditMenuLayout.place]), up
     * where the notices go; null just over the key.
     */
    class Fixed(val start: Int, val disabled: Set<Int>, val at: EditMenuLayout.Spot? = null)

    private val keySize = sceneKeys?.height?.toInt() ?: ctx.dp(CELL_DP)

    // words, not icons (an item of no icon: the handwriting pad's 注's 关 / 拼音 / 注音符号), in pills wide enough for them
    private val words = items.any { it.icon == 0 }
    private val itemWidth = sceneKeys?.width?.toInt() ?: if (words) (keySize * WORDS_WIDTH).toInt() else keySize

    private fun background(color: Int, inset: Int) = if (words) {
        InsetDrawable(GradientDrawable().apply { setColor(color); cornerRadius = (keySize - inset).toFloat() / 2 }, ctx.dp(2), inset / 2, ctx.dp(2), inset / 2)
    } else {
        InsetDrawable(ShapeDrawable(OvalShape()).apply { paint.color = color }, inset / 2)
    }

    private val inactiveBackground = background(theme.accentKeyBackgroundColor, keySize - ctx.dp(33))

    private val activeBackground = background(ColorUtils.compositeColors(theme.keyPressHighlightColor, theme.accentKeyBackgroundColor), keySize - ctx.dp(34))

    private val columnCount = items.size
    private val focusColumn = fixed?.start
        ?: calcInitialFocusedColumn(columnCount, itemWidth, outerBounds, triggerBounds)

    override val offsetX = when {
        fixed?.at != null -> fixed.at.left - triggerBounds.left
        fixed != null -> EditMenuLayout.rowLeft(triggerBounds.centerX(), itemWidth, outerBounds.left, outerBounds.right) - triggerBounds.left
        else -> ((triggerBounds.width() - itemWidth) / 2) - (itemWidth * focusColumn)
    }
    override val offsetY = fixed?.at?.let { it.top - triggerBounds.top }
        ?: scenes?.let { -Popups.px(Popups.MENU_LIFT_DP, it.density).toInt() }
        ?: ctx.dp(-52)

    private val columnOrder = if (fixed != null) IntArray(columnCount) { it } else createColumnOrder(columnCount, focusColumn)

    private var focusedIndex = columnOrder[focusColumn]

    private val sceneView = sceneKeys?.let { made(focusedIndex) }?.let { first -> scenes?.view(ctx)?.also { it.scene = first } }

    /**
     * The engine's, and it could not make the cells (logged): nothing to show, the long press as one
     * with no menu, never cells not drawn that a lift would still pick (no views either: they go).
     */
    val unmade: Boolean get() = scenes != null && sceneView == null

    private val keyViews: List<View> = if (scenes != null) emptyList() else items.map {
        if (it.icon == 0) {
            textView {
                background = inactiveBackground
                text = it.label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, WORDS_SP)
                setTextColor(theme.accentKeyTextColor)
            }
        } else {
            imageView {
                background = inactiveBackground
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                imageDrawable = drawable(it.icon)!!.apply {
                    setTint(theme.accentKeyTextColor)
                }
                contentDescription = it.label
            }
        }
    }.also { views ->
        fixed?.disabled?.forEach { views.getOrNull(it)?.alpha = DISABLED_ALPHA }
    }

    init {
        markFocus(focusedIndex)
    }

    // unmade: never shown (PopupComponent), so nothing
    override val root: View = sceneView ?: if (unmade) View(ctx) else horizontalLayout root@{
        for (i in 0 until columnCount) {
            val view = keyViews[columnOrder[i]]
            add(view, lParams(itemWidth, keySize))
        }
    }

    // the cells in their columns' order, the one in focus selected, the disabled faded
    private fun made(focus: Int) = scenes?.made("menu", builder?.menu(cells, columnOrder.toList(), focus, fixed?.disabled.orEmpty()))

    // the focus to move to [focus]: the scene made again; false where it could not be, the one before
    // kept and the focus with it, so a lift picks the cell drawn selected
    private fun drawScene(focus: Int): Boolean {
        val view = sceneView ?: return true
        view.scene = made(focus) ?: return false
        return true
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
            markInactive(focusedIndex)
            markFocus(newFocus)
            focusedIndex = newFocus
            onFocusMoved(root)
        }
        return false
    }

    override fun onTrigger() = items.getOrNull(focusedIndex)?.takeIf { fixed?.disabled?.contains(focusedIndex) != true }?.action

    companion object {
        /** A cell's width and height, in dp: a key's. */
        const val CELL_DP = 48

        private const val DISABLED_ALPHA = 0.3f

        // a pill of words, of a key's size; their text
        private const val WORDS_WIDTH = 1.75f
        private const val WORDS_SP = 14f
    }
}
