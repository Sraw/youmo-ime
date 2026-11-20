/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates.expanded

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.SceneKeyDef
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
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

@SuppressLint("ViewConstructor")
class ExpandedCandidateLayout(context: Context, theme: Theme, bar: BarLook = SceneBar.of(context, theme)) : ConstraintLayout(context) {

    class Keyboard(context: Context, theme: Theme) : BaseKeyboard(context, theme) {
        companion object {
            const val UpBtnLabel = "U"
            const val DownBtnLabel = "D"

            const val UP_KEY = "page-up"
            const val DOWN_KEY = "page-down"

            const val LAYOUT = "expanded-side"

            private val PAGES = mapOf(UP_KEY to KeyAction.LayoutSwitchAction(UpBtnLabel), DOWN_KEY to KeyAction.LayoutSwitchAction(DownBtnLabel))
        }

        private var upEnabled = true
        private var downEnabled = true

        /** The key faded and taking no touch where not. */
        fun showPages(up: Boolean, down: Boolean) {
            if (up == upEnabled && down == downEnabled) return
            upEnabled = up
            downEnabled = down
            canvas?.invalidateScene()
        }

        override val sceneTable: SceneKeyDef.Table get() = Pages

        private object Pages : SceneKeyDef.Table {
            override fun action(name: String, node: KeyNode): KeyAction? = PAGES[name]
        }

        // laid on the panel's colour and its divider, not a keyboard's fill
        override val sceneDrawsBase: Boolean get() = false

        override fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env {
            val texts = Texts().withReturn()
            val env = SceneEnvs.of(this, look, theme.isDark, width, height, Input(), texts = texts, marks = keyMarks(layout))
            val disabled = listOfNotNull(UP_KEY.takeIf { !upEnabled }, DOWN_KEY.takeIf { !downEnabled })
            return if (disabled.isEmpty()) env else env.copy(states = disabled.associateWith { setOf(KeyState.Disabled) })
        }

        // last: the scene reads the keyboard's state
        init {
            drawKeysWithScene(LAYOUT)
        }
    }


    // inflated as splitties' recyclerView {} is, its scrollbars' style and all
    val recyclerView = (LayoutInflater.from(context).inflate(R.layout.stroke_page_list, null, false) as StrokePageRecyclerView).apply {
        trailColor = theme.accentKeyBackgroundColor
        // disable item cross-fade animation
        itemAnimator = null
        isVerticalScrollBarEnabled = false
    }

    val embeddedKeyboard = Keyboard(context, theme)

    init {
        id = R.id.expanded_candidate_view

        val landscape = KeyboardArea.landscape(context)
        val keys = ThemeManager.shownLook(theme).keys
        val inset = dp(if (landscape) keys.gap.vLand else keys.gap.v)
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
}