/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.feedback

import org.fcitx.fcitx5.android.scene.theme.SoundKey

/** Once 按键音 plays at all: the theme's sound of the key's class, else its `letter`, where loaded; else the system's click. */
object KeySoundChoice {

    sealed interface Choice {
        val volume: Float

        data class Theme(val key: SoundKey, override val volume: Float) : Choice

        /** [SYSTEM_VOLUME]: the system's own. */
        data class System(val key: SoundKey, override val volume: Float) : Choice
    }

    /** The system's keypress sounds play at AOSP's `config_soundEffectVolumeDb`, −6 dB: half. */
    const val THEME_DEFAULT_VOLUME = 0.5f

    /** AudioManager.playSoundEffect's volume for the system's own. */
    const val SYSTEM_VOLUME = -1f

    private const val PERCENT = 100f

    /** [percent] 0: the system default. */
    fun systemVolume(percent: Int): Float = if (percent == 0) SYSTEM_VOLUME else percent / PERCENT

    /** [fromTheme]: 按键音音色 跟随主题; [system] the system click its layout's sound= gives (SceneBehavior.systemSound). */
    fun choose(key: SoundKey, given: Set<SoundKey>, loaded: Set<SoundKey>, fromTheme: Boolean, volumePercent: Int, system: SoundKey = key): Choice {
        val own = when {
            !fromTheme -> null
            key in given -> key
            SoundKey.LETTER in given -> SoundKey.LETTER
            else -> null
        }
        return if (own != null && own in loaded) {
            Choice.Theme(own, if (volumePercent == 0) THEME_DEFAULT_VOLUME else volumePercent / PERCENT)
        } else {
            Choice.System(system, systemVolume(volumePercent))
        }
    }
}
