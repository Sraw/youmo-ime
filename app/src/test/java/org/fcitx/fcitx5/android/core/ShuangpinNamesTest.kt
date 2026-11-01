/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.SchemeMigration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Every scheme offered and every one dropped has a name: the names are written out by their config
 * names, the lists kept in ime-core, and one left out would tell the user of a scheme by its key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ShuangpinNamesTest {

    @Test
    fun everySchemeOfferedOrDroppedIsNamed() {
        val context = RuntimeEnvironment.getApplication()
        val unnamed = (EngineSettings.SCHEMES.keys + SchemeMigration.RETIRED).filter { ShuangpinNames.of(context, it) == null }
        assertEquals(emptyList<String>(), unnamed)
    }
}
