/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.pet

import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.TypedComponent
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.scene.pet.Pet
import org.fcitx.fcitx5.android.utils.LowMemory
import kotlin.math.roundToInt

/**
 * A theme's pet on the keyboard: placed on a corner of [keys] (the board, as one-handed narrows it) or at the end of
 * [bar], which leaves it the room; shown over the keys' window only, told each key.
 */
class PetHost private constructor(
    val view: PetView,
    private val pet: Pet,
    private val keys: View,
    private val bar: View,
) : TypedComponent<PetHost>(), InputBroadcastReceiver {

    private val density = view.resources.displayMetrics.density
    private val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
    private val hide = Runnable { view.show(false) }

    init {
        keys.addOnLayoutChangeListener(relayout)
        bar.addOnLayoutChangeListener(relayout)
    }

    fun onKey(action: KeyAction) {
        PetTriggers.of(action)?.let(view::on)
    }

    override fun onWindowAttached(window: InputWindow) {
        view.show(window is KeyboardWindow)
    }

    // hiding the keyboard leaves the IME window visible on newer Android (the insets animate it away), so a finish hides it;
    // another field's start comes in the same message and keeps it as it was
    override fun onFinishInput() {
        view.post(hide)
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        view.removeCallbacks(hide)
    }

    private fun place() {
        val size = view.sizePx
        val ltr = view.layoutDirection == View.LAYOUT_DIRECTION_LTR
        if (pet.place == Pet.Place.BAR_END) {
            room(ltr)
            // the bar's side padding is screen edge, not slot: the pet sits next to the candidates
            val tx = bar.translationX.roundToInt()
            val gap = dp(GAP_DP) / 2
            val left = if (ltr) bar.right + tx - bar.paddingRight + gap else bar.left + tx + bar.paddingLeft - gap - size
            view.place(left, (bar.top + bar.bottom) / 2 + bar.translationY.roundToInt() - size / 2)
            return
        }
        val x = dp(pet.offsetXDp)
        val y = dp(pet.offsetYDp)
        val tx = keys.translationX.roundToInt()
        val left = if (pet.align.start == ltr) keys.left + tx + x else keys.right + tx - size - x
        val top = if (pet.align.top) keys.top + y else keys.bottom - size - y
        view.place(left, top)
    }

    // left or right, not end: ConstraintLayout keeps a left/right-constrained view's own left and right margins over marginEnd
    private fun room(ltr: Boolean) {
        val slot = view.sizePx + dp(GAP_DP)
        val (left, right) = if (ltr) 0 to slot else slot to 0
        val lp = bar.layoutParams as ConstraintLayout.LayoutParams
        if (lp.leftMargin != left || lp.rightMargin != right) {
            bar.updateLayoutParams<ConstraintLayout.LayoutParams> {
                leftMargin = left
                rightMargin = right
            }
        }
    }

    private fun dp(v: Double) = (v * density).roundToInt()

    companion object {
        // the bar's end slot is the pet and this, half each side
        private const val GAP_DP = 4.0

        /** The theme's pet, or null where it has none: then nothing is made, nothing told. */
        fun of(context: Context, theme: Theme, keys: View, bar: View): PetHost? {
            val pet = (theme as? Theme.Pack)?.drawn?.pet ?: return null
            val sizeDp = if (pet.place == Pet.Place.BAR_END) minOf(pet.sizeDp, KawaiiBarComponent.heightDp(context) - GAP_DP) else pet.sizeDp
            val density = context.resources.displayMetrics.density
            val budget = if (LowMemory.on(context)) PetFrames.LOW_MEMORY_BUDGET_BYTES else PetFrames.BUDGET_BYTES
            val view = PetView(context, pet, (sizeDp * density).roundToInt(), budget)
            return PetHost(view, pet, keys, bar).also {
                if (pet.place == Pet.Place.BAR_END) it.room(context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR)
            }
        }
    }
}
