/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A [Scene] as text, for snapshots (060-render-v2 §9): one line an op, slots then keys in layout
 * order, numbers in px to a hundredth, written by this code and not the platform's (a Double prints
 * differently on the JVM and in JS; these lines are the same on both, which the snapshot test holds
 * them to). A key's pressed drawing is written as what it changes of its unpressed one: `+i op` put
 * in at i, `-i op` taken out from i (a shortest such list; most keys only gain a layer). Before its
 * drawing, what the app works it by: `text` (label, alt, the alt as written where that differs), its
 * sound and shortcut, its `popup` and `menu` as the layout wrote them.
 */
object SceneText {

    fun of(scene: Scene): String = buildString {
        val whole = Box(0.0, 0.0, scene.width, scene.height)
        append("scene ${n(scene.width)}x${n(scene.height)}")
        if (scene.board != whole) append(" board ${box(scene.board)}")
        if (!scene.preview) append(" no-preview")
        if (!scene.popups) append(" no-popups")
        appendLine()
        scene.warnings.forEach { appendLine("warning line ${it.line}: ${it.message}") }
        if (scene.base.isNotEmpty()) appendLine("base")
        scene.base.forEach { appendLine("  ${op(it)}") }
        for (s in scene.slots) {
            appendLine("slot ${s.kind}${s.id?.let { " $it" }.orEmpty()} ${box(s.bounds)}")
            s.ink?.let { appendLine("  ink ${it.color} ${n(it.width)}") }
            s.ops.forEach { appendLine("  ${op(it)}") }
        }
        for (k in scene.keys) key(k)
    }

    private fun StringBuilder.key(k: KeyNode) {
        val copy = if (k.copy == 0) "" else "#${k.copy}"
        val states = if (k.states.isEmpty()) "" else " states=" + k.states.sorted().joinToString(",") { it.xml }
        appendLine("key ${k.id}$copy kind=${k.kind}${k.role?.let { " role=$it" }.orEmpty()}$states a11y=${q(k.a11y)}")
        appendLine("  bounds ${box(k.bounds)}")
        appendLine("  shape ${shape(k.shape)}")
        appendLine("  hit ${shape(k.hit)}")
        appendLine("  anchor ${n(k.anchor.x)},${n(k.anchor.y)} up ${n(k.up.x)},${n(k.up.y)}")
        if (k.actions.isNotEmpty()) appendLine("  actions ${k.actions.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${q(it.value)}" }}")
        interaction(k)
        k.motion?.let { m ->
            fun t(t: org.fcitx.fcitx5.android.scene.theme.Transition?) = t?.let { "${it.ms}ms,${it.easing}" } ?: "-"
            appendLine("  motion transition=${t(m.transition)} press=${t(m.press)} scale=${n(m.pressScale)} sink=${n(m.pressSink)}")
        }
        val normal = k.ops(pressed = false)
        appendLine("  normal")
        normal.forEach { appendLine("    ${op(it)}") }
        val pressed = k.ops(pressed = true)
        if (pressed == normal) {
            appendLine("  pressed the same")
        } else {
            appendLine("  pressed")
            diff(normal.map(::op), pressed.map(::op)).forEach { appendLine("    $it") }
        }
    }

    // what the app works the key by: its texts as shown, its sound, shortcut and group letters, its long press as written
    private fun StringBuilder.interaction(k: KeyNode) {
        val texts = listOfNotNull(
            k.label?.let { "label=${q(it)}" },
            k.alt?.let { "alt=${q(it)}" },
            k.altRaw?.takeIf { it != k.alt }?.let { "alt-raw=${q(it)}" },
        )
        if (texts.isNotEmpty()) appendLine("  text ${texts.joinToString(" ")}")
        val how = listOfNotNull(
            k.sound.takeIf { it != "standard" }?.let { "sound=$it" },
            k.shortcut?.let { "shortcut=${q(it)}" },
            k.letters?.let { "letters=${q(it.toString())}" },
            "lean=off".takeIf { (k.letters?.group?.length ?: 0) >= 2 && !k.lean },
        )
        if (how.isNotEmpty()) appendLine("  ${how.joinToString(" ")}")
        k.popup?.let { p ->
            val options = p.options.entries.sortedBy { it.key }.joinToString("") { " ${it.key}=${q(it.value)}" }
            appendLine("  popup from=${p.from}${p.preset?.let { " preset=${q(it)}" }.orEmpty()}$options")
            p.items.forEach { (text, send) -> appendLine("    item ${q(text)}${send?.let { " send=${q(it)}" }.orEmpty()}") }
        }
        k.menu.forEach { appendLine("  menu ${q(it.label)}${it.icon?.let { i -> " icon=$i" }.orEmpty()} do=${q(it.action)}") }
    }

    /** [b] as [a] changed: `-i` lines of [a] taken out, `+i` lines of [b] put in (by a longest common run). */
    fun diff(a: List<String>, b: List<String>): List<String> {
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val out = ArrayList<String>()
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> {
                    i++
                    j++
                }
                j < b.size && (i == a.size || lcs[i][j + 1] >= lcs[i + 1][j]) -> out += "+$j ${b[j++]}"
                else -> out += "-$i ${a[i++]}"
            }
        }
        return out
    }

    fun op(o: Op): String = when (o) {
        is Op.Fill -> "fill ${shape(o.shape)} ${o.color}" + (o.less?.let { " less=${shape(it)}" } ?: "")
        is Op.Gradient -> "gradient ${shape(o.shape)} ${n(o.angle)} ${o.stops.joinToString(" ") { "${it.first}@${n(it.second)}" }}"
        is Op.NineSlice -> "nine-slice ${shape(o.shape)} ${q(o.file)} ${o.insets.joinToString(",")} ${o.pxPerDp}" + (o.fallback?.let { " else=$it" } ?: "")
        is Op.Picture -> "picture ${shape(o.shape)} blur=${o.blurDp?.let(::n) ?: "-"} brightness=${o.brightness?.let(::n) ?: "-"}" + (o.over?.let { " over=$it" } ?: "")
        is Op.Shadow -> "shadow ${shape(o.shape)} blur=${n(o.blur)} ${o.color}"
        is Op.InnerShadow -> "inner-shadow ${shape(o.shape)} hole=${shape(o.hole)} ring=${box(o.ring)} blur=${n(o.blur)} ${o.color}"
        is Op.Stroke -> "stroke ${shape(o.shape)} ${n(o.width)} ${o.color}"
        is Op.Ripple -> "ripple ${shape(o.shape)} ${o.color}"
        is Op.Text -> text(o)
        is Op.Icon -> "icon ${o.name} ${box(o.box)} ${o.tint}" + (if (o.rotation != 0.0) " rotate=${n(o.rotation)}" else "")
    }

    // what a text has besides its place, font and colour only where it has it (a snapshot of none as before)
    private fun text(o: Op.Text): String = "text ${q(o.text)} ${n(o.x)},${n(o.baseline)} ${o.font} ${o.weight} ${n(o.size)} ${o.color}" +
        (if (o.scaleX != 1.0) " scale-x=${n(o.scaleX)}" else "") + (if (o.rotation != 0.0) " rotate=${n(o.rotation)}" else "") +
        (if (o.letterSpacing != 0.0) " spacing=${n(o.letterSpacing)}" else "") +
        (o.stroke?.let { " stroke=${n(it.width)},${it.color}" } ?: "") +
        (o.shadow?.let { " shadow=${n(it.dx)},${n(it.dy)},${n(it.blur)},${it.color}" } ?: "")

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
