/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.text.TextUtils
import android.view.View
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.data.InputFeedbacks

/**
 * What the two views held to talk in (VoiceWindow, VoiceHoldOverlay) show alike: in one place,
 * so that they stay alike.
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

    /** how long each change they show takes */
    const val ANIMATE_MS = 150L

    /** [view] as it shows what was heard so far: two lines at most */
    fun heard(view: TextView) {
        view.maxLines = 2
        // the end of a long one is what was just said
        view.ellipsize = TextUtils.TruncateAt.START
    }
}
