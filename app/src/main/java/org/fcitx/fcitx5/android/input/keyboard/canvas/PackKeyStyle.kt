/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import org.fcitx.fcitx5.android.input.theme.KeyStyleChoice
import org.fcitx.fcitx5.android.scene.look.Gap
import org.fcitx.fcitx5.android.scene.theme.Inset
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.Style
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.Value

/**
 * The spacing settings (横向间距, 纵向间距) over a 4.0 pack's keys, as they are over a 3.0 theme's
 * (KeyStyleChoice.applyTo): the pack's own `inset`s are its default only (the owner, 0.6.0-beta2: 国风·天青
 * drew its `inset="5dp 3dp"` whatever was set). For every key selector of the pack that sets an inset, and
 * the plain `key` (a pack setting none draws none), a style after all of the pack's, in each of the four
 * forms (upright, on its side, 紧凑, both) its scenes added, sets the inset with the set direction the
 * setting's: in each form it outranks the pack's style that won there (the same selector, a scene more or
 * as many and later), so a key's own inset in any form, `key.enter`'s or `@compact key`'s, gives way too.
 * At 紧凑 the vertical room is the level's shrunk ([Gap.compact]), as a 3.0 theme's is.
 */
object PackKeyGap {

    fun over(theme: Theme, h: KeyStyleChoice.Spacing?, v: KeyStyleChoice.Spacing?): Theme {
        if (h == null && v == null) return theme
        val setters = listOf(NONE to listOf(Selector(emptySet(), KEY))) + theme.styles.mapNotNull { s ->
            val inset = (s.values[INSET] as? Value.OfInset)?.inset ?: return@mapNotNull null
            inset to s.selectors.filter { it.widget == KEY && it.part == null }
        }
        var order = (theme.styles.maxOfOrNull { it.order } ?: 0) + 1
        val added = setters.flatMap { (inset, selectors) ->
            selectors.flatMap { sel ->
                FORMS.map { form ->
                    val scenes = sel.scenes + form
                    val gap = inset(inset, h, v, LANDSCAPE in scenes, COMPACT in scenes)
                    Style(listOf(sel.copy(scenes = scenes)), mapOf(INSET to Value.OfInset(gap)), order++, 0)
                }
            }
        }
        return with(theme) {
            Theme(name, dark, version, colors, styles + added, fonts, icons, picture, variants, baseColors, darkColors, meta, systemColors, soundFiles)
        }
    }

    // [inset] with the set directions the settings', in the form [landscape] and [compact] say
    private fun inset(inset: Inset, h: KeyStyleChoice.Spacing?, v: KeyStyleChoice.Spacing?, landscape: Boolean, compact: Boolean): Inset {
        val across = h?.let { (if (landscape) it.landscape else it.portrait).toDouble() }
        val upDown = v?.let { (if (landscape) it.landscape else it.portrait).let { dp -> if (compact) Gap.compact(dp) else dp }.toDouble() }
        return Inset(upDown ?: inset.top, across ?: inset.right, upDown ?: inset.bottom, across ?: inset.left)
    }

    private const val KEY = "key"
    private const val INSET = "inset"
    private const val LANDSCAPE = "landscape"
    private const val COMPACT = "compact"
    private val NONE = Inset(0.0, 0.0, 0.0, 0.0)
    private val FORMS = listOf(emptySet(), setOf(LANDSCAPE), setOf(COMPACT), setOf(LANDSCAPE, COMPACT))
}
