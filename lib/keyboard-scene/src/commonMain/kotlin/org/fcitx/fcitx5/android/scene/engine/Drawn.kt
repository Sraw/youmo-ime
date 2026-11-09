/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.rules.KeyMarkYield

/** Which part of a key a piece is (the theme's part names), so each state colours it as its part. */
/** [Hint]: what a function key's swipes do (its marks, drawn as [Shortcut]s); asked only whether it is hidden. */
enum class Part(val xml: String) { Label("label"), Icon("icon"), Badge("badge"), Mark("mark"), Shortcut("shortcut"), Hint("hint") }

/** Text placed: from ([x], [baseline]), [size] px, squeezed to [scaleX], its characters [spacing] em apart (a theme's letter-spacing). */
data class PlacedText(
    val text: String, val x: Double, val baseline: Double, val size: Double, val scaleX: Double, val font: String, val weight: Int, val part: Part,
    val spacing: Double = 0.0,
)

data class PlacedIcon(val name: String, val box: Box, val part: Part)

/**
 * What a key draws of a glyph or a mark, and its [ink]: what [KeyMarkYield] moves out of each other's
 * way, as a whole ([mapTo]: an icon and its label together, as theme 3.0's ImageTextKeyView did).
 */
data class Drawn(val texts: List<PlacedText>, val icons: List<PlacedIcon>, val ink: Box) {

    /**
     * Moved and scaled so its ink is [target]'s (KeyView: scaled from its corner, then moved); by [scale]
     * where it is known (a class's, the same to the last bit for every key of it), else by the inks' ratio.
     */
    fun mapTo(target: Box, scale: Double? = null): Drawn {
        if (target == ink) return this
        val s = scale ?: when {
            ink.width > 0 -> target.width / ink.width
            ink.height > 0 -> target.height / ink.height
            else -> 1.0
        }
        fun x(v: Double) = target.left + (v - ink.left) * s
        fun y(v: Double) = target.top + (v - ink.top) * s
        return Drawn(
            texts.map { it.copy(x = x(it.x), baseline = y(it.baseline), size = it.size * s) },
            icons.map { it.copy(box = Box(x(it.box.left), y(it.box.top), x(it.box.right), y(it.box.bottom))) },
            target,
        )
    }

    companion object {
        fun of(text: PlacedText, ink: Box) = Drawn(listOf(text), emptyList(), ink)

        fun of(icon: PlacedIcon) = Drawn(emptyList(), listOf(icon), icon.box)
    }
}

/**
 * How much more its ink is at a scale than its ink scaled ([KeyMarkYield.Grows]): each text measured again by
 * [measurer] at the size it is drawn, its pen where [mapTo] puts it; each scale measured once.
 */
fun Drawn.grows(measurer: TextMeasurer): KeyMarkYield.Grows? {
    if (texts.isEmpty()) return null
    // the ink at [s], from the ink's corner as [mapTo] has it at [s]
    fun at(s: Double): Box = texts.map { t ->
        val m = measurer.measure(t.text, t.font, t.weight, t.size * s, t.spacing).ink
        val x = (t.x - ink.left) * s
        val y = (t.baseline - ink.top) * s
        Box(x + m.left * t.scaleX, y + m.top, x + m.right * t.scaleX, y + m.bottom)
    }.plus(icons.map { i -> Box((i.box.left - ink.left) * s, (i.box.top - ink.top) * s, (i.box.right - ink.left) * s, (i.box.bottom - ink.top) * s) })
        .reduce(Box::union)
    val whole = at(1.0)
    val seen = HashMap<Double, KeyMarkYield.Ink>()
    return KeyMarkYield.Grows { s ->
        seen.getOrPut(s) {
            val b = at(s)
            KeyMarkYield.Ink(whole.left * s - b.left, whole.top * s - b.top, b.right - whole.right * s, b.bottom - whole.bottom * s)
        }
    }
}

/** Moved and scaled by [scale] so its ink as drawn ([grows]) is [target]. */
fun Drawn.drawnAt(target: Box, scale: Double, grows: KeyMarkYield.Grows?): Drawn {
    val d = grows?.at(scale) ?: return mapTo(target, scale)
    return mapTo(Box(target.left + d.left, target.top + d.top, target.right - d.right, target.bottom - d.bottom), scale).copy(ink = target)
}

fun Box.toInk() = KeyMarkYield.Ink(left, top, right, bottom)

fun KeyMarkYield.Ink.toBox() = Box(left, top, right, bottom)
