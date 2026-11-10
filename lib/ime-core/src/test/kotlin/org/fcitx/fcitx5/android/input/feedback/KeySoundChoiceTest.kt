/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.feedback

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.feedback.KeySoundChoice.Choice
import org.fcitx.fcitx5.android.scene.theme.SoundKey
import org.junit.Assert.assertEquals
import org.junit.Test

class KeySoundChoiceTest {

    private val all = SoundKey.entries.toSet()
    private val letter = setOf(SoundKey.LETTER)
    private val space = setOf(SoundKey.SPACE)

    private fun row(why: String, want: Choice, got: () -> Choice) = why to { assertEquals(want, got()) }

    @Test
    fun whichSoundAndHowLoud() = assertRows(
        row("the theme's sound of the class, at its default volume", Choice.Theme(SoundKey.SPACE, 0.5f)) {
            KeySoundChoice.choose(SoundKey.SPACE, all, all, fromTheme = true, volumePercent = 0)
        },
        row("the theme's sound of the class, at 按键音音量", Choice.Theme(SoundKey.ENTER, 0.9f)) {
            KeySoundChoice.choose(SoundKey.ENTER, all, all, fromTheme = true, volumePercent = 90)
        },
        row("a class not given takes the theme's letter", Choice.Theme(SoundKey.LETTER, 0.2f)) {
            KeySoundChoice.choose(SoundKey.BACKSPACE, letter, letter, fromTheme = true, volumePercent = 20)
        },
        row("no letter either: the system's, of the class", Choice.System(SoundKey.ENTER, KeySoundChoice.SYSTEM_VOLUME)) {
            KeySoundChoice.choose(SoundKey.ENTER, space, space, fromTheme = true, volumePercent = 0)
        },
        row("the class given but not loaded: the system's, not the theme's letter", Choice.System(SoundKey.SPACE, 0.5f)) {
            KeySoundChoice.choose(SoundKey.SPACE, all, letter, fromTheme = true, volumePercent = 50)
        },
        row("nothing loaded: the system's", Choice.System(SoundKey.LETTER, KeySoundChoice.SYSTEM_VOLUME)) {
            KeySoundChoice.choose(SoundKey.LETTER, all, emptySet(), fromTheme = true, volumePercent = 0)
        },
        row("the class not given and the letter given but not loaded: the system's of the class", Choice.System(SoundKey.SPACE, KeySoundChoice.SYSTEM_VOLUME)) {
            KeySoundChoice.choose(SoundKey.SPACE, letter, emptySet(), fromTheme = true, volumePercent = 0)
        },
        row("按键音音色 系统, system default volume", Choice.System(SoundKey.FUNCTION, KeySoundChoice.SYSTEM_VOLUME)) {
            KeySoundChoice.choose(SoundKey.FUNCTION, all, all, fromTheme = false, volumePercent = 0)
        },
        row("按键音音色 系统, at 按键音音量", Choice.System(SoundKey.FUNCTION, 0.5f)) {
            KeySoundChoice.choose(SoundKey.FUNCTION, all, all, fromTheme = false, volumePercent = 50)
        },
        row("按键音音色 系统: the key's own system click, not its class's", Choice.System(SoundKey.LETTER, KeySoundChoice.SYSTEM_VOLUME)) {
            KeySoundChoice.choose(SoundKey.ENTER, all, all, fromTheme = false, volumePercent = 0, system = SoundKey.LETTER)
        },
        // an enter-kind key of sound="standard" (wubi's 查): the theme's enter sound, else the standard click as before
        row("an enter-kind key of the standard click: the theme's enter sound", Choice.Theme(SoundKey.ENTER, 0.5f)) {
            KeySoundChoice.choose(SoundKey.ENTER, all, all, true, 0, system = SoundKey.LETTER)
        },
        row("an enter-kind key of the standard click, no theme sound: the standard click", Choice.System(SoundKey.LETTER, KeySoundChoice.SYSTEM_VOLUME)) {
            KeySoundChoice.choose(SoundKey.ENTER, emptySet(), emptySet(), true, 0, system = SoundKey.LETTER)
        },
    )
}
