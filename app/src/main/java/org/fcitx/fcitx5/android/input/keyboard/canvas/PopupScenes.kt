/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.scene.CanvasFrame
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The popups of the keys the canvas draws, drawn by the engine too (060-render-p2 S2): [theme] as the
 * engine's theme, its text measured and drawn as the keyboard's, each popup's scene made in the
 * environment the keyboard is in now ([view]'s: landscape, 紧凑, floating, dark, flat), so its
 * looks are the keyboard's. A popup asks [builder] for each scene (a focus moved is a new one).
 */
class PopupScenes(private val view: View, private val theme: Theme) {
    private val look = ThemeManager.shownLook(theme)
    private val fonts = SceneFonts(look)
    private val measurer = AndroidTextMeasurer(fonts)
    private val sceneTheme = SceneThemes.of(look, theme.name, theme.isDark)

    /** Whether the theme converted: its popups drawn as scenes. */
    val ready: Boolean get() = sceneTheme != null

    /** A builder in the environment now; null where the theme did not convert (the keyboard's keys are views then too). */
    fun builder(): SceneBuilder? = sceneTheme?.let {
        val env = SceneEnvs.of(view, look, theme.isDark, view.width, view.height, Input())
        SceneBuilder(it, env, measurer, cascades = SceneThemes.cascades(it))
    }

    /** px a dp, as the scenes are made in. */
    val density: Double get() = view.resources.displayMetrics.density.toDouble()

    /** A view drawing a popup's scene. */
    fun view(context: Context): PopupSceneView = PopupSceneView(context) { ScenePainter(it, look, BoardImages.NONE, fonts) }
}

/**
 * A popup's [scene] drawn ([ScenePainter]): its size the scene's and [outset] round it, where its
 * shadow reaches (a view draws nothing outside itself; theme 3.0's elevation shadow was the
 * renderer's). Placed [outset] up and left of where the popup is.
 */
@SuppressLint("ViewConstructor")
class PopupSceneView internal constructor(context: Context, painter: (View) -> ScenePainter) : View(context) {

    private val painter = painter(this)

    /** How far past the scene's box what it draws reaches, each side, px. */
    val outset = Rect()

    var scene: Scene? = null
        set(value) {
            val resized = value?.width != field?.width || value?.height != field?.height
            field = value
            outset.setEmpty()
            if (value != null) {
                // the faces' reach: a text or an icon stays in its cell, and CanvasFrame's guess of a text's ink is wide
                val ops = (value.base + value.keys.flatMap { it.ops(false) }).filter { it !is Op.Text && it !is Op.Icon }
                val e = CanvasFrame.extent(Box(0.0, 0.0, value.width, value.height), ops, EDGE_PX)
                outset.set(ceil(-e.left).toInt().coerceAtLeast(0), ceil(-e.top).toInt().coerceAtLeast(0), ceil(e.right - value.width).toInt().coerceAtLeast(0), ceil(e.bottom - value.height).toInt().coerceAtLeast(0))
            }
            // a focus moved draws again, its size the same
            if (resized) requestLayout()
            invalidate()
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

    private companion object {
        // room past what it draws for the edges' anti-aliasing, as a key's node has (KeyboardCanvasView)
        const val EDGE_PX = 2.0
    }
}
