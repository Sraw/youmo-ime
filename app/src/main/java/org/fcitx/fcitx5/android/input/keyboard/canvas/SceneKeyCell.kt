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
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.layout.Key
import org.fcitx.fcitx5.android.scene.layout.KeyAttrs
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.OnSplit
import org.fcitx.fcitx5.android.scene.layout.Row
import org.fcitx.fcitx5.android.scene.layout.RowSplit
import org.fcitx.fcitx5.android.scene.theme.Argb
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * One key drawn by the engine on its own (060-render-p2 S6): a scene of a board of that key only, its
 * size the view's, in the keyboard's theme and the environment the view is in. The symbol grid's cells
 * and the settings' key caps, which were key views each. No board under it: it sits on what is under
 * the view. A scene not made is logged and nothing drawn (never a key view instead). A grid's cell
 * ([ownSize]) is the size its attrs give, not the keyboard's 紧凑 or floating; a key cap is as the keyboard.
 */
@SuppressLint("ViewConstructor")
open class SceneKeyCell(context: Context, private val theme: Theme, private val ownSize: Boolean = false) : CustomGestureView(context) {

    private val look = ThemeManager.shownLook(theme)
    private val fonts = SceneFonts(look)
    private val measurer = AndroidTextMeasurer(fonts)
    private val sceneTheme = SceneThemes.of(look, theme.name, theme.isDark)
    private val painter = ScenePainter(this, look, BoardImages.NONE, fonts)

    /** The key: its face, its words, its marks' names. */
    var attrs: KeyAttrs = KeyAttrs()
        set(value) {
            if (field == value) return
            field = value
            dropScene()
        }

