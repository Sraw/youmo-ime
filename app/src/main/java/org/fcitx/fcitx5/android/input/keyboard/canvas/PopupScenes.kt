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
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import timber.log.Timber
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Each popup's scene made in the environment the keyboard is in now, so its looks are the keyboard's. */
class PopupScenes(
    private val view: View,
    theme: Theme,
    /** A test's, to make the engine fail. */
    @VisibleForTesting measuring: (TextMeasurer) -> TextMeasurer = { it },
) {
    // the built-in where the engine makes nothing of the theme or refused its keys' scene: popups shown still
    private val theme = SceneBoard.drawnTheme(theme)
    private val look = ThemeManager.shownLook(this.theme)
    private val fonts = SceneFonts.of(this.theme, look)
    private val measurer = measuring(AndroidTextMeasurer(fonts))
    private val sceneTheme = SceneThemes.of(this.theme, look)

    // none where the keys fell back to the built-in
    private val icons = PackIcons.of(this.theme)

    /** Asked once, so a theme with none (every built-in one) asks the engine nothing more. */
    val moves: Boolean by lazy {
        sceneTheme?.styles.orEmpty().any { s -> s.selectors.any { it.widget in POPUP_WIDGETS } && ("appear" in s.values || "transition" in s.values) }
    }

    val ready: Boolean get() = sceneTheme != null

    /** A test's, that they follow the keys. */
    @VisibleForTesting
    internal val drawsIn: String get() = theme.name

    fun env(): Env = SceneEnvs.of(view, look, theme.isDark, view.width, view.height, Input())

    /** Null where neither the theme nor the built-in converted (nothing drawn, logged). */
    fun builder(env: Env = env()): SceneBuilder? = sceneTheme?.let {
        SceneBuilder(it, env, measurer, cascades = SceneThemes.cascades(it))
    }

    /** A failure logged and null: never the views instead, and never a popup not drawn that a lift would still type. */
    fun <T> made(what: String, made: Parsed<T>?): T? = when (made) {
        is Parsed.Ok -> made.value
        is Parsed.Failed -> null.also { Timber.w("popup %s not made: %s", what, made.problems) }
        null -> null
    }

    val density: Double get() = view.resources.displayMetrics.density.toDouble()

    fun view(context: Context): PopupSceneView = PopupSceneView(context) { ScenePainter(it, look, BoardImages.NONE, fonts, icons) }
}

/**
 * [outset] round it where its shadow reaches: a view draws nothing outside itself. TalkBack reads one
 * virtual node a key, the one in focus selected.
 */
@SuppressLint("ViewConstructor")
class PopupSceneView internal constructor(context: Context, painter: (View) -> ScenePainter) : View(context) {

    private val painter = painter(this)

    private val speech = Speech()

    init {
        ViewCompat.setAccessibilityDelegate(this, speech)
    }

    /** Px each side. */
    val outset = Rect()

    /** Where it is placed moves by as much. */
    var onOutsetChanged: ((was: Rect, now: Rect) -> Unit)? = null

    var scene: Scene? = null
        set(value) {
            val before = Rect(outset)
            val resized = value?.width != field?.width || value?.height != field?.height
            field = value
            outset.setEmpty()
            if (value != null) {
                // a text or an icon stays in its cell, and CanvasFrame's guess of a text's ink is wide
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

    // as its view was; no click, as the views had none (picked as the finger lifts)
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
            // not a TextView's line or page: one line, no page
            reading.node(virtualViewId, k?.let(::words), node)
        }

        override fun onPopulateEventForVirtualView(virtualViewId: Int, event: AccessibilityEvent) {
            reading.populate(virtualViewId, scene?.keys?.getOrNull(virtualViewId)?.let(::words), event)
        }

        // picked as the finger lifts (PopupComponent): nothing done here
        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean =
            reading.perform(virtualViewId, scene?.keys?.getOrNull(virtualViewId)?.let(::words), action, arguments) ?: false
    }

    private fun words(k: KeyNode): String? = (k.a11y ?: k.label)?.takeIf { it.isNotEmpty() }

    private fun spoken(k: KeyNode) = words(k) != null

    /** For tests. */
    @VisibleForTesting
    fun spokenKeys(): List<AccessibilityNodeInfoCompat> {
        val provider = speech.getAccessibilityNodeProvider(this) ?: return emptyList()
        return scene?.keys.orEmpty().indices.filter { spoken(scene!!.keys[it]) }.mapNotNull { provider.createAccessibilityNodeInfo(it) }
    }

    private companion object {
        // for the edges' anti-aliasing, as a key's node has
        const val EDGE_PX = 2.0

        const val TEXT_CLASS = "android.widget.TextView"
        const val IMAGE_CLASS = "android.widget.ImageView"
    }
}

private val POPUP_WIDGETS = setOf("popup", "preview", "menu")
