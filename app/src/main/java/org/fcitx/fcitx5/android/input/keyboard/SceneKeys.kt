/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKey
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneTouch
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.scene.SceneBehavior
import org.fcitx.fcitx5.android.scene.KeyNode

/**
 * The keys a keyboard's scene draws (060-render-p2 S1, S4) and the touch that finds them, each a
 * [SceneKey] wired as BaseKeyboard wires a view ([wire]): made for the view it stands in for (U4 A,
 * [byViews]) or from its layout's node (U4 B, [byNodes]). [keyboard]: whose window the keys' timers
 * live as long as.
 */
internal class SceneKeys(private val keyboard: View, private val wire: (SceneKey, KeyDef) -> Unit) {

    /** What a touch on the canvas finds; null while the views draw the keys. */
    var touch: SceneTouch? = null
        private set

    // the scene's keys, by the views they were wired as ([byViews])
    private var byView: Map<KeyView, SceneKey> = emptyMap()

    // the keys made from the scene's nodes ([byNodes]), each with what it was made from: a node
    // that does the same in a scene made anew keeps its key (a finger on it, its timers)
    private val byNode = HashMap<KeyRef, Pair<SceneBehavior, SceneKey>>()

    /** None: the views draw the keys again. */
    fun none() {
        byView = emptyMap()
        byNode.clear()
        touch = null
    }

    /** The keys of [view], each wired as the view of [wired] that [refOf] names it for was; a view hidden, its key takes no touch. */
    fun byViews(view: KeyboardCanvasView, wired: List<Pair<KeyView, KeyDef>>, refOf: (KeyView) -> KeyRef?) {
        none()
        byView = wired.mapNotNull { (v, def) ->
            refOf(v)?.let { ref -> v to SceneKey(ref, def, view, keyboard).also { wire(it, def) } }
        }.toMap()
        val viewOf = byView.entries.associate { (v, k) -> k to v }
        touch = SceneTouch(view, byView.values.toList()) { k -> viewOf[k]?.visibility == View.VISIBLE }
    }

    /**
     * The keys of [view], each made as the scene first has its node, wired as the node says with
     * [table] the keyboard's own words ([SceneKeyDef]); [replaced] told of a key a node no longer does as
     * (its gestures cancelled). The keys of nodes gone from the scene are kept (060-render-p2: a to-do).
     */
    fun byNodes(view: KeyboardCanvasView, table: SceneKeyDef.Table, replaced: (SceneKey) -> Unit) {
        none()
        touch = SceneTouch(view, keyFor = { node -> nodeKey(view, node, table, replaced) }, shown = { true })
    }

    private fun nodeKey(view: KeyboardCanvasView, node: KeyNode, table: SceneKeyDef.Table, replaced: (SceneKey) -> Unit): SceneKey {
        val ref = KeyRef.scene(node.id, node.copy)
        val b = SceneBehavior.of(node)
        byNode[ref]?.takeIf { it.first == b }?.let { return it.second }
        // the key it was: its timers stopped (a repeat, a long press pending), as a key's are when its touch is taken
        byNode[ref]?.second?.let { old ->
            old.keyGesture.cancelGestures()
            replaced(old)
        }
        val def = SceneKeyDef.of(b, node, table)
        return SceneKey(ref, def, view, keyboard).also {
            wire(it, def)
            byNode[ref] = b to it
        }
    }

    /** The key wired as [view] was ([byViews]). */
    fun of(view: KeyView): SceneKey? = byView[view]

    /** The key wired for [node]: what a screen reader's actions on it do. */
    fun of(node: KeyNode): SceneKey? = touch?.keyOf(node)
}
