/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.feedback

import org.fcitx.fcitx5.android.scene.theme.SoundKey

/**
 * Which sound a key press plays, and how loud (060-theme-sounds-icons §1.4), once 按键音 has said it
 * plays at all: the selected theme's sound of the key's class, else its `letter`, where that is loaded;
 * else the system's click of the class (a function key the standard one).
 */
object KeySoundChoice {

    /** What to play: the theme's sound of [key] (a class it gives), or the system's of [key]; [volume] as each takes it. */
    sealed interface Choice {
        val volume: Float

        data class Theme(val key: SoundKey, override val volume: Float) : Choice

        /** The system's click of [key]; [volume] −1: the system's own (AudioManager.playSoundEffect's). */
        data class System(val key: SoundKey, override val volume: Float) : Choice
    }

    /**
     * Its theme's volume at 按键音音量's system default: the system's keypress sounds play at
     * AOSP's `config_soundEffectVolumeDb`, −6 dB by default, so half, as loud as they are.
     */
    const val THEME_DEFAULT_VOLUME = 0.5f

    private const val PERCENT = 100f

    /**
     * [key] pressed: the theme's sounds [given] (by class), [loaded] those of them ready to play,
     * [fromTheme] 按键音音色 跟随主题 (false: 系统), [volumePercent] 按键音音量 (0 the system default);
     * [system] the system's click it has always played (its layout's sound= alone: SceneBehavior.systemSound).
     */
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
            Choice.System(system, if (volumePercent == 0) -1f else volumePercent / PERCENT)
        }
    }
}
