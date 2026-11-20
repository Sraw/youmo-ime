/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.engine.Expand
import org.fcitx.fcitx5.android.scene.engine.Place

/**
 * LayoutCheck's `split-scaled` warning: a `split="halves"` row that, split, has other than 9 or 10 keys to halve in
 * one of the scenes it is checked in. The engine scales such a row instead of halving it (Place, a scene's warning
 * only), so the user, who meant halves, is told: its line, the keys it has there and that scene's name, once a row.
 */
internal object ScaledRows {
    fun find(layout: Layout, fragments: Map<String, Fragment>, scenes: List<LayoutCheck.Scene>): List<LayoutCheck.Finding> {
        if (layout.onSplit != OnSplit.Split) return emptyList()
        val out = LinkedHashMap<Int, LayoutCheck.Finding>()
        // every scene split: its when= (an input method's key, `!split`) as a split board has it; a part at a time, a
        // fragment's row (its line the fragment's) told at the <include> that brings it
        for (scene in scenes) for (part in layout.parts) {
            val line = when (part) {
                is Row -> part.line
                is Include -> part.line
            }
            val rows = Expand.rows(layout.with(layout.defaults, listOf(part), layout.popups), fragments, scene.env.copy(split = true)).orNull() ?: continue
            for (row in rows) {
                val n = Place.unhalved(row) ?: continue
                out.getOrPut(line) { LayoutCheck.Finding(line, "split-scaled", listOf(n.toString(), scene.name)) }
            }
        }
        return out.values.toList()
    }
}
