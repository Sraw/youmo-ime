/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.graphics.Rect
import android.view.View
import org.fcitx.fcitx5.android.input.keyboard.AltTextKeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.scene.KeyNode
import kotlin.math.roundToInt

/**
 * A key as a test sees it, whichever draws it (060-render-p2 S3): a key view ([ViewShownKey]) or a
 * key of the scene a [KeyboardCanvasView] draws ([SceneShownKey]). [KeyboardHarness] finds them
 * ([KeyboardHarness.key], [KeyboardHarness.keyNamed]) and puts fingers on them by where they are
 * ([bounds]), so a test written on it runs on both drawings; S8 deletes [ViewShownKey]. Places are
 * the window's, read each time asked: a key moves with its keyboard (one-handed, floating).
 */
interface ShownKey {
    /** The scene's key id (`q`, `space`, layout-1's), a key view's theme name ([KeyView.themeKey]); null for a view not named. */
    val id: String?

    /** Which of the two a split row draws of one key it is (G, V): 0, or 1 for the second. */
    val copy: Int

    /** Its main text as shown ("" for an icon). */
    val label: String

    /** Its corner as shown (a letter's symbol, the nine keys' digit, a table's letter under its roots); null for none. */
    val alt: String?

    /** What a screen reader reads for it: its view's content description, its node's; null for none. */
    val description: String?

    /** Whether it takes a press (a blank key does not, nor a key the scene has as disabled). */
    val enabled: Boolean

    /** Its cell in the window: a view's box, where a popup is placed from. */
    val bounds: Rect

    /** What is drawn of it in the window: its cell less the gaps between keys. */
    val face: Rect

    /** Where a finger counts as on it, in the window: its cell, a row's blank end too for the scene's (A's left). */
    val hit: Rect

    /** Whether it is shown pressed now. */
    val pressed: Boolean

    /** A click, as the end of a tap calls it (a view's performClick, the scene key's click): whether anything took it. */
    fun click(): Boolean

    val width: Int get() = bounds.width()
    val height: Int get() = bounds.height()
}

/** A key view as a [ShownKey]: the old drawing's, the key's own view. */
class ViewShownKey(val view: KeyView) : ShownKey {
    override val id: String? get() = view.themeKey
    override val copy: Int = 0
    override val label: String get() = (view as? TextKeyView)?.mainText?.text?.toString().orEmpty()
    override val alt: String?
        get() = (view as? AltTextKeyView)?.altText?.takeIf { it.visibility == View.VISIBLE }?.text?.toString()?.takeIf { it.isNotEmpty() }
    override val description: String? get() = view.contentDescription?.toString()
    override val enabled: Boolean get() = view.isEnabled
    override val bounds: Rect
        get() {
            val at = IntArray(2).also(view::getLocationInWindow)
            return Rect(at[0], at[1], at[0] + view.width, at[1] + view.height)
        }
    override val face: Rect get() = bounds.apply { inset(view.hMargin, view.vMargin) }
    override val hit: Rect get() = bounds
    override val pressed: Boolean get() = view.isPressed
    override fun click(): Boolean = view.performClick()
    override fun toString() = "view key ${id ?: label}"
}

/**
 * A key the scene of [canvas] draws as a [ShownKey], key [id]'s [copy]th, looked up in the scene
 * each time asked: a Shift relabels the letters in a scene made anew.
 */
class SceneShownKey(val canvas: KeyboardCanvasView, override val id: String, override val copy: Int = 0) : ShownKey {
    /** The scene's key now: what the canvas draws for it. */
    val node: KeyNode get() = canvas.keyAt(id, copy) ?: error("no key $id ($copy) in the scene now")

    override val label: String get() = node.label.orEmpty()
    override val alt: String? get() = canvas.alt(id, copy)
    override val description: String? get() = canvas.spoken(id, copy)
    override val enabled: Boolean get() = canvas.enabled(id, copy)
    override val bounds: Rect get() = canvas.bounds(id, copy) ?: error("no key $id ($copy) in the scene now")
    override val face: Rect get() = inWindow(node.shape.bounds)
    override val hit: Rect get() = inWindow(node.hit.bounds)
    override val pressed: Boolean get() = canvas.shownPressed(id, copy)
    override fun click(): Boolean = canvas.click(id, copy)
    override fun toString() = "scene key $id${if (copy != 0) " ($copy)" else ""}"

    private fun inWindow(b: org.fcitx.fcitx5.android.scene.Box): Rect {
        val at = IntArray(2).also(canvas::getLocationInWindow)
        return Rect(at[0] + b.left.roundToInt(), at[1] + b.top.roundToInt(), at[0] + b.right.roundToInt(), at[1] + b.bottom.roundToInt())
    }
}
