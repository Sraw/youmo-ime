/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Expand
import org.fcitx.fcitx5.android.scene.engine.ExpandedGap
import org.fcitx.fcitx5.android.scene.engine.Place
import org.fcitx.fcitx5.android.scene.engine.SplitRoom
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What every scene the snapshots hold must be, checked rather than looked at (review M5): on each
 * key, the label and the marks kept apart and inside its face; the keys' touch areas apart, and
 * together the whole board but its gaps and a split's middle. A snapshot shows where things are;
 * this says they are right.
 */
class SceneChecksTest {

    @Test
    fun theTextsOfAKeyKeepApart() = expectNone { shot, k ->
        val boxes = contents(shot, k)
        boxes.indices.flatMap { i -> (i + 1 until boxes.size).map { i to it } }
            .filter { (i, j) -> overlap(boxes[i].box, boxes[j].box) > AREA }
            .map { (i, j) -> "${shot.name} ${k.id}: ${boxes[i].what} and ${boxes[j].what} meet" }
    }

    // TalkBack has something to read on every key
    @Test
    fun everyKeyHasSomethingToSay() = expectNone { shot, k ->
        listOfNotNull("${shot.name} ${k.id}: no a11y".takeIf { k.a11y.isBlank() })
    }

    // within the face, or for the known exceptions ([leeway]) within the key's cell
    @Test
    fun theTextsOfAKeyKeepToItsFace() = expectNone { shot, k ->
        val all = contents(shot, k)
        all.mapNotNull { c ->
            val within = if (leeway(shot, k, c, all) != null) k.bounds else k.shape.bounds
            "${shot.name} ${k.id}: ${c.what} ${SceneText.box(c.box)} out of ${SceneText.box(within)}".takeUnless { inside(c.box, within) }
        }
    }

    // the exceptions are what theme 3.0 does too, and each still happens somewhere (or it is stale)
    @Test
    fun theFaceExceptionsAreStillMet() {
        val met = Shots.all.flatMap { shot ->
            shot.scene.keys.flatMap { k ->
                val all = contents(shot, k)
                all.filter { !inside(it.box, k.shape.bounds) }.mapNotNull { leeway(shot, k, it, all) }
            }
        }.toSet()
        assertEquals(setOf(FLAT_SPACE, ICON_ON_COMPACT), met)
    }

    /**
     * Why [c] may leave [k]'s face, kept to its cell instead, or null:
     * - [FLAT_SPACE]: flat, the space's face is a pill inset in the key (theme 3.0's InsetDrawable),
     *   its marks placed in the whole key as KeyView's appearance has them, so the edit menu's mark
     *   sits across the pill's corner; its marks only, never its label;
     * - [ICON_ON_COMPACT]: a key's icon, on a key with no text ([all]; marks of icons beside it), at its own 24 dp
     *   (ImageKeyView's wrap_content), taller than a compact key's face (⌫, 🌐, ↵ on the boards whose
     *   rows are four to the height), or wider than a narrow key's (the floating keyboard's 🌐, its face
     *   62.25 px under a 63 px icon once its edges are on whole pixels: the view's was the same).
     */
    private fun leeway(shot: Shot, k: KeyNode, c: Drawn, all: List<Drawn>): String? = when {
        shot.env.flat && k.kind == "space" && c.part == Part.MARK -> FLAT_SPACE
        c.part == Part.ICON && all.none { it.text } &&
            kotlin.math.abs(c.box.height - ICON_DP * shot.env.density) < EDGE &&
            (c.box.height > k.shape.bounds.height + EDGE || c.box.width > k.shape.bounds.width + EDGE) -> ICON_ON_COMPACT
        else -> null
    }

    private fun inside(b: Box, within: Box) = b.intersect(within.inset(-EDGE, -EDGE, -EDGE, -EDGE)) == b

    @Test
    fun theKeysTouchAreasKeepApart() {
        val met = Shots.all.flatMap { shot ->
            val keys = shot.scene.keys
            keys.indices.flatMap { i -> (i + 1 until keys.size).map { keys[i] to keys[it] } }
                .filter { (a, b) -> overlap(a.hit.bounds, b.hit.bounds) > AREA }
                .map { (a, b) -> "${shot.name}: ${a.id}#${a.copy} and ${b.id}#${b.copy} meet" }
        }
        assertTrue(met.isEmpty(), met.joinToString("\n"))
    }

