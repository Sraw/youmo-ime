/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import org.fcitx.fcitx5.android.scene.Icons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// contract: the icon names a theme file may use, as the theme reader lets them by
/** The icons a theme may name (keyboard-scene's [Icons], what its reader lets by) are the ones the app draws. */
class SceneIconsTest {
    @Test
    fun theThemesIconsAreTheAppsAlike() {
        assertEquals(Icons.ALL, SceneIcons.names)
        assertEquals(Icons.RETURN.toSet(), Icons.RETURN.filter { SceneIcons.returnName(SceneIcons.of(it)!!) == it }.toSet())
        assertNull(SceneIcons.of("rocket"))
    }
}
