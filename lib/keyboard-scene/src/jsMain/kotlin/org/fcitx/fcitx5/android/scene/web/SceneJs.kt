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
import org.fcitx.fcitx5.android.scene.SlotNode
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.keys.ScrollDefaults
import org.fcitx.fcitx5.android.scene.keys.ScrollItem
import org.fcitx.fcitx5.android.scene.keys.ScrollLists
import org.fcitx.fcitx5.android.scene.layout.LayoutReader

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
     * slots, in drawing order (a column's with the category [env] shows and its items); warnings as problems.
     */
    fun scene(scene: Scene, env: Env? = null): dynamic = obj(
        "width" to scene.width,
        "height" to scene.height,
        "board" to box(scene.board),
        "preview" to scene.preview,
        "popups" to scene.popups,
        "warnings" to problems(scene.warnings),
        "keys" to scene.keys.map(::key).toTypedArray(),
        "slots" to scene.slots.map {
            val shown = env?.let { e -> shown(it, e) }
            obj(
                "kind" to it.kind, "id" to it.id, "bounds" to box(it.bounds), "list" to it.list, "direction" to it.direction.xml, "show" to it.show,
                "shown" to shown,
                "items" to shown?.let { id -> items(id, env) },
            )
        }.toTypedArray(),
    )

    // the category the column shows in [e]'s field and input method, as the app picks it; null where it shows syllables
    private fun shown(s: SlotNode, e: Env): String? {
        if (s.kind != LayoutReader.SCROLL && s.kind != ScrollLists.NINE_SIDE) return null
        val i = e.input
        return (ScrollLists.shown(s.kind, s.list, i.field, i.fieldPad, i.pad, i.ime, i.composing) as? ScrollLists.Shown.Category)?.id
    }

    private fun items(id: String, e: Env): Array<dynamic> = (e.scrollItems?.get(id) ?: ScrollDefaults.of(id).orEmpty()).map { item ->
        obj("label" to ScrollLists.label(id, item, e.input.ime, e.texts.punctuation), "command" to (item as? ScrollItem.Command)?.key)
    }.toTypedArray()

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

    /** The scroll columns' `lists`, the `families` they go on, the `most` a board has and `maxShow`. */
    fun scrollFormat(): dynamic = obj(
        "lists" to ScrollDefaults.IDS.toTypedArray(),
        "families" to LayoutReader.SCROLL_FAMILIES.toTypedArray(),
        "most" to LayoutReader.MAX_SCROLLS,
        "maxShow" to LayoutReader.MAX_SHOW,
    )

    /** The scroll lists' categories, in the settings' order, each its built-in items as an env's `scrollItems` takes them. */
    fun scrollDefaults(): dynamic = obj(
        *ScrollDefaults.IDS.map { id ->
            id to ScrollDefaults.of(id).orEmpty().map { item ->
                when (item) {
                    is ScrollItem.Text -> obj("text" to item.text, "asIs" to item.asIs)
                    is ScrollItem.Command -> obj("command" to item.key)
                }
            }.toTypedArray()
        }.toTypedArray(),
    )
}
