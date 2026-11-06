/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.theme.Argb

/**
 * What the engine makes of a layout, a theme and an environment (060-render-v2 §3): every key's
 * shape, touch area and drawing, in px; plain data, the same on the JVM and in JS ([SceneText]).
 * [base] is drawn first (the keyboard's own fill), then each key's face for its state.
 */
class Scene(
    val width: Double,
    val height: Double,
    val base: List<Op>,
    val keys: List<KeyNode>,
    val slots: List<SlotNode>,
) {
    /** The key a finger at ([x], [y]) presses: the one whose [KeyNode.hit] holds it; none in a gap. */
    fun keyAt(x: Double, y: Double): KeyNode? = keys.firstOrNull { it.hit.contains(x, y) }
}

/** The states a key is drawn in; others (`:locked`, `:shifted`) come with the environment for now. */
enum class KeyState { Normal, Pressed }

/**
 * A key (§3, §3.1): [id] is the name themes, overrides, UAT and accessibility know it by
 * (`qwerty/q`); [copy] tells apart the two of a key a split row draws on both halves (G, V).
 * [shape] is its face's; [bounds] its cell (where a popup is placed, a screen reader's box); [hit]
 * where a finger counts as on it, gaps between keys and a row's blank ends included; [anchor] its
 * middle for glide typing and popups; [up] the way a swipe up goes (a unit vector, the screen's up).
 */
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
    val faces: Map<KeyState, List<Op>>,
    val a11y: String,
    val actions: Map<String, String>,
)

/** A native widget's place (the handwriting pad): the app puts its view there. */
class SlotNode(val kind: String, val id: String?, val bounds: Box)

/**
 * One drawing step, px, the engine's arithmetic done (§3, its geometry by [Shape] as §3.1 has it).
 * A backend draws them in order and computes nothing.
 */
sealed interface Op {
    /** A solid colour over [shape]. */
    data class Fill(val shape: Shape, val color: Argb) : Op

    /** A linear gradient over [shape], [angle] degrees, its stops (colour, 0–1). */
    data class Gradient(val shape: Shape, val angle: Double, val stops: List<Pair<Argb, Double>>) : Op

    /** A nine-slice image over [shape], its [insets] source pixels at [pxPerDp]. */
    data class NineSlice(val shape: Shape, val file: String, val insets: List<Int>, val pxPerDp: Int) : Op

    /** The keyboard's picture under [shape], as the theme's `<picture>` unless given. */
    data class Picture(val shape: Shape, val blurDp: Double?, val brightness: Double?) : Op

    /** A shadow: [shape] drawn in [color], blurred [blur] px (none: a solid edge, theme 3.0's). */
    data class Shadow(val shape: Shape, val blur: Double, val color: Argb) : Op

    /** A line [width] px wide along [shape]'s edge, centred on it (the engine has inset it by half the width, so the line keeps inside the face). */
    data class Stroke(val shape: Shape, val width: Double, val color: Argb) : Op

    /** A pressed key's ripple, from its touch, over [shape]. */
    data class Ripple(val shape: Shape, val color: Argb) : Op

    /**
     * [text] from ([x], [baseline]) in [font] [weight] at [size] px, squeezed to [scaleX] of its
     * width, turned [rotation] degrees about ([x], [baseline]) (§3.1; 0 for now).
     */
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
    ) : Op

    /** The icon [name] in [box], tinted [tint], turned [rotation] degrees about its middle. */
    data class Icon(val name: String, val box: Box, val tint: Argb, val rotation: Double = 0.0) : Op
}
