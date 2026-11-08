/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import org.fcitx.fcitx5.android.input.voice.VoiceEnd

/** 语音输入 → 句末句号: [VoiceEnd.Mode] as the settings list it. */
enum class VoiceEndMark(override val stringRes: Int, val mode: VoiceEnd.Mode) : ManagedPreferenceEnum {
    Auto(R.string.voice_end_mark_auto, VoiceEnd.Mode.AUTO),
    Keep(R.string.voice_end_mark_keep, VoiceEnd.Mode.KEEP),
    Drop(R.string.voice_end_mark_drop, VoiceEnd.Mode.DROP),
}
