/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.canvas.ScenePanels
import org.fcitx.fcitx5.android.scene.engine.PanelLooks

/** In one place, so the held and tapped views stay alike. */
object VoiceStyle {

    /** As a key's (按键时振动 with its length and strength), not the system's touch feedback alone. The JVM tests count them here. */
    @VisibleForTesting
    internal var haptics: (View, Boolean) -> Unit = { v, cue -> InputFeedbacks.hapticFeedback(v, longPress = cue) }

    fun vibrate(view: View, cue: Boolean = false) = haptics(view, cue)

    const val CANCEL_RED = 0xFFC62828.toInt()

    /** Not set: the keyboard's colour a little see-through (shapes and colours seen, the text clear). */
    fun panel(context: Context, theme: Theme): PanelLooks.Voice = ScenePanels.voice(context, theme)

    fun glass(context: Context, look: PanelLooks.Voice): VoiceGlass {
        val blur = look.blurDp.let { if (it == null) VoiceGlass.Blur.DEFAULT else VoiceGlass.Blur.of(it * context.resources.displayMetrics.density) }
        return VoiceGlass(look.fill.value, blur)
    }

    const val ANIMATE_MS = 150L

    /** two lines at most */
    fun heard(view: TextView) {
        view.maxLines = 2
        // the end of a long one is what was just said
        view.ellipsize = TextUtils.TruncateAt.START
    }
}
