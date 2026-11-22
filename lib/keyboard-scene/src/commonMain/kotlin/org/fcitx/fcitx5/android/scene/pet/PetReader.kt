/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.pet

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Problems
import org.fcitx.fcitx5.android.scene.SceneText
import org.fcitx.fcitx5.android.scene.theme.RasterIcon
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.Values
import org.fcitx.fcitx5.android.scene.xml.Attributes
import org.fcitx.fcitx5.android.scene.xml.XmlElement

/** A theme's `<pet>` read and checked; anything wrong with it is a warning and no pet: a decoration is not worth refusing a theme for. */
object PetReader {

    fun read(els: List<XmlElement>, p: Problems): Pet? {
        val el = els.firstOrNull() ?: return null
        val own = Problems()
        if (els.size > 1) own.add(els[1].line, "<pet> given twice")
        val pet = pet(el, own)
        return when (val read = own.result { pet }) {
            is Parsed.Ok -> read.value.also { read.warnings.forEach { w -> p.warn(w.line, w.message) } }
            is Parsed.Failed -> null.also { read.problems.forEach { e -> p.warn(e.line, "<pet> left out: ${e.message}") } }
        }
    }

    private val WHOLE = Regex("[0-9]{1,6}")

    private fun pet(el: XmlElement, p: Problems): Pet {
        val a = Attributes(el, p)
        val place = a.oneOf("place", Pet.Place.entries.map { it.id })?.let { id -> Pet.Place.entries.first { it.id == id } } ?: Pet.Place.KEYBOARD
        val align = a.oneOf("align", Pet.Align.entries.map { it.id })?.let { id -> Pet.Align.entries.first { it.id == id } }
        val size = clamped(a, "size", PetLimits.MIN_SIZE_DP, PetLimits.MAX_SIZE_DP) ?: PetLimits.DEFAULT_SIZE_DP
        var x = clamped(a, "offset-x", -PetLimits.MAX_OFFSET_DP, PetLimits.MAX_OFFSET_DP) ?: 0.0
        var y = clamped(a, "offset-y", -PetLimits.MAX_OFFSET_DP, PetLimits.MAX_OFFSET_DP) ?: 0.0
        // the bar's end has no corner to count from, and its slot is the pet's width
        if (place == Pet.Place.BAR_END) {
            listOf("align", "offset-x", "offset-y").filter { a.string(it) != null }.forEach { a.warn("$it= does nothing at the bar's end") }
            x = 0.0
            y = 0.0
        }
        a.done()
        val states = LinkedHashMap<PetState.Name, PetState>()
        for (c in el.children) {
            val read = if (c.name == "state") state(c, p) else null.also { p.add(c.line, "<${c.name}> is not allowed in <pet>") }
            val (name, state) = read ?: continue
            if (name in states) p.add(c.line, "the state ${name.id} is given twice") else states[name] = state
        }
        if (PetState.Name.IDLE !in states) p.add(el.line, "<pet> needs a <state name=\"idle\">")
        val pet = Pet(place, align ?: Pet.Align.TOP_END, size, x, y, states)
        if (PetLimits.total(pet) > PetLimits.MAX_TOTAL_FRAMES) p.add(el.line, "over ${PetLimits.MAX_TOTAL_FRAMES} frames in all")
        return pet
    }

    private fun clamped(a: Attributes, name: String, min: Double, max: Double): Double? {
        val v = a.value(name, "a length in dp") { Values.dp(it, Double.MAX_VALUE, signed = true) } ?: return null
        if (v in min..max) return v
        val taken = v.coerceIn(min, max)
        a.warn("$name=\"${a.string(name)}\" is taken as ${SceneText.n(taken)}dp: ${SceneText.n(min)}–${SceneText.n(max)}dp")
        return taken
    }

    private fun state(el: XmlElement, p: Problems): Pair<PetState.Name, PetState>? {
        val a = Attributes(el, p)
        val id = a.required("name") ?: return null
        // a later version's: not read, so its attributes are no error either
        val name = PetState.Name.of(id) ?: return null.also { a.warn("the state $id is not one this version knows, ignored") }
        val sheet = a.matching("sheet", ThemeReader.FILE, "a file name in the pack, no folder") ?: a.required("sheet")
        if (sheet != null && !RasterIcon.named(sheet)) a.problem("sheet=\"$sheet\" is not a .png or .webp")
        val frames = a.value("frames", "1–${PetLimits.MAX_FRAMES}") { it.takeIf(WHOLE::matches)?.toInt()?.takeIf { n -> n in 1..PetLimits.MAX_FRAMES } } ?: 1
        val columns = a.value("columns", "1–$frames") { it.takeIf(WHOLE::matches)?.toInt()?.takeIf { n -> n in 1..frames } } ?: frames
        val fps = a.value("fps", "a whole number") { it.takeIf(WHOLE::matches)?.toInt() }?.let { f ->
            f.coerceIn(1, PetLimits.MAX_FPS).also { if (it != f) a.warn("fps=$f is taken as $it") }
        } ?: DEFAULT_FPS
        var loop = a.oneOf("loop", listOf("true", "false"))?.let { it == "true" } ?: PetLimits.resting(name)
        a.done()
        if (name == PetState.Name.IDLE && !loop) {
            a.warn("loop=\"false\" on idle is taken as true: idle is what it shows between keys")
            loop = true
        }
        return sheet?.let { name to PetState(it, frames, columns, fps, loop) }
    }

    private const val DEFAULT_FPS = 8
}
