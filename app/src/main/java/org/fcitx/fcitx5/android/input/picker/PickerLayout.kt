/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.keyboard.BackspaceKey
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.LayoutSwitchKey
import org.fcitx.fcitx5.android.input.keyboard.ReturnKey
import org.fcitx.fcitx5.android.input.keyboard.SYMBOL_NAMES
import org.fcitx.fcitx5.android.input.keyboard.SpaceKey
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
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

/**
 * The picker over a row of keys. The emoji: one list, its tabs on the bar. The symbol [panel]:
 * the categories at the side, the one picked beside them, and a lock in the row of keys.
 */
@SuppressLint("ViewConstructor")
class PickerLayout(context: Context, private val theme: Theme, switchKey: KeyDef, density: PickerGridView.Density, private val panel: Boolean, searchKey: KeyDef? = null) :
    ConstraintLayout(context) {

    class Keyboard(context: Context, theme: Theme, keys: List<KeyDef>, board: String, sceneLayout: String) :
        BaseKeyboard(context, theme, listOf(keys), themeBoard = board) {

        class PunctuationKey(val symbol: String) : KeyDef(
            Appearance.Text(
                displayText = symbol,
                textSize = 23f,
                percentWidth = 0.1f,
                variant = Appearance.Variant.Alternative
            ),
            setOf(
                Behavior.Press(KeyAction.HostKeyAction(symbol))
            ),
            name = SYMBOL_NAMES[symbol],
        )

        // as last shown: the scene's lock and Return too
        private var locked = false
        private var returnAppearance = ReturnKeyAppearance.Enter

        fun showLocked(locked: Boolean) {
            this.locked = locked
            canvas?.invalidateScene()
        }

        override fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
            returnAppearance = appearance
            canvas?.invalidateScene()
        }

        /** Slot [id]'s cell in this keyboard's parent, into [out], as the scene has it (symbols.xml, 060-render-p2 S5c); false with no scene. */
        fun slot(id: String, out: Rect): Boolean {
            val c = canvas ?: return false
            if (!c.slot(id, out)) return false
            out.offset(left + c.left, top + c.top)
            return true
        }

        // the lock (its icon, :locked), Return, 返回 in the app's words, the space's microphone where holding it talks
        override fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env {
            val texts = Texts(
                returnLabel = returnAppearance.label(context).toString(),
                returnIcon = SceneIcons.returnName(returnAppearance.icon),
                back = context.getString(R.string.picker_back),
                punctuation = keysPunctuation, // , and . as typed ({punct:,}: 显示和输出一致)
            )
            // its named keys' marks as every keyboard's (060-keys-unify D5): ⌫'s, the space's, Return's, the punctuation's
            val env = SceneEnvs.of(this, look, theme.isDark, width, height, Input(holdVoice = holdsToTalk), texts = texts, marks = keyMarks(layout))
            return if (locked) env.copy(states = LOCKED) else env
        }

        // the lock said as what a tap does, as its view said (SceneA11y's _on and _off)
        override fun sceneString(name: String): String? = when (name) {
            "a11y_key_panel_lock_on" -> context.getString(R.string.picker_unlock)
            "a11y_key_panel_lock_off" -> context.getString(R.string.picker_lock)
            else -> null
        }

        // last: the scene reads the keyboard's state
        init {
            // the canvas at another size (one-handed, split, another height): the panel's views to the slots of the scene at that size
            drawKeysWithScene(sceneLayout)
            // only when its size changed: a layout at the same size places the slots where they are
            canvas?.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                if (r - l != oldR - oldL || b - t != oldB - oldT) post { (parent as? View)?.requestLayout() }
            }
        }

        private companion object {
            val LOCKED = mapOf("lock" to setOf(KeyState.Locked))
        }
    }

    // the symbols' board, or the emoji's (the emoticons' too)
    val embeddedKeyboard = Keyboard(
        context, theme, if (panel) panelKeys(context, switchKey) else listKeys(switchKey, searchKey),
        if (panel) KeyNames.SYMBOLS else KeyNames.EMOJI,
        // the symbol panel's keys, the categories' and the grid's slots; the emoji's row, with its search or not
        // (the emoticons', S8a)
        when {
            panel -> "symbols"
            searchKey != null -> "emoji"
            else -> "emoticon"
        },
    )

    // on the theme's `picker { fill }` (060-render-p3 T7b); not set, nothing: the keyboard's under it
    val grid = PickerGridView(context, theme, density).apply {
        ScenePanels.picker(context, theme, side = panel).fill?.let { setBackgroundColor(it.value) }
    }

    val tabsUi by lazy { PickerTabsUi(context, theme) }

    val side: PickerSideUi? = if (panel) PickerSideUi(context, theme) else null

    private val searches = searchKey != null

    /** The emoji's search ([searchKey]), over the rest while shown; none till first opened ([openSearch]). */
    var search: EmojiSearchUi? = null
        private set

    init {
        if (panel) {
            // the scene's board is the whole panel (symbols.xml): the keyboard under it all, the categories
            // and the grid over it, each in its slot (onLayout)
            add(embeddedKeyboard, lParams(matchConstraints, matchConstraints) { centerInParent() })
            side?.let { add(it, lParams(matchConstraints, matchConstraints) { topOfParent(); leftOfParent() }) }
            add(grid, lParams(matchConstraints, matchConstraints) { topOfParent(); leftOfParent() })
        } else {
            addViews()
        }
    }

    private val slotCell = Rect()

    // the categories and the grid in the scene's slots, not where the constraints put them
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!panel) return
        side?.let { if (embeddedKeyboard.slot(CATEGORIES_SLOT, slotCell)) it.layoutIn(slotCell) }
        if (embeddedKeyboard.slot(GRID_SLOT, slotCell)) grid.layoutIn(slotCell)
    }

    // the view at [r]: its constraints the slot's from the parent's left and top (absolute: not mirrored
    // right to left, as the scene's cells are not), and laid out there at once, so a size changed (one-handed,
    // split, another height) moves it in this pass. Done only when it moved, so the passes settle: at [r]
    // already the constraints put it nowhere else, and the RecyclerView is not asked to lay out again
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

    /** [search], made and laid over the rest the first time, [onMade] then; none without a [searchKey]. */
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

        /** symbols.xml's slots: the categories at the side, the grid beside them. */
        internal const val CATEGORIES_SLOT = "categories"
        internal const val GRID_SLOT = "grid"

        // the emoji's search in place of the comma, which the letters have a step away
        internal fun listKeys(switchKey: KeyDef, searchKey: KeyDef?) = listOf(
            LayoutSwitchKey("ABC", TextKeyboard.Name),
            searchKey ?: Keyboard.PunctuationKey(","),
            switchKey,
            SpaceKey(),
            Keyboard.PunctuationKey("."),
            // here, not in the list: it stays put while the list scrolls
            BackspaceKey(),
            ReturnKey()
        )

        /** Sogou's: 返回 where ABC was, the lock beside it; the punctuation is in the panel */
        internal fun panelKeys(context: Context, switchKey: KeyDef) = listOf(
            KeyDef(
                KeyDef.Appearance.Text(
                    displayText = context.getString(R.string.picker_back),
                    textSize = 16f,
                    textStyle = Typeface.BOLD,
                    percentWidth = 0.15f,
                    variant = KeyDef.Appearance.Variant.Alternative
                ),
                setOf(KeyDef.Behavior.Press(KeyAction.PanelBackAction)),
                name = "back",
            ),
            KeyDef(
                KeyDef.Appearance.Image(
                    src = R.drawable.ic_baseline_lock_open_24,
                    percentWidth = 0.12f,
                    variant = KeyDef.Appearance.Variant.Alternative,
                    viewId = R.id.button_panel_lock,
                    contentDescription = R.string.picker_lock
                ),
                setOf(KeyDef.Behavior.Press(KeyAction.PanelLockAction)),
                name = "lock",
            ),
            switchKey,
            SpaceKey(),
            BackspaceKey(),
            ReturnKey()
        )
    }
}
