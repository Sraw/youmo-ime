/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates.expanded

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.keyboard.BackspaceKey
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImageLayoutSwitchKey
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.ReturnKey
import org.fcitx.fcitx5.android.input.keyboard.SceneKeyDef
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.engine.BarLook
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.fcitx.fcitx5.android.utils.singleSideBorderDrawable
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.leftToRightOf
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.rightToLeftOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.wrapContent

@SuppressLint("ViewConstructor")
class ExpandedCandidateLayout(context: Context, theme: Theme, bar: BarLook = SceneBar.of(context, theme)) : ConstraintLayout(context) {

    class Keyboard(context: Context, theme: Theme) : BaseKeyboard(context, theme, Layout) {
        companion object {
            const val UpBtnLabel = "U"
            const val DownBtnLabel = "D"

            /** The pages' keys in expanded-side.xml (060-render-p2 S6): their ids, and what a tap on each sends. */
            const val UP_KEY = "page-up"
            const val DOWN_KEY = "page-down"

            /** The column's layout, drawn by the scene. */
            const val LAYOUT = "expanded-side"

            private const val UpBtnId = 0xff55
            private const val DownBtnId = 0xff56

            // read for its keys' images alone (BaseKeyboard's keyImages, by name): what the keys show and do is
            // expanded-side.xml's and PAGES'; the view ids are no one's now
            val Layout: List<List<KeyDef>> = listOf(
                listOf(
                    ImageLayoutSwitchKey(
                        R.drawable.ic_baseline_arrow_upward_24,
                        to = UpBtnLabel,
                        percentWidth = 1f,
                        variant = KeyDef.Appearance.Variant.Alternative,
                        viewId = UpBtnId,
                        contentDescription = R.string.a11y_candidates_prev_page
                    )
                ),
                listOf(
                    ImageLayoutSwitchKey(
                        R.drawable.ic_baseline_arrow_downward_24,
                        to = DownBtnLabel,
                        percentWidth = 1f,
                        variant = KeyDef.Appearance.Variant.Alternative,
                        viewId = DownBtnId,
                        contentDescription = R.string.a11y_candidates_next_page
                    )
                ),
                listOf(BackspaceKey(percentWidth = 1f, KeyDef.Appearance.Variant.Alternative)),
                listOf(ReturnKey(percentWidth = 1f))
            )

            private val PAGES = mapOf(UP_KEY to KeyAction.LayoutSwitchAction(UpBtnLabel), DOWN_KEY to KeyAction.LayoutSwitchAction(DownBtnLabel))
        }

        private var returnAppearance = ReturnKeyAppearance.Enter
        private var upEnabled = true
        private var downEnabled = true

        /** Whether a page up / down turns the list now: the key faded and taking no touch where not. */
        fun showPages(up: Boolean, down: Boolean) {
            if (up == upEnabled && down == downEnabled) return
            upEnabled = up
            downEnabled = down
            canvas?.invalidateScene()
        }

        override fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
            returnAppearance = appearance
            canvas?.invalidateScene()
        }

        override val sceneTable: SceneKeyDef.Table get() = Pages

        private object Pages : SceneKeyDef.Table {
            override fun action(name: String, node: KeyNode): KeyAction? = PAGES[name]
        }

        // laid on the panel's colour and its divider, not a keyboard's fill
        override val sceneDrawsBase: Boolean get() = false

        // Return, the pages that cannot turn `:disabled`; ⌫'s and Return's marks as every keyboard's (060-keys-unify D5)
        override fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env {
            val texts = Texts(returnLabel = returnAppearance.label(context).toString(), returnIcon = SceneIcons.returnName(returnAppearance.icon))
            val env = SceneEnvs.of(this, look, theme.isDark, width, height, Input(), texts = texts, marks = keyMarks(layout))
            val disabled = listOfNotNull(UP_KEY.takeIf { !upEnabled }, DOWN_KEY.takeIf { !downEnabled })
            return if (disabled.isEmpty()) env else env.copy(states = disabled.associateWith { setOf(KeyState.Disabled) })
        }

        // last: the scene reads the keyboard's state
        init {
            drawKeysWithScene(LAYOUT)
        }
    }


    // inflated as splitties' recyclerView {} is, its scrollbars' style and all; the strokes drawn on it in GlideInput's
    // trail colour (笔画筛选)
    val recyclerView = (LayoutInflater.from(context).inflate(R.layout.stroke_page_list, null, false) as StrokePageRecyclerView).apply {
        trailColor = theme.accentKeyBackgroundColor
        // disable item cross-fade animation
        itemAnimator = null
        isVerticalScrollBarEnabled = false
    }

    val embeddedKeyboard = Keyboard(context, theme)

    /**
     * 「这里划的是笔画，翻页用右边 ↑↓」 atop the list ([showHint]): over it, taking no touch (not clickable, the list's
     * strokes under it), not read (shown only where a finger draws: never with a screen reader's touch exploration).
     */
    val hint = TextView(context).apply {
        setText(R.string.stroke_page_hint)
        gravity = Gravity.CENTER
        textSize = HINT_SP
        setTextColor(theme.popupTextColor)
        backgroundColor = theme.popupBackgroundColor
        val pad = dp(HINT_PAD_DP)
        setPadding(pad, pad, pad, pad)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    private val hintTimeout = Runnable { hideHint(fade = true) }
    private var fades = true

    init {
        id = R.id.expanded_candidate_view

        val landscape = KeyboardArea.landscape(context)
        val keys = ThemeManager.shownLook(theme).keys
        val inset = dp(if (landscape) keys.gap.vLand else keys.gap.v)
        // the theme's `candidates-panel` (T6a), else its panels' (the flat keys': the bar's colour, ThemeColors.panel);
        // beside the keys, the line between candidates
        bar.panel?.let {
            backgroundColor = it.value
            bar.divider?.let { line -> embeddedKeyboard.background = singleSideBorderDrawable(dp(1), line.value, Gravity.LEFT, inset) }
        }

        add(recyclerView, lParams {
            topOfParent()
            leftOfParent()
            rightToLeftOf(embeddedKeyboard)
            bottomOfParent()
        })
        add(hint, lParams(0, wrapContent) {
            topOfParent()
            leftOfParent()
            rightToLeftOf(embeddedKeyboard)
        })
        add(embeddedKeyboard, lParams {
            matchConstraintPercentWidth = 0.15f
            topOfParent()
            leftToRightOf(recyclerView)
            rightOfParent()
            bottomOfParent()
        })
    }

    fun resetPosition() {
        recyclerView.scrollToPosition(0)
    }

    /** The hint shown, gone [millis] after; faded out if [fade] (移除动画 off). */
    fun showHint(millis: Long, fade: Boolean) {
        fades = fade
        removeCallbacks(hintTimeout)
        hint.animate().cancel()
        hint.alpha = 1f
        hint.visibility = VISIBLE
        postDelayed(hintTimeout, millis)
    }

    /** The hint gone now (the page closed). */
    fun hideHint() {
        removeCallbacks(hintTimeout)
        hideHint(fade = false)
    }

    private fun hideHint(fade: Boolean) {
        hint.animate().cancel()
        if (fade && fades && hint.visibility == VISIBLE) {
            hint.animate().alpha(0f).setDuration(HINT_FADE_MILLIS).withEndAction { hint.visibility = GONE }
        } else {
            hint.visibility = GONE
        }
    }

    private companion object {
        const val HINT_SP = 14f
        const val HINT_PAD_DP = 6
        const val HINT_FADE_MILLIS = 250L
    }
}