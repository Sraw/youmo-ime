/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.theme.BoardBase
import org.fcitx.fcitx5.android.input.theme.PressEffect
import org.fcitx.fcitx5.android.input.theme.ThemeEditLevels
import org.fcitx.fcitx5.android.input.theme.ThemeLook
import org.fcitx.fcitx5.android.utils.openUrl
import splitties.dimensions.dp
import splitties.resources.resolveThemeAttribute
import splitties.resources.styledDrawable
import splitties.views.textAppearance

/**
 * The theme edit page's few choices of how it looks (§1.5 of the design): the base under the keyboard,
 * the picture's blur, the keys' style in the steps the old key style settings had. The rest (colours,
 * key images, fonts) is the web page's, a line opens it. A value of no step, set there, shows as 自定义
 * till a step is picked. [onChange] is given each look picked.
 */
class ThemeLookChoicesUi(private val ctx: Context, private var look: ThemeLook, private val onChange: (ThemeLook) -> Unit) {

    val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    private val margin = ctx.dp(30)

    private fun label(@StringRes text: Int) = TextView(ctx).apply {
        setText(text)
        textAppearance = ctx.resolveThemeAttribute(android.R.attr.textAppearanceListItem)
        setPadding(margin, ctx.dp(12), margin, 0)
    }

    // a row of a few choices, 自定义 after them for a value of none; [pick] given the one tapped
    private inner class Choices(@StringRes title: Int, options: List<Int>, private val pick: (Int) -> ThemeLook) {
        private val custom = RadioButton(ctx).apply { setText(R.string.theme_level_custom); isEnabled = false }
        private val buttons = options.map { o -> RadioButton(ctx).apply { setText(o) } }
        private val group = RadioGroup(ctx).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(margin - ctx.dp(8), 0, margin, 0)
            (buttons + custom).forEach { addView(it.apply { id = View.generateViewId() }) }
        }
        private var showing = false

        init {
            root.addView(label(title))
            root.addView(group)
            group.setOnCheckedChangeListener { _, id ->
                if (showing) return@setOnCheckedChangeListener
                val i = buttons.indexOfFirst { it.id == id }
                if (i >= 0) set(pick(i))
            }
        }

        fun show(level: Int?) {
            showing = true
            custom.visibility = if (level == null) View.VISIBLE else View.GONE
            group.check((if (level == null) custom else buttons[level]).id)
            showing = false
        }

        fun visible(on: Boolean) {
            val v = if (on) View.VISIBLE else View.GONE
            group.visibility = v
            (root.getChildAt(root.indexOfChild(group) - 1)).visibility = v
        }
    }

    private val base = Choices(R.string.theme_base, listOf(R.string.theme_base_picture, R.string.theme_base_color)) {
        look.copy(base = if (it == 0) BoardBase.Picture else BoardBase.Color)
    }

    private val blurRow = label(R.string.theme_blur)
    private val blur = SeekBar(ctx).apply {
        max = MAX_BLUR_DP
        setPadding(margin, ctx.dp(4), margin, ctx.dp(4))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                blurRow.text = ctx.getString(R.string.theme_blur) + "  " + progress + " dp"
                if (fromUser) look.picture?.let { set(look.copy(picture = it.copy(blurDp = progress))) }
            }
        })
    }

    init {
        root.addView(blurRow)
        root.addView(blur)
    }

    private val blocks = Choices(R.string.theme_keys, listOf(R.string.theme_keys_blocks, R.string.theme_keys_flat)) {
        ThemeEditLevels.blocks(look, it == 0)
    }

    private val radius = Choices(R.string.theme_radius, listOf(R.string.level_square, R.string.level_rounded, R.string.level_round)) {
        ThemeEditLevels.radius(look, it)
    }

    private val border = Choices(R.string.theme_border, listOf(R.string.level_none, R.string.theme_border_thin, R.string.theme_border_thick)) {
        ThemeEditLevels.border(look, it)
    }

    private val gap = Choices(R.string.theme_gap, listOf(R.string.level_compact, R.string.level_standard, R.string.level_loose)) {
        ThemeEditLevels.gap(look, it)
    }

    private val press = Choices(R.string.theme_press, listOf(R.string.theme_press_tint, R.string.theme_press_ripple)) {
        ThemeEditLevels.press(look, if (it == 0) PressEffect.Tint else PressEffect.Ripple)
    }

    init {
        root.addView(label(R.string.theme_more_on_web).apply {
            background = ctx.styledDrawable(android.R.attr.selectableItemBackground)
            setPadding(margin, ctx.dp(16), margin, ctx.dp(16))
            setOnClickListener { ctx.openUrl(THEME_MAKER_URL) }
        })
        show(look)
    }

    private fun set(l: ThemeLook) {
        look = l
        show(l)
        onChange(l)
    }

    /** Shows [l]'s choices, [onChange] not told: a look changed elsewhere on the page (dark keys, a crop). */
    fun show(l: ThemeLook) {
        look = l
        val picture = l.picture
        base.visible(picture != null)
        base.show(if (l.base == BoardBase.Picture) 0 else 1)
        blurRow.visibility = if (picture != null) View.VISIBLE else View.GONE
        blur.visibility = blurRow.visibility
        if (picture != null) {
            blur.progress = picture.blurDp
            blurRow.text = ctx.getString(R.string.theme_blur) + "  " + picture.blurDp + " dp"
        }
        val k = l.keys
        blocks.show(if (k.blocks) 0 else 1)
        radius.show(ThemeEditLevels.of(ThemeEditLevels.RADIUS, k.radiusDp))
        border.show(ThemeEditLevels.of(ThemeEditLevels.BORDER, k.borderDp))
        gap.show(ThemeEditLevels.of(ThemeEditLevels.GAP, k.gap))
        press.show(if (k.press == PressEffect.Tint) 0 else 1)
    }

    companion object {
        /** The theme maker, the web page ([web/theme-maker] of the repository, published by Pages). */
        const val THEME_MAKER_URL = "https://sraw.github.io/youmo-ime/theme-maker/"

        private const val MAX_BLUR_DP = 25
    }
}
