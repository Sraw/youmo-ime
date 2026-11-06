/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Point
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.SlotNode
import org.fcitx.fcitx5.android.scene.getOr
import org.fcitx.fcitx5.android.scene.layout.Face
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.rules.KeyMarkYield
import org.fcitx.fcitx5.android.scene.theme.Cascade
import org.fcitx.fcitx5.android.scene.theme.KeyLook
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.TextLook
import org.fcitx.fcitx5.android.scene.theme.Theme
import kotlin.math.abs

/**
 * A layout and a theme in an environment, made a [Scene] (060-render-v2 §6): expanded ([Expand]),
 * placed ([Place]), each key's look cascaded from the theme per state, its face's layers placed
 * ([Faces]), its text and marks placed ([Content]) and kept apart ([KeyMarkYield]: the letters
 * together, the rest each on its own), all drawn as ops ([Paint]). The same values give the same
 * scene, on the phone, in a browser, in a test.
 */
class SceneBuilder(private val theme: Theme, private val env: Env, private val measurer: TextMeasurer) {
    private val cascade = Cascade(theme, env.scenes)

    // `face="keep"`: the face theme 3.0 drew whatever 按键样式 said (the lookup key on, 确定)
    private val unflat = Cascade(theme, env.scenes - "flat")
    private val paint = Paint(theme, env.density)
    private val content = Content(env, measurer)

    /** [layout] with the files its includes name, [fragments] by id; its problems if it cannot be laid out here. */
    fun build(layout: Layout, fragments: Map<String, Fragment> = emptyMap()): Parsed<Scene> {
        val rows = Expand.rows(layout, fragments, env).getOr { return Parsed.Failed(it) }
        val placed = Place.rows(rows, layout.columns, layout.onSplit, env).getOr { return Parsed.Failed(it) }
        val keys = placed.mapNotNull { p -> (p.item as? ExpandedKey)?.let { prepare(layout.id, p, it) } }
        val settled = settle(keys)
        val nodes = keys.mapIndexed { i, k -> node(k, settled[i]) }
        val slots = placed.mapNotNull { p -> (p.item as? ExpandedSlot)?.let { SlotNode(it.slot.kind, it.slot.id, p.cell) } }
        return Parsed.Ok(Scene(env.width, env.height, base(), nodes, slots))
    }

    // the keyboard's own fill under the keys
    private fun base(): List<Op> {
        val look = KeyLook(cascade.of(Selector.Target("keyboard")), theme)
        val box = Box(0.0, 0.0, env.width, env.height)
        return listOfNotNull(paint.fill(Shape.Rect(box, Faces.radii(look.radius, box, env.density)), look.fill))
    }

    private class Prepared(
        val placed: Placed,
        val key: ExpandedKey,
        val looks: Map<KeyState, KeyLook>,
        val parts: Map<KeyState, (Part) -> TextLook>,
        val geometry: Map<KeyState, FaceGeometry>,
        val content: KeyContent,
    )

    private class Settled(val glyph: Drawn?, val marks: List<Drawn?>)

    private fun prepare(board: String, placed: Placed, key: ExpandedKey): Prepared {
        val face = key.attrs.face ?: Face.Default
        val c = if (face == Face.Keep) unflat else cascade
        val flat = env.flat && face != Face.Keep
        val margin = key.attrs.margin ?: true
        val looks = KeyState.entries.associateWith { KeyLook(c.of(target(board, key, it, null)), theme) }
        val parts = KeyState.entries.associateWith { state ->
            val byPart = Part.entries.associateWith { TextLook(c.of(target(board, key, state, it)), theme) }
            byPart::getValue
        }
        val geometry = looks.mapValues { (_, look) -> Faces.of(placed.cell, look, face, margin, flat, env.density) }
        val normal = parts.getValue(KeyState.Normal)
        val looksNow = Looks(normal(Part.Label), normal(Part.Icon), normal(Part.Badge), normal(Part.Mark), normal(Part.Shortcut))
        val drawn = content.of(key, placed.cell, geometry.getValue(KeyState.Normal).content, looksNow)
        return Prepared(placed, key, looks, parts, geometry, drawn)
    }

