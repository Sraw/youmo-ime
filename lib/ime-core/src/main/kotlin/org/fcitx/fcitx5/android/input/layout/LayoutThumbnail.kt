/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.layout

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.engine.FixedMeasurer
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.scene.look.KeyKind
import org.fcitx.fcitx5.android.scene.theme.Theme

/**
 * A layout's keys in little, for its card on 外观 → 布局 (061-custom-layout §4.3): where each key is, as a share of the
 * board, and its kind, drawn in the theme's colours and shape as a theme's card draws its 26 keys (ThemeThumbnailKeys).
 * Made from the scene the engine builds on the first screen the check builds it on (an upright phone's), a number board
 * in its first pad; a slot (the symbols' grid, the nine keys' side column) as a function key.
 */
object LayoutThumbnail {
    /** A key's box, each side a share of the board's width or height (0 to 1), and its [kind]. */
    data class Cell(val left: Float, val top: Float, val right: Float, val bottom: Float, val kind: KeyKind)

    /** [layout]'s keys with its [fragments]; none where the engine builds no scene of it. */
    fun cells(layout: Layout, fragments: Map<String, Fragment>): List<Cell> {
        val pads = if (layout.family == "number") layout.pads ?: listOf("full") else emptyList()
        val env = LayoutCheck.scenes(layout, emptyList(), pads).first().env
        val scene = (SceneBuilder(PLAIN, env, FixedMeasurer).build(layout, fragments) as? Parsed.Ok)?.value ?: return emptyList()
        val w = scene.width.toFloat()
        val h = scene.height.toFloat()
        if (w <= 0f || h <= 0f) return emptyList()
        val keys = scene.keys.map { k -> Cell(k.bounds.left.toFloat() / w, k.bounds.top.toFloat() / h, k.bounds.right.toFloat() / w, k.bounds.bottom.toFloat() / h, kind(k.kind)) }
        val slots = scene.slots.map { s -> Cell(s.bounds.left.toFloat() / w, s.bounds.top.toFloat() / h, s.bounds.right.toFloat() / w, s.bounds.bottom.toFloat() / h, KeyKind.Function) }
        return keys + slots
    }

    private fun kind(kind: String): KeyKind = when (kind) {
        "letter" -> KeyKind.Letter
        "space" -> KeyKind.Space
        "enter" -> KeyKind.Enter
        else -> KeyKind.Function
    }

    // a theme of nothing: only where the keys go is asked
    private val PLAIN = Theme("thumbnail", false, "1", emptyMap(), emptyList(), emptyMap(), emptyMap(), null)
}
