/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import android.media.AudioManager
import android.os.Trace
import android.os.VibrationEffect
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import org.fcitx.fcitx5.android.input.feedback.KeySoundChoice
import org.fcitx.fcitx5.android.scene.theme.SoundKey
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.audioManager
import org.fcitx.fcitx5.android.utils.getSystemSettings
import org.fcitx.fcitx5.android.utils.vibrator

object InputFeedbacks {

    enum class InputFeedbackMode(override val stringRes: Int) : ManagedPreferenceEnum {
        FollowingSystem(R.string.following_system_settings),
        Enabled(R.string.enabled),
        Disabled(R.string.disabled);
    }

    private var systemSoundEffects = false
    private var systemHapticFeedback = false

    fun syncSystemPrefs() {
        systemSoundEffects = getSystemSettings<Int>(Settings.System.SOUND_EFFECTS_ENABLED) == 1
        // it says "Replaced by using android.os.VibrationAttributes.USAGE_TOUCH"
        // but gives no clue about how to use it, and this one still works
        @Suppress("DEPRECATION")
        systemHapticFeedback = getSystemSettings<Int>(Settings.System.HAPTIC_FEEDBACK_ENABLED) == 1
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val soundOnKeyPress by keyboardPrefs.soundOnKeyPress
    private val soundOnKeyPressVolume by keyboardPrefs.soundOnKeyPressVolume
    private val hapticOnKeyPress by keyboardPrefs.hapticOnKeyPress
    private val hapticOnKeyUp by keyboardPrefs.hapticOnKeyUp
    private val buttonPressVibrationMilliseconds by keyboardPrefs.buttonPressVibrationMilliseconds
    private val buttonLongPressVibrationMilliseconds by keyboardPrefs.buttonLongPressVibrationMilliseconds
    private val buttonPressVibrationAmplitude by keyboardPrefs.buttonPressVibrationAmplitude
    private val buttonLongPressVibrationAmplitude by keyboardPrefs.buttonLongPressVibrationAmplitude

    private val vibrator = appContext.vibrator

    private val hasAmplitudeControl = vibrator.hasAmplitudeControl()

    fun hapticFeedback(view: View, longPress: Boolean = false, keyUp: Boolean = false) {
        when (hapticOnKeyPress) {
            InputFeedbackMode.Enabled -> {}
            InputFeedbackMode.Disabled -> return
            InputFeedbackMode.FollowingSystem -> if (!systemHapticFeedback) return
        }
        if (keyUp && !hapticOnKeyUp) return
        val duration: Long
        val amplitude: Int
        val hfc: Int
        if (longPress) {
            duration = buttonLongPressVibrationMilliseconds.toLong()
            amplitude = buttonLongPressVibrationAmplitude
            hfc = HapticFeedbackConstants.LONG_PRESS
        } else {
            duration = buttonPressVibrationMilliseconds.toLong()
            amplitude = buttonPressVibrationAmplitude
            hfc = if (keyUp) {
                HapticFeedbackConstants.KEYBOARD_RELEASE
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
        }

        // there is `VibrationEffect.DEFAULT_AMPLITUDE` but no default duration;
        // also `VibrationEffect.createOneShot()` only accepts positive duration.
        // so changing amplitude without changing duration makes no sense
        if (duration != 0L) {
            // on Android 13, if system haptic feedback was disabled, `vibrator.vibrate()` won't work
            // but `view.performHapticFeedback()` with `FLAG_IGNORE_GLOBAL_SETTING` still works
            if (hasAmplitudeControl && amplitude != 0) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else {
                val ve = VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(ve)
            }
        } else {
            var flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            if (hapticOnKeyPress == InputFeedbackMode.Enabled) {
                // it says "Starting TIRAMISU only privileged apps can ignore user settings for touch feedback"
                // but we still seem to be able to use `FLAG_IGNORE_GLOBAL_SETTING`
                @Suppress("DEPRECATION")
                flags = flags or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            }
            view.performHapticFeedback(hfc, flags)
        }
    }

    /**
     * The four system clicks the old keys were made with (KeyDef.Appearance): kept for the tests that hold the
     * layouts' keys to those (060-theme-sounds-icons); a press plays by [SoundKey].
     */
    enum class SoundEffect {
        Standard, SpaceBar, Delete, Return
    }

    /** 按键音音色: the selected theme's sounds where it has them, or the system's always. */
    enum class KeySoundSource(override val stringRes: Int) : ManagedPreferenceEnum {
        FollowTheme(R.string.key_sound_source_theme),
        System(R.string.key_sound_source_system);
    }

    private val keySoundSource by keyboardPrefs.keySoundSource

    private val audioManager = appContext.audioManager

    /**
     * A key of class [key] pressed (060-theme-sounds-icons §1.4): whether it sounds, then which and how loud
     * (KeySoundChoice); [system] the system's click it plays where no theme's does.
     */
    fun soundEffect(key: SoundKey, system: SoundKey = key) {
        Trace.beginSection("InputFeedbacks.soundEffect")
        try {
            val plays = soundPlays()
            val fromTheme = keySoundSource == KeySoundSource.FollowTheme
            ThemeSounds.sync(plays && fromTheme)
            if (!plays) return
            play(key, system, fromTheme, soundOnKeyPressVolume)
        } finally {
            Trace.endSection()
        }
    }

    /**
     * A letter's sound at [volume] (按键音音量's 0..100, 0 the system's), as a press would play it: the
     * slider let go, so the user hears what they picked. It plays whether or not the keys would.
     */
    fun previewSound(volume: Int) {
        val fromTheme = keySoundSource == KeySoundSource.FollowTheme
        ThemeSounds.sync(fromTheme)
        play(SoundKey.LETTER, SoundKey.LETTER, fromTheme, volume)
    }

    private fun play(key: SoundKey, system: SoundKey, fromTheme: Boolean, volume: Int) {
        val sounds = ThemeSounds.shared
        when (val choice = KeySoundChoice.choose(key, sounds.given, sounds.loaded, fromTheme, volume, system)) {
            is KeySoundChoice.Choice.Theme ->
                if (!sounds.play(choice.key, choice.volume)) playSystemClick(system, KeySoundChoice.systemVolume(volume))
            is KeySoundChoice.Choice.System -> playSystemClick(choice.key, choice.volume)
        }
    }

    private fun soundPlays(): Boolean = when (soundOnKeyPress) {
        InputFeedbackMode.Enabled -> true
        InputFeedbackMode.Disabled -> false
        InputFeedbackMode.FollowingSystem -> systemSoundEffects
    }

    /** The selected theme's sounds loaded ahead of the first press, where they would play (the keyboard shown). */
    fun syncSounds() = ThemeSounds.sync(soundPlays() && keySoundSource == KeySoundSource.FollowTheme)

    private fun playSystemClick(key: SoundKey, volume: Float) = audioManager.playSoundEffect(fx(key), volume)

    // a function key clicks as the standard one: the system has no click of its own for it
    private fun fx(key: SoundKey): Int = when (key) {
        SoundKey.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
        SoundKey.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
        SoundKey.ENTER -> AudioManager.FX_KEYPRESS_RETURN
        SoundKey.LETTER, SoundKey.FUNCTION -> AudioManager.FX_KEYPRESS_STANDARD
    }

    /** No theme sounds held: the service is gone. */
    fun releaseSounds() = ThemeSounds.shared.release()

}