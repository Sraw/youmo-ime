/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Scene

/** The letters' keys are told by their scene keys, not where they are; the gaps between keys shared out. */
internal class LetterArea(
    private val letters: () -> List<Pair<Char, ScenePlace>>,
    private val also: (KeyNode) -> Boolean,
) {
    // worked out once a scene
    private var keys: Set<KeyRef> = emptySet()
    private var keysOf: Scene? = null

    private var shown = false

    fun fade(now: Boolean, canvas: KeyboardCanvasView?) {
        if (now == shown) return
        shown = now
        canvas?.refade()
    }

    fun faded(canvas: KeyboardCanvasView, k: KeyNode): Boolean = shown && has(canvas, k)

    fun at(canvas: KeyboardCanvasView?, x: Float, y: Float): Boolean {
        val c = canvas ?: return false
        return c.keyAt(x - c.left, y - c.top)?.let { has(c, it) } == true
    }

    fun has(canvas: KeyboardCanvasView?, k: KeyNode): Boolean = also(k) || KeyRef(k.id, k.copy == 1) in letterKeys(canvas)

    // the scene drawn last (not the testing accessor's, which makes one anew where it is dirty)
    private fun letterKeys(c: KeyboardCanvasView?): Set<KeyRef> {
        val s = c?.shownScene ?: return emptySet()
        if (s === keysOf) return keys
        keys = letters().mapTo(HashSet()) { (_, p) -> p.ref }
        keysOf = s
        return keys
    }
}
