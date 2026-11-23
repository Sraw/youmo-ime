/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.look.ThemeLook

@SuppressLint("ViewConstructor")
class NumberKeyboard(
    context: Context,
    theme: Theme,
    private val pad: NumberPad = NumberPad.FULL,
    /** `id@stamp`; null for the app's pad. */
    private val custom: String? = null,
) : BaseKeyboard(context, theme, themeBoard = KeyNames.NUMBER) {

    companion object {
        const val Name = NumberPad.FULL_LAYOUT

        internal const val LAYOUT = KeyNames.NUMBER
    }

    override fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env {
        val texts = Texts(
            alts = keyReadings(layout),
            back = context.getString(R.string.picker_back),
        ).withReturn()
        return SceneEnvs.of(this, look, theme.isDark, width, height, Input(pad = pad.id), texts = texts, marks = keyMarks(layout))
    }

    // the panel's words (选完符号后停留) say nothing of a pad kept across fields
    override fun sceneString(name: String): String? = when (name) {
        "a11y_key_panel_lock_on" -> context.getString(R.string.number_pad_unlock)
        "a11y_key_panel_lock_off" -> context.getString(R.string.number_pad_lock)
        else -> super.sceneString(name)
    }

    // last: the scene reads the pad. The user's board where it reads and is taken, the app's where it is not
    init {
        drawKeysWithScene(custom?.let { customLayout(context, it) } ?: SceneLayouts.of(context, LAYOUT))
    }
}
