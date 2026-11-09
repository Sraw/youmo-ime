/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.layout.Letters
import org.fcitx.fcitx5.android.scene.layout.MenuItem
import org.fcitx.fcitx5.android.scene.layout.Popup
import org.fcitx.fcitx5.android.scene.theme.Argb

/**
 * What the engine makes of a layout, a theme and an environment (060-render-v2 §3): every key's
 * shape, touch area and drawing, in px; plain data, the same on the JVM and in JS ([SceneText]).
 * [base] is drawn first (the keyboard's own fill), then the slots' faces, then each key's face for
 * its states. [board] is where the keys are: the whole of it, or one-handed or split at a side
 * (rules D §2), the rest a `side-buttons` slot. [preview] and [popups]: whether its keys pop up a
 * preview, a long press's keyboard (the layout's; the number pad's do not); a menu written on a key
 * pops up all the same (the number pad's Return's, rules C 疑问 8). [warnings]: what
 * the layout asked that this environment could not give and was done another way (a split row of
 * other than nine or ten keys, scaled).
 */
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
    /**
     * The key a finger at ([x], [y]) presses: the one whose [KeyNode.hit] holds it; none in a gap.
     * A touch on the scene's right or bottom edge, or past it (a finger's centre the system reports a
     * pixel out), counts as just inside: the edge keys' hits stop short of their right and bottom.
     */
    fun keyAt(x: Double, y: Double): KeyNode? {
        val cx = x.coerceIn(0.0, maxOf(0.0, width - EDGE))
        val cy = y.coerceIn(0.0, maxOf(0.0, height - EDGE))
        return keys.firstOrNull { it.hit.contains(cx, cy) }
    }

    private companion object {
        const val EDGE = 1e-6
    }
}

/**
 * The states a key is drawn in (theme-4.xsd's `:pressed` and the rest). A key is in a set of them:
 * [Pressed] while a finger is on it, the others as the input is ([KeyNode.states]: Shift on or
 * locked, the handwriting's 注 on, a panel's lock locked, a key not usable, a popup's focused cell).
 */
enum class KeyState(val xml: String) {
    Pressed("pressed"), Shifted("shifted"), Locked("locked"), On("on"), Disabled("disabled"), Selected("selected");

    companion object {
        fun of(xml: String): KeyState? = entries.firstOrNull { it.xml == xml }
    }
}

/**
 * A key (§3, §3.1): [id] is the name themes, overrides, UAT and accessibility know it by
 * (`qwerty/q`); [copy] tells apart the two of a key a split row draws on both halves (G, V).
 * [shape] is its face's; [bounds] its cell (where a popup is placed, a screen reader's box); [hit]
 * where a finger counts as on it, gaps between keys and a row's blank ends included; [anchor] its
 * middle for glide typing and popups; [up] the way a swipe up goes (a unit vector, the screen's up).
 *
 * What the app needs to work it, so it never goes back to the layout: [label] and [alt] as shown (the
 * bindings read; null where there is none), [altRaw] the alt as written (`{punct:~}`), its long
 * press's [popup] as written (null: PopupPreset's by its label and send; [Scene.popups] off: none at
 * all) and [menu], the [sound] it clicks with, the [shortcut] its swipes' symbols are kept under.
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
    /** The states it is in now, but [KeyState.Pressed]: a change of these is a new scene for the key (§3). */
    val states: Set<KeyState>,
    /** Its drawing in [states], and pressed ([states] and [KeyState.Pressed]). */
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
    /** The letters it types as a group, row by row as they sit on it (the layout's `letters=`), null for a key of no group. */
    val letters: Letters? = null,
    /** A tap on it leans to the letter it fell nearest ([Key.leans][org.fcitx.fcitx5.android.scene.layout.Key.leans]). */
    val lean: Boolean = false,
    /** Its layout's say over its face (`face=`) and its gap (`margin=`): what an exporter of the layout (the theme maker's) passes on. */
    val face: org.fcitx.fcitx5.android.scene.layout.Face = org.fcitx.fcitx5.android.scene.layout.Face.Default,
    val margin: Boolean = true,
) {
    /** What a backend draws for it: [faces] in its [states], pressed or not. */
    fun ops(pressed: Boolean): List<Op> = faces.getValue(if (pressed) states + KeyState.Pressed else states)
}

