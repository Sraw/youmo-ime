/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKey
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneTouch
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.scene.SceneBehavior
import org.fcitx.fcitx5.android.scene.KeyNode

/** A key is one layout's, none kept for another's. */
internal class SceneKeys(
    private val keyboard: View,
    private val layout: () -> SceneLayout? = { null },
    private val wire: (SceneKey, KeyDef) -> Unit,
) {

    /** Null while the views draw the keys. */
    var touch: SceneTouch? = null
        private set

    // a node that does the same in a scene made anew keeps its key (a finger on it, its timers)
    private val byNode = HashMap<KeyRef, Kept>()

    private class Kept(val does: SceneBehavior, val read: Any?, val key: SceneKey)

    private var madeFor: SceneLayout? = null

    fun none() {
        byNode.clear()
        madeFor = null
        touch = null
    }

    /** [replaced] told of a key a node no longer does as (its gestures cancelled). Keys of nodes gone are kept. */
    fun byNodes(view: KeyboardCanvasView, table: SceneKeyDef.Table, replaced: (SceneKey) -> Unit) {
        none()
        touch = SceneTouch(view, keyFor = { node -> nodeKey(view, node, table, replaced) }, shown = { true })
    }

    private fun nodeKey(view: KeyboardCanvasView, node: KeyNode, table: SceneKeyDef.Table, replaced: (SceneKey) -> Unit): SceneKey {
        // another layout drawn keeps none of the last one's keys: a group's code is its layout's, and a kept key went on
        // sending the other layout's, which the engine reads as nothing or as another keyboard's
        val drawn = layout()
        if (drawn !== madeFor) {
            byNode.values.forEach { gone(it.key, replaced) }
            byNode.clear()
            madeFor = drawn
        }
        val ref = KeyRef.scene(node.id, node.copy)
        // the number pad's no preview, no long press's keys
        val b = SceneKeyDef.onBoard(SceneBehavior.of(node), view.scene)
        val read = table.reads(node)
        // differing in its bubble alone (Shift's case) keeps its key; not one whose table read something of the node that changed
        byNode[ref]?.takeIf { it.does.sansBubble() == b.sansBubble() && it.read == read }?.let { kept ->
            kept.key.bubble = SceneKeyDef.bubbleOf(b, node, table)
            byNode[ref] = Kept(b, read, kept.key)
            return kept.key
        }
        byNode[ref]?.let { gone(it.key, replaced) }
        val def = SceneKeyDef.of(b, node, table)
        return SceneKey(ref, def, view, keyboard).also {
            wire(it, def)
            byNode[ref] = Kept(b, read, it)
        }
    }

    // its timers stopped (a repeat, a long press pending), as when its touch is taken
    private fun gone(old: SceneKey, replaced: (SceneKey) -> Unit) {
        old.keyGesture.cancelGestures()
        replaced(old)
    }

    private fun SceneBehavior.sansBubble() = copy(bubble = bubble?.copy(content = ""))

    fun of(node: KeyNode): SceneKey? = touch?.keyOf(node)
}
