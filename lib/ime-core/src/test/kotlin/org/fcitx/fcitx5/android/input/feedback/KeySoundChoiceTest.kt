/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.feedback

import org.fcitx.fcitx5.android.input.feedback.KeySoundChoice.Choice
import org.fcitx.fcitx5.android.scene.theme.SoundKey
import org.junit.Assert.assertEquals
import org.junit.Test

class KeySoundChoiceTest {

    private val all = SoundKey.entries.toSet()

    @Test
    fun theThemesSoundOfTheClass() {
        assertEquals(Choice.Theme(SoundKey.SPACE, 0.5f), KeySoundChoice.choose(SoundKey.SPACE, all, all, fromTheme = true, volumePercent = 0))
        assertEquals(Choice.Theme(SoundKey.ENTER, 0.9f), KeySoundChoice.choose(SoundKey.ENTER, all, all, fromTheme = true, volumePercent = 90))
    }

    @Test
    fun aClassNotGivenTakesTheThemesLetter() {
        val letter = setOf(SoundKey.LETTER)
        assertEquals(Choice.Theme(SoundKey.LETTER, 0.2f), KeySoundChoice.choose(SoundKey.BACKSPACE, letter, letter, fromTheme = true, volumePercent = 20))
        // no letter either: the system's, of the class
        val space = setOf(SoundKey.SPACE)
        assertEquals(Choice.System(SoundKey.ENTER, -1f), KeySoundChoice.choose(SoundKey.ENTER, space, space, fromTheme = true, volumePercent = 0))
    }

    @Test
    fun notLoadedIsTheSystems() {
        // the class given but not (yet) loaded: the system's, not the theme's letter
        assertEquals(
            Choice.System(SoundKey.SPACE, 0.5f),
            KeySoundChoice.choose(SoundKey.SPACE, all, setOf(SoundKey.LETTER), fromTheme = true, volumePercent = 50),
        )
        assertEquals(Choice.System(SoundKey.LETTER, -1f), KeySoundChoice.choose(SoundKey.LETTER, all, emptySet(), fromTheme = true, volumePercent = 0))
    }

    @Test
    fun systemChosen() {
        assertEquals(Choice.System(SoundKey.FUNCTION, -1f), KeySoundChoice.choose(SoundKey.FUNCTION, all, all, fromTheme = false, volumePercent = 0))
        assertEquals(Choice.System(SoundKey.FUNCTION, 0.5f), KeySoundChoice.choose(SoundKey.FUNCTION, all, all, fromTheme = false, volumePercent = 50))
    }

    @Test
    fun theSystemsClickIsTheOneTheKeyAlwaysHad() {
        // an enter-kind key of sound="standard" (wubi's 查): the theme's enter sound, else the standard click as before
        assertEquals(Choice.Theme(SoundKey.ENTER, 0.5f), KeySoundChoice.choose(SoundKey.ENTER, all, all, true, 0, system = SoundKey.LETTER))
        assertEquals(Choice.System(SoundKey.LETTER, -1f), KeySoundChoice.choose(SoundKey.ENTER, emptySet(), emptySet(), true, 0, system = SoundKey.LETTER))
    }
}