    // the keys' hits, the slots, the gaps and a split's middle: the board, all of it, once
    @Test
    fun theTouchAreasCoverTheBoard() {
        val wrong = Shots.all.mapNotNull { shot ->
            val scene = shot.scene
            val board = scene.board
            val blank = blanks(shot)
            val parts = scene.keys.map { it.hit.bounds } + scene.slots.map { it.bounds }.filter { overlap(it, board) > 0 } + blank
            val sum = parts.sumOf { overlap(it, board) }
            val whole = board.width * board.height
            "${shot.name}: ${SceneText.n(sum)} of ${SceneText.n(whole)} px²".takeIf { kotlin.math.abs(sum - whole) > whole * 1e-9 + AREA }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    // where the board takes no touch: its gaps' cells and a split row's middle (from Place, which the scene does not keep)
    private fun blanks(shot: Shot): List<Box> {
        val layout = Forms.layout(shot.board)
        val env = if (layout.family == "number" && shot.env.input.pad == null) shot.env.copy(input = shot.env.input.copy(pad = "full")) else shot.env
        val rows = Expand.rows(layout, Forms.fragments, env, splitSide(shot)).ok()
        return Place.rows(rows, layout.columns, layout.onSplit, env, shot.scene.board, Problems()).ok()
            .filter { it.item is ExpandedGap || it.item === SplitRoom }.map { it.cell }
    }

    private fun splitSide(shot: Shot): String? =
        if (shot.env.split && shot.scene.board != Box(0.0, 0.0, shot.env.width, shot.env.height)) {
            if (shot.scene.board.left > 0) SplitKeyboard.Dock.RIGHT.name.lowercase() else SplitKeyboard.Dock.LEFT.name.lowercase()
        } else {
            null
        }

    private fun expectNone(check: (Shot, KeyNode) -> List<String>) {
        val found = Shots.all.flatMap { shot -> shot.scene.keys.flatMap { check(shot, it) } }
        assertTrue(found.isEmpty(), found.joinToString("\n"))
    }

    private enum class Part { LABEL, MARK, ICON }

    private class Drawn(val what: String, val box: Box, val part: Part, val text: Boolean = false)

    // what a key's drawing puts on it, each text's ink as the measurer has it and each icon's box: the
    // label (its part's, each run of it), a mark (any other text; an icon at a shortcut's 11 dp, 8 compact) or an icon (glyph, badge)
    private fun contents(shot: Shot, k: KeyNode): List<Drawn> = k.ops(pressed = false).mapNotNull { op ->
        when (op) {
            is Op.Text -> {
                val ink = FakeMeasurer.measure(op.text, op.font, op.weight, op.size, op.letterSpacing).ink
                val box = Box(op.x + ink.left * op.scaleX, op.baseline + ink.top, op.x + ink.right * op.scaleX, op.baseline + ink.bottom)
                Drawn("\"${op.text}\"", box, if (op.part == "label") Part.LABEL else Part.MARK, text = true)
            }
            is Op.Icon -> {
                val dp = if (shot.env.compact) SHORTCUT_ICON_COMPACT_DP else SHORTCUT_ICON_DP
                // its dp in whole pixels, as its ImageView was
                val mark = kotlin.math.abs(op.box.height - dp * shot.env.density) <= HALF_PX + EDGE
                Drawn("icon ${op.name}", op.box, if (mark) Part.MARK else Part.ICON)
            }
            else -> null
        }
    }

    private fun overlap(a: Box, b: Box): Double = a.intersect(b).let { if (it.hasSize) it.width * it.height else 0.0 }

    private companion object {
        // a hundredth of a pixel each way, what the snapshots round to
        const val AREA = 1e-4
        const val EDGE = 0.01
        const val HALF_PX = 0.5
        const val ICON_DP = 24.0
        const val SHORTCUT_ICON_DP = 11.0
        const val SHORTCUT_ICON_COMPACT_DP = 8.0
        const val FLAT_SPACE = "flat space: marks by the key, not its inset pill"
        const val ICON_ON_COMPACT = "an icon alone at its own 24 dp on a key smaller than it"
    }
}
