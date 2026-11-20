/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.keyboard.customLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.ScenePanels
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add

/** The emoji: one list, its tabs on the bar. The symbol [panel]: categories at the side, a lock in the keys. */
@SuppressLint("ViewConstructor")
class PickerLayout(
    context: Context,
    private val theme: Theme,
    density: PickerGridView.Density,
    private val panel: Boolean,
    private val searches: Boolean = false,
    custom: String? = null,
) : ConstraintLayout(context) {

    class Keyboard(context: Context, theme: Theme, board: String, sceneLayout: String, custom: String? = null) :
        BaseKeyboard(context, theme, themeBoard = board) {

        private var locked = false

        fun showLocked(locked: Boolean) {
            this.locked = locked
            canvas?.invalidateScene()
        }

        /** False with no scene. */
        fun slot(id: String, out: Rect): Boolean {
            val c = canvas ?: return false
            if (!c.slot(id, out)) return false
            out.offset(left + c.left, top + c.top)
            return true
        }

        override fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env {
            val texts = Texts(
                back = context.getString(R.string.picker_back),
                punctuation = keysPunctuation, // , and . as typed ({punct:,}: 显示和输出一致)
            ).withReturn()
            val env = SceneEnvs.of(this, look, theme.isDark, width, height, Input(holdVoice = holdsToTalk), texts = texts, marks = keyMarks(layout))
            return if (locked) env.copy(states = LOCKED) else env
        }

        // said as what a tap does, as its view said
        override fun sceneString(name: String): String? = when (name) {
            "a11y_key_panel_lock_on" -> context.getString(R.string.picker_unlock)
            "a11y_key_panel_lock_off" -> context.getString(R.string.picker_lock)
            else -> null
        }

        // last: the scene reads the keyboard's state
        init {
            // the canvas at another size: the panel's views to the slots of the scene at that size
            drawKeysWithScene(custom?.let { customLayout(context, it) } ?: SceneLayouts.of(context, sceneLayout))
            // only when its size changed: a layout at the same size places the slots where they are
            canvas?.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                if (r - l != oldR - oldL || b - t != oldB - oldT) post { (parent as? View)?.requestLayout() }
            }
        }

        private companion object {
            val LOCKED = mapOf("lock" to setOf(KeyState.Locked))
        }
    }

    var custom: String? = custom.takeIf { panel }
        private set

    var embeddedKeyboard = if (panel) {
        panelBoard(this.custom)
    } else {
        Keyboard(context, theme, KeyNames.EMOJI, if (searches) "emoji" else "emoticon")
    }
        private set

    private fun panelBoard(custom: String?) = Keyboard(context, theme, KeyNames.SYMBOLS, "symbols", custom)

    /** Told before it is shown (PickerWindow.onAttached), which wires its listeners after. */
    fun drawKeys(custom: String?) {
        if (!panel || custom == this.custom) return
        this.custom = custom
        removeView(embeddedKeyboard)
        embeddedKeyboard = panelBoard(custom)
        addView(embeddedKeyboard, 0, lParams(matchConstraints, matchConstraints) { centerInParent() })
    }

    // not set, nothing: the keyboard's under it
    val grid = PickerGridView(context, theme, density).apply {
        ScenePanels.picker(context, theme, side = panel).fill?.let { setBackgroundColor(it.value) }
    }

    val tabsUi by lazy { PickerTabsUi(context, theme) }

    val side: PickerSideUi? = if (panel) PickerSideUi(context, theme) else null

    /** None till first opened. */
    var search: EmojiSearchUi? = null
        private set

    init {
        if (panel) {
            // the keyboard under it all, the categories and the grid over it, each in its slot (onLayout)
            add(embeddedKeyboard, lParams(matchConstraints, matchConstraints) { centerInParent() })
            side?.let { add(it, lParams(matchConstraints, matchConstraints) { topOfParent(); leftOfParent() }) }
            add(grid, lParams(matchConstraints, matchConstraints) { topOfParent(); leftOfParent() })
        } else {
            addViews()
        }
    }

    private val slotCell = Rect()

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!panel) return
        side?.let { if (embeddedKeyboard.slot(CATEGORIES_SLOT, slotCell)) it.layoutIn(slotCell) }
        if (embeddedKeyboard.slot(GRID_SLOT, slotCell)) grid.layoutIn(slotCell)
    }

    // absolute: not mirrored right to left, as the scene's cells are not. Laid out at once, so a size changed moves
    // it in this pass; only when it moved, so the passes settle and the RecyclerView is not asked to lay out again
    private fun View.layoutIn(r: Rect) {
        val p = layoutParams as LayoutParams
        val placed = left == r.left && top == r.top && width == r.width() && height == r.height()
        val kept = p.width == r.width() && p.height == r.height() && p.leftMargin == r.left && p.topMargin == r.top
        if (placed && kept) return
        layoutParams = lParams(r.width(), r.height()) {
            leftOfParent(r.left)
            topOfParent(r.top)
        }
        measure(MeasureSpec.makeMeasureSpec(r.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(r.height(), MeasureSpec.EXACTLY))
        layout(r.left, r.top, r.right, r.bottom)
    }

    private fun addViews() {
        if (side != null) {
            add(side, lParams {
                topOfParent()
                startOfParent()
                above(embeddedKeyboard)
                matchConstraintPercentWidth = SIDE_WIDTH
            })
        }
        add(grid, lParams {
            topOfParent()
            if (side != null) {
                after(side)
                endOfParent()
            } else {
                centerHorizontally()
            }
            above(embeddedKeyboard)
        })
        add(embeddedKeyboard, lParams {
            below(grid)
            centerHorizontally()
            bottomOfParent()
            matchConstraintPercentHeight = 0.25f
        })
    }

    fun openSearch(onMade: (EmojiSearchUi) -> Unit): EmojiSearchUi? {
        if (search == null && searches) {
            val ui = EmojiSearchUi(context, theme).apply { visibility = GONE }
            add(ui, lParams(matchConstraints, matchConstraints) { centerInParent() })
            search = ui
            onMade(ui)
        }
        return search
    }

    companion object {
        private const val SIDE_WIDTH = 0.18f

        internal const val CATEGORIES_SLOT = "categories"
        internal const val GRID_SLOT = "grid"
    }
}
