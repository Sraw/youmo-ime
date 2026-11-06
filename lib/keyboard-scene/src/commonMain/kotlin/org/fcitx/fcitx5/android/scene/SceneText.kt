/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A [Scene] as text, for snapshots (060-render-v2 §9): one line an op, keys in layout order, numbers
 * in px to a hundredth, written by this code and not the platform's (a Double prints differently on
 * the JVM and in JS; these lines are the same on both, which the snapshot test holds them to).
 */
object SceneText {

    fun of(scene: Scene): String = buildString {
        appendLine("scene ${n(scene.width)}x${n(scene.height)}")
        if (scene.base.isNotEmpty()) appendLine("base")
        scene.base.forEach { appendLine("  ${op(it)}") }
        for (k in scene.keys) {
            val copy = if (k.copy == 0) "" else "#${k.copy}"
            appendLine("key ${k.id}$copy kind=${k.kind}${k.role?.let { " role=$it" }.orEmpty()} a11y=${q(k.a11y)}")
            appendLine("  bounds ${box(k.bounds)}")
            appendLine("  shape ${shape(k.shape)}")
            appendLine("  hit ${shape(k.hit)}")
            appendLine("  anchor ${n(k.anchor.x)},${n(k.anchor.y)} up ${n(k.up.x)},${n(k.up.y)}")
            if (k.actions.isNotEmpty()) appendLine("  actions ${k.actions.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${q(it.value)}" }}")
            for ((state, ops) in k.faces) {
                appendLine("  ${state.name.lowercase()}")
                ops.forEach { appendLine("    ${op(it)}") }
            }
        }
        for (s in scene.slots) appendLine("slot ${s.kind}${s.id?.let { " $it" }.orEmpty()} ${box(s.bounds)}")
    }

    fun op(o: Op): String = when (o) {
        is Op.Fill -> "fill ${shape(o.shape)} ${o.color}"
        is Op.Gradient -> "gradient ${shape(o.shape)} ${n(o.angle)} ${o.stops.joinToString(" ") { "${it.first}@${n(it.second)}" }}"
        is Op.NineSlice -> "nine-slice ${shape(o.shape)} ${q(o.file)} ${o.insets.joinToString(",")} ${o.pxPerDp}"
        is Op.Picture -> "picture ${shape(o.shape)} blur=${o.blurDp?.let(::n) ?: "-"} brightness=${o.brightness?.let(::n) ?: "-"}"
        is Op.Shadow -> "shadow ${shape(o.shape)} blur=${n(o.blur)} ${o.color}"
        is Op.Stroke -> "stroke ${shape(o.shape)} ${n(o.width)} ${o.color}"
        is Op.Ripple -> "ripple ${shape(o.shape)} ${o.color}"
        is Op.Text -> "text ${q(o.text)} ${n(o.x)},${n(o.baseline)} ${o.font} ${o.weight} ${n(o.size)} ${o.color}" +
            (if (o.scaleX != 1.0) " scale-x=${n(o.scaleX)}" else "") + (if (o.rotation != 0.0) " rotate=${n(o.rotation)}" else "")
        is Op.Icon -> "icon ${o.name} ${box(o.box)} ${o.tint}" + (if (o.rotation != 0.0) " rotate=${n(o.rotation)}" else "")
    }

    fun shape(s: Shape): String = when (s) {
        is Shape.Rect -> "rect(${box(s.box)}${if (s.radii.all { it == 0.0 }) "" else " r=" + s.radii.joinToString(",", transform = ::n)})"
        is Shape.Path -> "path(" + s.ops.joinToString(" ") {
            when (it) {
                is Shape.Path.Op.MoveTo -> "M${n(it.x)},${n(it.y)}"
                is Shape.Path.Op.LineTo -> "L${n(it.x)},${n(it.y)}"
                Shape.Path.Op.Close -> "Z"
            }
        } + ")"
    }

    fun box(b: Box): String = "${n(b.left)},${n(b.top)},${n(b.right)},${n(b.bottom)}"

    /** [v] to a hundredth, no trailing zeros: 12, 12.5, -0.25. */
    fun n(v: Double): String {
        val h = (v * HUNDRED).roundToLong()
        val sign = if (h < 0) "-" else ""
        val a = abs(h)
        val whole = a / HUNDRED.toLong()
        val frac = (a % HUNDRED.toLong()).toInt()
        return when {
            frac == 0 -> "$sign$whole"
            frac % TEN == 0 -> "$sign$whole.${frac / TEN}"
            else -> "$sign$whole.${if (frac < TEN) "0" else ""}$frac"
        }
    }

    private fun q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private const val HUNDRED = 100.0
    private const val TEN = 10
}
