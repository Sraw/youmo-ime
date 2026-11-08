/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.scene.KeyRef

// A keyboard made alone (no input view) looked at through ShownKey, as its canvas draws it.

/** Measured and laid out [width] × [height]: a canvas makes its scene at its size. */
internal fun <K : View> K.laidOut(width: Int = 1080, height: Int = 800): K = apply {
    measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
    layout(0, 0, width, height)
}

/** The keys shown, the canvas's scene's. */
internal fun View.shownKeys(): List<ShownKey> = KeyboardHarness.keysOf(this)

/** The key showing [label]. */
internal fun View.shownKey(label: String): ShownKey = shownKeys().firstOrNull { it.label == label }
    ?: error("no key $label in ${shownKeys().map { it.label }}")

/** The key whose view had the id [id] (R.id.button_comma), its node the same name; null for none shown. */
internal fun View.shownKeyWithId(id: Int): ShownKey? =
    resources.getResourceEntryName(id).let { name -> shownKeys().firstOrNull { it.resourceName == name } }

/** The key's reference as a popup action carries it: the scene's key. */
internal fun ShownKey.ref(): KeyRef = KeyRef.scene(id!!, copy)