    private fun target(board: String, key: ExpandedKey, state: KeyState, part: Part?): Selector.Target {
        val states = buildSet {
            if (state == KeyState.Pressed) add("pressed")
            if (key.attrs.role == "shift") {
                if (env.shift == Shift.Once) add("shifted")
                if (env.shift == Shift.Lock) add("locked")
            }
        }
        return Selector.Target("key", board, key.key.id, key.attrs.role, setOf(key.kind) + key.attrs.classes.orEmpty(), states, part?.xml)
    }

    // rules B §7: the letters' marks kept apart together (one size for all the letters), the other keys' each
    private fun settle(keys: List<Prepared>): List<Settled> {
        val gap = GAP_DP * env.density
        val out = arrayOfNulls<Settled>(keys.size)
        val letters = keys.indices.filter { keys[it].content.letter && keys[it].content.glyph != null }
        val group = KeyMarkYield.letters(
            letters.map { i ->
                val c = keys[i].content
                KeyMarkYield.LetterKey(c.glyph!!.ink.toInk(), c.baseline, c.marks.map(::mark), c.room.toInk())
            },
            gap,
        )
        letters.forEachIndexed { n, i -> out[i] = settled(keys[i].content, group.keys[n]) }
        keys.forEachIndexed { i, k ->
            if (out[i] != null) return@forEachIndexed
            val c = k.content
            out[i] = if (c.glyph == null || c.marks.isEmpty()) {
                Settled(c.glyph, c.marks.map { it.drawn })
            } else {
                settled(c, KeyMarkYield.key(c.glyph.ink.toInk(), c.marks.map(::mark), c.room.toInk(), gap))
            }
        }
        return out.map { it!! }
    }

    private fun mark(m: MarkDrawn) =
        KeyMarkYield.Mark(m.drawn.ink.toInk(), m.slot, m.minScale, m.drop, m.elsewhere.map { (slot, d) -> slot to d.ink.toInk() })

    // a settled glyph and marks drawn where their inks went: a mark from whichever of its places it settled nearest
    private fun settled(c: KeyContent, s: KeyMarkYield.Settled): Settled {
        val glyph = c.glyph?.mapTo(s.glyph.toBox())
        val marks = c.marks.mapIndexed { i, m ->
            val ink = s.marks[i]?.toBox() ?: return@mapIndexed null
            val from = (listOf(m.drawn) + m.elsewhere.map { it.second }).minBy { distance(it.ink, ink) }
            from.mapTo(ink)
        }
        return Settled(glyph, marks)
    }

    private fun distance(a: Box, b: Box) = abs(a.centerX - b.centerX) + abs(a.centerY - b.centerY)

    private fun node(k: Prepared, s: Settled): KeyNode {
        val cell = k.placed.cell
        val normal = k.geometry.getValue(KeyState.Normal)
        val faces = KeyState.entries.associateWith { state ->
            val looks = k.parts.getValue(state)
            paint.face(k.geometry.getValue(state), k.looks.getValue(state), state == KeyState.Pressed) +
                listOfNotNull(s.glyph).flatMap { paint.content(it, looks) } +
                s.marks.filterNotNull().flatMap { paint.content(it, looks) }
        }
        return KeyNode(
            id = k.key.key.id,
            copy = k.placed.copy,
            kind = k.key.kind,
            role = k.key.attrs.role,
            shape = normal.face ?: normal.pressed,
            bounds = cell,
            hit = Shape.Rect(k.placed.hit ?: cell),
            anchor = Point(cell.centerX, cell.centerY),
            up = UP,
            faces = faces,
            a11y = k.key.a11y,
            actions = k.key.attrs.actions,
        )
    }

    companion object {
        /** KeyMarkYield's: a glyph and a mark this far apart at least. */
        const val GAP_DP = 1.0
        private val UP = Point(0.0, -1.0)
    }
}
