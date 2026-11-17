/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Scene

/**
 * Where the toolbar's 手写 writes on a keyboard (061-handwriting-toolbar §3.2): the keys of its [letters], by
 * the keys' hit areas (the gaps between them shared out), and the keys of [also] (Shift, the user's Q13);
 * faded while written on. The letters' keys are told by their scene keys ([ScenePlace]), not where they are.
 */
internal class LetterArea(
    private val letters: () -> List<Pair<Char, ScenePlace>>,
    private val also: (KeyNode) -> Boolean,
) {
    // the letters' keys of the scene [keysOf]: worked out once a scene
    private var keys: Set<KeyRef> = emptySet()
    private var keysOf: Scene? = null

    // whether the letters were shown faded last: drawn again as that changes
    private var shown = false

    /** The area shown faded ([now], written on) or not: [canvas] drawn again where that changed. */
    fun fade(now: Boolean, canvas: KeyboardCanvasView?) {
        if (now == shown) return
        shown = now
        canvas?.refade()
    }

    /** Whether [k] of [canvas]'s scene is drawn faded: the area's, while written on. */
    fun faded(canvas: KeyboardCanvasView, k: KeyNode): Boolean = shown && has(canvas, k)

    /** Whether ([x], [y]), the keyboard's, is on a key of the area as [canvas] draws it. */
    fun at(canvas: KeyboardCanvasView?, x: Float, y: Float): Boolean {
        val c = canvas ?: return false
        return c.keyAt(x - c.left, y - c.top)?.let { has(c, it) } == true
    }

    /** Whether [k] of [canvas]'s scene is in the area. */
    fun has(canvas: KeyboardCanvasView?, k: KeyNode): Boolean = also(k) || KeyRef(k.id, k.copy == 1) in letterKeys(canvas)

    // the letters' keys of the scene drawn last (not the testing accessor's, which makes one anew where it is dirty)
    private fun letterKeys(c: KeyboardCanvasView?): Set<KeyRef> {
        val s = c?.shownScene ?: return emptySet()
        if (s === keysOf) return keys
        keys = letters().mapTo(HashSet()) { (_, p) -> p.ref }
        keysOf = s
        return keys
    }
}
