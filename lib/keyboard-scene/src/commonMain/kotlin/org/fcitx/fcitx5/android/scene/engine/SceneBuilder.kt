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
import org.fcitx.fcitx5.android.scene.Problems
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.SlotNode
import org.fcitx.fcitx5.android.scene.getOr
import org.fcitx.fcitx5.android.scene.layout.Face
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.OnSplit
import org.fcitx.fcitx5.android.scene.rules.KeyMarkYield
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard
import org.fcitx.fcitx5.android.scene.theme.Cascade
import org.fcitx.fcitx5.android.scene.theme.KeyLook
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.TextLook
import org.fcitx.fcitx5.android.scene.theme.Theme
import kotlin.math.abs

/**
 * A layout and a theme in an environment, made a [Scene] (060-render-v2 §6): the board placed in the
 * keyboard (one-handed or split at a side: rules D §2), expanded ([Expand]), placed ([Place]), each
 * key's look cascaded from the theme for its states and pressed, its face's layers placed ([Faces]),
 * its text and marks placed ([Content]) and kept apart ([KeyMarkYield]: the letters together, the
 * rest each on its own), all drawn as ops ([Paint]); a slot's or a blank place's face under it. The
 * same values give the same scene, on the phone, in a browser, in a test.
 */
class SceneBuilder(
    private val theme: Theme,
    private val env: Env,
    private val measurer: TextMeasurer,
    icons: IconSizes = IconSizes.MATERIAL,
) {
    private val cascade = Cascade(theme, env.scenes)

    // `face="keep"`: the face theme 3.0 drew whatever 按键样式 said (the lookup key on, 确定)
    private val unflat = Cascade(theme, env.scenes - "flat")
    private val paint = Paint(theme, env.density)
    private val content = Content(env, measurer, icons)

    /**
     * [layout] with the files its includes name, [fragments] by id; its problems if it cannot be laid
     * out here. Never throws: a fault of the engine's own is a problem too, so a bad file a user
     * imported cannot take the keyboard down.
     */
    // anything at all: the caller is the keyboard drawing itself, which has nowhere to send a throw
    @Suppress("TooGenericExceptionCaught")
    fun build(layout: Layout, fragments: Map<String, Fragment> = emptyMap()): Parsed<Scene> = try {
        scene(layout, fragments)
    } catch (e: Throwable) {
        Parsed.Failed(0, "the scene could not be built: ${e::class.simpleName}")
    }

    private fun scene(layout: Layout, fragments: Map<String, Fragment>): Parsed<Scene> {
        val warnings = Problems()
        // rules C §7: a number pad the app named no pad for (a field it does not know) is the full one
        val padless = layout.family == "number" && env.input.pad == null
        if (padless) warnings.add(0, "a number pad with no pad= given: the full pad")
        val expanding = if (padless) env.copy(input = env.input.copy(pad = FULL_PAD)) else env
        val placement = placement(layout)
        val splitSide = placement?.side?.takeIf { env.split }?.name?.lowercase()
        val rows = Expand.rows(layout, fragments, expanding, splitSide).getOr { return Parsed.Failed(it) }
        val board = board(placement)
        val placed = Place.rows(rows, layout.columns, layout.onSplit, env, board, warnings).getOr { return Parsed.Failed(it) }
        val keys = placed.mapNotNull { p -> (p.item as? ExpandedKey)?.let { prepare(layout.id, p, it) } }
        val settled = settle(keys)
        val nodes = keys.mapIndexed { i, k -> node(k, settled[i]) }
        val slots = placed.mapNotNull { p ->
            val s = (p.item as? ExpandedSlot)?.slot ?: return@mapNotNull null
            SlotNode(s.kind, s.id, p.cell, surface(layout.id, s.id, s.look, s.classes, s.face, p.cell, slot = true))
        }
        val blanks = placed.flatMap { p ->
            val g = (p.item as? ExpandedGap)?.gap ?: return@flatMap emptyList()
            surface(layout.id, g.id, g.look, g.classes, Face.Default, p.cell, slot = false)
        }
        val whole = Box(0.0, 0.0, env.width, env.height)
        val side = sideRoom(board, whole)?.let { SlotNode(SIDE_BUTTONS, null, it) }
        return Parsed.Ok(Scene(env.width, env.height, base(whole) + blanks, nodes, slots + listOfNotNull(side), board, layout.preview, layout.popups, warnings.all))
    }

    // a side of the keyboard one-handed, or split for a board but the letters; null: the whole of it
    private fun placement(layout: Layout): SplitKeyboard.Placement? = if (env.floating != null) {
        null
    } else {
        SplitKeyboard.placement(env.oneHanded, env.dock, env.split, layout.onSplit != OnSplit.Side, env.widthDp)
    }

    // where the keys go
    private fun board(placement: SplitKeyboard.Placement?): Box {
        val whole = Box(0.0, 0.0, env.width, env.height)
        if (placement == null) return whole
        val w = placement.widthDp * env.density
        return if (placement.side == SplitKeyboard.Dock.LEFT) Box(0.0, 0.0, w, env.height) else Box(env.width - w, 0.0, env.width, env.height)
    }

    // the keyboard less the board, where the app's buttons go (moving it across, back to the whole width)
    private fun sideRoom(board: Box, whole: Box): Box? = when {
        board == whole -> null
        board.left > 0 -> Box(0.0, 0.0, board.left, whole.bottom)
        else -> Box(board.right, 0.0, whole.right, whole.bottom)
    }

    // the keyboard's own fill under the keys
    private fun base(box: Box): List<Op> {
        val look = KeyLook(cascade.of(Selector.Target("keyboard")), theme)
        return listOfNotNull(paint.fill(Shape.Rect(box, Faces.radii(look.radius, box, env.density)), look.fill))
    }

    // a key's face where a slot or a blank place is given a look: never pressed, `keep` block on flat keys too;
    // a slot is not one of its board's every key (`handwriting/*`), but by its own name and look, as theme 3.0 drew the pad
    private fun surface(board: String, id: String?, look: String?, classes: List<String>, face: Face, cell: Box, slot: Boolean): List<Op> {
        if (look == null || face == Face.None) return emptyList()
        val c = if (face == Face.Keep) unflat else cascade
        val l = KeyLook(c.of(Selector.Target("key", board, id, null, setOf(look) + classes, slot = slot)), theme)
        return paint.face(Faces.of(cell, l, face, true, env.flat && face != Face.Keep, env.density), l, false)
    }

    private class Prepared(
        val placed: Placed,
        val key: ExpandedKey,
        val states: Set<KeyState>,
        val looks: Map<Set<KeyState>, KeyLook>,
        val parts: Map<Set<KeyState>, (Part) -> TextLook>,
        val geometry: Map<Set<KeyState>, FaceGeometry>,
        val content: KeyContent,
    )

    private class Settled(val glyph: Drawn?, val marks: List<Drawn?>)

    private fun prepare(board: String, placed: Placed, key: ExpandedKey): Prepared {
        val face = key.attrs.face ?: Face.Default
        val c = if (face == Face.Keep) unflat else cascade
        val flat = env.flat && face != Face.Keep
        val margin = key.attrs.margin ?: true
        val states = statesOf(key)
        val sets = listOf(states, states + KeyState.Pressed)
        val looks = HashMap<Set<KeyState>, KeyLook>()
        val parts = HashMap<Set<KeyState>, (Part) -> TextLook>()
        for (s in sets) {
            // the key's own once a state, each part on it (M4)
            val own = c.of(target(board, key, s, null))
            looks[s] = KeyLook(own, theme)
            val byPart = Part.entries.associateWith { TextLook(c.of(target(board, key, s, it), own), theme) }
            parts[s] = byPart::getValue
        }
        val geometry = looks.mapValues { (_, look) -> Faces.of(placed.cell, look, face, margin, flat, env.density) }
        val now = parts.getValue(states)
        val looksNow = Looks(now(Part.Label), now(Part.Icon), now(Part.Badge), now(Part.Mark), now(Part.Shortcut))
        val drawn = content.of(key, placed.cell, geometry.getValue(states).content, looksNow)
        return Prepared(placed, key, states, looks, parts, geometry, drawn)
    }

    // the states a key is in but pressed: the environment's for its id or role, Shift's from the shift
    private fun statesOf(key: ExpandedKey): Set<KeyState> = buildSet {
        env.states[key.key.id]?.let(::addAll)
        key.attrs.role?.let { env.states[it] }?.let(::addAll)
        if (key.attrs.role == "shift") {
            if (env.shift == Shift.Once) add(KeyState.Shifted)
            if (env.shift == Shift.Lock) add(KeyState.Locked)
        }
    }

    private fun target(board: String, key: ExpandedKey, states: Set<KeyState>, part: Part?): Selector.Target = Selector.Target(
        "key", board, key.key.id, key.attrs.role, setOf(key.kind) + key.attrs.classes.orEmpty(), states.map { it.xml }.toSet(), part?.xml,
    )

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
        val normal = k.geometry.getValue(k.states)
        val faces = k.looks.keys.associateWith { states ->
            val looks = k.parts.getValue(states)
            paint.face(k.geometry.getValue(states), k.looks.getValue(states), KeyState.Pressed in states) +
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
            states = k.states,
            faces = faces,
            a11y = k.key.a11y,
            actions = k.key.attrs.actions,
            label = k.key.label,
            alt = k.key.alt,
            altRaw = k.key.altRaw,
            popup = k.key.key.popup,
            menu = k.key.key.menu,
            sound = k.key.attrs.sound ?: "standard",
            shortcut = k.key.attrs.shortcut,
        )
    }

    companion object {
        /** KeyMarkYield's: a glyph and a mark this far apart at least. */
        const val GAP_DP = 1.0

        /** The slot beside a board one-handed or split at a side: the app's buttons there (rules D §2). */
        const val SIDE_BUTTONS = "side-buttons"
        private const val FULL_PAD = "full"
        private val UP = Point(0.0, -1.0)
    }
}
