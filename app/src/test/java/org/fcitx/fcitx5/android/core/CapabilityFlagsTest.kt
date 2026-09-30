/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityFlagsTest {

    @Test
    fun aFieldIsPasswordOrSensitiveWithEitherFlag() {
        // a password field has no Sensitive, an incognito tab no Password
        val password = CapabilityFlags(CapabilityFlag.Password)
        val incognito = CapabilityFlags(CapabilityFlag.Sensitive)
        assertTrue(password.hasAny(CapabilityFlag.PasswordOrSensitive))
        assertTrue(incognito.hasAny(CapabilityFlag.PasswordOrSensitive))
        assertFalse(password.has(CapabilityFlag.PasswordOrSensitive))
        assertFalse(CapabilityFlags(CapabilityFlag.Preedit).hasAny(CapabilityFlag.PasswordOrSensitive))
    }
}
