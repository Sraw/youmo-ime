/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.pinyin.KeyBoxes
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.scene.KeyRef
import kotlin.math.roundToInt

/** A keyboard whose taps go to the engine with where they fell (061-neighbour-correction). */
internal interface TypingKeys {
    /** Its keys that do: what each types as the engine reads it (a letter, 九键's digit, a group's code) and which key of the scene it is. */
    fun typingKeys(): List<Pair<Char, KeyRef>>

    /** Of [typingKeys]' characters, those whose key's letters the layout stacks, a place along them down it (Near.stacked). */
    fun stackedKeys(): Set<Char> = emptySet()
}

/**
 * [action], the press of the key [k], with where its tap fell (across and down the key; a tap that
 * slid too: where it came down, KeyGesture.fellAt) and the keys around it, in dp as the scene drawn
 * last ([canvas]) has them, where [k] is one of [typing]'s keys and the action types. As it is where
 * no place is known, and on a keyboard none of whose taps carry it.
 */
internal fun withTouch(action: KeyAction, k: KeyHandle, typing: TypingKeys?, density: Float, canvas: KeyboardCanvasView?): KeyAction {
    val at = k.keyGesture.fellAt
    val down = k.keyGesture.fellDown
    // an action that types nothing, no place: nothing to measure, the keys not even asked for
    val types = action is KeyAction.HostKeyAction || action is KeyAction.SymAction
    val measured = types && !at.isNaN() && !down.isNaN()
    if (typing == null || canvas == null || !measured) return action
    val typed = typing.typingKeys().associate { it.second to it.first }
    // space, return and the like: none of [typing]'s keys
    if (k.ref !in typed) return action
    // one pass over the keys drawn, each looked up (not each key's cell searched for)
    val boxes = ArrayList<Pair<Char, KeyBoxes.Box>>(typed.size)
    var hitBox = -1
    for (node in canvas.shownKeys) {
        val ref = KeyRef(node.id, node.copy != 0)
        val ch = typed[ref] ?: continue
        if (ref == k.ref && hitBox < 0) hitBox = boxes.size
        // in whole pixels, as the cells drawn are (Rect.set(Box))
        val r = node.bounds
        boxes += ch to KeyBoxes.Box(r.left.roundToInt() / density, r.top.roundToInt() / density, r.right.roundToInt() / density, r.bottom.roundToInt() / density)
    }
    val box = boxes.getOrNull(hitBox)?.second
    val placed = box?.let { KeyBoxes.touch(boxes, hitBox, it.left + at * it.w, it.top + down * it.h) } ?: return action
    // a slip onto a key whose letters are stacked is placed down it, as a tap on it is sent (GroupedKeyboard.tapped)
    val stacked = typing.stackedKeys()
    val touch = if (stacked.isEmpty()) placed else placed.copy(near = placed.near.map { n -> if (n.char in stacked) n.copy(stacked = true) else n })
    return when (action) {
        is KeyAction.HostKeyAction -> action.copy(touch = touch)
        is KeyAction.SymAction -> action.copy(touch = touch)
        else -> action
    }
}
