/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** When VoiceEngine.acquire lets the models read go to read them anew (F5833: a voice pack imported since). */
class VoiceModelsOutdatedTest {

    private val old = File("/packs/voice-1")
    private val new = File("/packs/voice-2")

    @Test
    fun theModelsAreReadAnewOnlyWhenAnotherPackIsInAndNoTalkHoldsThem() {
        fun row(why: String, users: Int, loadedDir: File?, loaded: Boolean, dir: File?, want: Boolean) =
            why to { assertEquals(why, want, modelsOutdated(users, loadedDir, loaded, dir)) }
        assertRows(
            row("another pack imported, nobody talking: read anew", 0, old, true, new, true),
            row("a talk holds the old models: kept until it ends", 1, old, true, new, false),
            row("the same pack: kept", 0, old, true, old, false),
            row("nothing read yet: nothing to let go", 0, null, false, new, false),
            row("the pack removed: the old models not used again", 0, old, true, null, true),
        )
    }
}