/**
 * A native widget's place (the handwriting pad, the nine keys' column, a one-handed board's side
 * buttons): the app puts its view there, over [ops], a key's face drawn under it where the layout
 * gives it a look (never pressed).
 */
class SlotNode(val kind: String, val id: String?, val bounds: Box, val ops: List<Op> = emptyList())

/**
 * One drawing step, px, the engine's arithmetic done (§3, its geometry by [Shape] as §3.1 has it).
 * A backend draws them in order and computes nothing.
 */
sealed interface Op {
    /**
     * A solid colour over [shape], less what is inside [less] where given (a key cap's band under a face
     * that may be translucent: only what shows below the face).
     */
    data class Fill(val shape: Shape, val color: Argb, val less: Shape? = null) : Op

    /** A linear gradient over [shape], [angle] degrees, its stops (colour, 0–1). */
    data class Gradient(val shape: Shape, val angle: Double, val stops: List<Pair<Argb, Double>>) : Op

    /** A nine-slice image over [shape], its [insets] source pixels at [pxPerDp]; [fallback] filled where the image is not there (theme 3.0's key colour). */
    data class NineSlice(val shape: Shape, val file: String, val insets: List<Int>, val pxPerDp: Int, val fallback: Argb? = null) : Op

    /** The keyboard's picture under [shape], as the theme's `<picture>` unless given; [over] laid on it (theme 3.0's key colour). */
    data class Picture(val shape: Shape, val blurDp: Double?, val brightness: Double?, val over: Argb? = null) : Op

    /** A shadow: [shape] drawn in [color], blurred [blur] px (none: a solid edge, theme 3.0's). */
    data class Shadow(val shape: Shape, val blur: Double, val color: Argb) : Op

    /**
     * A shadow inside [shape] (a theme's `inner-shadow`, CSS's inset box-shadow): [ring] less [hole] (the
     * shape moved by the shadow's offset, filled by the same rule as [shape]) in [color], blurred [blur] px (none:
     * sharp), and only what falls inside [shape] drawn. [ring] is far enough past [shape] that its blur is
     * whole at [shape]'s edge.
     */
    data class InnerShadow(val shape: Shape, val hole: Shape, val ring: Box, val blur: Double, val color: Argb) : Op

    /** A line [width] px wide along [shape]'s edge, centred on it (the engine has inset it by half the width, so the line keeps inside the face). */
    data class Stroke(val shape: Shape, val width: Double, val color: Argb) : Op

    /** A pressed key's ripple, from its touch, over [shape]. */
    data class Ripple(val shape: Shape, val color: Argb) : Op

    /**
     * [text] from ([x], [baseline]) in [font] [weight] at [size] px, squeezed to [scaleX] of its
     * width, turned [rotation] degrees about ([x], [baseline]) (§3.1; 0 for now); its characters
     * [letterSpacing] em apart (Paint.letterSpacing's), outlined by [stroke] under its fill and
     * shadowed by [shadow] (a theme's `stroke`, `text-shadow`: drawn, never measured, as CSS's).
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
        val letterSpacing: Double = 0.0,
        val stroke: TextStroke? = null,
        val shadow: TextShadow? = null,
        /** The part of its key it is (`label`, `mark`, `shortcut`, `badge`…, a theme's part names; null: none), for what reads a scene: not drawn. */
        val part: String? = null,
    ) : Op

    /** The icon [name] in [box], tinted [tint], turned [rotation] degrees about its middle. */
    data class Icon(val name: String, val box: Box, val tint: Argb, val rotation: Double = 0.0) : Op
}

/** An outline [width] px outside a text's glyphs, in [color]: drawn first, its fill over it. */
data class TextStroke(val width: Double, val color: Argb)

/** A text's shadow: its glyphs moved ([dx], [dy]) px, blurred [blur] px (0: sharp), in [color]. */
data class TextShadow(val dx: Double, val dy: Double, val blur: Double, val color: Argb)
