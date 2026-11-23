/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.layout.Direction
import org.fcitx.fcitx5.android.scene.layout.Letters
import org.fcitx.fcitx5.android.scene.layout.MenuItem
import org.fcitx.fcitx5.android.scene.layout.Popup
import org.fcitx.fcitx5.android.scene.layout.Slot
import org.fcitx.fcitx5.android.scene.theme.Argb

/** What the engine makes of a layout, a theme and an environment, in px: plain data, the same on the JVM and in JS. */
class Scene(
    val width: Double,
    val height: Double,
    val base: List<Op>,
    val keys: List<KeyNode>,
    val slots: List<SlotNode>,
    val board: Box = Box(0.0, 0.0, width, height),
    val preview: Boolean = true,
    val popups: Boolean = true,
    val warnings: List<Problem> = emptyList(),
) {
    /** A touch on or past the scene's edges counts as just inside (the system may report a finger's centre a pixel out). */
    fun keyAt(x: Double, y: Double): KeyNode? {
        val cx = x.coerceIn(0.0, maxOf(0.0, width - EDGE))
        val cy = y.coerceIn(0.0, maxOf(0.0, height - EDGE))
        return keys.firstOrNull { it.hit.contains(cx, cy) }
    }

    private companion object {
        const val EDGE = 1e-6
    }
}

enum class KeyState(val xml: String) {
    Pressed("pressed"), Shifted("shifted"), Locked("locked"), On("on"), Disabled("disabled"), Selected("selected");

    companion object {
        fun of(xml: String): KeyState? = entries.firstOrNull { it.xml == xml }
    }
}

/** It carries everything the app needs to work it, so the app never goes back to the layout. [copy] tells apart a split row's two copies. */
class KeyNode(
    val id: String,
    val copy: Int,
    val kind: String,
    val role: String?,
    val shape: Shape,
    val bounds: Box,
    val hit: Shape,
    val anchor: Point,
    val up: Point,
    /** A change of these is a new scene for the key. */
    val states: Set<KeyState>,
    val faces: Map<Set<KeyState>, List<Op>>,
    val a11y: String,
    val actions: Map<String, String>,
    val label: String? = null,
    val alt: String? = null,
    val altRaw: String? = null,
    val popup: Popup? = null,
    val menu: List<MenuItem> = emptyList(),
    val sound: String = "standard",
    val shortcut: String? = null,
    val letters: Letters? = null,
    val lean: Boolean = false,
    val face: org.fcitx.fcitx5.android.scene.layout.Face = org.fcitx.fcitx5.android.scene.layout.Face.Default,
    val margin: Boolean = true,
    val motion: Motion? = null,
    /** [MarkFate.Dropped] only for a mark placed then left out for room. */
    val fates: Map<Boolean, MarkFate> = emptyMap(),
    /** Differs only for a key across rows, whose bubble goes over its top row. */
    val head: Box = bounds,
    /** What its glyph and sticker are placed in now: a state's picture read ahead is decoded at its size. */
    val content: Box = bounds,
) {
    fun ops(pressed: Boolean): List<Op> = faces.getValue(if (pressed) states + KeyState.Pressed else states)
}

/** The pressed face itself is shown whole at once, never faded in. */
data class Motion(
    val transition: org.fcitx.fcitx5.android.scene.theme.Transition?,
    val press: org.fcitx.fcitx5.android.scene.theme.Transition?,
    val pressScale: Double = 1.0,
    val pressSink: Double = 0.0,
    val effect: KeyEffect? = null,
) {
    companion object {
        const val MAX_PRESS_MS = 80
    }
}

sealed interface KeyEffect {
    val file: String
    val ms: Int

    data class Ripple(override val file: String, val radius: Double, override val ms: Int) : KeyEffect

    data class Particles(override val file: String, val count: Int, val spread: Double, override val ms: Int) : KeyEffect {
        /** The same seed gives the same flight on every platform (kotlin.random's generator is common code). */
        fun ends(seed: Int): DoubleArray {
            val r = kotlin.random.Random(seed)
            val out = DoubleArray(count * 2)
            for (i in 0 until count) {
                val angle = (i + r.nextDouble() * JITTER) * 2 * kotlin.math.PI / count
                val d = spread * (NEAREST + (1 - NEAREST) * r.nextDouble())
                out[2 * i] = d * kotlin.math.cos(angle)
                out[2 * i + 1] = d * kotlin.math.sin(angle)
            }
            return out
        }

        private companion object {
            const val JITTER = 0.6
            const val NEAREST = 0.5
        }
    }
}

class SlotNode(
    val kind: String,
    val id: String?,
    val bounds: Box,
    val ops: List<Op> = emptyList(),
    val ink: Ink? = null,
    val list: String? = null,
    val direction: Direction = Direction.Vertical,
    val show: Int = Slot.DEFAULT_SHOW,
)

data class Ink(val color: Argb, val width: Double)

/** One drawing step, px, the engine's arithmetic done: a backend draws them in order and computes nothing. */
sealed interface Op {
    data class Fill(val shape: Shape, val color: Argb, val less: Shape? = null) : Op

    data class Gradient(val shape: Shape, val angle: Double, val stops: List<Pair<Argb, Double>>) : Op

    data class NineSlice(val shape: Shape, val file: String, val insets: List<Int>, val pxPerDp: Int, val fallback: Argb? = null) : Op

    data class Picture(val shape: Shape, val blurDp: Double?, val brightness: Double?, val over: Argb? = null) : Op

    data class Shadow(val shape: Shape, val blur: Double, val color: Argb) : Op

    /** [ring] reaches far enough past [shape] that its blur is whole at the edge. */
    data class InnerShadow(val shape: Shape, val hole: Shape, val ring: Box, val blur: Double, val color: Argb) : Op

    /** The engine inset [shape] by half the width, so the line stays inside the face. */
    data class Stroke(val shape: Shape, val width: Double, val color: Argb) : Op

    data class Ripple(val shape: Shape, val color: Argb) : Op

    /** [stroke] and [shadow] are drawn, never measured, as in CSS. */
    data class Text(
        val text: String,
        val x: Double,
        val baseline: Double,
        val font: String,
        val weight: Int,
        val size: Double,
        val color: Argb,
        val scaleX: Double = 1.0,
        val rotation: Double = 0.0,
        val letterSpacing: Double = 0.0,
        val stroke: TextStroke? = null,
        val shadow: TextShadow? = null,
        /** For readers of a scene: not drawn. */
        val part: String? = null,
    ) : Op

    /** [raster]: the theme's PNG or WebP, fitted into [box] keeping its proportions; [tintMask]: only its alpha, coloured [tint]. */
    data class Icon(
        val name: String,
        val box: Box,
        val tint: Argb,
        val rotation: Double = 0.0,
        val raster: Boolean = false,
        val tintMask: Boolean = false,
    ) : Op

    /**
     * A theme's image over a key's face, under its text and icon, fitted into [box] keeping its proportions; [standIn] is
     * the label and icon it hides, drawn instead while the image is not decoded, so a key is never blank.
     */
    data class Sticker(val file: String, val box: Box, val alpha: Double = 1.0, val standIn: List<Op> = emptyList()) : Op
}

data class TextStroke(val width: Double, val color: Argb)

data class TextShadow(val dx: Double, val dy: Double, val blur: Double, val color: Argb)

sealed interface MarkFate {
    data object AsSet : MarkFate
    data class Shortened(val text: String) : MarkFate
    data object Sign : MarkFate
    data object Dropped : MarkFate
}
