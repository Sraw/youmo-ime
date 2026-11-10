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

/**
 * What the two views talked in (VoiceHoldOverlay, held; VoiceTapOverlay, tapped) show alike: in
 * one place, so that they stay alike.
 */
object VoiceStyle {

    /**
     * Voice's vibrations, as a key's: the 按键时振动 setting with its length and strength
     * (InputFeedbacks), not the system's touch feedback alone. [cue]: the 正在听 one, as firm as
     * a long press. The JVM tests count them here.
     */
    @VisibleForTesting
    internal var haptics: (View, Boolean) -> Unit = { v, cue -> InputFeedbacks.hapticFeedback(v, longPress = cue) }

    fun vibrate(view: View, cue: Boolean = false) = haptics(view, cue)

    /** the colour of the finger in the zone where lifting drops it */
    const val CANCEL_RED = 0xFFC62828.toInt()

    /**
     * The mask as the theme has it (`voice`, `voice mic`; 060-render-p3 T7b): its colour over the keys, on their
     * blur ([VoiceGlass]), the microphone's bubble and bars. Not set: the keyboard's colour a little see-through
     * (PanelLooks.MASK_ALPHA: shapes and colours seen, the text clear, user 2026-11-07), the glass of before.
     */
    fun panel(context: Context, theme: Theme): PanelLooks.Voice = ScenePanels.voice(context, theme)

    /** The mask's glass of [look] on [context]'s screen: its blur dp as px ([VoiceGlass.Blur.of]), not set the app's of before. */
    fun glass(context: Context, look: PanelLooks.Voice): VoiceGlass {
        val blur = look.blurDp.let { if (it == null) VoiceGlass.Blur.DEFAULT else VoiceGlass.Blur.of(it * context.resources.displayMetrics.density) }
        return VoiceGlass(look.fill.value, blur)
    }

    /** how long each change they show takes */
    const val ANIMATE_MS = 150L

    /** [view] as it shows what was heard so far: two lines at most */
    fun heard(view: TextView) {
        view.maxLines = 2
        // the end of a long one is what was just said
        view.ellipsize = TextUtils.TruncateAt.START
    }
}
