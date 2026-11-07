/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.ViewShownKey
import org.fcitx.fcitx5.android.input.scene.KeyRef
import splitties.views.existingOrNewId

// A keyboard made alone (no input view) looked at through ShownKey, whichever draws it: a keyboard
// moved to the scene (060-render-p2 S4) is tested both ways until S8 drops the views.

/**
 * [check] run with the keys drawn by their views, then by the canvas (060-render-p2 S4 step 8), the
 * switch put back after; a keyboard made in [check] is drawn as the switch says then.
 */
internal fun bothDrawings(check: (canvas: Boolean) -> Unit) {
    val switch = AppPrefs.getInstance().internal.canvasKeyboard
    val was = switch.getValue()
    try {
        assertRows(listOf(false, true), { if (it) "canvas" else "views" }) { canvas ->
            switch.setValue(canvas)
            check(canvas)
        }
    } finally {
        switch.setValue(was)
    }
}

/** Measured and laid out [width] × [height]: a canvas makes its scene at its size. */
internal fun <K : View> K.laidOut(width: Int = 1080, height: Int = 800): K = apply {
    measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
    layout(0, 0, width, height)
}

/** The keys shown, the canvas's scene's or the views visible. */
internal fun View.shownKeys(): List<ShownKey> = KeyboardHarness.keysOf(this)

/** The key showing [label]. */
internal fun View.shownKey(label: String): ShownKey = shownKeys().firstOrNull { it.label == label }
    ?: error("no key $label in ${shownKeys().map { it.label }}")

/** The key whose view had the id [id] (R.id.button_comma), its node the same name; null for none shown. */
internal fun View.shownKeyWithId(id: Int): ShownKey? =
    resources.getResourceEntryName(id).let { name -> shownKeys().firstOrNull { it.resourceName == name } }

/** The key's reference as a popup action carries it: the view's id, or the scene's key. */
internal fun ShownKey.ref(): KeyRef = when (this) {
    is ViewShownKey -> KeyRef.view(view.existingOrNewId)
    is SceneShownKey -> KeyRef.scene(id, copy)
    else -> error("$this")
}