    /** The key's states (selected, disabled, locked) as the scene shows them. */
    var states: Set<KeyState> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            dropScene()
        }

    /** The marks a shortcut name of [attrs] reaches (the settings' caps): none by default. */
    var marks: MarkSettings = NO_MARKS
        set(value) {
            if (field == value) return
            field = value
            dropScene()
        }

    /**
     * The key's own words at this of their alpha, its face and marks as they are (the settings' cap of a key
     * whose long press is off, as its key view faded its label alone): the cell's, not a scene state.
     */
    var labelAlpha: Double = 1.0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * The keyboard's key height, px, this cell stands for (a settings cap): the key made at the keyboard's
     * size and drawn shrunk to the cell, words, marks and all, so its marks are kept or given way as there
     * and its label is the keyboard's times the cell's height over this (060-pending-decisions); 0: as the cell is.
     */
    var keyboardKeyHeight: Int = 0
        set(value) {
            if (field == value) return
            field = value
            dropScene()
        }

    /**
     * The keyboard whose key this cell is (a settings cap, 060-render-p2 S7a): its [Board.layout] made at the
     * keys' size there ([Board.width] × [Board.height], KeyboardArea) and the key of [Board.shortcut] drawn
     * shrunk to the cell, so its label, its marks kept and given way are the keyboard's own; [attrs] unused. Null: [attrs]'s key alone.
     */
    data class Board(val layout: String, val shortcut: String, val width: Int, val height: Int, val alts: Map<String, String> = emptyMap())

    var board: Board? = null
        set(value) {
            if (field == value) return
            field = value
            dropScene()
        }

    /** An outline in this colour along the key's face, its corners the face's (the key being edited); none if null. */
    var outline: Argb? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var made: Scene? = null
    private var madeFor = 0 to 0
    private var failed = false

    init {
        setWillNotDraw(false)
        // a cell's own id: what a popup's KeyRef names it by (View.keyRef)
        if (id == View.NO_ID) id = View.generateViewId()
    }

    private fun dropScene() {
        made = null
        failed = false
        invalidate()
    }

    /** The scene as the cell is now: made again for a change or another size; null where it could not be made. */
    @get:VisibleForTesting
    val scene: Scene?
        get() {
            if (width <= 0 || height <= 0) return null
            if (made != null && madeFor == (width to height)) return made
            if (failed && madeFor == (width to height)) return null
            madeFor = width to height
            made = build()
            failed = made == null
            return made
        }

    /** The cell's key in [scene]. */
    val keyNode: KeyNode? get() = scene?.keys?.let { keys -> board?.let { b -> keys.firstOrNull { it.shortcut == b.shortcut && it.copy == 0 } } ?: keys.firstOrNull() }

    private fun build(): Scene? {
        val t = sceneTheme ?: return null.also { Timber.e("key cell: theme %s not converted; nothing drawn", theme.name) }
        board?.let { return onBoard(t, it) }
        // a grid's cell the text the size [attrs] gives it, not the keyboard's 紧凑 or floating scale (the cells'
        // key views never took either); a key cap as the keyboard is, its marks' corners as there
        val env = SceneEnvs.of(this, look, theme.isDark, (width / shrink).roundToInt(), (height / shrink).roundToInt(), Input(), texts = Texts(), marks = marks)
            .let { if (ownSize) it.copy(compact = false, floating = null) else it }
            .let { if (states.isEmpty()) it else it.copy(states = mapOf(KEY to states)) }
        val layout = Layout(
            KEY, KEY, KEY, 1.0, OnSplit.None, KeyAttrs(),
            listOf(Row(null, 0.0, 0.0, 1.0, null, RowSplit.Halves, KeyAttrs(), listOf(Key(KEY, null, attrs, null, emptyList(), 0)), 0)),
            preview = false,
            popups = false,
        )
        return when (val s = SceneBuilder(t, env, measurer, cascades = SceneThemes.cascades(t)).build(layout)) {
            is Parsed.Ok -> s.value
            is Parsed.Failed -> null.also { Timber.e("key cell: no scene of %s: %s; nothing drawn", attrs.label, s.problems) }
        }
    }

    // the whole keyboard of [b], as it is docked (the marks the page's)
    private fun onBoard(t: org.fcitx.fcitx5.android.scene.theme.Theme, b: Board): Scene? {
        val layout = SceneLayouts.of(context, b.layout) ?: return null
        val env = SceneEnvs.of(this, look, theme.isDark, b.width, b.height, Input(), texts = Texts(alts = b.alts), marks = marks)
        return when (val s = SceneBuilder(t, env, measurer, cascades = SceneThemes.cascades(t)).build(layout.layout, layout.fragments)) {
            is Parsed.Ok -> s.value.also { v -> if (v.keys.none { it.shortcut == b.shortcut }) Timber.e("key cell: no key of %s on %s", b.shortcut, b.layout) }
            is Parsed.Failed -> null.also { Timber.e("key cell: no scene of %s: %s; nothing drawn", b.layout, s.problems) }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidate()
    }

    override fun setPressed(pressed: Boolean) {
        val was = isPressed
        super.setPressed(pressed)
        if (was != pressed) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val saved = canvas.save()
        // a board's key: its face centred in the cell
        if (board != null) keyNode?.bounds?.let { k ->
            canvas.translate(((width - k.width * shrink) / 2 - k.left * shrink).toFloat(), ((height - k.height * shrink) / 2 - k.top * shrink).toFloat())
        }
        canvas.scale(shrink.toFloat(), shrink.toFloat())
        painter.draw(canvas, drawn())
        canvas.restoreToCount(saved)
    }

    // the drawing's scale: the cell's height over the keyboard key's it stands for, 1 where none
    // (a board's key: the whole key within the cell)
    private val shrink: Double
        get() {
            if (board != null) return keyNode?.bounds?.takeIf { it.hasSize }?.let { minOf(width / it.width, height / it.height) } ?: 1.0
            return if (keyboardKeyHeight <= 0 || height <= 0) 1.0 else height.toDouble() / keyboardKeyHeight
        }

    /** What the cell draws: its key's ops, its label at [labelAlpha], its [outline] over the face. */
    @VisibleForTesting
    fun drawn(): List<Op> {
        val k = keyNode ?: return emptyList()
        val ops = k.ops(isPressed).let { ops ->
            if (labelAlpha == 1.0) ops
            else ops.map { if (it is Op.Text && it.text == k.label) it.copy(color = it.color.times(labelAlpha)) else it }
        }
        val line = outline ?: return ops
        return ops + Op.Stroke(k.shape, OUTLINE_DP * resources.displayMetrics.density / shrink, line)
    }

    private fun Argb.times(a: Double) = Argb((value and RGB) or ((alpha * a).roundToInt() shl ALPHA_SHIFT))

    private val at = IntArray(2)

    /** Where the key's face is, in the window: what a popup over it aligns with (across its face, as [KeyboardCanvasView.popupBounds]). */
    val bounds: Rect
        get() {
            getLocationInWindow(at)
            val f = keyNode?.shape?.bounds
            return if (f == null) Rect(at[0], at[1], at[0] + width, at[1] + height)
            else Rect(at[0] + (f.left * shrink).roundToInt(), at[1], at[0] + (f.right * shrink).roundToInt(), at[1] + height)
        }

    companion object {
        const val KEY = "key"
        private const val OUTLINE_DP = 2.0
        private const val RGB = 0xFFFFFF
        private const val ALPHA_SHIFT = 24
        private val NO_MARKS = MarkSettings(shortcuts = emptyMap())

        /** Whether one key is drawn by the engine (the canvas keyboard on), not as a key view. */
        val on: Boolean get() = AppPrefs.getInstance().internal.canvasKeyboard.getValue()
    }
}
