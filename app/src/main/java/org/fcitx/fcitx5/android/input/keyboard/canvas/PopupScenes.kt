/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.VisibleForTesting
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.scene.CanvasFrame
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import timber.log.Timber
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The popups of the keys the canvas draws, drawn by the engine too (060-render-p2 S2): [theme] as the
 * engine's theme, its text measured and drawn as the keyboard's, each popup's scene made in the
 * environment the keyboard is in now ([view]'s: landscape, 紧凑, floating, dark, flat), so its
 * looks are the keyboard's. A popup asks [builder] for each scene (a focus moved is a new one).
 */
class PopupScenes(
    private val view: View,
    private val theme: Theme,
    /** The text measured through this: a test's, to make the engine fail. */
    @VisibleForTesting measuring: (TextMeasurer) -> TextMeasurer = { it },
) {
    private val look = ThemeManager.shownLook(theme)
    private val fonts = SceneFonts.of(theme, look)
    private val measurer = measuring(AndroidTextMeasurer(fonts))
    private val sceneTheme = SceneThemes.of(theme, look)

    /** Whether the theme converted: its popups drawn as scenes. */
    val ready: Boolean get() = sceneTheme != null

    /** A builder in the environment now; null where the theme did not convert (the keyboard's keys are views then too). */
    fun builder(): SceneBuilder? = sceneTheme?.let {
        val env = SceneEnvs.of(view, look, theme.isDark, view.width, view.height, Input())
        SceneBuilder(it, env, measurer, cascades = SceneThemes.cascades(it))
    }

    /**
     * What [made] made of [what]; a failure logged and null: the popup then not shown (a new one) or
     * the scene it had kept (a focus moved), never the views instead (they go in S8) and never a
     * popup not drawn that a lift would still type.
     */
    fun <T> made(what: String, made: Parsed<T>?): T? = when (made) {
        is Parsed.Ok -> made.value
        is Parsed.Failed -> null.also { Timber.w("popup %s not made: %s", what, made.problems) }
        null -> null
    }

    /** px a dp, as the scenes are made in. */
    val density: Double get() = view.resources.displayMetrics.density.toDouble()

    /** A view drawing a popup's scene. */
    fun view(context: Context): PopupSceneView = PopupSceneView(context) { ScenePainter(it, look, BoardImages.NONE, fonts) }
}

/**
 * A popup's [scene] drawn ([ScenePainter]): its size the scene's and [outset] round it, where its
 * shadow reaches (a view draws nothing outside itself; theme 3.0's elevation shadow was the
 * renderer's). Placed [outset] up and left of where the popup is. TalkBack reads its keys as it
 * read the views' (their text or description): one virtual node a key, the one in focus selected.
 */
@SuppressLint("ViewConstructor")
class PopupSceneView internal constructor(context: Context, painter: (View) -> ScenePainter) : View(context) {

    private val painter = painter(this)

    private val speech = Speech()

    init {
        ViewCompat.setAccessibilityDelegate(this, speech)
    }

    /** How far past the scene's box what it draws reaches, each side, px. */
    val outset = Rect()

    /** Told the [outset] before and now as a scene changes it: where it is placed moves by as much. */
    var onOutsetChanged: ((was: Rect, now: Rect) -> Unit)? = null

    var scene: Scene? = null
        set(value) {
            val before = Rect(outset)
            val resized = value?.width != field?.width || value?.height != field?.height
            field = value
            outset.setEmpty()
            if (value != null) {
                // the faces' reach: a text or an icon stays in its cell, and CanvasFrame's guess of a text's ink is wide
                val ops = (value.base + value.keys.flatMap { it.ops(false) }).filter { it !is Op.Text && it !is Op.Icon }
                val e = CanvasFrame.extent(Box(0.0, 0.0, value.width, value.height), ops, EDGE_PX)
                outset.set(ceil(-e.left).toInt().coerceAtLeast(0), ceil(-e.top).toInt().coerceAtLeast(0), ceil(e.right - value.width).toInt().coerceAtLeast(0), ceil(e.bottom - value.height).toInt().coerceAtLeast(0))
            }
            // a focus moved draws again, its size the same; its shadow's reach is its size too
            if (outset != before) onOutsetChanged?.invoke(before, Rect(outset))
            if (resized || outset != before) requestLayout()
            invalidate()
            speech.reading.clear()
            speech.invalidateRoot()
        }

    /** The scene's size in whole px (the engine's are). */
    val sceneWidth: Int get() = scene?.width?.let { floor(it).toInt() } ?: 0
    val sceneHeight: Int get() = scene?.height?.let { floor(it).toInt() } ?: 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sceneWidth + outset.left + outset.right, sceneHeight + outset.top + outset.bottom)
    }

    override fun onDraw(canvas: Canvas) {
        val s = scene ?: return
        canvas.save()
        canvas.translate(outset.left.toFloat(), outset.top.toFloat())
        painter.draw(canvas, s.base)
        s.keys.forEach { painter.draw(canvas, it.ops(false)) }
        canvas.restore()
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = speech.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    // a node a key, as its view was (060-render-p2 S2, S3): its words, where it is, selected and enabled
    // as drawn, its class the view's (a text's TextView, a menu's icon's ImageView), its words read a
    // piece at a time; no click, as the views had none (picked as the finger lifts)
    private inner class Speech : ExploreByTouchHelper(this) {
        val reading = NodeReading({ resources.configuration.locales[0] }, ::sendEventForVirtualView)

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val keys = scene?.keys ?: return INVALID_ID
            val at = keys.indexOfFirst { spoken(it) && it.bounds.contains(x.toDouble() - outset.left, y.toDouble() - outset.top) }
            return if (at < 0) INVALID_ID else at
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            scene?.keys?.forEachIndexed { i, k -> if (spoken(k)) virtualViewIds += i }
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val k = scene?.keys?.getOrNull(virtualViewId)
            val b = k?.bounds
            // asked of a key gone with the last scene: a node there must still have bounds and words
            node.contentDescription = k?.let(::words).orEmpty()
            node.text = k?.label.orEmpty()
            @Suppress("DEPRECATION")
            node.setBoundsInParent(
                if (b == null) Rect() else Rect(
                    b.left.roundToInt() + outset.left, b.top.roundToInt() + outset.top,
                    b.right.roundToInt() + outset.left, b.bottom.roundToInt() + outset.top,
                ),
            )
            node.isSelected = k != null && KeyState.Selected in k.states
            node.isEnabled = k != null && KeyState.Disabled !in k.states
            node.className = if (k != null && k.ops(false).any { it is Op.Icon }) IMAGE_CLASS else TEXT_CLASS
            // a character, word or paragraph at a time, as the views' text or description (not a
            // TextView's line or page: one line, no page, the cell's words)
            reading.node(virtualViewId, k?.let(::words), node)
        }

        override fun onPopulateEventForVirtualView(virtualViewId: Int, event: AccessibilityEvent) {
            reading.populate(virtualViewId, scene?.keys?.getOrNull(virtualViewId)?.let(::words), event)
        }

        // picked as the finger lifts (PopupComponent), as the views' were: nothing done here
        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean =
            reading.perform(virtualViewId, scene?.keys?.getOrNull(virtualViewId)?.let(::words), action, arguments) ?: false
    }

    // a key's words for TalkBack: the scene's (a menu's item's name), else its label; none, not read
    private fun words(k: KeyNode): String? = (k.a11y ?: k.label)?.takeIf { it.isNotEmpty() }

    private fun spoken(k: KeyNode) = words(k) != null

    /** The nodes TalkBack has for the keys read out, as its provider makes them: for tests. */
    @VisibleForTesting
    fun spokenKeys(): List<AccessibilityNodeInfoCompat> {
        val provider = speech.getAccessibilityNodeProvider(this) ?: return emptyList()
        return scene?.keys.orEmpty().indices.filter { spoken(scene!!.keys[it]) }.mapNotNull { provider.createAccessibilityNodeInfo(it) }
    }

    private companion object {
        // room past what it draws for the edges' anti-aliasing, as a key's node has (KeyboardCanvasView)
        const val EDGE_PX = 2.0

        // the views' classes it stands for: a key's or item's text, a menu item's icon
        const val TEXT_CLASS = "android.widget.TextView"
        const val IMAGE_CLASS = "android.widget.ImageView"
    }
}
