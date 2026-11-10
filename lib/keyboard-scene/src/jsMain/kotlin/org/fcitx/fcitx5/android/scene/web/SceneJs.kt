/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.web

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Point
import org.fcitx.fcitx5.android.scene.Problem
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.Shape

/** Plain JS objects of the engine's values, for a page: what it reads of a scene (never its ops: [CanvasPainter] draws them). */
internal object SceneJs {
    fun obj(vararg members: Pair<String, Any?>): dynamic {
        val o: dynamic = js("({})")
        for ((k, v) in members) o[k] = v
        return o
    }

    fun problems(list: List<Problem>): Array<dynamic> = list.map { obj("line" to it.line, "message" to it.message) }.toTypedArray()

    fun box(b: Box): Array<Double> = arrayOf(b.left, b.top, b.right, b.bottom)

    fun point(p: Point): Array<Double> = arrayOf(p.x, p.y)

    fun shape(s: Shape): dynamic = when (s) {
        is Shape.Rect -> obj("rect" to box(s.box), "radii" to s.radii.toTypedArray())
        is Shape.Path -> obj(
            "path" to s.ops.map {
                when (it) {
                    is Shape.Path.Op.MoveTo -> arrayOf<Any>("M", it.x, it.y)
                    is Shape.Path.Op.LineTo -> arrayOf<Any>("L", it.x, it.y)
                    Shape.Path.Op.Close -> arrayOf<Any>("Z")
                }
            }.toTypedArray(),
        )
    }

    fun map(m: Map<String, String>): dynamic = obj(*m.entries.map { it.key to it.value }.toTypedArray())

    /**
     * [scene] as a page reads it: its size and board, its keys (id, which copy, kind, role, states, shape, cell,
     * touch area, middle, up, texts as shown, what a screen reader says, actions, long press as written) and
     * slots, in drawing order; warnings as problems.
     */
    fun scene(scene: Scene): dynamic = obj(
        "width" to scene.width,
        "height" to scene.height,
        "board" to box(scene.board),
        "preview" to scene.preview,
        "popups" to scene.popups,
        "warnings" to problems(scene.warnings),
        "keys" to scene.keys.map(::key).toTypedArray(),
        "slots" to scene.slots.map { obj("kind" to it.kind, "id" to it.id, "bounds" to box(it.bounds)) }.toTypedArray(),
    )

    private fun key(k: KeyNode): dynamic = obj(
        "id" to k.id,
        "copy" to k.copy,
        "kind" to k.kind,
        "role" to k.role,
        "states" to k.states.map { it.xml }.sorted().toTypedArray(),
        "shape" to shape(k.shape),
        "bounds" to box(k.bounds),
        "hit" to shape(k.hit),
        "anchor" to point(k.anchor),
        "up" to point(k.up),
        "label" to k.label,
        "alt" to k.alt,
        "a11y" to k.a11y,
        "actions" to map(k.actions),
        "popup" to k.popup?.let { p ->
            obj(
                "from" to p.from,
                "preset" to p.preset,
                "items" to p.items.map { (text, send) -> obj("text" to text, "send" to send) }.toTypedArray(),
                "options" to map(p.options),
            )
        },
        "menu" to k.menu.map { obj("label" to it.label, "icon" to it.icon, "action" to it.action) }.toTypedArray(),
        "sound" to k.sound,
        "shortcut" to k.shortcut,
        "letters" to k.letters?.rows?.toTypedArray(),
    )
}
