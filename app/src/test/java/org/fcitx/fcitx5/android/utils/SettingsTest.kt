/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.fcitx.fcitx5.android.FcitxApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// the real application: the settings are read through appContext
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class SettingsTest {

    /** The default input method can be unset: below Android 14 reading it crashed the setup and main screens. */
    @Test
    fun anUnsetSecureStringReadsAsEmpty() {
        assertEquals("", getSecureSettings<String>("youmo_never_set"))
    }
}
